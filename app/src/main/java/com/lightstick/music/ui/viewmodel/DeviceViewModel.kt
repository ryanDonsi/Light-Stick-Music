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
import com.lightstick.music.domain.usecase.device.GetConnectedDevicesUseCase
import com.lightstick.music.domain.usecase.device.StartScanUseCase
import com.lightstick.music.domain.usecase.device.StopScanUseCase
import com.lightstick.music.domain.device.ObserveDeviceStatesUseCase
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import com.lightstick.events.EventType
import kotlinx.coroutines.Job
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

    private val sendFindEffectUseCase        = SendFindEffectUseCase()
    private val connectDeviceUseCase         = ConnectDeviceUseCase()
    private val disconnectDeviceUseCase      = DisconnectDeviceUseCase()
    private val registerEventRulesUseCase    = RegisterEventRulesUseCase()
    private val sendConnectionEffectUseCase  = SendConnectionEffectUseCase()
    private val getConnectedDevicesUseCase   = GetConnectedDevicesUseCase()
    private val startScanUseCase             = StartScanUseCase()
    private val stopScanUseCase              = StopScanUseCase()

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

    /** 스캔 Job 추적 - 취소 가능하도록 보관 */
    private var scanJob: Job? = null

    /** 이전 연결 상태 스냅샷 - 변화 감지용 */
    private var previousConnectionStates = emptyMap<String, Boolean>()

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

        if (PermissionManager.hasBluetoothConnectPermission(appContext!!)) {
            syncConnectedDevicesOnInit()
        }

        PermissionManager.logPermissionStatus(appContext!!, TAG)

        Log.d(TAG, "📡 Starting to observe connection states...")
        observeConnectionStates()
    }

    // ═══════════════════════════════════════════════════════════
    // Connection State Observer
    // ═══════════════════════════════════════════════════════════

    private fun observeConnectionStates() {

        // 단일 옵저버에서 상태 변화(연결/해제)를 직접 감지
        // → EffectViewModel 등 외부에서 연결해도 DeviceViewModel 상태가 자동 동기화
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeConnectionStates().collect { newStates ->
                val prevStates = previousConnectionStates

                newStates.forEach { (mac, isNowConnected) ->
                    val wasConnected = prevStates[mac] ?: false
                    when {
                        isNowConnected && !wasConnected  -> onDeviceConnectedFromSdk(mac)
                        !isNowConnected && wasConnected  -> onDeviceDisconnectedFromSdk(mac)
                    }
                }

                previousConnectionStates = newStates
                _connectionStates.value = newStates
            }
        }

        // 연결 개수 관찰
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeConnectedCount().collect { count ->
                _connectedDeviceCount.value = count
            }
        }

        // 연결된 디바이스 목록을 _devices에 병합
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeConnectedDevices().collect { connectedList ->
                val merged = (_devices.value + connectedList)
                    .distinctBy { it.mac }
                    .sortedWith(
                        compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                            .thenByDescending { it.rssi ?: -100 }
                    )
                _devices.value = merged
            }
        }
    }

    /**
     * SDK에서 신규 연결 이벤트 감지 시 처리.
     * DeviceViewModel.connect()를 통해 이미 처리된 경우는 skip.
     */
    private fun onDeviceConnectedFromSdk(mac: String) {
        Log.d(TAG, "🔗 [SDK 감지] 신규 연결: $mac")

        if (connectedDevices.containsKey(mac)) {
            Log.d(TAG, "   ⏭️ 이미 DeviceViewModel에서 관리 중, skip")
            return
        }

        val device = _devices.value.find { it.mac == mac }
            ?: Device(mac = mac, name = null, rssi = null)

        connectedDevices[mac] = device

        if (!_deviceDetails.value.containsKey(mac)) {
            initializeDeviceDetail(device)
            Log.d(TAG, "   ✅ deviceDetail 초기화: $mac")
        }

        registerDeviceEventRules(device)
        Log.d(TAG, "   ✅ 외부 연결 처리 완료: $mac")
    }

    /**
     * SDK에서 연결 해제 이벤트 감지 시 처리.
     * device OFF, 범위 이탈 등 모든 원인 포함.
     */
    private fun onDeviceDisconnectedFromSdk(mac: String) {
        Log.d(TAG, "🔌 [SDK 감지] 연결 해제: $mac")

        connectedDevices.remove(mac)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = false)
        }

        _devices.value = _devices.value.sortedWith(
            compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                .thenByDescending { it.rssi ?: -100 }
        )

        Log.d(TAG, "   ✅ 연결 해제 처리 완료: $mac")
    }

    // ═══════════════════════════════════════════════════════════
    // BLE Scan
    // ═══════════════════════════════════════════════════════════

    /** 스캔 결과를 _devices에 병합·정렬하는 공통 헬퍼 */
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

    /**
     * 최초 1회 스캔 (LaunchedEffect 등에서 호출).
     * 이미 스캔 중이라면 skip → 재시작은 [refreshScan] 사용.
     * 스캔 시간: [AppConstants.DEVICE_SCAN_DURATION_MS]
     */
    fun startScan(context: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted")
            return
        }
        if (_isScanning.value) {
            Log.d(TAG, "⏭️ Already scanning. Use refreshScan() to restart.")
            return
        }

        _isScanning.value = true
        _devices.value = _devices.value.filter { _connectionStates.value[it.mac] == true }

        scanJob = viewModelScope.launch {
            try {
                Log.d(TAG, "🔍 Starting BLE scan (${AppConstants.DEVICE_SCAN_DURATION_MS}ms)...")
                val result = startScanUseCase(
                    context   = context,
                    durationMs = AppConstants.DEVICE_SCAN_DURATION_MS,
                    filter    = { true }
                )
                result.onSuccess { scannedDevices ->
                    applyScannedDevices(scannedDevices)
                    _isScanning.value = false
                    Log.d(TAG, "✅ Scan completed: ${scannedDevices.size} devices found")
                }.onFailure { error ->
                    _isScanning.value = false
                    Log.e(TAG, "❌ Scan failed: ${error.message}")
                }
            } catch (e: Exception) {
                // Job cancel 시 CancellationException → isScanning 정리만 수행
                _isScanning.value = false
                Log.d(TAG, "⏹️ Scan job cancelled: ${e.message}")
            }
        }
    }

    /**
     * Pull-to-Refresh 전용 재스캔.
     * 1. 이전 scanJob 즉시 취소 (delay(SCAN_TIMEOUT_MS) 코루틴 종료)
     * 2. BLE 하드웨어 스캔 중지
     * 3. 순서 보장: 하나의 코루틴에서 stop → start 실행
     */
    fun refreshScan(context: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted")
            return
        }
        val ctx = appContext ?: return

        viewModelScope.launch {
            try {
                // 1. 이전 스캔 Job 취소
                if (scanJob?.isActive == true) {
                    Log.d(TAG, "🛑 Cancelling previous scan job...")
                    scanJob?.cancel()
                    scanJob = null
                }

                // 2. BLE 하드웨어 스캔 중지
                stopScanUseCase(ctx)
                _isScanning.value = false
                Log.d(TAG, "✅ Previous scan stopped")

                // 3. 연결된 디바이스만 유지
                _devices.value = _devices.value.filter { _connectionStates.value[it.mac] == true }

                // 4. 새 스캔 시작
                _isScanning.value = true
                Log.d(TAG, "🔍 Refresh scan started (${AppConstants.DEVICE_SCAN_DURATION_MS}ms)...")

                scanJob = viewModelScope.launch {
                    val result = startScanUseCase(
                        context   = context,
                        durationMs = AppConstants.DEVICE_SCAN_DURATION_MS,
                        filter    = { true }
                    )
                    result.onSuccess { scannedDevices ->
                        applyScannedDevices(scannedDevices)
                        _isScanning.value = false
                        Log.d(TAG, "✅ Refresh scan completed: ${scannedDevices.size} devices found")
                    }.onFailure { error ->
                        _isScanning.value = false
                        Log.e(TAG, "❌ Refresh scan failed: ${error.message}")
                    }
                }

            } catch (e: Exception) {
                _isScanning.value = false
                Log.e(TAG, "❌ Refresh scan error: ${e.message}")
            }
        }
    }

    fun stopScan() {
        if (!_isScanning.value) {
            Log.d(TAG, "Not scanning, skip stopScan()")
            return
        }

        Log.d(TAG, "🛑 Stopping BLE scan...")

        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            scanJob = null
        }

        val ctx = appContext
        if (ctx == null) {
            _isScanning.value = false
            return
        }

        viewModelScope.launch {
            val result = stopScanUseCase(ctx)
            result.onSuccess {
                _isScanning.value = false
                Log.d(TAG, "✅ Scan stopped")
            }.onFailure { error ->
                _isScanning.value = false
                Log.e(TAG, "❌ Error stopping scan: ${error.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Connection Management
    // ═══════════════════════════════════════════════════════════

    fun toggleConnection(context: Context, device: Device) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not granted")
            return
        }
        val isConnected = _connectionStates.value[device.mac] ?: false
        if (isConnected) disconnect(device) else connect(device)
    }

    private fun connect(device: Device) {
        viewModelScope.launch {
            try {
                val ctx = appContext
                if (ctx == null || !PermissionManager.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available")
                    return@launch
                }

                Log.d(TAG, "═══════════════════════════════════════")
                Log.d(TAG, "🔗 Connecting to ${device.mac}...")

                val result = connectDeviceUseCase(
                    context     = ctx,
                    device      = device,
                    onConnected = {
                        Log.d(TAG, "✅ Connected to ${device.mac}")
                        // connectedDevices에 추가 → onDeviceConnectedFromSdk에서 skip 처리
                        connectedDevices[device.mac] = device

                        viewModelScope.launch {
                            sendConnectionEffectUseCase(ctx, device).onFailure { error ->
                                Log.e(TAG, "❌ Connection animation failed: ${error.message}")
                            }
                        }

                        initializeDeviceDetail(device)
                        registerDeviceEventRules(device)
                    },
                    onFailed    = { error ->
                        Log.e(TAG, "❌ Connection failed for ${device.mac}: ${error.message}")
                        connectedDevices.remove(device.mac)
                    },
                    onDeviceInfo = { info ->
                        Log.d(TAG, "📋 DeviceInfo: ${info.deviceName}, bat=${info.batteryLevel}%")
                        updateDeviceInfoFromCallback(device.mac, info)
                    }
                )

                result.onFailure { error ->
                    Log.e(TAG, "❌ Connect use case failed: ${error.message}")
                    connectedDevices.remove(device.mac)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during connect: ${e.message}", e)
                connectedDevices.remove(device.mac)
            }
        }
    }

    private fun disconnect(device: Device) {
        viewModelScope.launch {
            try {
                val ctx = appContext
                if (ctx == null || !PermissionManager.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available")
                    return@launch
                }

                Log.d(TAG, "═══════════════════════════════════════")
                Log.d(TAG, "🔌 Disconnecting from ${device.mac}...")

                val result = disconnectDeviceUseCase(ctx, device)
                result.onSuccess {
                    // UI 상태는 onDeviceDisconnectedFromSdk()에서 자동 처리
                    Log.d(TAG, "✅ Disconnect request sent: ${device.mac}")
                }.onFailure { error ->
                    Log.e(TAG, "❌ Disconnect failed: ${error.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Event Rules Management
    // ═══════════════════════════════════════════════════════════

    private fun registerDeviceEventRules(device: Device) {
        val ctx = appContext ?: return
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
    // State Updates
    // ═══════════════════════════════════════════════════════════

    private fun updateConnectionState(mac: String, isConnected: Boolean) {
        _connectionStates.value += (mac to isConnected)
        Log.d(TAG, "📍 Connection state: $mac -> $isConnected")

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) this[mac] = existing.copy(isConnected = isConnected)
        }

        _devices.value = _devices.value.sortedWith(
            compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                .thenByDescending { it.rssi ?: -100 }
        )
    }

    @SuppressLint("MissingPermission")
    private fun syncConnectedDevicesOnInit() {
        viewModelScope.launch {
            try {
                val ctx = appContext ?: return@launch
                val result = getConnectedDevicesUseCase(ctx)

                result.onSuccess { systemConnected ->
                    Log.d(TAG, "═══════════════════════════════════════")
                    Log.d(TAG, "📱 Syncing ${systemConnected.size} connected devices from SDK")

                    systemConnected.forEach { device ->
                        Log.d(TAG, "  - ${device.mac} (${device.name}) RSSI: ${device.rssi}")
                        if (!connectedDevices.containsKey(device.mac)) {
                            connectedDevices[device.mac] = device
                            updateConnectionState(device.mac, true)
                            initializeDeviceDetail(device)
                            registerDeviceEventRules(device)
                            Log.d(TAG, "✅ Synced: ${device.mac}")
                        }
                    }

                    Log.d(TAG, "✅ Device sync completed")
                    Log.d(TAG, "═══════════════════════════════════════")
                }.onFailure { error ->
                    Log.e(TAG, "❌ Error syncing devices: ${error.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error syncing devices: ${e.message}", e)
            }
        }
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
            EventType.CALL_RINGING  to callEventEnabled,
            EventType.SMS_RECEIVED  to smsEventEnabled
        ))

        Log.d(TAG, "✅ Device detail initialized: ${device.mac}")
    }

    private fun updateDeviceInfoFromCallback(mac: String, deviceInfo: DeviceInfo) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            this[mac] = existing?.copy(deviceInfo = deviceInfo, batteryLevel = deviceInfo.batteryLevel)
                ?: DeviceDetailInfo(
                    mac              = mac,
                    name             = deviceInfo.deviceName,
                    rssi             = deviceInfo.rssi,
                    isConnected      = _connectionStates.value[mac] ?: false,
                    deviceInfo       = deviceInfo,
                    batteryLevel     = deviceInfo.batteryLevel,
                    otaProgress      = null,
                    isOtaInProgress  = false,
                    callEventEnabled = DevicePreferences.getCallEventEnabled(mac),
                    smsEventEnabled  = DevicePreferences.getSmsEventEnabled(mac),
                    broadcasting     = DevicePreferences.getBroadcasting(mac)
                )
        }
        Log.d(TAG, "✅ Device info updated: $mac")
    }

    private fun buildDeviceDetailInfo(
        device: Device,
        callEventEnabled: Boolean = DevicePreferences.getCallEventEnabled(device.mac),
        smsEventEnabled:  Boolean = DevicePreferences.getSmsEventEnabled(device.mac),
        broadcasting:     Boolean = DevicePreferences.getBroadcasting(device.mac)
    ) = DeviceDetailInfo(
        mac              = device.mac,
        name             = device.name,
        rssi             = device.rssi,
        isConnected      = _connectionStates.value[device.mac] ?: false,
        deviceInfo       = null,
        batteryLevel     = null,
        otaProgress      = null,
        isOtaInProgress  = false,
        callEventEnabled = callEventEnabled,
        smsEventEnabled  = smsEventEnabled,
        broadcasting     = broadcasting
    )

    // ═══════════════════════════════════════════════════════════
    // OTA Update
    // ═══════════════════════════════════════════════════════════

    fun startOtaUpdate(device: Device, firmwareUri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = appContext
                if (ctx == null || !PermissionManager.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available")
                    return@launch
                }

                val firmwareBytes = ctx.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                    ?: run {
                        Log.e(TAG, "❌ Failed to read firmware file")
                        return@launch
                    }

                Log.d(TAG, "🚀 Starting OTA for ${device.mac}, size=${firmwareBytes.size} bytes")

                _otaInProgress.value  += (device.mac to true)
                _otaProgress.value    += (device.mac to 0)
                _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                    this[device.mac] = this[device.mac]?.copy(isOtaInProgress = true, otaProgress = 0)
                        ?: return@apply
                }

                @SuppressLint("MissingPermission")
                fun doOta() {
                    device.startOta(
                        firmware   = firmwareBytes,
                        onProgress = { progress ->
                            _otaProgress.value   += (device.mac to progress)
                            _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                                this[device.mac] = this[device.mac]?.copy(otaProgress = progress) ?: return@apply
                            }
                            Log.d(TAG, "📊 OTA ${device.mac}: $progress%")
                        },
                        onResult   = { result ->
                            val done = result.isSuccess
                            _otaInProgress.value += (device.mac to false)
                            _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                                this[device.mac] = this[device.mac]?.copy(
                                    isOtaInProgress = false,
                                    otaProgress     = if (done) 100 else null
                                ) ?: return@apply
                            }
                            if (done) Log.d(TAG, "✅ OTA completed: ${device.mac}")
                            else      Log.e(TAG, "❌ OTA failed: ${result.exceptionOrNull()?.message}")
                        }
                    )
                }
                doOta()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start OTA: ${e.message}", e)
                _otaInProgress.value += (device.mac to false)
                _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                    this[device.mac] = this[device.mac]?.copy(isOtaInProgress = false, otaProgress = null)
                        ?: return@apply
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // FIND Effect
    // ═══════════════════════════════════════════════════════════

    fun sendFindEffect(device: Device) {
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
                sendFindEffectUseCase(context = ctx, deviceMac = device.mac)
                    .onSuccess { Log.d(TAG, "✅ FIND effect sent: ${device.mac}") }
                    .onFailure { error -> Log.e(TAG, "❌ FIND effect failed: ${error.message}") }
            } catch (e: Exception) {
                Log.e(TAG, "❌ FIND effect error: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 Cleaning up ViewModel...")

        scanJob?.cancel()
        scanJob = null

        val ctx = appContext
        if (ctx != null && PermissionManager.hasBluetoothConnectPermission(ctx)) {
            connectedDevices.values.forEach { device ->
                try {
                    disconnectDeviceUseCase(ctx, device)
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