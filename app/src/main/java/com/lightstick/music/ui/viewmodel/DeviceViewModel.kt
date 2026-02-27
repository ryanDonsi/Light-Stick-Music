package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.lightstick.music.core.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.core.constants.AppConstants
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
import com.lightstick.music.domain.device.ObserveDeviceStatesUseCase
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

class DeviceViewModel : ViewModel() {

    companion object {
        private const val TAG = AppConstants.Feature.VM_DEVICE
    }

    // ═══════════════════════════════════════════════════════════
    // UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val sendFindEffectUseCase       = SendFindEffectUseCase()
    private val connectDeviceUseCase        = ConnectDeviceUseCase()
    private val disconnectDeviceUseCase     = DisconnectDeviceUseCase()
    private val registerEventRulesUseCase   = RegisterEventRulesUseCase()
    private val sendConnectionEffectUseCase = SendConnectionEffectUseCase()
    private val getCachedDeviceInfoUseCase  = GetCachedDeviceInfoUseCase()
    private val startScanUseCase            = StartScanUseCase()
    private val stopScanUseCase             = StopScanUseCase()

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

    private val _eventStates = MutableStateFlow<Map<String, Map<EventType, Boolean>>>(emptyMap())
    val eventStates: StateFlow<Map<String, Map<EventType, Boolean>>> = _eventStates.asStateFlow()

    /** 스캔 Job 추적 */
    private var scanJob: Job? = null

    /** 배터리 모니터링 Job (MAC → Job) */
    private val batteryMonitoringJobs = mutableMapOf<String, Job>()

    private var appContext: Context? = null

    // ═══════════════════════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════════════════════

    fun initializeWithContext(context: Context) {
        if (appContext != null) {
            Log.d(TAG, "⏭️ Already initialized, skipping")
            return
        }

        Log.d(TAG, "🚀 Initializing DeviceViewModel...")
        appContext = context.applicationContext

        DevicePreferences.initialize(context.applicationContext)

        PermissionManager.logPermissionStatus(appContext!!, TAG)

        Log.d(TAG, "📡 Starting to observe device state events...")
        observeDeviceStateEvents()
    }

    // ═══════════════════════════════════════════════════════════
    // Device State Event Observer
    // ═══════════════════════════════════════════════════════════

