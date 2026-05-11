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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.domain.usecase.device.ConnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.DisconnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.GetCachedDeviceInfoUseCase
import com.lightstick.music.domain.usecase.device.RegisterEventRulesUseCase
import com.lightstick.music.domain.usecase.device.SendFindEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.music.domain.usecase.device.StartScanUseCase
import com.lightstick.music.domain.usecase.device.StopScanUseCase
import com.lightstick.music.domain.usecase.device.ObserveDeviceStatesUseCase
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import com.lightstick.events.EventType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sendFindEffectUseCase:       SendFindEffectUseCase,
    private val connectDeviceUseCase:        ConnectDeviceUseCase,
    private val disconnectDeviceUseCase:     DisconnectDeviceUseCase,
    private val registerEventRulesUseCase:   RegisterEventRulesUseCase,
    private val sendConnectionEffectUseCase: SendConnectionEffectUseCase,
    private val getCachedDeviceInfoUseCase:  GetCachedDeviceInfoUseCase,
    private val startScanUseCase:            StartScanUseCase,
    private val stopScanUseCase:             StopScanUseCase
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

    private val _eventStates = MutableStateFlow<Map<String, Map<EventType, Boolean>>>(emptyMap())
    @Suppress("unused")
    val eventStates: StateFlow<Map<String, Map<EventType, Boolean>>> = _eventStates.asStateFlow()

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
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
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
        Log.i(TAG, "onDeviceConnectedFromSdk: $mac")

        val sdkDevice = LSBluetooth.connectedDevices().find { it.mac == mac }
        val device = _devices.value.find { it.mac == mac }
            ?: Device(mac = mac, name = sdkDevice?.name, rssi = sdkDevice?.rssi?.takeIf { it != 0 })

        connectedDevices[mac] = device

        if (_devices.value.none { it.mac == mac }) {
            _devices.value = (_devices.value + device).sortedWith(
                compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                    .thenByDescending { it.rssi ?: -100 }
            )
        }

        updateConnectionState(mac, true)

        val hadDetail = _deviceDetails.value.containsKey(mac)
        val existingFw = _deviceDetails.value[mac]?.deviceInfo?.firmwareRevision
        Log.d(TAG, "onDeviceConnectedFromSdk: $mac hadDetail=$hadDetail existingFw=$existingFw")

        if (!hadDetail) {
            initializeDeviceDetail(device)
        }

        val deviceInfo = getCachedDeviceInfoUseCase(mac)
        val hasValidInfo = deviceInfo?.firmwareRevision?.isNotBlank() == true
        Log.d(TAG, "getCachedDeviceInfo: $mac result=${if (deviceInfo != null) "hit fw=${deviceInfo.firmwareRevision}" else "miss"} hasValidInfo=$hasValidInfo")
        if (hasValidInfo) {
            updateDeviceInfoFromCallback(mac, deviceInfo!!)
        } else {
            device.fetchDeviceInfo { info ->
                Log.i(TAG, "fetchDeviceInfo: $mac fw=${info.firmwareRevision} model=${info.modelNumber}")
                updateDeviceInfoFromCallback(mac, info)
            }
        }

        registerDeviceEventRules(device)
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

        if (_eventStates.value.containsKey(mac)) {
            _eventStates.value -= mac
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
                        registerDeviceEventRules(device)
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
                        Log.i(TAG, "onDeviceInfo [connect path]: ${device.mac} fw=${info.firmwareRevision} model=${info.modelNumber}")
                        updateDeviceInfoFromCallback(device.mac, info)
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

    private fun registerDeviceEventRules(device: Device) {
        registerEventRulesUseCase(context, device).onFailure { error ->
            Log.e(TAG, "Failed to register event rules for ${device.mac}: ${error.message}")
        }
    }

    fun toggleCallEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setCallEventEnabled(device.mac, enabled)

        val states = _eventStates.value[device.mac]?.toMutableMap() ?: mutableMapOf()
        states[EventType.CALL_RINGING] = enabled
        _eventStates.value += (device.mac to states)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(callEventEnabled = enabled)
                ?: buildDeviceDetailInfo(device, callEventEnabled = enabled)
        }

        registerDeviceEventRules(device)
    }

    fun toggleSmsEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setSmsEventEnabled(device.mac, enabled)

        val states = _eventStates.value[device.mac]?.toMutableMap() ?: mutableMapOf()
        states[EventType.SMS_RECEIVED] = enabled
        _eventStates.value += (device.mac to states)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(smsEventEnabled = enabled)
                ?: buildDeviceDetailInfo(device, smsEventEnabled = enabled)
        }

        registerDeviceEventRules(device)
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

        _eventStates.value += (device.mac to mapOf(
            EventType.CALL_RINGING to callEventEnabled,
            EventType.SMS_RECEIVED to smsEventEnabled
        ))
    }

    private fun updateDeviceInfoFromCallback(mac: String, deviceInfo: DeviceInfo) {
        Log.d(TAG, "updateDeviceInfo: $mac fw=${deviceInfo.firmwareRevision} model=${deviceInfo.modelNumber} mfr=${deviceInfo.manufacturer}")
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
        Log.d(TAG, "updateDeviceInfo done: $mac fw=${_deviceDetails.value[mac]?.deviceInfo?.firmwareRevision}")
    }

    private fun updateBatteryLevel(mac: String, level: Int) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(batteryLevel = level)
        }
    }

    private fun buildDeviceDetailInfo(
        device: Device,
        callEventEnabled: Boolean = DevicePreferences.getCallEventEnabled(device.mac),
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
