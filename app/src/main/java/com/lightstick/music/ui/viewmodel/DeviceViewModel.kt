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
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState  // ✅ 추가
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
        if (appContext != null) return
        appContext = context.applicationContext

        DevicePreferences.initialize(context.applicationContext)

        if (PermissionManager.hasBluetoothConnectPermission(appContext!!)) {
            // ✅ SDK에서 이미 연결된 디바이스 동기화 추가
            syncConnectedDevicesOnInit()
            updateConnectedCount()
        }

        PermissionManager.logPermissionStatus(appContext!!, TAG)

        // ✅ 추가: SDK 연결 상태 실시간 관찰
        observeConnectionStates()
    }

    // ✅ 추가: SDK 연결 상태 실시간 관찰
    /**
     * SDK 연결 상태 실시간 관찰
     * - EffectViewModel과 상태 동기화
     * - 연결/해제 즉시 반영
     * - Navigation bar 배지 자동 업데이트
     */
    private fun observeConnectionStates() {
        viewModelScope.launch {
            LSBluetooth.observeDeviceStates().collect { states ->
                // 연결된 디바이스 MAC 주소 추출
                val connectedMacs = states
                    .filter { (_, state) ->
                        state.connectionState is ConnectionState.Connected
                    }
                    .keys

                // 연결 상태 맵 업데이트
                val updatedStates = _connectionStates.value.toMutableMap()

                // 연결된 디바이스 true로 설정
                connectedMacs.forEach { mac ->
                    updatedStates[mac] = true
                    Log.d(TAG, "✅ [observeConnectionStates] Device connected: $mac")
                }

                // 연결 끊긴 디바이스 false로 설정
                _devices.value.forEach { device ->
                    if (device.mac !in connectedMacs && updatedStates[device.mac] == true) {
                        updatedStates[device.mac] = false
                        Log.d(TAG, "⚠️ [observeConnectionStates] Device disconnected: ${device.mac}")
                    }
                }

                _connectionStates.value = updatedStates

                // ✅ 연결 개수 업데이트 (Navigation bar badge)
                _connectedDeviceCount.value = connectedMacs.size
                Log.d(TAG, "📊 [observeConnectionStates] Connected count: ${connectedMacs.size}")

                // ✅ 디바이스 목록 재정렬 (연결된 것 먼저)
                _devices.value = _devices.value.sortedWith(
                    compareByDescending<Device> {
                        _connectionStates.value[it.mac] ?: false
                    }.thenByDescending {
                        it.rssi ?: -100
                    }
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
// BLE Scan (✅ 개선 버전)
// ═══════════════════════════════════════════════════════════

    /**
     * ✅ 개선 사항:
     * 1. 블루투스 어댑터 상태 확인 추가
     * 2. 스캔 타임아웃 추가 (30초)
     * 3. 디버그 로깅 강화 (모든 스캔 디바이스 로깅)
     * 4. 에러 메시지 개선
     * 5. "LS"로 끝나는 디바이스만 필터링 (주요 기능 유지)
     */
    fun startScan(context: Context) {
        // ✅ 1. 블루투스 권한 확인
        if (!PermissionManager.hasBluetoothScanPermission(context)) {
            Log.w(TAG, "⚠️ BLUETOOTH_SCAN permission not granted")
            PermissionManager.logPermissionStatus(context, TAG)
            return
        }

        // ✅ 2. 블루투스 어댑터 상태 확인
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ BluetoothAdapter is null - 블루투스를 지원하지 않는 기기입니다")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "❌ Bluetooth is disabled - 블루투스를 켜주세요")
            // TODO: UI에 토스트 메시지 표시 또는 블루투스 활성화 요청
            return
        }

        // ✅ 3. 이미 스캔 중인지 확인
        if (_isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }

        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🔍 Starting BLE scan...")
        Log.d(TAG, "   Bluetooth Adapter: ${bluetoothAdapter.address}")
        Log.d(TAG, "   Bluetooth Enabled: ${bluetoothAdapter.isEnabled}")
        Log.d(TAG, "   Filter: Device name ends with 'LS'")
        _isScanning.value = true
        _devices.value = emptyList()

        // ✅ 4. 스캔 타임아웃 설정 (30초)
        val scanJob = viewModelScope.launch {
            delay(30_000) // 30초
            if (_isScanning.value) {
                Log.w(TAG, "⏱️ Scan timeout (30s) - stopping scan")
                Log.w(TAG, "   Total devices found: ${_devices.value.size}")
                stopScan()
            }
        }

        try {
            @SuppressLint("MissingPermission")
            fun doStartScan() {
                var scannedCount = 0
                var filteredCount = 0

                LSBluetooth.startScan { device ->
                    scannedCount++

                    // ✅ 5. 모든 스캔 결과 로깅 (디버그용)
                    Log.v(TAG, "📡 Scanned #$scannedCount: ${device.mac} | ${device.name} | RSSI: ${device.rssi}")

                    // ✅ 6. 디바이스 필터링 ("LS"로 끝나는 것만 - 주요 기능)
                    if (device.name?.endsWith("LS") == true) {
                        Log.d(TAG, "✅ Found LS device: ${device.mac} | ${device.name} | RSSI: ${device.rssi}")

                        val current = _devices.value.toMutableList()
                        val existingIndex = current.indexOfFirst { it.mac == device.mac }

                        if (existingIndex >= 0) {
                            current[existingIndex] = device
                            Log.v(TAG, "   ↻ Updated existing device")
                        } else {
                            current.add(device)
                            Log.v(TAG, "   ➕ Added new device (total: ${current.size})")
                        }

                        _devices.value = current.sortedWith(
                            compareByDescending<Device> {
                                _connectionStates.value[it.mac] ?: false
                            }.thenByDescending {
                                it.rssi ?: -100
                            }
                        )
                    } else {
                        filteredCount++
                        Log.v(TAG, "   ⊘ Filtered out: name='${device.name}' (not ending with 'LS')")
                    }

                    // ✅ 7. 주기적으로 스캔 통계 로깅 (10개마다)
                    if (scannedCount % 10 == 0) {
                        Log.d(TAG, "📊 Scan statistics:")
                        Log.d(TAG, "   ├─ Total scanned: $scannedCount")
                        Log.d(TAG, "   ├─ Filtered out: $filteredCount")
                        Log.d(TAG, "   └─ LS devices found: ${_devices.value.size}")
                    }
                }
            }

            doStartScan()
            Log.d(TAG, "✅ Scan started successfully")
            Log.d(TAG, "   Will auto-stop after 30 seconds")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException during scan: ${e.message}")
            Log.e(TAG, "   권한이 거부되었거나 런타임에 취소되었습니다.")
            _isScanning.value = false
            scanJob.cancel()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ IllegalStateException: ${e.message}")
            Log.e(TAG, "   블루투스 어댑터가 비활성화되었거나 사용 불가능합니다.")
            _isScanning.value = false
            scanJob.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error during scan: ${e.message}", e)
            _isScanning.value = false
            scanJob.cancel()
        }
    }

    fun stopScan() {
        if (!_isScanning.value) {
            Log.d(TAG, "Not scanning, skip stopScan()")
            return
        }

        Log.d(TAG, "🛑 Stopping BLE scan...")
        Log.d(TAG, "   Found devices: ${_devices.value.size}")

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

                            // 연결 성공 연출 (타임라인 기반)
                            viewModelScope.launch {
                                try {
                                    val connectionAnimation = listOf(
                                        0L to LSEffectPayload.Effects.blink(3, Colors.WHITE).toByteArray(),
                                        1200L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
                                    )

                                    if (device.loadTimeline(connectionAnimation)) {
                                        Log.d(TAG, "🎬 Connection animation timeline loaded (2 frames)")

                                        val startTime = System.currentTimeMillis()
                                        val duration = 1200L

                                        while (true) {
                                            val elapsed = System.currentTimeMillis() - startTime
                                            if (elapsed >= duration) {
                                                device.updatePlaybackPosition(duration)
                                                delay(50)
                                                break
                                            }
                                            device.updatePlaybackPosition(elapsed)
                                            delay(16)
                                        }

                                        device.stopTimeline()
                                        Log.d(TAG, "✅ Connection animation completed")
                                    } else {
                                        Log.w(TAG, "⚠️ Failed to load connection animation timeline")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ 연결 연출 실패: ${e.message}")
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
                Log.e(TAG, "❌ startOta error", e)
                _otaInProgress.update { it + (device.mac to false) }
            }
        }
    }

    fun abortOta(device: Device) {
        val ctx = appContext
        if (ctx == null || !PermissionManager.hasBluetoothConnectPermission(ctx)) {
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
        DevicePreferences.setCallEventEnabled(device.mac, enabled)

        _eventStates.update { states ->
            val deviceStates = states[device.mac]?.toMutableMap() ?: mutableMapOf()
            deviceStates[EventType.CALL_RINGING] = enabled
            states + (device.mac to deviceStates)
        }

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(callEventEnabled = enabled)
            }
        }

        if (enabled) {
            Log.d(TAG, "✅ CALL event enabled for ${device.mac}")
        } else {
            Log.d(TAG, "🔕 CALL event disabled for ${device.mac}")
        }
    }

    fun toggleSmsEvent(device: Device, enabled: Boolean) {
        DevicePreferences.setSmsEventEnabled(device.mac, enabled)

        _eventStates.update { states ->
            val deviceStates = states[device.mac]?.toMutableMap() ?: mutableMapOf()
            deviceStates[EventType.SMS_RECEIVED] = enabled
            states + (device.mac to deviceStates)
        }

        _deviceDetails.value = _deviceDetails.value.toMutableMap().apply {
            val existing = this[device.mac]
            if (existing != null) {
                this[device.mac] = existing.copy(smsEventEnabled = enabled)
            }
        }

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

    // ═══════════════════════════════════════════════════════════
    // ✅ SDK 연결 디바이스 동기화 (기존)
    // ═══════════════════════════════════════════════════════════

    /**
     * 앱 시작 시 SDK에서 이미 연결된 디바이스 동기화
     * Effect 화면에서 연결한 디바이스를 Device 화면에서도 인식하도록 함
     */
    @SuppressLint("MissingPermission")
    private fun syncConnectedDevicesOnInit() {
        try {
            val ctx = appContext ?: return
            if (!PermissionManager.hasBluetoothConnectPermission(ctx)) {
                Log.w(TAG, "⚠️ Cannot sync: permission not available")
                return
            }

            // ✅ SDK에서 실제 연결된 디바이스 조회
            val systemConnected = LSBluetooth.connectedDevices()

            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "📱 Syncing ${systemConnected.size} connected devices from SDK")

            systemConnected.forEach { device ->
                Log.d(TAG, "  - ${device.mac} (${device.name}) RSSI: ${device.rssi}")

                // ✅ connectedDevices Map에 추가 (중요!)
                if (!connectedDevices.containsKey(device.mac)) {
                    connectedDevices[device.mac] = device
                    updateConnectionState(device.mac, true)

                    // ✅ DeviceDetailInfo 초기화
                    initializeDeviceDetail(device)

                    // ✅ 이벤트 규칙 등록
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
}