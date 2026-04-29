package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.lightstick.music.core.util.FirmwareVersionParser
import com.lightstick.music.core.util.Log
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
import com.lightstick.music.domain.usecase.device.RegisterEventRulesUseCase
import com.lightstick.music.domain.usecase.device.SendFindEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.music.domain.usecase.device.GetCachedDeviceInfoUseCase
import com.lightstick.music.domain.usecase.device.StartScanUseCase
import com.lightstick.music.domain.usecase.device.StopScanUseCase
import com.lightstick.music.domain.usecase.device.ObserveDeviceStatesUseCase
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
    @ApplicationContext private val context: Context,
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

    // ═══════════════════════════════════════════════════════════
    // State Flows
    // ═══════════════════════════════════════════════════════════

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
    val otaProgress: StateFlow<Map<String, Int>> = _otaProgress.asStateFlow()

    private val _otaInProgress = MutableStateFlow<Map<String, Boolean>>(emptyMap())
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
    val eventStates: StateFlow<Map<String, Map<EventType, Boolean>>> = _eventStates.asStateFlow()

    /** 스캔 Job 추적 */
    private var scanJob: Job? = null

    /** 배터리 모니터링 Job (MAC → Job) */
    private val batteryMonitoringJobs = mutableMapOf<String, Job>()

    // ═══════════════════════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════════════════════

    init {
        Log.d(TAG, "🚀 Initializing DeviceViewModel...")
        PermissionManager.logPermissionStatus(context, TAG)
        Log.d(TAG, "📡 Starting to observe device state events...")
        observeDeviceStateEvents()
    }

    // ═══════════════════════════════════════════════════════════
    // Device State Event Observer
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // SDK 이벤트 처리
    // ═══════════════════════════════════════════════════════════

    /**
     * SDK에서 연결 이벤트 감지 시 처리.
     * DeviceViewModel.connect()를 통해 이미 처리된 경우는 skip.
     *
     * [수정] 스캔 없이 복원된 기기도 _devices에 즉시 추가
     * → 스캔 중 연결되어도 "연결된 기기" 섹션에 즉시 표시
     */
    private fun onDeviceConnectedFromSdk(mac: String) {
        Log.d(TAG, "🔗 [SDK] Connected: $mac")

        if (connectedDevices.containsKey(mac)) {
            Log.d(TAG, "⏭️ 이미 관리 중: $mac"); return
        }

        val device = _devices.value.find { it.mac == mac }
            ?: Device(mac = mac, name = DevicePreferences.getDeviceName(mac), rssi = null)

        connectedDevices[mac] = device

        // ✅ _devices에 없는 기기(스캔 외 복원/외부 연결)도 즉시 목록에 추가
        if (_devices.value.none { it.mac == mac }) {
            _devices.value = (_devices.value + device).sortedWith(
                compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                    .thenByDescending { it.rssi ?: -100 }
            )
            Log.d(TAG, "📋 Device added to list: $mac")
        }

        updateConnectionState(mac, true)

        if (!_deviceDetails.value.containsKey(mac)) {
            initializeDeviceDetail(device)
        }

        val deviceInfo = getCachedDeviceInfoUseCase(mac)
        if (deviceInfo != null) {
            updateDeviceInfoFromCallback(mac, deviceInfo)
            Log.d(TAG, "✅ DeviceInfo 캐시 적용: $mac")
        }

        registerDeviceEventRules(device)
        startBatteryMonitoring(mac)

        Log.d(TAG, "✅ 연결 처리 완료: $mac")
    }

    /**
     * SDK에서 연결 해제 이벤트 감지 시 처리.
     */
    private fun onDeviceDisconnectedFromSdk(mac: String) {
        Log.d(TAG, "🔌 [SDK] Disconnected: $mac")

        connectedDevices.remove(mac)
        updateConnectionState(mac, false)
        stopBatteryMonitoring(mac)

        // 연결 해제 시 디바이스 상세 상태 초기화
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = false)
        }

        // OTA 진행 상태 정리
        if (_otaInProgress.value.containsKey(mac)) {
            _otaInProgress.value = _otaInProgress.value - mac
            _otaProgress.value   = _otaProgress.value - mac
        }

        // 이벤트 규칙 상태 정리
        if (_eventStates.value.containsKey(mac)) {
            _eventStates.value = _eventStates.value - mac
        }

        Log.d(TAG, "✅ 연결 해제 처리 완료: $mac")
    }

    // ═══════════════════════════════════════════════════════════
    // BLE Scan
    // ═══════════════════════════════════════════════════════════

    /**
     * 스캔 결과 단건을 _devices에 즉시 반영 (실시간 업데이트용)
     * onFound 콜백에서 호출됩니다.
     */
    private fun applyScannedDevice(device: Device) {
        DevicePreferences.saveDeviceName(device.mac, device.name)
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.mac == device.mac }
        if (idx >= 0) {
            current[idx] = device  // RSSI 등 정보 갱신
        } else {
            current.add(device)    // 신규 디바이스 추가
        }
        _devices.value = current.sortedWith(
            compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                .thenByDescending { it.rssi ?: -100 }
        )
    }

    /**
     * 스캔 완료 후 전체 결과를 일괄 반영
     */
    private fun applyScannedDevices(scannedDevices: List<Device>) {
        scannedDevices.forEach { DevicePreferences.saveDeviceName(it.mac, it.name) }
        val current = _devices.value.toMutableList()
        scannedDevices.forEach { device ->
            val idx = current.indexOfFirst { it.mac == device.mac }
            if (idx >= 0) current[idx] = device else current.add(device)
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
        if (_isScanning.value) {
            Log.d(TAG, "⏭️ Already scanning. Use refreshScan() to restart."); return
        }

        _isScanning.value = true
        // 연결된 기기는 유지, 미연결 기기만 초기화
        _devices.value = _devices.value.filter { _connectionStates.value[it.mac] == true }

        scanJob = viewModelScope.launch {
            try {
                Log.d(TAG, "🔍 Starting BLE scan (${AppConstants.DEVICE_SCAN_DURATION_MS}ms)...")
                startScanUseCase(
                    context    = context,
                    durationMs = AppConstants.DEVICE_SCAN_DURATION_MS,
                    filter     = { true },
                    // ✅ 발견 즉시 _devices 업데이트
                    onFound    = { device -> applyScannedDevice(device) }
                ).onSuccess { scannedDevices ->
                    Log.d(TAG, "✅ Scan completed: ${scannedDevices.size} devices found")
                }.onFailure { error ->
                    Log.e(TAG, "❌ Scan failed: ${error.message}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "⏹️ Scan job cancelled: ${e.message}")
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * Pull-to-Refresh 전용 재스캔.
     *
     * [수정] 기존 문제: 외부 launch의 finally { _isScanning = false }가
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
                // 1. 이전 스캔 Job 취소
                if (scanJob?.isActive == true) {
                    Log.d(TAG, "🛑 Cancelling previous scan job...")
                    scanJob?.cancel()
                    scanJob = null
                }

                // 2. BLE 하드웨어 스캔 중지
                stopScanUseCase(context)
                _isScanning.value = false
                Log.d(TAG, "✅ Previous scan stopped")

            } catch (e: Exception) {
                _isScanning.value = false
                Log.e(TAG, "❌ Stop scan error: ${e.message}")
                return@launch
            }

            // 3. startScan() 재사용 → _isScanning 생명주기는 startScan이 관리
            startScan(context)
        }
    }

    fun stopScan() {
        if (!_isScanning.value) {
            Log.d(TAG, "Not scanning, skip stopScan()"); return
        }

        scanJob?.cancel()
        scanJob = null

        viewModelScope.launch {
            stopScanUseCase(context)
                .onSuccess { Log.d(TAG, "✅ Scan stopped") }
                .onFailure { Log.e(TAG, "❌ Error stopping scan: ${it.message}") }
            _isScanning.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Device Connection
    // ═══════════════════════════════════════════════════════════

    fun toggleConnection(context: Context, device: Device) {
        val isConnected = _connectionStates.value[device.mac] == true
        if (isConnected) disconnect(context, device) else connect(context, device)
    }

    private fun connect(context: Context, device: Device) {
        viewModelScope.launch {
            try {
                if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available"); return@launch
                }

                Log.d(TAG, "🔗 Connecting to ${device.mac}...")

                connectDeviceUseCase(
                    context      = context,
                    device       = device,
                    onConnected  = {
                        Log.d(TAG, "✅ Connected: ${device.mac}")
                        // SDK 이벤트로 onDeviceConnectedFromSdk 호출 전에 먼저 등록
                        // → 중복 처리 방지
                        connectedDevices[device.mac] = device
                        viewModelScope.launch {
                            sendConnectionEffectUseCase(context, device).onFailure { error ->
                                Log.e(TAG, "❌ Connection animation failed: ${error.message}")
                            }
                        }
                    },
                    onFailed     = { error ->
                        Log.e(TAG, "❌ Connection failed: ${error.message}")
                        connectedDevices.remove(device.mac)
                    },
                    onDeviceInfo = { info ->
                        Log.d(TAG, "📋 DeviceInfo: ${info.deviceName}, bat=${info.batteryLevel}%")
                        updateDeviceInfoFromCallback(device.mac, info)
                    }
                ).onFailure { error ->
                    Log.e(TAG, "❌ Connect use case failed: ${error.message}")
                    connectedDevices.remove(device.mac)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during connect: ${e.message}", e)
                connectedDevices.remove(device.mac)
            }
        }
    }

    private fun disconnect(context: Context, device: Device) {
        viewModelScope.launch {
            try {
                if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available"); return@launch
                }

                Log.d(TAG, "🔌 Disconnecting: ${device.mac}...")

                disconnectDeviceUseCase(context, device)
                    .onSuccess { Log.d(TAG, "✅ Disconnect request sent: ${device.mac}") }
                    .onFailure { error -> Log.e(TAG, "❌ Disconnect failed: ${error.message}") }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Find Effect
    // ═══════════════════════════════════════════════════════════

    fun sendFindEffect(device: Device) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음"); return
        }

        viewModelScope.launch {
            try {
                if (_connectionStates.value[device.mac] != true) {
                    Log.w(TAG, "⚠️ Device not connected: ${device.mac}"); return@launch
                }
                sendFindEffectUseCase(context = context, deviceMac = device.mac)
                    .onSuccess { Log.d(TAG, "✅ FIND effect sent: ${device.mac}") }
                    .onFailure { error -> Log.e(TAG, "❌ FIND effect failed: ${error.message}") }
            } catch (e: Exception) {
                Log.e(TAG, "❌ FIND effect error: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // OTA
    // ═══════════════════════════════════════════════════════════

    /**
     * [테스트용] 파일 선택 → 버전 비교 → [otaVersionCheck] StateFlow 업데이트
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
                    Log.e(TAG, "❌ OTA: Cannot open firmware file")
                    return@launch
                }

                Log.d(TAG, "📦 OTA firmware size: ${firmware.size} bytes")

                val deviceVersion = _deviceDetails.value[device.mac]
                    ?.deviceInfo?.firmwareRevision.orEmpty()

                // 파일에서 버전 파싱, 실패 시 테스트용 시뮬레이션 버전 사용
                val fileVersion = FirmwareVersionParser.parseFromBytes(firmware)
                    ?: FirmwareVersionParser.simulateTestVersion(deviceVersion).also {
                        Log.d(TAG, "⚠️ OTA: 파일에서 버전 파싱 불가 → 테스트 버전 사용: $it")
                    }

                val isUpdateAvailable = FirmwareVersionParser.isNewerVersion(fileVersion, deviceVersion)
                Log.d(TAG, "OTA 버전 비교: 파일=$fileVersion / 디바이스=$deviceVersion / 업데이트=${isUpdateAvailable}")

                pendingOtaFirmware = firmware
                _otaVersionCheck.value = OtaVersionCheck(fileVersion, deviceVersion, isUpdateAvailable)

            } catch (e: Exception) {
                Log.e(TAG, "❌ OTA version check error: ${e.message}", e)
            }
        }
    }

    /**
     * 사용자가 업데이트 확인 → [pendingOtaFirmware] 로 OTA 시작
     */
    @SuppressLint("MissingPermission")
    fun startPendingOta(device: Device) {
        val firmware = pendingOtaFirmware ?: run {
            Log.e(TAG, "❌ OTA: 대기 중인 펌웨어 없음")
            return
        }
        pendingOtaFirmware = null
        startOtaFromBytes(device, firmware)
    }

    /** 버전 체크 결과 및 대기 펌웨어 초기화 */
    fun clearOtaVersionCheck() {
        _otaVersionCheck.value = null
        pendingOtaFirmware = null
    }

    /** 펌웨어 ByteArray로 OTA 진행 */
    @SuppressLint("MissingPermission")
    private fun startOtaFromBytes(device: Device, firmware: ByteArray) {
        viewModelScope.launch {
            try {
                _otaInProgress.value += (device.mac to true)
                _otaProgress.value   += (device.mac to 0)

                val ok = device.startOta(
                    firmware   = firmware,
                    onProgress = { percent ->
                        _otaProgress.value += (device.mac to percent)
                        Log.d(TAG, "OTA progress: $percent%")
                    },
                    onResult   = { result ->
                        _otaInProgress.value += (device.mac to false)
                        result
                            .onSuccess { Log.d(TAG, "✅ OTA completed: ${device.mac}") }
                            .onFailure { Log.e(TAG, "❌ OTA failed: ${it.message}") }
                    }
                )
                if (!ok) {
                    _otaInProgress.value += (device.mac to false)
                    Log.e(TAG, "❌ OTA submit failed: ${device.mac}")
                }

            } catch (e: Exception) {
                _otaInProgress.value += (device.mac to false)
                Log.e(TAG, "❌ OTA error: ${e.message}", e)
            }
        }
    }

    // [테스트용] URI 직접 전달 방식 — checkFirmwareVersion() 으로 대체됨, 하위 호환용으로 유지
    @SuppressLint("MissingPermission")
    fun startOta(context: Context, device: Device, firmwareUri: Uri) {
        viewModelScope.launch {
            try {
                val firmware = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                } ?: run { Log.e(TAG, "❌ OTA: Cannot open firmware file"); return@launch }

                Log.d(TAG, "📦 OTA firmware size: ${firmware.size} bytes")
                startOtaFromBytes(device, firmware)

            } catch (e: Exception) {
                Log.e(TAG, "❌ OTA error: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Event Rules
    // ═══════════════════════════════════════════════════════════

    private fun registerDeviceEventRules(device: Device) {
        registerEventRulesUseCase(context, device).onFailure { error ->
            Log.e(TAG, "❌ Failed to register event rules for ${device.mac}: ${error.message}")
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
        Log.d(TAG, if (enabled) "✅ Call event ON: ${device.mac}" else "🔕 Call event OFF: ${device.mac}")
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
        Log.d(TAG, if (enabled) "✅ SMS event ON: ${device.mac}" else "🔕 SMS event OFF: ${device.mac}")
    }

    fun toggleBroadcasting(device: Device, enabled: Boolean) {
        DevicePreferences.setBroadcasting(device.mac, enabled)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            this[device.mac] = existing?.copy(broadcasting = enabled)
                ?: buildDeviceDetailInfo(device, broadcasting = enabled)
        }

        Log.d(TAG, if (enabled) "✅ Broadcasting ON: ${device.mac}" else "🔕 Broadcasting OFF: ${device.mac}")
    }

    // ═══════════════════════════════════════════════════════════
    // Battery Monitoring
    // ═══════════════════════════════════════════════════════════

    /**
     * 배터리 레벨 모니터링 시작
     *
     * 펌웨어 BAS Notification 미지원으로 주기적 readBattery() 방식 사용.
     * - Connected 시 즉시 1회 조회
     * - 이후 [AppConstants.BATTERY_MONITOR_INTERVAL_MS] 간격으로 반복 조회
     * - Disconnected 시 [stopBatteryMonitoring]으로 자동 중지
     */
    private fun startBatteryMonitoring(mac: String) {
        stopBatteryMonitoring(mac)  // 중복 방지

        batteryMonitoringJobs[mac] = viewModelScope.launch {
            Log.d(TAG, "🔋 Battery monitoring started: $mac")

            readBatteryLevel(mac)  // 연결 직후 즉시 1회

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
        Log.d(TAG, "🔋 Battery monitoring stopped: $mac")
    }

    /**
     * 배터리 레벨 1회 조회 및 UI 업데이트
     */
    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(mac: String) {
        val device = Device(mac = mac)
        val submitted = device.readBattery { result ->
            result
                .onSuccess { level ->
                    Log.d(TAG, "🔋 Battery: $mac → $level%")
                    updateBatteryLevel(mac, level)
                }
                .onFailure { e ->
                    Log.w(TAG, "⚠️ Battery read failed: $mac → ${e.message}")
                }
        }
        if (!submitted) Log.w(TAG, "⚠️ Battery read not submitted: $mac")
    }

    // ═══════════════════════════════════════════════════════════
    // Device Detail Management
    // ═══════════════════════════════════════════════════════════

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

        Log.d(TAG, "✅ Device detail initialized: ${device.mac}")
    }

    private fun updateDeviceInfoFromCallback(mac: String, deviceInfo: DeviceInfo) {
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
        Log.d(TAG, "✅ DeviceInfo updated: $mac")
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

    // ═══════════════════════════════════════════════════════════
    // State Updates (내부 헬퍼)
    // ═══════════════════════════════════════════════════════════

    private fun updateConnectionState(mac: String, isConnected: Boolean) {
        _connectionStates.value += (mac to isConnected)
        _connectedDeviceCount.value = _connectionStates.value.count { it.value }
        Log.d(TAG, "📍 Connection state: $mac → $isConnected")

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = isConnected)
        }

        _devices.value = _devices.value.sortedWith(
            compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                .thenByDescending { it.rssi ?: -100 }
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 Cleaning up ViewModel...")

        scanJob?.cancel()
        batteryMonitoringJobs.values.forEach { it.cancel() }
        batteryMonitoringJobs.clear()

        if (PermissionManager.hasBluetoothConnectPermission(context)) {
            connectedDevices.values.forEach { device ->
                try {
                    disconnectDeviceUseCase(context, device)
                    Log.d(TAG, "   Disconnected: ${device.mac}")
                } catch (e: Exception) {
                    Log.e(TAG, "   Error disconnecting ${device.mac}: ${e.message}")
                }
            }
        }

        connectedDevices.clear()
        Log.d(TAG, "✅ Cleanup completed")
    }
}