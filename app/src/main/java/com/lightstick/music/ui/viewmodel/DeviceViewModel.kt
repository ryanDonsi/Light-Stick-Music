package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.lightstick.music.core.util.FirmwareVersionParser
import com.lightstick.music.core.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.manager.DeviceStatusNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.state.OtaState
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.domain.usecase.device.ConnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.DisconnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.GetCachedDeviceInfoUseCase
import com.lightstick.music.domain.usecase.device.SendFindEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.music.domain.usecase.device.StartScanUseCase
import com.lightstick.music.domain.usecase.device.StopScanUseCase
import com.lightstick.music.domain.usecase.device.ObserveDeviceStatesUseCase
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sendFindEffectUseCase:        SendFindEffectUseCase,
    private val connectDeviceUseCase:         ConnectDeviceUseCase,
    private val disconnectDeviceUseCase:      DisconnectDeviceUseCase,
    private val sendConnectionEffectUseCase:  SendConnectionEffectUseCase,
    private val startScanUseCase:             StartScanUseCase,
    private val stopScanUseCase:              StopScanUseCase,
    private val getCachedDeviceInfoUseCase:   GetCachedDeviceInfoUseCase
) : ViewModel() {

    companion object {
        private const val TAG = AppConstants.Feature.VM_DEVICE
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates.asStateFlow()

    private val connectedDevices = mutableMapOf<String, Device>()

    private val _deviceDetails = MutableStateFlow<Map<String, DeviceDetailInfo>>(emptyMap())
    val deviceDetails: StateFlow<Map<String, DeviceDetailInfo>> = _deviceDetails.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private val _otaProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    @Suppress("unused")
    val otaProgress: StateFlow<Map<String, Int>> = _otaProgress.asStateFlow()

    private val _otaInProgress = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    @Suppress("unused")
    val otaInProgress: StateFlow<Map<String, Boolean>> = _otaInProgress.asStateFlow()

    /**
     * 파일 선택 후 버전 비교 결과
     * - null: 체크 결과 없음
     * - 비어있지 않으면 UI에서 다이얼로그 표시 후 [clearOtaVersionCheck] 호출
     */
    data class OtaVersionCheck(
        val fileVersion: String,
        val deviceVersion: String,
        val isUpdateAvailable: Boolean
    )

    private val _otaVersionCheck = MutableStateFlow<OtaVersionCheck?>(null)
    val otaVersionCheck: StateFlow<OtaVersionCheck?> = _otaVersionCheck.asStateFlow()

    /** 버전 확인 후 사용자 승인 시 전달할 펌웨어 바이트 임시 보관 */
    private var pendingOtaFirmware: ByteArray? = null

    /** 스캔 Job 추적 */
    private var scanJob: Job? = null

    /** 배터리 모니터링 Job (MAC → Job) */
    private val batteryMonitoringJobs = mutableMapOf<String, Job>()

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            batteryMonitoringJobs.values.forEach { it.cancel() }
            batteryMonitoringJobs.clear()
        }

        override fun onStart(owner: LifecycleOwner) {
            connectedDevices.keys.toList().forEach { mac -> startBatteryMonitoring(mac) }
        }
    }

    init {
        observeDeviceStateEvents()
        observeDeviceInfoUpdates()
        syncCurrentlyConnectedDevices()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        viewModelScope.launch { _otaInProgress.collect { OtaState.update(it) } }
    }

    /**
     * 앱 시작 시 이미 연결된 디바이스 상태 동기화.
     * observeDeviceStateEvents()는 앞으로의 변경만 감지하므로 현재 연결 상태를 별도로 읽어야 함.
     */
    @SuppressLint("MissingPermission")
    private fun syncCurrentlyConnectedDevices() {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        Log.d(TAG, "[syncCurrentlyConnectedDevices] 시작")
        viewModelScope.launch {
            try {
                val connected = LSBluetooth.connectedDevices()
                Log.d(TAG, "[syncCurrentlyConnectedDevices] 연결된 기기: ${connected.size}개")

                connected.forEach { sdkDevice ->
                    Log.d(TAG, "  ${sdkDevice.mac}: connectDeviceUseCase 호출 전 / _deviceDetails=${_deviceDetails.value[sdkDevice.mac]?.deviceInfo?.firmwareRevision ?: "null"}")

                    // Immediately reflect connected state in UI
                    onDeviceConnectedFromSdk(sdkDevice.mac)

                    // Re-establish GATT layer (lost on process restart even though BLE connection
                    // persists at OS level) so DIS can be read and battery reads work
                    connectDeviceUseCase(
                        context      = context,
                        device       = sdkDevice,
                        onConnected  = {
                            Log.i(TAG, "GATT 재연결 성공: ${sdkDevice.mac}")
                            startBatteryMonitoring(sdkDevice.mac)
                        },
                        onFailed     = { err ->
                            Log.w(TAG, "GATT 재연결 실패: ${sdkDevice.mac} - ${err.message}")
                        },
                        onDeviceInfo = { info ->
                            Log.d(TAG, "[syncCurrentlyConnectedDevices] DIS 콜백 실행: ${sdkDevice.mac} name=${info.deviceName} fw=${info.firmwareRevision}")
                            updateDeviceInfoFromCallback(sdkDevice.mac, info)
                        }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "초기 연결 상태 동기화 실패: ${e.message}")
            }
        }
    }

    /**
     * SDK SharedFlow 기반 디바이스 상태 이벤트 관찰
     * - StateFlow Conflation 없이 Connected/Disconnected 이벤트 수신
     * - 기존 3개 Job → 1개로 통합
     */
    private fun observeDeviceStateEvents() {
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeDeviceStateEvents()
                .collect { event ->
                    when (event.state) {
                        is ConnectionState.Connected    -> onDeviceConnectedFromSdk(event.mac)
                        is ConnectionState.Disconnected -> onDeviceDisconnectedFromSdk(event.mac)
                        else -> Unit
                    }
                }
        }
    }

    /**
     * SDK DeviceState 구독 — DIS 읽기 완료 이벤트 감시
     */
    private fun observeDeviceInfoUpdates() {
        Log.d(TAG, "[observeDeviceInfoUpdates] 구독 시작")
        viewModelScope.launch {
            LSBluetooth.observeDeviceStates()
                .collect { stateMap ->
                    Log.d(TAG, "[observeDeviceInfoUpdates] 변경사항 수신 - devices=${stateMap.keys} / fw=${stateMap.entries.map { "${it.key}→${it.value.deviceInfo?.firmwareRevision ?: "null"}" }}")
                    stateMap.forEach { (mac, state) ->
                        val info = state.deviceInfo ?: return@forEach
                        if (info.firmwareRevision?.isNotBlank() != true) return@forEach

                        Log.d(TAG, "  [$mac] DIS 업데이트: fw=${info.firmwareRevision}")
                        updateDeviceInfoFromCallback(mac, info)
                    }
                }
        }
    }

    /**
     * SDK에서 연결 이벤트 감지 시 처리.
     * DeviceViewModel.connect()를 통해 이미 처리된 경우는 skip.
     *
     * 스캔 없이 복원된 기기도 _devices에 즉시 추가
     * → 스캔 중 연결되어도 "연결된 기기" 섹션에 즉시 표시
     */
    @SuppressLint("MissingPermission")
    private fun onDeviceConnectedFromSdk(mac: String) {
        if (connectedDevices.containsKey(mac)) {
            Log.d(TAG, "onDeviceConnectedFromSdk: skip (already handled by connect()) $mac")
            return
        }
        Log.i(TAG, "[onDeviceConnectedFromSdk] $mac")

        val sdkDevice = LSBluetooth.connectedDevices().find { it.mac == mac }
        val device = _devices.value.find { it.mac == mac }
            ?: Device(mac = mac, name = sdkDevice?.name, rssi = sdkDevice?.rssi?.takeIf { it != 0 })

        Log.d(TAG, "  device name=${device.name}")

        connectedDevices[mac] = device

        if (_devices.value.none { it.mac == mac }) {
            _devices.value = (_devices.value + device).sortedWith(
                compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                    .thenByDescending { it.rssi ?: -100 }
            )
            Log.d(TAG, "  _devices에 추가")
        }

        updateConnectionState(mac, true)

        if (!_deviceDetails.value.containsKey(mac)) {
            Log.d(TAG, "  _deviceDetails 초기화 중...")
            initializeDeviceDetail(device)
            Log.d(TAG, "  _deviceDetails 초기화 완료 (DIS 없음, fw=null)")
        } else {
            Log.d(TAG, "  _deviceDetails 이미 존재")
        }

        // SDK 캐시에서 DIS 즉시 로드 (SharedFlow 리플레이 부족 문제 해결)
        val cachedInfo = getCachedDeviceInfoUseCase(mac)
        if (cachedInfo != null) {
            Log.d(TAG, "  [캐시 DIS 로드] $mac fw=${cachedInfo.firmwareRevision} name=${cachedInfo.deviceName}")
            updateDeviceInfoFromCallback(mac, cachedInfo)
        } else {
            Log.d(TAG, "  [캐시 DIS 없음] $mac → GATT 재연결 후 콜백으로 수신 예정")
        }

        startBatteryMonitoring(mac)
    }

    /**
     * SDK에서 연결 해제 이벤트 감지 시 처리.
     */
    private fun onDeviceDisconnectedFromSdk(mac: String) {
        val fwAtDisconnect = _deviceDetails.value[mac]?.deviceInfo?.firmwareRevision
        Log.d(TAG, "onDeviceDisconnectedFromSdk: $mac fwAtDisconnect=$fwAtDisconnect")
        connectedDevices.remove(mac)
        updateConnectionState(mac, false)
        stopBatteryMonitoring(mac)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = false)
        }

        if (_otaInProgress.value.containsKey(mac)) {
            _otaInProgress.value -= mac
            _otaProgress.value   -= mac
        }
    }

    /**
     * 스캔 결과 단건을 _devices에 즉시 반영 (실시간 업데이트용)
     * onFound 콜백에서 호출됩니다.
     */
    private fun applyScannedDevice(device: Device) {
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.mac == device.mac }
        if (idx >= 0) {
            current[idx] = device
        } else {
            current.add(device)
        }
        _devices.value = current.sortedWith(
            compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                .thenByDescending { it.rssi ?: -100 }
        )
    }

    /**
     * 최초 1회 스캔.
     * 이미 스캔 중이라면 skip → 재시작은 [refreshScan] 사용.
     */
    fun startScan(context: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted"); return
        }
        if (_isScanning.value) return

        _isScanning.value = true
        _devices.value = _devices.value.filter { _connectionStates.value[it.mac] == true }

        scanJob = viewModelScope.launch {
            try {
                startScanUseCase(
                    context    = context,
                    durationMs = AppConstants.DEVICE_SCAN_DURATION_MS,
                    filter     = { true },
                    onFound    = { device -> applyScannedDevice(device) }
                ).onFailure { error ->
                    Log.e(TAG, "Scan failed: ${error.message}")
                }
            } catch (_: Exception) {
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * Pull-to-Refresh 전용 재스캔.
     *
     * 기존 문제: 외부 launch의 finally { _isScanning = false }가
     * 내부 scanJob 완료 전 즉시 실행 → PullToRefresh 인디케이터 즉시 사라짐
     *
     * 해결: stop 처리 후 startScan() 재사용
     * → startScan() 내부 finally에서 _isScanning 생명주기 전담
     */
    fun refreshScan(@Suppress("UNUSED_PARAMETER") externalContext: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted"); return
        }

        viewModelScope.launch {
            try {
                if (scanJob?.isActive == true) {
                    scanJob?.cancel()
                    scanJob = null
                }

                stopScanUseCase(context)
                _isScanning.value = false

            } catch (e: Exception) {
                _isScanning.value = false
                Log.e(TAG, "Stop scan error: ${e.message}")
                return@launch
            }

            startScan(context)
        }
    }

    @Suppress("unused")
    fun stopScan() {
        if (!_isScanning.value) return

        scanJob?.cancel()
        scanJob = null

        viewModelScope.launch {
            stopScanUseCase(context)
                .onFailure { Log.e(TAG, "Error stopping scan: ${it.message}") }
            _isScanning.value = false
        }
    }

    fun toggleConnection(context: Context, device: Device) {
        val isConnected = _connectionStates.value[device.mac] == true
        if (isConnected) disconnect(context, device) else connect(context, device)
    }

    private fun connect(context: Context, device: Device) {
        viewModelScope.launch {
            try {
                if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not available"); return@launch
                }

                connectDeviceUseCase(
                    context      = context,
                    device       = device,
                    onConnected  = {
                        Log.i(TAG, "onConnected: ${device.mac}")
                        connectedDevices[device.mac] = device
                        updateConnectionState(device.mac, true)
                        if (!_deviceDetails.value.containsKey(device.mac)) {
                            initializeDeviceDetail(device)
                        }
                        startBatteryMonitoring(device.mac)
                        viewModelScope.launch {
                            sendConnectionEffectUseCase(context, device).onFailure { error ->
                                Log.e(TAG, "Connection animation failed: ${error.message}")
                            }
                        }
                    },
                    onFailed     = { error ->
                        Log.e(TAG, "Connection failed: ${error.message}")
                        connectedDevices.remove(device.mac)
                        updateConnectionState(device.mac, false)
                    },
                    onDeviceInfo = { info ->
                        Log.d(TAG, "onDeviceInfo [connect path]: ${device.mac} fw=${info.firmwareRevision}")
                    }
                ).onFailure { error ->
                    Log.e(TAG, "Connect use case failed: ${error.message}")
                    connectedDevices.remove(device.mac)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during connect: ${e.message}", e)
                connectedDevices.remove(device.mac)
            }
        }
    }

    private fun disconnect(context: Context, device: Device) {
        viewModelScope.launch {
            try {
                if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not available"); return@launch
                }

                disconnectDeviceUseCase(context, device)
                    .onFailure { error -> Log.e(TAG, "Disconnect failed: ${error.message}") }

            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect: ${e.message}", e)
            }
        }
    }

    fun sendFindEffect(device: Device) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT 권한 없음"); return
        }

        viewModelScope.launch {
            try {
                if (_connectionStates.value[device.mac] != true) {
                    Log.w(TAG, "Device not connected: ${device.mac}"); return@launch
                }
                sendFindEffectUseCase(context = context, deviceMac = device.mac)
                    .onFailure { error -> Log.e(TAG, "FIND effect failed: ${error.message}") }
            } catch (e: Exception) {
                Log.e(TAG, "FIND effect error: ${e.message}", e)
            }
        }
    }

    /**
     * 파일 선택 → 버전 비교 → otaVersionCheck StateFlow 업데이트
     *
     * 흐름:
     *  1. URI에서 파일 바이트 읽기 (IO 스레드)
     *  2. 바이너리에서 버전 파싱 — 실패 시 현재 디바이스 버전의 minor +1 시뮬레이션
     *  3. 디바이스 현재 버전과 비교
     *  4. [otaVersionCheck] 에 결과 저장 → UI에서 다이얼로그 결정
     *
     * [TODO] 최종 구현 시 서버 API에서 버전 + 다운로드 URL 조회로 대체
     */
    @SuppressLint("MissingPermission")
    fun checkFirmwareVersion(context: Context, device: Device, firmwareUri: Uri) {
        viewModelScope.launch {
            try {
                val firmware = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                } ?: run {
                    Log.e(TAG, "OTA: Cannot open firmware file")
                    return@launch
                }

                val deviceVersion = _deviceDetails.value[device.mac]
                    ?.deviceInfo?.firmwareRevision.orEmpty().trim()

                // Telink 바이너리는 버전 정보가 신뢰할 수 없으므로 항상 시뮬레이션 버전(minor+1) 사용
                val parsedVersion = FirmwareVersionParser.parseFromBytes(firmware)
                val baseVersion = deviceVersion.ifEmpty { "0.0.0" }
                val fileVersion = FirmwareVersionParser.simulateTestVersion(baseVersion)

                Log.i(TAG, "OTA version check: device=$deviceVersion, file=$fileVersion, parsed=$parsedVersion")
                val isUpdateAvailable = FirmwareVersionParser.isNewerVersion(fileVersion, deviceVersion)

                pendingOtaFirmware = firmware
                _otaVersionCheck.value = OtaVersionCheck(fileVersion, deviceVersion, isUpdateAvailable)

            } catch (e: Exception) {
                Log.e(TAG, "OTA version check error: ${e.message}", e)
            }
        }
    }

    /**
     * 사용자가 업데이트 확인 → [pendingOtaFirmware] 로 OTA 시작
     */
    @SuppressLint("MissingPermission")
    fun startPendingOta(device: Device) {
        val firmware = pendingOtaFirmware ?: run {
            Log.e(TAG, "OTA: 대기 중인 펌웨어 없음")
            return
        }
        pendingOtaFirmware = null
        startOtaFromBytes(device, firmware)
    }

    /** 버전 체크 결과 초기화 (pendingOtaFirmware는 startPendingOta/abortOta에서만 해제) */
    fun clearOtaVersionCheck() {
        _otaVersionCheck.value = null
    }

    /** 펌웨어 ByteArray로 OTA 진행 */
    @SuppressLint("MissingPermission")
    private fun startOtaFromBytes(device: Device, firmware: ByteArray) {
        viewModelScope.launch {
            try {
                _otaInProgress.value += (device.mac to true)
                _otaProgress.value   += (device.mac to 0)

                Log.i(TAG, "OTA start: ${device.mac}, size=${firmware.size}B")
                val ok = device.startOta(
                    firmware   = firmware,
                    onProgress = { percent ->
                        _otaProgress.value += (device.mac to percent)
                        if (percent % 10 == 0) Log.i(TAG, "OTA progress: $percent% [${device.mac}]")
                    },
                    onResult   = { result ->
                        _otaInProgress.value += (device.mac to false)
                        result
                            .onSuccess { Log.i(TAG, "OTA success: ${device.mac}") }
                            .onFailure { Log.e(TAG, "OTA failed: ${it.message}") }
                    }
                )
                if (!ok) {
                    _otaInProgress.value += (device.mac to false)
                    Log.e(TAG, "OTA submit failed: ${device.mac}")
                }

            } catch (e: Exception) {
                _otaInProgress.value += (device.mac to false)
                Log.e(TAG, "OTA error: ${e.message}", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun abortOta(device: Device) {
        device.abortOta()
        _otaInProgress.value -= device.mac
        _otaProgress.value   -= device.mac
        Log.i(TAG, "OTA aborted: ${device.mac}")
    }

    fun toggleCallEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setCallEventEnabled(device.mac, enabled)
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(callEventEnabled = enabled)
                ?: buildDeviceDetailInfo(device, callEventEnabled = enabled)
        }
    }

    fun toggleSmsEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setSmsEventEnabled(device.mac, enabled)
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(smsEventEnabled = enabled)
                ?: buildDeviceDetailInfo(device, smsEventEnabled = enabled)
        }
    }

    fun toggleBroadcasting(device: Device, enabled: Boolean) {
        DevicePreferences.setBroadcasting(device.mac, enabled)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(broadcasting = enabled)
                ?: buildDeviceDetailInfo(device, broadcasting = enabled)
        }

    }

    /**
     * 디바이스 정보 다이얼로그 표시 직전 호출 — GATT에서 DIS를 직접 읽어 즉시 반영
     * getCachedDeviceInfo는 프로세스 재시작 후 캐시가 비어 null을 반환하므로
     * fetchDeviceInfo로 디바이스에서 직접 조회.
     */
    @SuppressLint("MissingPermission")
    fun refreshDeviceInfo(mac: String) {
        val device = connectedDevices[mac] ?: run {
            Log.w(TAG, "[refreshDeviceInfo] $mac → 연결된 기기 없음")
            return
        }
        Log.d(TAG, "[refreshDeviceInfo] $mac → fetchDeviceInfo 호출")
        device.fetchDeviceInfo { info ->
            Log.d(TAG, "[refreshDeviceInfo] DIS 수신: $mac fw=${info.firmwareRevision}")
            updateDeviceInfoFromCallback(mac, info)
        }
    }

    /**
     * 배터리 레벨 모니터링 시작
     *
     * 펌웨어 BAS Notification 미지원으로 주기적 readBattery() 방식 사용.
     * - Connected 시 즉시 1회 조회
     * - 이후 [AppConstants.BATTERY_MONITOR_INTERVAL_MS] 간격으로 반복 조회
     * - Disconnected 시 [stopBatteryMonitoring]으로 자동 중지
     */
    @SuppressLint("MissingPermission")
    private fun startBatteryMonitoring(mac: String) {
        stopBatteryMonitoring(mac)

        val device = connectedDevices[mac] ?: Device(mac = mac)
        if (!device.supportsBattery()) {
            return
        }

        batteryMonitoringJobs[mac] = viewModelScope.launch {
            readBatteryLevel(mac)

            while (true) {
                delay(AppConstants.BATTERY_MONITOR_INTERVAL_MS)
                if (!connectedDevices.containsKey(mac)) break
                readBatteryLevel(mac)
            }
        }
    }

    /**
     * 배터리 레벨 모니터링 중지
     */
    private fun stopBatteryMonitoring(mac: String) {
        batteryMonitoringJobs[mac]?.cancel()
        batteryMonitoringJobs.remove(mac)
    }

    /**
     * 배터리 레벨 1회 조회 및 UI 업데이트
     */
    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(mac: String) {
        val device = Device(mac = mac)
        device.readBattery { result ->
            result
                .onSuccess { level ->
                    updateBatteryLevel(mac, level)
                }
                .onFailure { e ->
                    Log.w(TAG, "Battery read failed: $mac → ${e.message}")
                }
        }
    }

    private fun initializeDeviceDetail(device: Device) {
        val callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac)
        val smsEventEnabled  = DevicePreferences.getSmsEventEnabled(device.mac)
        val broadcasting     = DevicePreferences.getBroadcasting(device.mac)

        _deviceDetails.value += (device.mac to DeviceDetailInfo(
            mac              = device.mac,
            name             = device.name,
            rssi             = device.rssi,
            isConnected      = true,
            deviceInfo       = null,
            batteryLevel     = null,
            otaProgress      = null,
            isOtaInProgress  = false,
            callEventEnabled = callEventEnabled,
            smsEventEnabled  = smsEventEnabled,
            broadcasting     = broadcasting
        ))

    }

    private fun updateDeviceInfoFromCallback(mac: String, deviceInfo: DeviceInfo) {
        Log.d(TAG, "[updateDeviceInfo] 시작: $mac")
        Log.d(TAG, "  name=${deviceInfo.deviceName} model=${deviceInfo.modelName} modelNum=${deviceInfo.modelNumber} fw=${deviceInfo.firmwareRevision} mfr=${deviceInfo.manufacturer}")

        val existingBefore = _deviceDetails.value[mac]?.deviceInfo
        Log.d(TAG, "  기존 상태: fw=${existingBefore?.firmwareRevision ?: "null"}")

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            this[mac] = existing?.copy(deviceInfo = deviceInfo, batteryLevel = deviceInfo.batteryLevel)
                ?: DeviceDetailInfo(
                    mac          = mac,
                    name         = deviceInfo.deviceName,
                    rssi         = deviceInfo.rssi,
                    isConnected  = true,
                    deviceInfo   = deviceInfo,
                    batteryLevel = deviceInfo.batteryLevel
                )
        }

        val updated = _deviceDetails.value[mac]?.deviceInfo
        Log.d(TAG, "[updateDeviceInfo] 완료: $mac fw=${updated?.firmwareRevision ?: "null"} (existed=${existingBefore != null})")
    }

    private fun updateBatteryLevel(mac: String, level: Int) {
        Log.d(TAG, "[updateBatteryLevel] $mac: $level%")
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) {
                this[mac] = existing.copy(batteryLevel = level)
                Log.d(TAG, "  _deviceDetails 업데이트 완료")
            } else {
                Log.d(TAG, "  _deviceDetails에 $mac 없음 → skip")
            }
        }
        DeviceStatusNotificationManager.updateDeviceBattery(mac, level)
    }

    private fun buildDeviceDetailInfo(
        device: Device,
        callEventEnabled:     Boolean = DevicePreferences.getCallEventEnabled(device.mac),
        smsEventEnabled:  Boolean = DevicePreferences.getSmsEventEnabled(device.mac),
        broadcasting:     Boolean = DevicePreferences.getBroadcasting(device.mac)
    ): DeviceDetailInfo = DeviceDetailInfo(
        mac              = device.mac,
        name             = device.name,
        rssi             = device.rssi,
        isConnected      = connectedDevices.containsKey(device.mac),
        deviceInfo       = null,
        batteryLevel     = null,
        otaProgress      = null,
        isOtaInProgress  = false,
        callEventEnabled = callEventEnabled,
        smsEventEnabled  = smsEventEnabled,
        broadcasting     = broadcasting
    )

    private fun updateConnectionState(mac: String, isConnected: Boolean) {
        _connectionStates.value += (mac to isConnected)
        _connectedDeviceCount.value = _connectionStates.value.count { it.value }

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = isConnected)
        }

        _devices.value = _devices.value.sortedWith(
            compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                .thenByDescending { it.rssi ?: -100 }
        )
    }

    override fun onCleared() {
        super.onCleared()

        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        scanJob?.cancel()
        batteryMonitoringJobs.values.forEach { it.cancel() }
        batteryMonitoringJobs.clear()

        if (PermissionManager.hasBluetoothConnectPermission(context)) {
            connectedDevices.values.forEach { device ->
                try {
                    disconnectDeviceUseCase(context, device)
                } catch (e: Exception) {
                    Log.e(TAG, "   Error disconnecting ${device.mac}: ${e.message}")
                }
            }
        }

        connectedDevices.clear()
    }
}
