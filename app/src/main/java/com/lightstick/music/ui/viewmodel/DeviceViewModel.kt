package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.lightstick.music.core.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.manager.DeviceStatusNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.state.GroupEffectState
import com.lightstick.music.core.state.OtaState
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.data.local.preferences.GroupPreferences
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
import com.lightstick.device.DeviceMode
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

        /** SHOW_EXIT(Write With Response)가 나간 뒤 연결을 끊기까지 기다리는 시간 — 바로 끊으면 쓰기가 취소될 수 있다 */
        private const val SHOW_EXIT_DISCONNECT_DELAY_MS = 300L

        /** 이보다 작은 파일은 펌웨어로 보지 않는다 */
        private const val OTA_MIN_FILE_BYTES = 1_024
        /** 확인 화면에서 배터리 경고를 띄우는 기준(%) */
        private const val OTA_LOW_BATTERY_PERCENT = 30
        /** 전송 완료 후 재연결을 시도하기 전 대기. 펌웨어 측 재부팅 시간이 정해지면 맞출 것 */
        private const val OTA_REBOOT_WAIT_MS = 5_000L
        /** 재부팅 후 재연결을 기다리는 최대 시간 */
        private const val OTA_RECONNECT_TIMEOUT_MS = 35_000L
        /** 재연결 재시도 간격 — ConnectDeviceUseCase 시간 제한(30초)과 겹치지 않게 */
        private const val OTA_RECONNECT_RETRY_MS = 31_000L
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates.asStateFlow()

    private val connectedDevices = mutableMapOf<String, Device>()

    /** mac → FF06으로 읽은 DeviceMode. 미지원/읽기 전/실패 기기는 넣지 않는다(fail-open). */
    private val deviceModes = mutableMapOf<String, DeviceMode>()

    /**
     * 그룹 Effect(그룹 배정, groupMask 지정 Effect) 허용 여부.
     * 연결된 기기 중 하나라도 BLE Only 모드면 false — 콘서트 규모 그룹 제어가 전제인
     * 기능이라 일부 기기만 BLE 모드여도 그룹 명령 자체를 막는다.
     * 구형 펌웨어(FF06 미지원)는 중계기 모드로 간주해 계속 허용한다.
     */
    private val _isGroupEffectAllowed = MutableStateFlow(true)
    val isGroupEffectAllowed: StateFlow<Boolean> = _isGroupEffectAllowed.asStateFlow()

    private val _deviceDetails = MutableStateFlow<Map<String, DeviceDetailInfo>>(emptyMap())
    val deviceDetails: StateFlow<Map<String, DeviceDetailInfo>> = _deviceDetails.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private val _otaProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    @Suppress("unused")
    val otaProgress: StateFlow<Map<String, Int>> = _otaProgress.asStateFlow()

    /**
     * 공연 모드(SHOW_ENTER/SHOW_EXIT) 켜짐 상태 — 앱이 마지막으로 보낸 값(mac 기준, 메모리만).
     * 응원봉은 ACK/상태 통지가 없어 실제 상태를 읽을 수 없다. 연결이 끊기면 알 수 없으므로 끈 것으로 표시한다.
     */
    private val _showModeEnabled = MutableStateFlow<Set<String>>(emptySet())
    val showModeEnabled: StateFlow<Set<String>> = _showModeEnabled.asStateFlow()

    private val _otaInProgress = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    @Suppress("unused")
    val otaInProgress: StateFlow<Map<String, Boolean>> = _otaInProgress.asStateFlow()

    /** OTA 진행 화면 상태 (MAC → [OtaUi]). init 의 수집기보다 먼저 초기화되도록 위에 둔다. */
    private val _otaUi = MutableStateFlow<Map<String, OtaUi>>(emptyMap())
    val otaUi: StateFlow<Map<String, OtaUi>> = _otaUi.asStateFlow()

    /** 확인 대기 중인 펌웨어 (MAC → 바이트). 다시 시도할 때도 재사용한다. */
    private val pendingOtaFirmware = mutableMapOf<String, ByteArray>()
    private val otaRebootJobs = mutableMapOf<String, Job>()

    /** 이번 행사의 전체 그룹 개수 (1~20) — GroupAssignDialog에서 사용, GroupPreferences에 영속화 */
    private val _groupCount = MutableStateFlow(GroupPreferences.getGroupCount(context))
    val groupCount: StateFlow<Int> = _groupCount.asStateFlow()

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
     *
     * SDK는 동일한 InternalDeviceInfo가 재생성될 때만 emit을 스킵한다. 스캔 콜백이 RSSI를
     * 계속 갱신하면서 매번 rssi가 다른 InternalDeviceInfo가 새로 만들어지므로(DeviceStateManager의
     * equals 비교상 "실변경"), DIS 자체(name/model/firmware/manufacturer)는 그대로여도 emit은
     * 계속 발생한다. 여기서 DIS 관련 필드만 비교해서 실제로 달라졌을 때만 반영·로깅한다.
     */
    private fun observeDeviceInfoUpdates() {
        Log.d(TAG, "[observeDeviceInfoUpdates] 구독 시작")
        viewModelScope.launch {
            LSBluetooth.observeDeviceStates()
                .collect { stateMap ->
                    stateMap.forEach { (mac, state) ->
                        val info = state.deviceInfo ?: return@forEach
                        if (info.firmwareRevision?.isNotBlank() != true) return@forEach

                        val current = _deviceDetails.value[mac]?.deviceInfo
                        val unchanged = current != null &&
                            current.deviceName == info.deviceName &&
                            current.modelName == info.modelName &&
                            current.modelNumber == info.modelNumber &&
                            current.firmwareRevision == info.firmwareRevision &&
                            current.manufacturer == info.manufacturer
                        if (unchanged) return@forEach

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
        refreshDeviceMode(device)

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
     * FF06(Device Mode) 1회 읽기 — 연결 직후(GATT 서비스 디스커버리 완료 후)에만 호출.
     * 재연결마다 다시 읽어야 하므로 캐시하지 않고, 연결 해제 시 [deviceModes]에서 제거한다.
     */
    @SuppressLint("MissingPermission")
    private fun refreshDeviceMode(device: Device) {
        if (!device.supportsDeviceMode()) {
            // 구형 펌웨어(FF06 미지원) → 중계기 모드로 간주(fail-open)
            deviceModes.remove(device.mac)
            recomputeGroupEffectAllowed()
            setBroadcastingSupported(device.mac, false)
            return
        }
        device.readDeviceMode { result ->
            result.onSuccess { mode ->
                Log.i(TAG, "readDeviceMode: ${device.mac} → $mode")
                deviceModes[device.mac] = mode
                recomputeGroupEffectAllowed()
                if (mode == DeviceMode.BLE) applyBroadcastingOnConnect(device)
                else setBroadcastingSupported(device.mac, false)
            }.onFailure { error ->
                Log.w(TAG, "readDeviceMode 실패: ${device.mac} - ${error.message}")
            }
        }
    }

    /** 연결된 기기 중 BLE Only 모드가 하나라도 있으면 그룹 Effect를 막는다. */
    private fun recomputeGroupEffectAllowed() {
        val hasBleModeDevice = connectedDevices.keys.any { deviceModes[it] == DeviceMode.BLE }
        val allowed = !hasBleModeDevice
        _isGroupEffectAllowed.value = allowed
        GroupEffectState.update(allowed)
    }

    /**
     * SDK에서 연결 해제 이벤트 감지 시 처리.
     */
    private fun onDeviceDisconnectedFromSdk(mac: String) {
        val fwAtDisconnect = _deviceDetails.value[mac]?.deviceInfo?.firmwareRevision
        Log.d(TAG, "onDeviceDisconnectedFromSdk: $mac fwAtDisconnect=$fwAtDisconnect")
        connectedDevices.remove(mac)
        deviceModes.remove(mac)
        _showModeEnabled.value = _showModeEnabled.value - mac
        recomputeGroupEffectAllowed()
        updateConnectionState(mac, false)
        stopBatteryMonitoring(mac)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = false, supportsBroadcasting = false)
        }

        if (_otaInProgress.value.containsKey(mac)) {
            _otaInProgress.value -= mac
            _otaProgress.value   -= mac
        }
        // 전송 중 연결이 끊기면 결과를 실패로 보여 준다(재부팅 대기·결과 화면은 그대로 둔다)
        (_otaUi.value[mac] as? OtaUi.Transferring)?.let {
            setOtaUi(mac, OtaUi.Failed(it.deviceName, "업데이트 중 연결이 끊겼어요. 응원봉을 켜고 다시 연결한 뒤 시도해 주세요.", canRetry = false))
        }
    }

    /**
     * 스캔 결과 단건을 _devices에 즉시 반영 (실시간 업데이트용)
     * onFound 콜백에서 호출됩니다.
     */
    private fun applyScannedDevice(device: Device) {
        // 스캔 결과마다 RSSI 로 다시 정렬하면 카드가 계속 자리를 바꿔 다른 기기를 누를 수 있다.
        // 이미 있는 기기는 제자리에서 값만 바꾸고, 새 기기는 끝에 붙인다(발견 순서 유지).
        // 정렬은 연결 상태가 바뀔 때만 한다([updateConnectionState]).
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.mac == device.mac }
        if (idx >= 0) {
            current[idx] = device
        } else {
            current.add(device)
        }
        _devices.value = current
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
                        refreshDeviceMode(device)
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
                        deviceModes.remove(device.mac)
                        recomputeGroupEffectAllowed()
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

                // 이 기기로 공연 모드를 켠 상태면 해제를 먼저 보내고, 전송이 나갈 시간을 준 뒤 끊는다.
                if (sendShowExitIfNeeded(device)) delay(SHOW_EXIT_DISCONNECT_DELAY_MS)

                disconnectDeviceUseCase(context, device)
                    .onSuccess {
                        // 사용자 해제만 상대 앱(EfxCreator)에 알림 (앱 종료 정리/연결 끊김은 보내지 않음)
                        com.lightstick.music.core.sync.DeviceDisconnectSync.notifyPeer(context, device.mac)
                    }
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

    /** 이번 행사의 전체 그룹 개수 변경 (GroupAssignDialog) */
    fun setGroupCount(count: Int) {
        GroupPreferences.setGroupCount(context, count)
        _groupCount.value = GroupPreferences.getGroupCount(context)
    }

    /**
     * 그룹 배정 방송 전송 (GroupSetup).
     * 연결된 기기(RL 중계기 또는 직접연결된 GL/LS)를 통해 groupId 색상 BLINK를 방송한다.
     */
    @SuppressLint("MissingPermission")
    fun sendGroupSetting(device: Device, groupId: Int) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT 권한 없음"); return
        }
        if (_connectionStates.value[device.mac] != true) {
            Log.w(TAG, "Device not connected: ${device.mac}"); return
        }
        if (!_isGroupEffectAllowed.value) {
            Log.w(TAG, "그룹 배정 차단: BLE Only 모드는 그룹 기능을 지원하지 않음 (${device.mac})"); return
        }

        val ok = device.sendGroupSetting(groupId)
        if (ok) {
            Log.i(TAG, "Group setting sent: ${device.mac} → group $groupId")
        } else {
            Log.e(TAG, "Group setting failed: ${device.mac} → group $groupId")
        }
    }

    /**
     * OTA 진행 화면 상태 (기기 MAC 기준).
     *
     * 펌웨어 버전 정보가 아직 없어, 사용자가 고른 파일로 무조건 업데이트한다.
     * 그래서 "최신 버전/새 버전" 판정 대신 파일 정보를 보여 주고 확인을 받는다.
     * 흐름: 파일 선택 → [Confirm] → [Transferring] → [Rebooting] → [Success] / [Failed]
     */
    sealed class OtaUi {
        abstract val deviceName: String

        data class FileError(override val deviceName: String, val message: String) : OtaUi()

        data class Confirm(
            override val deviceName: String,
            val currentVersion: String?,
            val fileName: String,
            val sizeBytes: Int,
            val modifiedAtMs: Long?,
            /** 확장자가 .bin 이 아니면 경고만 한다 */
            val unusualExtension: Boolean,
            /** 배터리가 [OTA_LOW_BATTERY_PERCENT] 미만이면 경고 */
            val lowBattery: Boolean
        ) : OtaUi()

        data class Transferring(
            override val deviceName: String,
            val percent: Int,
            /** 최근 진행 속도로 추정한 남은 시간(초). 추정 전이면 null */
            val etaSeconds: Int?
        ) : OtaUi()

        data class Rebooting(override val deviceName: String) : OtaUi()

        data class Success(
            override val deviceName: String,
            /** 재연결 후 다시 읽은 버전. 재연결하지 못했으면 null */
            val version: String?,
            val reconnected: Boolean
        ) : OtaUi()

        data class Failed(
            override val deviceName: String,
            val message: String,
            val canRetry: Boolean
        ) : OtaUi()
    }

    private fun setOtaUi(mac: String, ui: OtaUi?) {
        _otaUi.value = if (ui == null) _otaUi.value - mac else _otaUi.value + (mac to ui)
    }

    private fun displayName(device: Device): String = device.name ?: "Unknown Device"

    /**
     * 펌웨어 파일 선택 결과 처리: 읽기 + 간단한 검사 → [OtaUi.Confirm] 또는 [OtaUi.FileError].
     * [TODO] 서버에서 버전을 받게 되면 Confirm 에 "새 버전"을 추가한다.
     */
    @SuppressLint("MissingPermission")
    fun selectFirmwareFile(context: Context, device: Device, firmwareUri: Uri) {
        val name = displayName(device)
        viewModelScope.launch {
            try {
                val (bytes, meta) = withContext(Dispatchers.IO) {
                    val data = context.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                    data to readFileMeta(context, firmwareUri)
                }
                if (bytes == null) {
                    setOtaUi(device.mac, OtaUi.FileError(name, "파일을 열 수 없어요. 다른 파일을 선택해 주세요."))
                    return@launch
                }
                if (bytes.size < OTA_MIN_FILE_BYTES) {
                    setOtaUi(device.mac, OtaUi.FileError(name, "펌웨어 파일이 아닌 것 같아요 (크기 ${bytes.size}B)."))
                    return@launch
                }
                pendingOtaFirmware[device.mac] = bytes
                val detail = _deviceDetails.value[device.mac]
                setOtaUi(
                    device.mac,
                    OtaUi.Confirm(
                        deviceName       = name,
                        currentVersion   = detail?.deviceInfo?.firmwareRevision?.trim()?.ifEmpty { null },
                        fileName         = meta.first ?: "펌웨어 파일",
                        sizeBytes        = bytes.size,
                        modifiedAtMs     = meta.second,
                        unusualExtension = meta.first?.endsWith(".bin", ignoreCase = true) == false,
                        lowBattery       = (detail?.batteryLevel ?: 100) < OTA_LOW_BATTERY_PERCENT
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "OTA file read error: ${e.message}", e)
                setOtaUi(device.mac, OtaUi.FileError(name, "파일을 읽지 못했어요. 다른 파일을 선택해 주세요."))
            }
        }
    }

    /** 파일 이름, 수정 시각(ms). SAF 문서에 없으면 null */
    private fun readFileMeta(context: Context, uri: Uri): Pair<String?, Long?> = try {
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val modIdx  = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val n = if (nameIdx >= 0) c.getString(nameIdx) else null
            val m = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else null
            n to m
        } ?: (null to null)
    } catch (e: Exception) {
        null to null
    }

    /** 확인 화면에서 "업데이트 시작" (실패 후 "다시 시도"도 같은 파일로) */
    @SuppressLint("MissingPermission")
    fun confirmOta(device: Device) {
        val firmware = pendingOtaFirmware[device.mac] ?: run {
            Log.e(TAG, "OTA: 대기 중인 펌웨어 없음")
            setOtaUi(device.mac, OtaUi.FileError(displayName(device), "파일을 다시 선택해 주세요."))
            return
        }
        startOtaFromBytes(device, firmware)
    }

    /** 결과·오류 화면을 닫는다 */
    fun dismissOta(mac: String) {
        if (_otaInProgress.value[mac] == true) return // 진행 중에는 닫지 않는다
        otaRebootJobs.remove(mac)?.cancel()
        pendingOtaFirmware.remove(mac)
        setOtaUi(mac, null)
    }

    /** 펌웨어 ByteArray로 OTA 진행 */
    @SuppressLint("MissingPermission")
    private fun startOtaFromBytes(device: Device, firmware: ByteArray) {
        val name = displayName(device)
        viewModelScope.launch {
            try {
                _otaInProgress.value += (device.mac to true)
                _otaProgress.value   += (device.mac to 0)
                setOtaUi(device.mac, OtaUi.Transferring(name, 0, null))

                Log.i(TAG, "OTA start: ${device.mac}, size=${firmware.size}B")
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val ok = device.startOta(
                    firmware   = firmware,
                    onProgress = { percent ->
                        _otaProgress.value += (device.mac to percent)
                        if (percent % 10 == 0) Log.i(TAG, "OTA progress: $percent% [${device.mac}]")
                        // 남은 시간: 지금까지 평균 속도 기준, 5% 이상 진행된 뒤부터 표시
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        val eta = if (percent in 5..99) ((elapsed * (100 - percent) / percent) / 1000).toInt() else null
                        if (_otaUi.value[device.mac] is OtaUi.Transferring) {
                            setOtaUi(device.mac, OtaUi.Transferring(name, percent, eta))
                        }
                    },
                    onResult   = { result ->
                        _otaInProgress.value += (device.mac to false)
                        result
                            .onSuccess {
                                Log.i(TAG, "OTA success: ${device.mac}")
                                pendingOtaFirmware.remove(device.mac)
                                awaitRebootAndReconnect(device)
                            }
                            .onFailure {
                                Log.e(TAG, "OTA failed: ${it.message}")
                                // 중지(abort)로 이미 결과를 표시했으면 덮어쓰지 않는다
                                if (_otaUi.value[device.mac] is OtaUi.Transferring) {
                                    setOtaUi(device.mac, OtaUi.Failed(name, otaFailureMessage(it), canRetry = true))
                                }
                            }
                    }
                )
                if (!ok) {
                    _otaInProgress.value += (device.mac to false)
                    Log.e(TAG, "OTA submit failed: ${device.mac}")
                    setOtaUi(device.mac, OtaUi.Failed(name, "응원봉이 업데이트를 시작하지 못했어요.", canRetry = true))
                }

            } catch (e: Exception) {
                _otaInProgress.value += (device.mac to false)
                Log.e(TAG, "OTA error: ${e.message}", e)
                setOtaUi(device.mac, OtaUi.Failed(name, otaFailureMessage(e), canRetry = true))
            }
        }
    }

    private fun otaFailureMessage(t: Throwable): String {
        val msg = t.message.orEmpty().lowercase()
        return when {
            "disconnect" in msg || "connection" in msg || "gatt" in msg ->
                "업데이트 중 연결이 끊겼어요. 응원봉을 가까이 두고 다시 시도해 주세요."
            "reject" in msg || "denied" in msg || "invalid" in msg ->
                "응원봉이 이 파일을 받지 않았어요. 파일이 맞는지 확인해 주세요."
            else -> "업데이트에 실패했어요. 다시 시도해 주세요."
        }
    }

    /**
     * 전송 완료 후 응원봉이 재부팅하는 동안 기다렸다가 다시 연결해 버전을 읽는다.
     * 재부팅 시간은 펌웨어 측 값이 아직 없어 [OTA_REBOOT_WAIT_MS] / [OTA_RECONNECT_TIMEOUT_MS] 기본값을 쓴다.
     */
    private fun awaitRebootAndReconnect(device: Device) {
        val name = displayName(device)
        setOtaUi(device.mac, OtaUi.Rebooting(name))
        otaRebootJobs.remove(device.mac)?.cancel()
        otaRebootJobs[device.mac] = viewModelScope.launch {
            delay(OTA_REBOOT_WAIT_MS)
            val deadline = android.os.SystemClock.elapsedRealtime() + OTA_RECONNECT_TIMEOUT_MS
            var lastAttemptAt = 0L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (_connectionStates.value[device.mac] == true) break
                val now = android.os.SystemClock.elapsedRealtime()
                // 연결 시도가 겹치지 않도록 일정 간격으로만 다시 시도한다
                if (now - lastAttemptAt >= OTA_RECONNECT_RETRY_MS) {
                    lastAttemptAt = now
                    connect(context, device)
                }
                delay(1_000L)
            }
            val reconnected = _connectionStates.value[device.mac] == true
            if (reconnected) delay(1_500L) // DIS 읽기 대기
            val version = _deviceDetails.value[device.mac]?.deviceInfo?.firmwareRevision?.trim()?.ifEmpty { null }
            setOtaUi(device.mac, OtaUi.Success(name, if (reconnected) version else null, reconnected))
        }
    }

    /**
     * OTA 중지. 사용자 결정(2026-11-27): 중지하면 응원봉이 꺼진다. UI 에서 확인을 받은 뒤 호출한다.
     */
    @SuppressLint("MissingPermission")
    fun abortOta(device: Device) {
        device.abortOta()
        _otaInProgress.value -= device.mac
        _otaProgress.value   -= device.mac
        pendingOtaFirmware.remove(device.mac)
        setOtaUi(
            device.mac,
            OtaUi.Failed(
                displayName(device),
                "업데이트를 중지했어요. 응원봉이 꺼졌으니 다시 켜서 연결한 뒤 업데이트해 주세요.",
                canRetry = false
            )
        )
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

    /**
     * 전파 여부(FF09) 토글 — BLE 모드 응원봉 전용.
     * 설정은 연결 단위이므로 앱에도 저장해 두고, 재연결 시 [applyBroadcastingOnConnect]로 다시 적용한다.
     */
    fun toggleBroadcasting(device: Device, enabled: Boolean) {
        // BLE 모드 + FF09 지원 기기에서만 동작 (UI에서도 비활성)
        if (_deviceDetails.value[device.mac]?.supportsBroadcasting != true) {
            Log.w(TAG, "toggleBroadcasting 무시: FF09 미지원/중계기 모드 ${device.mac}")
            return
        }
        DevicePreferences.setBroadcasting(device.mac, enabled)

        if (device.supportsBroadcasting() && !device.setBroadcasting(enabled)) {
            Log.w(TAG, "setBroadcasting($enabled) 실패: ${device.mac}")
        }

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(broadcasting = enabled)
                ?: buildDeviceDetailInfo(device, broadcasting = enabled)
        }
    }

    /**
     * 공연 모드 토글(FF0A SHOW_ENTER / SHOW_EXIT).
     * 공연 모드 중에는 응원봉이 전원 버튼 외의 버튼 입력을 무시한다. 신호가 닿는 모든 응원봉이 대상(groupMask=0).
     * 명령은 멱등이라 상태가 어긋나 있어도 다시 보내면 맞춰진다.
     * @return 전송을 제출했으면 true (이때만 표시 상태를 바꾼다)
     */
    @SuppressLint("MissingPermission")
    fun toggleShowMode(device: Device, enabled: Boolean): Boolean {
        if (_otaInProgress.value[device.mac] == true) {
            Log.w(TAG, "toggleShowMode 무시: OTA 진행 중 ${device.mac}")
            return false
        }
        if (!device.supportsControl()) {
            Log.w(TAG, "toggleShowMode 무시: FF0A 미지원(펌웨어 < 2.200) ${device.mac}")
            return false
        }
        val ok = if (enabled) device.showEnter() else device.showExit()
        Log.i(TAG, "${if (enabled) "SHOW_ENTER" else "SHOW_EXIT"} ${device.mac} → $ok")
        if (ok) {
            _showModeEnabled.value =
                if (enabled) _showModeEnabled.value + device.mac else _showModeEnabled.value - device.mac
        }
        return ok
    }

    /**
     * 이 기기로 공연 모드를 켠 상태(앱이 SHOW_ENTER를 보낸 기기)면 연결을 끊기 전에 SHOW_EXIT를 보낸다.
     * 연결이 끊긴 뒤에는 FF0A를 쓸 수 없고, 공연 모드는 응원봉이 재부팅될 때까지 남기 때문이다.
     * @return SHOW_EXIT 전송을 제출했으면 true (호출자는 전송이 나갈 시간을 준 뒤 끊는다)
     */
    @SuppressLint("MissingPermission")
    private fun sendShowExitIfNeeded(device: Device): Boolean {
        if (device.mac !in _showModeEnabled.value) return false
        _showModeEnabled.value = _showModeEnabled.value - device.mac
        return try {
            val ok = device.supportsControl() && device.showExit()
            Log.i(TAG, "연결 해제 전 SHOW_EXIT ${device.mac} → $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "연결 해제 전 SHOW_EXIT 실패 ${device.mac}: ${e.message}")
            false
        }
    }

    /**
     * 공연 제어 SYSTEM_OFF(FF0A) — 복구 불가(꺼진 응원봉은 버튼으로만 켤 수 있다).
     * 호출 전에 UI에서 사용자 확인을 반드시 거쳐야 한다(SDK는 확인 절차를 제공하지 않음).
     * groupMask=0 → 중계기/Master를 통해 도달하는 모든 응원봉이 대상(연결된 기기 1대가 아님).
     * 중계기 자신은 대상이 아니며, BLE 모드 Master는 꺼지면서 연결이 끊어진다.
     */
    @SuppressLint("MissingPermission")
    fun systemOff(device: Device): Boolean {
        if (_otaInProgress.value[device.mac] == true) {
            Log.w(TAG, "systemOff 무시: OTA 진행 중 ${device.mac}")
            return false
        }
        if (!device.supportsControl()) {
            Log.w(TAG, "systemOff 무시: FF0A 미지원(펌웨어 < 2.200) ${device.mac}")
            return false
        }
        val ok = device.systemOff()
        Log.i(TAG, "systemOff 전송 ${device.mac} → $ok")
        return ok
    }

    private fun setBroadcastingSupported(mac: String, supported: Boolean) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            this[mac]?.let { this[mac] = it.copy(supportsBroadcasting = supported) }
        }
    }

    /**
     * 연결 직후 FF09 적용. 응원봉은 연결할 때마다 true로 초기화되므로
     * 저장된 값이 false(연결된 응원봉만 반응)인 경우에만 다시 써 준다.
     * 중계기는 FF09가 없어 [Device.supportsBroadcasting]이 false → 호출하지 않는다.
     */
    private fun applyBroadcastingOnConnect(device: Device) {
        val supported = device.supportsBroadcasting()
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac] ?: buildDeviceDetailInfo(device)
            this[device.mac] = existing.copy(supportsBroadcasting = supported)
        }
        if (!supported) return

        val desired = DevicePreferences.getBroadcasting(device.mac)
        if (!desired && !device.setBroadcasting(false)) {
            Log.w(TAG, "setBroadcasting(false) 실패: ${device.mac}")
        }
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            this[device.mac]?.let { this[device.mac] = it.copy(broadcasting = desired) }
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

        // 기기 연결은 사용자가 직접 끊을 때까지 유지한다(2026-11-27 결정, B1).
        // 예전에는 Activity 가 끝나면 여기서 모든 기기에 SHOW_EXIT 후 연결을 끊었다.
        // 연결은 SDK 가 계속 갖고 있고, 화면이 다시 열리면 init 에서 SDK 연결 상태를 다시 읽어 온다.
        connectedDevices.clear()
    }
}