    /**
     * SDK SharedFlow 기반 디바이스 상태 이벤트 관찰
     * - StateFlow Conflation 문제 없이 Connected/Disconnected 수신
     * - previousConnectionStates 수동 비교 불필요
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
     */
    private fun onDeviceConnectedFromSdk(mac: String) {
        Log.d(TAG, "🔗 [SDK] Connected: $mac")

        if (connectedDevices.containsKey(mac)) {
            Log.d(TAG, "⏭️ 이미 관리 중: $mac"); return
        }

        val device = _devices.value.find { it.mac == mac }
            ?: Device(mac = mac, name = null, rssi = null)

        connectedDevices[mac] = device
        updateConnectionState(mac, true)

        if (!_deviceDetails.value.containsKey(mac)) {
            initializeDeviceDetail(device)
        }

        // Connected 후 캐시된 DeviceInfo 조회
        val deviceInfo = getCachedDeviceInfoUseCase(mac)
        if (deviceInfo != null) {
            updateDeviceInfoFromCallback(mac, deviceInfo)
            Log.d(TAG, "✅ DeviceInfo 캐시 적용: $mac")
        }

        registerDeviceEventRules(device)

        // 배터리 모니터링 시작
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

        // 배터리 모니터링 중지
        stopBatteryMonitoring(mac)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = false)
        }

        Log.d(TAG, "✅ 연결 해제 처리 완료: $mac")
    }

    // ═══════════════════════════════════════════════════════════
    // BLE Scan
    // ═══════════════════════════════════════════════════════════

    private fun applyScannedDevices(scannedDevices: List<Device>) {
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

    fun startScan(context: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted"); return
        }
        if (_isScanning.value) {
            Log.d(TAG, "⏭️ Already scanning. Use refreshScan() to restart."); return
        }

        _isScanning.value = true
        _devices.value = _devices.value.filter { _connectionStates.value[it.mac] == true }

        scanJob = viewModelScope.launch {
            try {
                Log.d(TAG, "🔍 Starting BLE scan (${AppConstants.DEVICE_SCAN_DURATION_MS}ms)...")
                startScanUseCase(
                    context    = context,
                    durationMs = AppConstants.DEVICE_SCAN_DURATION_MS,
                    filter     = { true }
                ).onSuccess { scannedDevices ->
                    applyScannedDevices(scannedDevices)
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

    fun refreshScan(context: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted"); return
        }
        val ctx = appContext ?: return

        viewModelScope.launch {
            try {
                scanJob?.cancel()
                scanJob = null
                stopScanUseCase(ctx)
                _isScanning.value = false
                _devices.value = _devices.value.filter { _connectionStates.value[it.mac] == true }
                startScan(context)
            } catch (e: Exception) {
                _isScanning.value = false
                Log.e(TAG, "❌ Refresh scan error: ${e.message}")
            }
        }
    }

    fun stopScan(context: Context) {
        scanJob?.cancel()
        scanJob = null
        viewModelScope.launch {
            stopScanUseCase(context)
            _isScanning.value = false
            Log.d(TAG, "⏹️ Scan stopped")
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
                    context     = context,
                    device      = device,
                    onConnected = {
                        Log.d(TAG, "✅ Connected: ${device.mac}")
                        // SDK 이벤트로 onDeviceConnectedFromSdk 호출되지만
                        // connect()를 통한 경우 중복 처리 방지를 위해 먼저 등록
                        connectedDevices[device.mac] = device
                        viewModelScope.launch {
                            sendConnectionEffectUseCase(context, device).onFailure { error ->
                                Log.e(TAG, "❌ Connection animation failed: ${error.message}")
                            }
                        }
                    },
                    onFailed    = { error ->
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

    fun sendFindEffect(device: Device) {   // ← context 파라미터 제거 (기존 GitHub 패턴 유지)
        val ctx = appContext
        if (ctx == null || !PermissionManager.hasBluetoothConnectPermission(ctx)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                if (_connectionStates.value[device.mac] != true) {
                    Log.w(TAG, "⚠️ Device not connected: ${device.mac}")
                    return@launch
                }
                // ✅ UseCase 시그니처: invoke(context: Context, deviceMac: String)
                sendFindEffectUseCase(context = ctx, deviceMac = device.mac)
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

    @SuppressLint("MissingPermission")
    fun startOta(context: Context, device: Device, firmwareUri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(firmwareUri)
                    ?: run { Log.e(TAG, "❌ OTA: Cannot open firmware file"); return@launch }

                val firmware = inputStream.use { it.readBytes() }
                Log.d(TAG, "📦 OTA firmware size: ${firmware.size} bytes")

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

    // ═══════════════════════════════════════════════════════════
    // Event Rules
    // ═══════════════════════════════════════════════════════════

    private fun registerDeviceEventRules(device: Device) {
        val ctx = appContext ?: return
        // RegisterEventRulesUseCase.invoke(context: Context, device: Device)
        registerEventRulesUseCase(ctx, device).onFailure { error ->
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
     * 펌웨어가 BAS Notification 미지원(CCCD 없음)이므로
     * 주기적으로 readBattery()를 호출하여 배터리 상태를 갱신합니다.
     *
     * - Connected 시 즉시 1회 조회
     * - 이후 [AppConstants.BATTERY_MONITOR_INTERVAL_MS] 간격으로 반복 조회
     * - Disconnected 시 [stopBatteryMonitoring]으로 자동 중지
     */
    private fun startBatteryMonitoring(mac: String) {
        stopBatteryMonitoring(mac)  // 중복 방지

        batteryMonitoringJobs[mac] = viewModelScope.launch {
            Log.d(TAG, "🔋 Battery monitoring started: $mac")

            // 연결 직후 즉시 1회 조회
            readBatteryLevel(mac)

            // 이후 주기적 조회
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
}