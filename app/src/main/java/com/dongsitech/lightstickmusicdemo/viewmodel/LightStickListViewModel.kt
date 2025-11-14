package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
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
 * - 스캔: LSBluetooth.startScan/stopScan 사용
 * - 디바이스 모델: SDK의 Device 사용
 * - 연결/해제: Device.connect()/disconnect() 호출
 * - 권한 체크: PermissionUtils.hasPermission(...) 사용
 */
class LightStickListViewModel : ViewModel() {

    // ─────────────────────────────────────────────────────────────────────────────
    // Devices (SDK Device로 직접 노출)
    // ─────────────────────────────────────────────────────────────────────────────
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> =
        _devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    // 주소별 연결 여부 스냅샷
    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    // Context를 저장하는 변수 (ApplicationContext 사용)
    @Volatile
    private var appContext: Context? = null

    companion object {
        private var instance: LightStickListViewModel? = null
        fun getInstance(): LightStickListViewModel? = instance
    }

    init {
        instance = this
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Context 설정
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Context를 설정합니다.
     * MainActivity.onCreate()에서 한 번 호출해야 합니다.
     */
    fun initializeWithContext(context: Context) {
        if (appContext != null) return // 이미 초기화됨
        appContext = context.applicationContext

        // 초기 연결 개수 설정
        updateConnectedCount()
    }

    /**
     * 연결된 디바이스 개수를 업데이트합니다.
     * 연결/해제 시 호출됩니다.
     */
    private fun updateConnectedCount() {
        viewModelScope.launch {
            try {
                val ctx = appContext ?: return@launch
                if (!PermissionUtils.hasPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)) {
                    return@launch
                }

                // SDK 메서드로 직접 개수 가져오기
                _connectedDeviceCount.value = LSBluetooth.connectedCount()

                // 연결 상태도 함께 업데이트
                val connectedDevices = LSBluetooth.connectedDevices()
                val connectedMacs = connectedDevices.map { it.mac }.toSet()

                _connectionStates.update { prev ->
                    val next = prev.toMutableMap()

                    // 연결된 디바이스는 true
                    connectedMacs.forEach { mac ->
                        next[mac] = true
                    }

                    // 스캔된 디바이스 중 연결 안된 것들은 false
                    _devices.value.forEach { device ->
                        if (device.mac !in connectedMacs) {
                            next[device.mac] = false
                        }
                    }

                    next
                }

            } catch (se: SecurityException) {
                Log.w("LightStickVM", "Permission error in updateConnectedCount: ${se.message}")
            } catch (t: Throwable) {
                Log.e("LightStickVM", "Error in updateConnectedCount: ${t.message}", t)
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _permissionGranted.value = granted
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Scan
    // ─────────────────────────────────────────────────────────────────────────────

    fun startScan(context: Context) {
        if (_isScanning.value) return
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) return

        _devices.value = emptyList()
        _isScanning.value = true

        try {
            // 신 SDK 방식: 콜백으로 스캔 결과 수신
            LSBluetooth.startScan { device ->
                // ✅ null 체크 강화
                try {
                    // device의 필수 필드 검증
                    if (device.mac.isBlank()) {
                        Log.w("LightStickVM", "Skipping device with blank MAC")
                        return@startScan
                    }

                    // 이름이 "LS"로 끝나는 기기만 필터링 (null 안전)
                    val deviceName = device.name
                    if (deviceName != null && deviceName.endsWith("LS")) {
                        _devices.value = (_devices.value + device).distinctBy { it.mac }
                    } else {
                        Log.d("LightStickVM", "Filtered out device: mac=${device.mac}, name=$deviceName")
                    }
                } catch (e: Exception) {
                    Log.e("LightStickVM", "Error processing scan result: ${e.message}", e)
                }
            }
        } catch (se: SecurityException) {
            Log.w("LightStickVM", "startScan permission error: ${se.message}")
            _isScanning.value = false
            return
        } catch (t: Throwable) {
            Log.w("LightStickVM", "startScan error: ${t.message}", t)
            _isScanning.value = false
            return
        }

        // 3초 후 자동 종료
        viewModelScope.launch {
            delay(3000)
            stopScan(context)
            // 스캔 종료 후 연결 상태 업데이트
            updateConnectedCount()
        }
    }

    fun stopScan(context: Context) {
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
            _isScanning.value = false
            return
        }

        try {
            LSBluetooth.stopScan()
        } catch (se: SecurityException) {
            Log.w("LightStickVM", "stopScan permission error: ${se.message}")
        } catch (t: Throwable) {
            Log.w("LightStickVM", "stopScan error: ${t.message}")
        }

        _isScanning.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Connect / Disconnect
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 스캔 결과(Device)에서 바로 연결/해제 토글.
     */
    fun toggleConnection(context: Context, device: Device) {
        toggleConnectionByAddress(context, device.mac)
    }

    /**
     * 주소(mac) 기반 연결/해제 토글.
     */
    fun toggleConnectionByAddress(context: Context, address: String) {
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w("LightStickVM", "BLUETOOTH_CONNECT permission not granted")
            return
        }

        try {
            // 신 SDK에서는 Device 객체를 찾아서 connect/disconnect 호출
            val device = _devices.value.find { it.mac == address }
            if (device == null) {
                Log.w("LightStickVM", "Device not found: $address")
                return
            }

            // 연결 상태 확인 후 토글
            if (device.isConnected()) {
                // 연결 해제
                device.disconnect()
                _connectionStates.update { it + (address to false) }
                Log.d("LightStickVM", "Disconnected: $address")

                // 연결 해제 후 개수 업데이트
                updateConnectedCount()
            } else {
                // 연결 시작
                device.connect(
                    onConnected = { controller ->
                        Log.d("LightStickVM", "Connected: $address")
                        _connectionStates.update { it + (address to true) }

                        // 연결 성공 후 개수 업데이트
                        updateConnectedCount()
                    },
                    onFailed = { error ->
                        Log.w("LightStickVM", "Connection failed: $address - ${error.message}")
                        _connectionStates.update { it + (address to false) }
                    }
                )
            }
        } catch (se: SecurityException) {
            Log.w("LightStickVM", "toggleConnection permission error: ${se.message}")
        } catch (t: Throwable) {
            Log.w("LightStickVM", "toggleConnection error: ${t.message}", t)
        }
    }

    /**
     * 외부에서 수동으로 연결 상태를 업데이트할 때 사용 (선택적).
     */
    fun updateConnectionState(address: String, connected: Boolean) {
        _connectionStates.update { map ->
            map.toMutableMap().apply { this[address] = connected }
        }
        updateConnectedCount()
    }
}