package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.domain.usecase.device.SendFindEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceViewModel : ViewModel() {

    private val TAG = "DeviceVM"

    // ═══════════════════════════════════════════════════════════
    // UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val sendFindEffectUseCase = SendFindEffectUseCase()
    private val sendConnectionEffectUseCase = SendConnectionEffectUseCase()

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

        // ✅ SDK 연결 상태 실시간 관찰
        Log.d(TAG, "📡 Starting to observe connection states...")
        observeConnectionStates()
    }

    private fun observeConnectionStates() {
        viewModelScope.launch {
            LSBluetooth.observeDeviceStates().collect { states ->
                _connectionStates.value = states.mapValues { (_, state) ->
                    state.connectionState is ConnectionState.Connected
                }

                _connectedDeviceCount.value = states.count { (_, state) ->
                    state.connectionState is ConnectionState.Connected
                }

                val connectedDevices = states
                    .filter { (_, state) -> state.connectionState is ConnectionState.Connected }
                    .map { (mac, state) ->
                        Device(
                            mac = mac,
                            name = state.deviceInfo?.deviceName,
                            rssi = state.deviceInfo?.rssi
                        )
                    }

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

        _devices.value = _devices.value.filter {
            _connectionStates.value[it.mac] == true
        }

        viewModelScope.launch {
            delay(30_000)
            if (_isScanning.value) stopScan()
        }

        @SuppressLint("MissingPermission")
        fun doStartScan() {
            LSBluetooth.startScan { device ->
                val current = _devices.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.mac == device.mac }

                if (existingIndex >= 0) {
                    current[existingIndex] = device
                } else {
                    current.add(device)
                }

                _devices.value = current.sortedWith(
                    compareByDescending<Device> { _connectionStates.value[it.mac] ?: false }
                        .thenByDescending { it.rssi ?: -100 }
                )
            }
        }

        try {
            doStartScan()
        } catch (e: Exception) {
            Log.e(TAG, "Scan error: ${e.message}")
            _isScanning.value = false
        }
    }

    fun stopScan() {
        if (!_isScanning.value) {
            Log.d(TAG, "Not scanning, skip stopScan()")
            return
        }

        Log.d(TAG, "🛑 Stopping BLE scan...")

        try {
            val ctx = appContext
            if (ctx != null && !PermissionManager.hasBluetoothScanPermission(ctx)) {
                Log.w(TAG, "⚠️ BLUETOOTH_SCAN permission not available for stopScan()")
                _isScanning.value = false
                return
            }

            @SuppressLint("MissingPermission")
            fun doStopScan() {
                LSBluetooth.stopScan()
            }

            doStopScan()
            _isScanning.value = false
            Log.d(TAG, "✅ Scan stopped successfully")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException during stopScan: ${e.message}")
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping scan: ${e.message}", e)
            _isScanning.value = false
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

                @SuppressLint("MissingPermission")
                fun doConnect() {
                    device.connect(
                        onConnected = {
                            Log.d(TAG, "✅ Connected to ${device.mac}")

                            connectedDevices[device.mac] = device
                            updateConnectionState(device.mac, true)
                            updateConnectedCount()

                            // ✅ UseCase 사용: 연결 애니메이션
                            viewModelScope.launch {
                                val result = sendConnectionEffectUseCase(ctx, device)
                                result.onFailure { error ->
                                    Log.e(TAG, "❌ Connection animation failed: ${error.message}")
                                }
                            }

                            initializeDeviceDetail(device)
                            registerDeviceEventRules(device)
                        },
                        onFailed = { error ->
                            Log.e(TAG, "❌ Connection failed for ${device.mac}")
                            Log.e(TAG, "   Error: ${error.message}", error)

                            updateConnectionState(device.mac, false)
                            connectedDevices.remove(device.mac)
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
                }

                doConnect()

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException during connect: ${e.message}")
                updateConnectionState(device.mac, false)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "❌ IllegalStateException during connect: ${e.message}")
                updateConnectionState(device.mac, false)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error during connect: ${e.message}", e)
                updateConnectionState(device.mac, false)
            }
        }
    }

    private fun disconnect(device: Device) {
        viewModelScope.launch {
            try {
                val ctx = appContext
                if (ctx == null || !PermissionManager.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available for disconnect")
                    connectedDevices.remove(device.mac)
                    updateConnectionState(device.mac, false)
                    clearDeviceDetails(device.mac)
                    return@launch
                }

                Log.d(TAG, "🔌 Disconnecting from ${device.mac}...")

                @SuppressLint("MissingPermission")
                fun doDisconnect() {
                    device.disconnect()
                }

                doDisconnect()

                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                updateConnectedCount()
                clearDeviceDetails(device.mac)

                Log.d(TAG, "✅ Disconnected from ${device.mac}")

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException during disconnect: ${e.message}")
                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                clearDeviceDetails(device.mac)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
                connectedDevices.remove(device.mac)
                updateConnectionState(device.mac, false)
                clearDeviceDetails(device.mac)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Device Details
    // ═══════════════════════════════════════════════════════════

    private fun initializeDeviceDetail(device: Device) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            this[device.mac] = DeviceDetailInfo(
                mac = device.mac,
                name = device.name,
                rssi = device.rssi,
                isConnected = true,
                deviceInfo = null,
                batteryLevel = null,
                otaProgress = null,
                isOtaInProgress = false,
                callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac),
                smsEventEnabled = DevicePreferences.getSmsEventEnabled(device.mac),
                broadcasting = DevicePreferences.getBroadcasting(device.mac)
            )
        }
    }

    private fun updateDeviceInfoFromCallback(mac: String, info: DeviceInfo) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) {
                this[mac] = existing.copy(
                    deviceInfo = info,
                    batteryLevel = info.batteryLevel
                )
            }
        }
    }

    private fun clearDeviceDetails(mac: String) {
        _deviceDetails.value -= mac
    }

    // ═══════════════════════════════════════════════════════════
    // OTA Implementation
    // ═══════════════════════════════════════════════════════════

    fun startOta(context: Context, device: Device, firmwareUri: Uri) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                val firmwareBytes = context.contentResolver.openInputStream(firmwareUri)?.use { input ->
                    input.readBytes()
                } ?: run {
                    Log.e(TAG, "❌ Failed to read firmware file")
                    return@launch
                }

                Log.d(TAG, "📦 Starting OTA for ${device.mac}, size: ${firmwareBytes.size} bytes")

                _otaInProgress.update { it + (device.mac to true) }
                _otaProgress.update { it + (device.mac to 0) }

                @SuppressLint("MissingPermission")
                fun doStartOta() {
                    device.startOta(
                        firmware = firmwareBytes,
                        onProgress = { progress ->
                            Log.d(TAG, "📊 OTA Progress for ${device.mac}: $progress%")
                            _otaProgress.update { it + (device.mac to progress) }
                        },
                        onResult = { result ->
                            result.onSuccess {
                                Log.d(TAG, "✅ OTA completed for ${device.mac}")
                                _otaInProgress.update { it + (device.mac to false) }
                                _otaProgress.update { it + (device.mac to 100) }
                            }.onFailure { error ->
                                Log.e(TAG, "❌ OTA failed for ${device.mac}: ${error.message}")
                                _otaInProgress.update { it + (device.mac to false) }
                            }
                        }
                    )
                }

                doStartOta()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start OTA: ${e.message}", e)
                _otaInProgress.update { it + (device.mac to false) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Event Rules (SDK v1.4 API)
    // ═══════════════════════════════════════════════════════════

    private fun registerDeviceEventRules(device: Device) {
        try {
            val callEnabled = DevicePreferences.getCallEventEnabled(device.mac)
            val smsEnabled = DevicePreferences.getSmsEventEnabled(device.mac)

            val rules = mutableListOf<EventRule>()

            if (callEnabled) {
                rules.add(
                    EventRule(
                        id = "call-${device.mac}",
                        trigger = EventTrigger(
                            type = EventType.CALL_RINGING,
                            filter = EventFilter()
                        ),
                        action = EventAction.SendEffectFrame(
                            bytes20 = LSEffectPayload.Effects.blink(4, Colors.CYAN).toByteArray()
                        ),
                        target = EventTarget.THIS_DEVICE,
                        stopAfterMatch = false
                    )
                )
            }

            if (smsEnabled) {
                rules.add(
                    EventRule(
                        id = "sms-${device.mac}",
                        trigger = EventTrigger(
                            type = EventType.SMS_RECEIVED,
                            filter = EventFilter()
                        ),
                        action = EventAction.SendEffectFrame(
                            bytes20 = LSEffectPayload.Effects.blink(6, Colors.GREEN).toByteArray()
                        ),
                        target = EventTarget.THIS_DEVICE,
                        stopAfterMatch = true
                    )
                )
            }

            // ✅ SDK v1.4 API: Device.registerEventRules() 사용
            device.registerEventRules(rules)

            // ✅ Event States 업데이트
            _eventStates.update { states ->
                val deviceStates = states[device.mac]?.toMutableMap() ?: mutableMapOf()
                deviceStates[EventType.CALL_RINGING] = callEnabled
                deviceStates[EventType.SMS_RECEIVED] = smsEnabled
                states + (device.mac to deviceStates)
            }

            Log.d(TAG, "✅ Event rules registered for ${device.mac}: ${rules.size} rules")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register event rules for ${device.mac}: ${e.message}", e)
        }
    }

    fun toggleCallEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setCallEventEnabled(device.mac, enabled)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(callEventEnabled = enabled)
            }
        }

        // ✅ 이벤트 룰 재등록
        registerDeviceEventRules(device)

        Log.d(TAG, "📝 Call event ${if (enabled) "enabled" else "disabled"} for ${device.mac}")
    }

    fun toggleSmsEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setSmsEventEnabled(device.mac, enabled)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(smsEventEnabled = enabled)
            }
        }

        // ✅ 이벤트 룰 재등록
        registerDeviceEventRules(device)

        Log.d(TAG, "📝 SMS event ${if (enabled) "enabled" else "disabled"} for ${device.mac}")
    }

    fun toggleBroadcasting(device: Device, enabled: Boolean) {
        DevicePreferences.setBroadcasting(device.mac, enabled)

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(broadcasting = enabled)
            }
        }

        // ✅ Note: setBroadcasting() API가 SDK v1.4에서 제거됨
        // Broadcasting 설정은 Preferences에만 저장
        Log.d(TAG, "📡 Broadcasting ${if (enabled) "enabled" else "disabled"} for ${device.mac}")
    }

    // ═══════════════════════════════════════════════════════════
    // State Updates
    // ═══════════════════════════════════════════════════════════

    private fun updateConnectionState(mac: String, isConnected: Boolean) {
        _connectionStates.value = _connectionStates.value.toMutableMap().apply {
            this[mac] = isConnected
        }
    }

    private fun updateConnectedCount() {
        viewModelScope.launch {
            try {
                val ctx = appContext ?: return@launch

                if (!PermissionManager.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ Cannot update connected count: permission not available")
                    _connectedDeviceCount.value = 0
                    return@launch
                }

                @SuppressLint("MissingPermission")
                fun doUpdateCount() {
                    val count = LSBluetooth.connectedCount()
                    _connectedDeviceCount.value = count

                    val connectedDevices = LSBluetooth.connectedDevices()
                    val connectedMacs = connectedDevices.map { it.mac }.toSet()

                    Log.d(TAG, "📊 Connected devices: $count")
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
                }

                doUpdateCount()

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException in updateConnectedCount: ${e.message}")
                _connectedDeviceCount.value = 0
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating connected count: ${e.message}", e)
                _connectedDeviceCount.value = 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun syncConnectedDevicesOnInit() {
        try {
            val ctx = appContext ?: return
            if (!PermissionManager.hasBluetoothConnectPermission(ctx)) {
                Log.w(TAG, "⚠️ Cannot sync: permission not available")
                return
            }

            val systemConnected = LSBluetooth.connectedDevices()

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

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException during sync: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error syncing devices: ${e.message}", e)
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
}