package com.dongsitech.lightstickmusicdemo.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.model.DeviceDetailInfo
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.lightstick.LSBluetooth
import com.lightstick.device.Controller
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

class LightStickListViewModel : ViewModel() {

    private val TAG = "LightStickListVM"

    // ═══════════════════════════════════════════════════════════
    // State Flows
    // ═══════════════════════════════════════════════════════════

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates.asStateFlow()

    private val controllers = mutableMapOf<String, Controller>()

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
        if (appContext != null) return
        appContext = context.applicationContext

        if (PermissionUtils.hasBluetoothConnectPermission(appContext!!)) {
            updateConnectedCount()
        }

        PermissionUtils.logPermissionStatus(appContext!!, TAG)
    }

    // ═══════════════════════════════════════════════════════════
    // BLE Scan
    // ═══════════════════════════════════════════════════════════

    fun startScan(context: Context) {
        if (!PermissionUtils.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_SCAN permission not granted")
            PermissionUtils.logPermissionStatus(context, TAG)
            return
        }

        if (_isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }

        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🔍 Starting BLE scan...")
        _isScanning.value = true
        _devices.value = emptyList()

        try {
            @SuppressLint("MissingPermission")
            fun doStartScan() {
                LSBluetooth.startScan { device ->
                    if (device.name?.endsWith("LS") == true) {
                        Log.d(TAG, "📱 Found: ${device.mac} | ${device.name} | RSSI: ${device.rssi}")

                        val current = _devices.value.toMutableList()
                        val existingIndex = current.indexOfFirst { it.mac == device.mac }

                        if (existingIndex >= 0) {
                            current[existingIndex] = device
                        } else {
                            current.add(device)
                        }

                        _devices.value = current.sortedWith(
                            compareByDescending<Device> {
                                _connectionStates.value[it.mac] ?: false
                            }.thenByDescending {
                                it.rssi ?: -100
                            }
                        )
                    }
                }
            }

            doStartScan()
            Log.d(TAG, "✅ Scan started successfully")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException during scan: ${e.message}")
            Log.e(TAG, "   권한이 거부되었거나 런타임에 취소되었습니다.")
            _isScanning.value = false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ IllegalStateException: ${e.message}")
            Log.e(TAG, "   블루투스 어댑터가 비활성화되었거나 사용 불가능합니다.")
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error during scan: ${e.message}", e)
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
            if (ctx != null && !PermissionUtils.hasBluetoothScanPermission(ctx)) {
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
            Log.e(TAG, "   BLUETOOTH_SCAN 권한이 런타임에 취소되었습니다.")
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
        if (!PermissionUtils.hasBluetoothConnectPermission(context)) {
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
                if (ctx == null || !PermissionUtils.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available")
                    return@launch
                }

                Log.d(TAG, "═══════════════════════════════════════")
                Log.d(TAG, "🔗 Connecting to ${device.mac}...")

                @SuppressLint("MissingPermission")
                fun doConnect() {
                    device.connect(
                        onConnected = { controller ->
                            Log.d(TAG, "✅ Connected to ${device.mac}")

                            controllers[device.mac] = controller
                            updateConnectionState(device.mac, true)
                            updateConnectedCount()

                            // 연결 성공 연출
                            viewModelScope.launch {
                                try {
                                    repeat(3) {
                                        controller.sendColor(Colors.WHITE, transition = 5)
                                        delay(200)
                                        controller.sendColor(Colors.BLACK, transition = 5)
                                        delay(200)
                                    }
                                    controller.sendColor(Colors.WHITE, transition = 10)
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ 연결 연출 실패: ${e.message}")
                                }
                            }
			
                            // 초기 DeviceDetailInfo 생성
                            initializeDeviceDetail(device)

                            // 디바이스 정보 읽기
                            fetchDeviceInfo(device)
                            fetchBatteryLevel(device)

                            // 이벤트 규칙 등록
                            registerDeviceEventRules(device)
                        },
                        onFailed = { error ->
                            Log.e(TAG, "❌ Connection failed for ${device.mac}")
                            Log.e(TAG, "   Error: ${error.message}", error)

                            updateConnectionState(device.mac, false)
                            controllers.remove(device.mac)
                        }
                    )
                }

                doConnect()

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException during connect: ${e.message}")
                Log.e(TAG, "   BLUETOOTH_CONNECT 권한이 거부되었습니다.")
                updateConnectionState(device.mac, false)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "❌ IllegalStateException during connect: ${e.message}")
                Log.e(TAG, "   블루투스가 비활성화되었거나 디바이스가 범위를 벗어났습니다.")
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
                if (ctx == null || !PermissionUtils.hasBluetoothConnectPermission(ctx)) {
                    Log.w(TAG, "⚠️ BLUETOOTH_CONNECT permission not available for disconnect")
                    controllers.remove(device.mac)
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

                controllers.remove(device.mac)
                updateConnectionState(device.mac, false)
                updateConnectedCount()
                clearDeviceDetails(device.mac)

                Log.d(TAG, "✅ Disconnected from ${device.mac}")

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException during disconnect: ${e.message}")
                controllers.remove(device.mac)
                updateConnectionState(device.mac, false)
                clearDeviceDetails(device.mac)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
                controllers.remove(device.mac)
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
                callEventEnabled = true,
                smsEventEnabled = true,
                broadcasting = true
            )
        }
    }

    private fun fetchDeviceInfo(device: Device) {
        val ctx = appContext
        if (ctx == null || !PermissionUtils.hasBluetoothConnectPermission(ctx)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                val isConnected = try {
                    device.isConnected()
                } catch (e: Exception) {
                    false
                }

                if (!isConnected) {
                    Log.w(TAG, "⚠️ Device not connected: ${device.mac}")
                    return@launch
                }

                Log.d(TAG, "📋 Reading device info for ${device.mac}...")

                var deviceName: String? = null
                var modelNumber: String? = null
                var firmwareRevision: String? = null
                var manufacturer: String? = null

                @SuppressLint("MissingPermission")
                fun doFetchInfo() {
                    device.readDeviceName { result: Result<String> ->
                        result.onSuccess { name ->
                            deviceName = name
                            Log.d(TAG, "   ├─ Device Name: $name")
                            updateDeviceInfo(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                        }.onFailure { error ->
                            Log.w(TAG, "   ├─ readDeviceName failed: ${error.message}")
                        }
                    }

                    device.readModelNumber { result: Result<String> ->
                        result.onSuccess { model ->
                            modelNumber = model
                            Log.d(TAG, "   ├─ Model Number: $model")
                            updateDeviceInfo(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                        }.onFailure { error ->
                            Log.w(TAG, "   ├─ readModelNumber failed: ${error.message}")
                        }
                    }

                    device.readFirmwareRevision { result: Result<String> ->
                        result.onSuccess { fw ->
                            firmwareRevision = fw
                            Log.d(TAG, "   ├─ Firmware Revision: $fw")
                            updateDeviceInfo(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                        }.onFailure { error ->
                            Log.w(TAG, "   ├─ readFirmwareRevision failed: ${error.message}")
                        }
                    }

                    device.readManufacturer { result: Result<String> ->
                        result.onSuccess { mfr ->
                            manufacturer = mfr
                            Log.d(TAG, "   └─ Manufacturer: $mfr")
                            updateDeviceInfo(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                        }.onFailure { error ->
                            Log.w(TAG, "   └─ readManufacturer failed: ${error.message}")
                        }
                    }
                }

                doFetchInfo()

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException fetching device info: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching device info: ${e.message}", e)
            }
        }
    }

    private fun fetchBatteryLevel(device: Device) {
        val ctx = appContext
        if (ctx == null || !PermissionUtils.hasBluetoothConnectPermission(ctx)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                val isConnected = try {
                    device.isConnected()
                } catch (e: Exception) {
                    false
                }

                if (!isConnected) {
                    Log.w(TAG, "⚠️ Device not connected for battery read: ${device.mac}")
                    return@launch
                }

                Log.d(TAG, "🔋 Reading battery level for ${device.mac}...")

                @SuppressLint("MissingPermission")
                fun doFetchBattery() {
                    device.readBattery { result: Result<Int> ->
                        result.onSuccess { level ->
                            Log.d(TAG, "   └─ Battery: $level%")
                            updateBattery(device.mac, level)
                        }.onFailure { error ->
                            Log.w(TAG, "   └─ readBattery failed: ${error.message}")
                        }
                    }
                }

                doFetchBattery()

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException fetching battery: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching battery: ${e.message}", e)
            }
        }
    }

    private fun updateDeviceInfo(
        mac: String,
        deviceName: String?,
        modelNumber: String?,
        firmwareRevision: String?,
        manufacturer: String?
    ) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) {
                val deviceInfo = DeviceInfo(
                    deviceName = deviceName,
                    modelNumber = modelNumber,
                    firmwareRevision = firmwareRevision,
                    manufacturer = manufacturer,
                    macAddress = mac,
                    batteryLevel = existing.batteryLevel,
                    rssi = existing.rssi,
                    isConnected = existing.isConnected,
                    lastUpdated = System.currentTimeMillis()
                )

                this[mac] = existing.copy(deviceInfo = deviceInfo)
            }
        }
    }

    private fun updateBattery(mac: String, batteryLevel: Int) {
        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[mac]
            if (existing != null) {
                val updatedDeviceInfo = existing.deviceInfo?.copy(
                    batteryLevel = batteryLevel,
                    lastUpdated = System.currentTimeMillis()
                )

                this[mac] = existing.copy(
                    batteryLevel = batteryLevel,
                    deviceInfo = updatedDeviceInfo
                )
            }
        }
    }

    private fun clearDeviceDetails(mac: String) {
        _deviceDetails.value = _deviceDetails.value - mac
    }

    // ═══════════════════════════════════════════════════════════
    // OTA Implementation
    // ═══════════════════════════════════════════════════════════

    fun startOta(context: Context, device: Device, firmwareUri: Uri) {
        if (!PermissionUtils.hasBluetoothConnectPermission(context)) {
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
                Log.e(TAG, "❌ startOta error", e)
                _otaInProgress.update { it + (device.mac to false) }
            }
        }
    }

    fun abortOta(device: Device) {
        val ctx = appContext
        if (ctx == null || !PermissionUtils.hasBluetoothConnectPermission(ctx)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "🛑 Aborting OTA for ${device.mac}")

                @SuppressLint("MissingPermission")
                fun doAbortOta() {
                    device.abortOta()
                }

                doAbortOta()
                _otaInProgress.update { it + (device.mac to false) }

            } catch (e: Exception) {
                Log.e(TAG, "❌ abortOta error", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Event Implementation
    // ═══════════════════════════════════════════════════════════

    private fun registerDeviceEventRules(device: Device) {
        try {
            val callRule = EventRule(
                id = "call-${device.mac}",
                trigger = EventTrigger(
                    type = EventType.CALL_RINGING,
                    filter = EventFilter()
                ),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.blink(4,Colors.CYAN).toByteArray()
                ),
                target = EventTarget.THIS_DEVICE,
                stopAfterMatch = false
            )

            val smsRule = EventRule(
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

            device.registerEventRules(listOf(callRule, smsRule))

            _eventStates.update { states ->
                val deviceStates = states[device.mac]?.toMutableMap() ?: mutableMapOf()
                deviceStates[EventType.CALL_RINGING] = false
                deviceStates[EventType.SMS_RECEIVED] = false
                states + (device.mac to deviceStates)
            }

            Log.d(TAG, "✅ Event rules registered for ${device.mac}: 2 rules")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register event rules for ${device.mac}: ${e.message}", e)
        }
    }

    fun toggleCallEvent(device: Device, enabled: Boolean) {
        _eventStates.update { states ->
            val deviceStates = states[device.mac]?.toMutableMap() ?: mutableMapOf()
            deviceStates[EventType.CALL_RINGING] = enabled
            states + (device.mac to deviceStates)
        }

        if (enabled) {
            Log.d(TAG, "✅ CALL event enabled for ${device.mac}")
        } else {
            Log.d(TAG, "🔕 CALL event disabled for ${device.mac}")
        }
    }

    fun toggleSmsEvent(device: Device, enabled: Boolean) {
        _eventStates.update { states ->
            val deviceStates = states[device.mac]?.toMutableMap() ?: mutableMapOf()
            deviceStates[EventType.SMS_RECEIVED] = enabled
            states + (device.mac to deviceStates)
        }

        if (enabled) {
            Log.d(TAG, "✅ SMS event enabled for ${device.mac}")
        } else {
            Log.d(TAG, "🔕 SMS event disabled for ${device.mac}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // State Updates
    // ═══════════════════════════════════════════════════════════

    private fun updateConnectionState(mac: String, isConnected: Boolean) {
        _connectionStates.value = _connectionStates.value + (mac to isConnected)

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

                if (!PermissionUtils.hasBluetoothConnectPermission(ctx)) {
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

    // ═══════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()

        Log.d(TAG, "🧹 Cleaning up ViewModel...")

        stopScan()

        val ctx = appContext
        if (ctx != null && PermissionUtils.hasBluetoothConnectPermission(ctx)) {
            controllers.values.forEach { controller ->
                try {
                    @SuppressLint("MissingPermission")
                    fun doDisconnect() {
                        controller.device.disconnect()
                    }
                    doDisconnect()
                    Log.d(TAG, "   Disconnected: ${controller.device.mac}")
                } catch (e: Exception) {
                    Log.e(TAG, "   Error disconnecting ${controller.device.mac}: ${e.message}")
                }
            }
        }

        controllers.clear()
        Log.d(TAG, "✅ Cleanup completed")
    }
}