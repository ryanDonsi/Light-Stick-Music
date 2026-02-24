package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.domain.usecase.device.ConnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.DisconnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.SendFindEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.music.domain.usecase.device.GetConnectedDevicesUseCase
import com.lightstick.music.domain.usecase.device.StartScanUseCase
import com.lightstick.music.domain.usecase.device.StopScanUseCase
import com.lightstick.music.domain.device.ObserveDeviceStatesUseCase
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import com.lightstick.events.EventAction
import com.lightstick.events.EventFilter
import com.lightstick.events.EventRule
import com.lightstick.events.EventTarget
import com.lightstick.events.EventTrigger
import com.lightstick.events.EventType
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceViewModel : ViewModel() {

    private val TAG = "DeviceVM"

    // ═══════════════════════════════════════════════════════════
    // UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val sendFindEffectUseCase = SendFindEffectUseCase()
    private val connectDeviceUseCase = ConnectDeviceUseCase()
    private val disconnectDeviceUseCase = DisconnectDeviceUseCase()
    private val sendConnectionEffectUseCase = SendConnectionEffectUseCase()
    private val getConnectedDevicesUseCase = GetConnectedDevicesUseCase()
    private val startScanUseCase = StartScanUseCase()
    private val stopScanUseCase = StopScanUseCase()

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
            updateConnectedCount()
        }

        PermissionManager.logPermissionStatus(appContext!!, TAG)

        Log.d(TAG, "📡 Starting to observe connection states...")
        observeConnectionStates()
    }

    private fun observeConnectionStates() {
        // 연결 상태 Map 관찰
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeConnectionStates().collect { states ->
                _connectionStates.value = states
            }
        }

        // 연결 개수 관찰
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeConnectedCount().collect { count ->
                _connectedDeviceCount.value = count
            }
        }

        // 연결된 디바이스 목록 병합
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeConnectedDevices().collect { connectedDevices ->
                val merged = (_devices.value + connectedDevices)
                    .distinctBy { it.mac }
                    .sortedWith(
                        compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                            .thenByDescending { it.rssi ?: -100 }
                    )

                _devices.value = merged
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // BLE Scan
    // ═══════════════════════════════════════════════════════════

    fun startScan(context: Context) {
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted")
            return
        }

        if (_isScanning.value) stopScan()

        _isScanning.value = true

        // 연결된 디바이스만 남기기
        _devices.value = _devices.value.filter {
            _connectionStates.value[it.mac] == true
        }

        viewModelScope.launch {
            try {
                // UseCase로 스캔 (30초)
                val result = startScanUseCase(
                    context = context,
                    durationMs = 30_000L,
                    filter = { true } // 모든 디바이스 스캔
                )

                result.onSuccess { scannedDevices ->
                    // 스캔된 디바이스 병합
                    val current = _devices.value.toMutableList()

                    scannedDevices.forEach { device ->
                        val existingIndex = current.indexOfFirst { it.mac == device.mac }
                        if (existingIndex >= 0) {
                            current[existingIndex] = device
                        } else {
                            current.add(device)
                        }
                    }

                    _devices.value = current.sortedWith(
                        compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                            .thenByDescending { it.rssi ?: -100 }
                    )

                    _isScanning.value = false
                    Log.d(TAG, "✅ Scan completed: ${scannedDevices.size} devices found")

                }.onFailure { error ->
                    _isScanning.value = false
                    Log.e(TAG, "❌ Scan failed: ${error.message}")
                }

            } catch (e: Exception) {
                _isScanning.value = false
                Log.e(TAG, "❌ Scan error: ${e.message}")
            }
        }
    }

    fun stopScan() {
        if (!_isScanning.value) {
            Log.d(TAG, "Not scanning, skip stopScan()")
            return
        }

        Log.d(TAG, "🛑 Stopping BLE scan...")

        val ctx = appContext
        if (ctx == null) {
            _isScanning.value = false
            return
        }

        viewModelScope.launch {
            val result = stopScanUseCase(ctx)

            result.onSuccess {
                _isScanning.value = false
                Log.d(TAG, "✅ Scan stopped successfully")
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

        val isCurrentlyConnected = _connectionStates.value[device.mac] ?: false

        if (isCurrentlyConnected) {
            disconnect(device)
        } else {
            connect(device)
        }
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
                    context = ctx,
                    device = device,
                    onConnected = {
                        Log.d(TAG, "✅ Connected to ${device.mac}")

                        connectedDevices[device.mac] = device
                        updateConnectionState(device.mac, true)
                        updateConnectedCount()

                        // ✅ UseCase 사용: 연결 애니메이션
                        viewModelScope.launch {
                            val animResult = sendConnectionEffectUseCase(ctx, device)
                            animResult.onFailure { error ->
                                Log.e(TAG, "❌ Connection animation failed: ${error.message}")
                            }
                        }

                        initializeDeviceDetail(device)
                        registerDeviceEventRules(device)
                    },
                    onFailed = { error ->
                        Log.e(TAG, "❌ Connection failed for ${device.mac}")
                        Log.e(TAG, "   Error: ${error.message}", error)
                    },
                    onDeviceInfo = { info ->
                        Log.d(TAG, "📋 DeviceInfo received for ${device.mac}:")
                        Log.d(TAG, "   ├─ Device Name: ${info.deviceName}")
                        Log.d(TAG, "   ├─ Model Number: ${info.modelNumber}")
                        Log.d(TAG, "   ├─ Firmware Revision: ${info.firmwareRevision}")
                        Log.d(TAG, "   ├─ Manufacturer: ${info.manufacturer}")
                        Log.d(TAG, "   └─ Battery: ${info.batteryLevel}%")

                        updateDeviceInfoFromCallback(device.mac, info)
                    }
                )

                result.onFailure { error ->
                    Log.e(TAG, "❌ Connect use case failed for ${device.mac}: ${error.message}")
                    connectedDevices.remove(device.mac)
                    updateConnectionState(device.mac, false)
                    updateConnectedCount()
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException during connect: ${e.message}")
                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                updateConnectedCount()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "❌ IllegalStateException during connect: ${e.message}")
                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                updateConnectedCount()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error during connect: ${e.message}", e)
                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                updateConnectedCount()
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

                @SuppressLint("MissingPermission")
                fun doDisconnect() {
                    device.disconnect()
                }

                doDisconnect()

                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                updateConnectedCount()

                Log.d(TAG, "✅ Disconnected from ${device.mac}")
                Log.d(TAG, "═══════════════════════════════════════")

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException during disconnect: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Event Rules Management
    // ═══════════════════════════════════════════════════════════

    fun toggleCallEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setCallEventEnabled(device.mac, enabled)

        val deviceStates = _eventStates.value[device.mac]?.toMutableMap() ?: mutableMapOf()
        deviceStates[EventType.CALL_RINGING] = enabled

        _eventStates.value = _eventStates.value + (device.mac to deviceStates)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(callEventEnabled = enabled)
            } else {
                this[device.mac] = DeviceDetailInfo(
                    mac = device.mac,
                    name = device.name,
                    rssi = device.rssi,
                    isConnected = _connectionStates.value[device.mac] ?: false,
                    deviceInfo = null,
                    batteryLevel = null,
                    otaProgress = null,
                    isOtaInProgress = false,
                    callEventEnabled = enabled,
                    smsEventEnabled = DevicePreferences.getSmsEventEnabled(device.mac),
                    broadcasting = DevicePreferences.getBroadcasting(device.mac)
                )
            }
        }

        // Event Rules 재등록
        registerDeviceEventRules(device)

        if (enabled) {
            Log.d(TAG, "✅ Call event enabled for ${device.mac}")
        } else {
            Log.d(TAG, "🔕 Call event disabled for ${device.mac}")
        }
    }

    fun toggleSmsEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setSmsEventEnabled(device.mac, enabled)

        val deviceStates = _eventStates.value[device.mac]?.toMutableMap() ?: mutableMapOf()
        deviceStates[EventType.SMS_RECEIVED] = enabled

        _eventStates.value = _eventStates.value + (device.mac to deviceStates)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(smsEventEnabled = enabled)
            } else {
                this[device.mac] = DeviceDetailInfo(
                    mac = device.mac,
                    name = device.name,
                    rssi = device.rssi,
                    isConnected = _connectionStates.value[device.mac] ?: false,
                    deviceInfo = null,
                    batteryLevel = null,
                    otaProgress = null,
                    isOtaInProgress = false,
                    callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac),
                    smsEventEnabled = enabled,
                    broadcasting = DevicePreferences.getBroadcasting(device.mac)
                )
            }
        }

        // Event Rules 재등록
        registerDeviceEventRules(device)

        if (enabled) {
            Log.d(TAG, "✅ SMS event enabled for ${device.mac}")
        } else {
            Log.d(TAG, "🔕 SMS event disabled for ${device.mac}")
        }
    }

    fun toggleBroadcasting(device: Device, enabled: Boolean) {
        DevicePreferences.setBroadcasting(device.mac, enabled)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(broadcasting = enabled)
            } else {
                this[device.mac] = DeviceDetailInfo(
                    mac = device.mac,
                    name = device.name,
                    rssi = device.rssi,
                    isConnected = _connectionStates.value[device.mac] ?: false,
                    deviceInfo = null,
                    batteryLevel = null,
                    otaProgress = null,
                    isOtaInProgress = false,
                    callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac),
                    smsEventEnabled = DevicePreferences.getSmsEventEnabled(device.mac),
                    broadcasting = enabled
                )
            }
        }

        if (enabled) {
            Log.d(TAG, "✅ Broadcasting enabled for ${device.mac}")
        } else {
            Log.d(TAG, "🔕 Broadcasting disabled for ${device.mac}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // State Updates
    // ═══════════════════════════════════════════════════════════

    private fun updateConnectionState(mac: String, isConnected: Boolean) {
        _connectionStates.value += (mac to isConnected)

        Log.d(TAG, "📍 Connection state updated: $mac -> $isConnected")

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) {
                this[mac] = existing.copy(isConnected = isConnected)
            }
        }

        _devices.value = _devices.value.sortedWith(
            compareByDescending<Device> {
                _connectionStates.value[it.mac] ?: false
            }.thenByDescending {
                it.rssi ?: -100
            }
        )
    }

    private fun updateConnectedCount() {
        viewModelScope.launch {
            try {
                val ctx = appContext ?: return@launch

                val result = getConnectedDevicesUseCase(ctx)

                result.onSuccess { connectedDevices ->
                    _connectedDeviceCount.value = connectedDevices.size

                    val connectedMacs = connectedDevices.map { it.mac }.toSet()

                    Log.d(TAG, "📊 Connected devices: ${connectedDevices.size}")
                    connectedDevices.forEach { device ->
                        Log.d(TAG, "   - ${device.mac} (${device.name})")
                    }

                    val updatedStates = _connectionStates.value.toMutableMap()
                    connectedMacs.forEach { mac -> updatedStates[mac] = true }
                    _devices.value.forEach { device ->
                        if (device.mac !in connectedMacs) {
                            updatedStates[device.mac] = false
                        }
                    }
                    _connectionStates.value = updatedStates

                }.onFailure { error ->
                    Log.e(TAG, "❌ Error updating connected count: ${error.message}")
                    _connectedDeviceCount.value = 0
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating connected count: ${e.message}", e)
                _connectedDeviceCount.value = 0
            }
        }
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
                            Log.d(TAG, "✅ Synced device: ${device.mac}")
                        } else {
                            Log.d(TAG, "⏭️ Already synced: ${device.mac}")
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
    // Device Detail Initialization
    // ═══════════════════════════════════════════════════════════

    private fun initializeDeviceDetail(device: Device) {
        val callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac)
        val smsEventEnabled = DevicePreferences.getSmsEventEnabled(device.mac)
        val broadcasting = DevicePreferences.getBroadcasting(device.mac)

        _deviceDetails.value = _deviceDetails.value + (device.mac to DeviceDetailInfo(
            mac = device.mac,
            name = device.name,
            rssi = device.rssi,
            isConnected = true,
            deviceInfo = null,
            batteryLevel = null,
            otaProgress = null,
            isOtaInProgress = false,
            callEventEnabled = callEventEnabled,
            smsEventEnabled = smsEventEnabled,
            broadcasting = broadcasting
        ))

        val deviceEventStates = mapOf(
            EventType.CALL_RINGING to callEventEnabled,
            EventType.SMS_RECEIVED to smsEventEnabled
        )
        _eventStates.value = _eventStates.value + (device.mac to deviceEventStates)

        Log.d(TAG, "✅ Device detail initialized for ${device.mac}")
    }

    private fun updateDeviceInfoFromCallback(mac: String, deviceInfo: DeviceInfo) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) {
                this[mac] = existing.copy(
                    deviceInfo = deviceInfo,
                    batteryLevel = deviceInfo.batteryLevel
                )
            } else {
                this[mac] = DeviceDetailInfo(
                    mac = mac,
                    name = deviceInfo.deviceName,
                    rssi = deviceInfo.rssi,
                    isConnected = _connectionStates.value[mac] ?: false,
                    deviceInfo = deviceInfo,
                    batteryLevel = deviceInfo.batteryLevel,
                    otaProgress = null,
                    isOtaInProgress = false,
                    callEventEnabled = DevicePreferences.getCallEventEnabled(mac),
                    smsEventEnabled = DevicePreferences.getSmsEventEnabled(mac),
                    broadcasting = DevicePreferences.getBroadcasting(mac)
                )
            }
        }

        Log.d(TAG, "✅ Device info updated for $mac")
    }

    @SuppressLint("MissingPermission")
    private fun registerDeviceEventRules(device: Device) {
        try {
            val callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac)
            val smsEventEnabled = DevicePreferences.getSmsEventEnabled(device.mac)

            val rules = mutableListOf<EventRule>()

            // Call Event Rule
            if (callEventEnabled) {
                rules.add(
                    EventRule(
                        id = "call-${device.mac}",
                        trigger = EventTrigger(
                            type = EventType.CALL_RINGING,
                            filter = EventFilter()
                        ),
                        action = EventAction.SendEffectFrame(
                            bytes20 = LSEffectPayload.Effects.blink(
                                period = 10,
                                color = Colors.CYAN,
                                backgroundColor = Colors.BLACK
                            ).toByteArray()
                        ),
                        target = EventTarget.THIS_DEVICE,
                        stopAfterMatch = false
                    )
                )
            }

            // SMS Event Rule
            if (smsEventEnabled) {
                rules.add(
                    EventRule(
                        id = "sms-${device.mac}",
                        trigger = EventTrigger(
                            type = EventType.SMS_RECEIVED,
                            filter = EventFilter()
                        ),
                        action = EventAction.SendEffectFrame(
                            bytes20 = LSEffectPayload.Effects.blink(
                                period = 10,
                                color = Colors.GREEN,
                                backgroundColor = Colors.BLACK
                            ).toByteArray()
                        ),
                        target = EventTarget.THIS_DEVICE,
                        stopAfterMatch = true
                    )
                )
            }

            device.registerEventRules(rules)

            Log.d(TAG, "✅ Event rules registered for ${device.mac}")
            Log.d(TAG, "   ├─ CALL: ${if (callEventEnabled) "enabled" else "disabled"}")
            Log.d(TAG, "   └─ SMS:  ${if (smsEventEnabled) "enabled" else "disabled"}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register event rules: ${e.message}", e)
        }
    }

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

                // 펌웨어 파일 읽기
                val firmwareBytes = ctx.contentResolver.openInputStream(firmwareUri)?.use { input ->
                    input.readBytes()
                } ?: run {
                    Log.e(TAG, "❌ Failed to read firmware file")
                    return@launch
                }

                Log.d(TAG, "🚀 Starting OTA update for ${device.mac}")
                Log.d(TAG, "📦 Firmware size: ${firmwareBytes.size} bytes")

                _otaInProgress.value = _otaInProgress.value + (device.mac to true)
                _otaProgress.value = _otaProgress.value + (device.mac to 0)

                _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                    val existing = this[device.mac]
                    if (existing != null) {
                        this[device.mac] = existing.copy(
                            isOtaInProgress = true,
                            otaProgress = 0
                        )
                    }
                }

                @SuppressLint("MissingPermission")
                fun doOta() {
                    device.startOta(
                        firmware = firmwareBytes,
                        onProgress = { progress ->
                            _otaProgress.value = _otaProgress.value + (device.mac to progress)

                            _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                                val existing = this[device.mac]
                                if (existing != null) {
                                    this[device.mac] = existing.copy(otaProgress = progress)
                                }
                            }

                            Log.d(TAG, "📊 OTA progress for ${device.mac}: $progress%")
                        },
                        onResult = { result ->
                            result.onSuccess {
                                _otaInProgress.value = _otaInProgress.value + (device.mac to false)
                                _otaProgress.value = _otaProgress.value + (device.mac to 100)

                                _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                                    val existing = this[device.mac]
                                    if (existing != null) {
                                        this[device.mac] = existing.copy(
                                            isOtaInProgress = false,
                                            otaProgress = 100
                                        )
                                    }
                                }

                                Log.d(TAG, "✅ OTA update completed for ${device.mac}")

                            }.onFailure { error ->
                                _otaInProgress.value = _otaInProgress.value + (device.mac to false)

                                _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                                    val existing = this[device.mac]
                                    if (existing != null) {
                                        this[device.mac] = existing.copy(
                                            isOtaInProgress = false,
                                            otaProgress = null
                                        )
                                    }
                                }

                                Log.e(TAG, "❌ OTA update failed for ${device.mac}: ${error.message}")
                            }
                        }
                    )
                }

                doOta()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start OTA update: ${e.message}", e)

                _otaInProgress.value = _otaInProgress.value + (device.mac to false)

                _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
                    val existing = this[device.mac]
                    if (existing != null) {
                        this[device.mac] = existing.copy(
                            isOtaInProgress = false,
                            otaProgress = null
                        )
                    }
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
                    Log.w(TAG, "⚠️ Device ${device.mac} is not connected")
                    return@launch
                }

                Log.d(TAG, "📍 Sending FIND effect to ${device.mac}")

                val result = sendFindEffectUseCase(
                    context = ctx,
                    deviceMac = device.mac
                )

                result.onSuccess {
                    Log.d(TAG, "✅ FIND effect sent to ${device.mac}")
                }.onFailure { error ->
                    Log.e(TAG, "❌ Failed to send FIND effect: ${error.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send FIND effect: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()

        Log.d(TAG, "🧹 Cleaning up ViewModel...")

        stopScan()

        val ctx = appContext
        if (ctx != null && PermissionManager.hasBluetoothConnectPermission(ctx)) {
            connectedDevices.values.forEach { device ->
                try {
                    @SuppressLint("MissingPermission")
                    fun doDisconnect() {
                        device.disconnect()
                    }
                    doDisconnect()
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