package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.model.DeviceDetailInfo
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Light Stick 목록/스캔/연결 상태를 관리하는 ViewModel.
 *
 * - 모든 Permission 체크는 이 ViewModel에서만 수행
 * - UI(Screen)에서는 단순히 함수만 호출
 * - @SuppressLint("MissingPermission")로 경고 무시
 */
@SuppressLint("MissingPermission")
class LightStickListViewModel : ViewModel() {

    companion object {
        private const val TAG = "LightStickListVM"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // State Flows
    // ─────────────────────────────────────────────────────────────────────────────
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> =
        _devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates.asStateFlow()

    private val _deviceDetails = MutableStateFlow<Map<String, DeviceDetailInfo>>(emptyMap())
    val deviceDetails: StateFlow<Map<String, DeviceDetailInfo>> = _deviceDetails.asStateFlow()

    private val _otaProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val otaProgress: StateFlow<Map<String, Int>> = _otaProgress.asStateFlow()

    private val _otaInProgress = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val otaInProgress: StateFlow<Map<String, Boolean>> = _otaInProgress.asStateFlow()

    private val _eventStates = MutableStateFlow<Map<String, Map<EventType, Boolean>>>(emptyMap())
    val eventStates: StateFlow<Map<String, Map<EventType, Boolean>>> = _eventStates.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private var appContext: Context? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // Permission Helpers (Internal)
    // ─────────────────────────────────────────────────────────────────────────────
    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────────
    fun initializeWithContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        val ctx = appContext
        if (ctx != null && hasBluetoothConnectPermission(ctx)) {
            updateConnectedCount()
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _permissionGranted.value = granted
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Connected Count Update
    // ─────────────────────────────────────────────────────────────────────────────
    private fun updateConnectedCount() {
        viewModelScope.launch {
            try {
                val ctx = appContext ?: return@launch
                if (!hasBluetoothConnectPermission(ctx)) return@launch

                _connectedDeviceCount.value = LSBluetooth.connectedCount()

                val connectedDevices = LSBluetooth.connectedDevices()
                val connectedMacs = connectedDevices.map { it.mac }.toSet()

                _connectionStates.update { prev ->
                    val next = prev.toMutableMap()
                    connectedMacs.forEach { mac -> next[mac] = true }
                    _devices.value.forEach { device ->
                        if (device.mac !in connectedMacs) {
                            next[device.mac] = false
                        }
                    }
                    next
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in updateConnectedCount: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Scan Control
    // ─────────────────────────────────────────────────────────────────────────────
    fun startScan(context: Context) {
        if (!hasBluetoothScanPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_SCAN 권한 없음")
            return
        }

        if (_isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }

        _isScanning.value = true
        _devices.value = emptyList()

        LSBluetooth.startScan { device ->
            if (device.name?.endsWith("LS") == true) {
                Log.d(TAG, "📡 Device discovered: ${device.name} (${device.mac}), RSSI: ${device.rssi}")

                val existingDevice = _devices.value.find { it.mac == device.mac }
                if (existingDevice == null) {
                    _devices.update { current ->
                        (current + device).sortedWith(
                            compareByDescending<Device> {
                                try {
                                    if (hasBluetoothConnectPermission(context)) {
                                        it.isConnected()
                                    } else false
                                } catch (e: Exception) {
                                    false
                                }
                            }.thenByDescending { it.rssi }
                        )
                    }
                } else {
                    _devices.update { current ->
                        current.map { d ->
                            if (d.mac == device.mac) device else d
                        }.sortedWith(
                            compareByDescending<Device> {
                                try {
                                    if (hasBluetoothConnectPermission(context)) {
                                        it.isConnected()
                                    } else false
                                } catch (e: Exception) {
                                    false
                                }
                            }.thenByDescending { it.rssi }
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            delay(10_000)
            if (_isScanning.value) {
                stopScan(context)
            }
        }
    }

    fun stopScan(context: Context) {
        if (!hasBluetoothScanPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_SCAN 권한 없음")
            return
        }

        if (!_isScanning.value) return

        LSBluetooth.stopScan()
        _isScanning.value = false
        Log.d(TAG, "🛑 Scan stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Connect / Disconnect
    // ─────────────────────────────────────────────────────────────────────────────
    fun connect(context: Context, device: Device) {
        if (!hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "🔗 연결 시도: ${device.mac}")

                device.connect(
                    onConnected = { controller ->
                        Log.d(TAG, "✅ 연결 성공: ${controller.device.mac}")
                        _connectionStates.update { it + (device.mac to true) }
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

                        // Device 정보 가져오기
                        viewModelScope.launch {
                            delay(1000)
                            Log.d(TAG, "📋 Starting to fetch device info for ${controller.device.mac}")
                            fetchDeviceInfo(controller.device)
                            delay(500)
                            fetchBatteryLevel(controller.device)
                            registerDeviceEventRules(controller.device)
                        }
                    },
                    onFailed = { throwable ->
                        Log.w(TAG, "❌ 연결 실패: ${device.mac} - ${throwable.message}")
                        _connectionStates.update { it + (device.mac to false) }
                        updateConnectedCount()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ connect error", e)
            }
        }
    }

    fun disconnect(device: Device) {
        val ctx = appContext
        if (ctx == null || !hasBluetoothConnectPermission(ctx)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "🔌 연결 해제: ${device.mac}")
                device.disconnect()
                _connectionStates.update { it + (device.mac to false) }
                updateConnectedCount()
            } catch (e: Exception) {
                Log.e(TAG, "❌ disconnect error", e)
            }
        }
    }

    fun toggleConnection(context: Context, device: Device) {
        if (!hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }

        try {
            val isConnected = try {
                device.isConnected()
            } catch (e: Exception) {
                Log.w(TAG, "Exception checking connection state: ${e.message}")
                false
            }

            if (isConnected) {
                disconnect(device)
                Log.d(TAG, "Disconnected: ${device.mac}")
            } else {
                connect(context, device)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "toggleConnection error: ${t.message}", t)
        }
    }

    fun toggleConnectionByAddress(context: Context, address: String) {
        if (!hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }

        val device = _devices.value.find { it.mac == address }
        if (device == null) {
            Log.w(TAG, "Device not found: $address")
            return
        }

        toggleConnection(context, device)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Device Info
    // ─────────────────────────────────────────────────────────────────────────────
    private fun fetchDeviceInfo(device: Device) {
        val ctx = appContext
        if (ctx == null || !hasBluetoothConnectPermission(ctx)) {
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

                Log.d(TAG, "📋 Device is connected, reading device info for ${device.mac}")

                var deviceName: String? = null
                var modelNumber: String? = null
                var firmwareRevision: String? = null
                var manufacturer: String? = null

                device.readDeviceName { result ->
                    result.onSuccess { name ->
                        deviceName = name
                        Log.d(TAG, "📋 Device Name: $name")
                        updateDeviceInfoInMap(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                    }.onFailure { error ->
                        Log.w(TAG, "⚠️ readDeviceName failed: ${error.message}")
                    }
                }

                device.readModelNumber { result ->
                    result.onSuccess { model ->
                        modelNumber = model
                        Log.d(TAG, "📋 Model Number: $model")
                        updateDeviceInfoInMap(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                    }.onFailure { error ->
                        Log.w(TAG, "⚠️ readModelNumber failed: ${error.message}")
                    }
                }

                device.readFirmwareRevision { result ->
                    result.onSuccess { fw ->
                        firmwareRevision = fw
                        Log.d(TAG, "📋 Firmware Revision: $fw")
                        updateDeviceInfoInMap(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                    }.onFailure { error ->
                        Log.w(TAG, "⚠️ readFirmwareRevision failed: ${error.message}")
                    }
                }

                device.readManufacturer { result ->
                    result.onSuccess { mfr ->
                        manufacturer = mfr
                        Log.d(TAG, "📋 Manufacturer: $mfr")
                        updateDeviceInfoInMap(device.mac, deviceName, modelNumber, firmwareRevision, manufacturer)
                    }.onFailure { error ->
                        Log.w(TAG, "⚠️ readManufacturer failed: ${error.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ fetchDeviceInfo error", e)
            }
        }
    }

    private fun updateDeviceInfoInMap(
        mac: String,
        deviceName: String?,
        modelNumber: String?,
        firmwareRevision: String?,
        manufacturer: String?
    ) {
        _deviceDetails.update { currentMap ->
            val existing = currentMap[mac]
            val deviceInfo = com.lightstick.device.dto.DeviceInfo(
                deviceName = deviceName,
                modelNumber = modelNumber,
                firmwareRevision = firmwareRevision,
                manufacturer = manufacturer
            )

            if (existing != null) {
                currentMap + (mac to existing.copy(deviceInfo = deviceInfo))
            } else {
                val device = _devices.value.find { it.mac == mac }
                currentMap + (mac to DeviceDetailInfo(
                    mac = mac,
                    name = device?.name,
                    rssi = device?.rssi,
                    isConnected = true,
                    deviceInfo = deviceInfo
                ))
            }
        }
    }

    private fun fetchBatteryLevel(device: Device) {
        val ctx = appContext
        if (ctx == null || !hasBluetoothConnectPermission(ctx)) {
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

                Log.d(TAG, "🔋 Device is connected, requesting battery level for ${device.mac}")

                device.readBattery { result ->
                    result.onSuccess { level ->
                        Log.d(TAG, "🔋 Battery Level Success: $level% for ${device.mac}")
                        _deviceDetails.update { currentMap ->
                            val existing = currentMap[device.mac]
                            if (existing != null) {
                                currentMap + (device.mac to existing.copy(batteryLevel = level))
                            } else {
                                currentMap + (device.mac to DeviceDetailInfo(
                                    mac = device.mac,
                                    name = device.name,
                                    rssi = device.rssi,
                                    isConnected = true,
                                    batteryLevel = level
                                ))
                            }
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "⚠️ readBattery failed for ${device.mac}: ${error.message}", error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ fetchBatteryLevel error", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // OTA
    // ─────────────────────────────────────────────────────────────────────────────
    fun startOta(context: Context, device: Device, firmwareUri: Uri) {
        if (!hasBluetoothConnectPermission(context)) {
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
            } catch (e: Exception) {
                Log.e(TAG, "❌ startOta error", e)
                _otaInProgress.update { it + (device.mac to false) }
            }
        }
    }

    fun abortOta(device: Device) {
        val ctx = appContext
        if (ctx == null || !hasBluetoothConnectPermission(ctx)) {
            Log.w(TAG, "⚠️ BLUETOOTH_CONNECT 권한 없음")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "🛑 Aborting OTA for ${device.mac}")
                device.abortOta()
                _otaInProgress.update { it + (device.mac to false) }
            } catch (e: Exception) {
                Log.e(TAG, "❌ abortOta error", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────────
    private fun registerDeviceEventRules(device: Device) {
        val callRule = EventRule(
            id = "call-${device.mac}",
            trigger = EventTrigger(
                type = EventType.CALL_RINGING,
                filter = EventFilter()
            ),
            action = EventAction.SendEffectFrame(
                bytes16 = LSEffectPayload.Effects.blink(Colors.CYAN, period = 4).toByteArray()
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
                bytes16 = LSEffectPayload.Effects.blink(Colors.GREEN, period = 6).toByteArray()
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
}