package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import io.lightstick.sdk.ble.BleSdk
import io.lightstick.sdk.ble.manager.BleConnectionState
import io.lightstick.sdk.ble.model.ScanResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Light Stick 목록/스캔/연결 상태를 관리하는 ViewModel.
 *
 * - 스캔: SDK ScanManager(startScan/stopScan + scanResults Flow) 사용
 * - 디바이스 모델: android.bluetooth.* 를 쓰지 않고 SDK의 ScanResult(이름/주소/RSSI)만 노출
 * - 연결/해제: 주소(address) 기반으로 BleSdk.gattManager 호출
 * - 권한 체크: PermissionUtils.hasPermission(...) 사용
 */
class LightStickListViewModel : ViewModel() {

    // ─────────────────────────────────────────────────────────────────────────────
    // Devices (SDK ScanResult로 직접 노출)
    // ─────────────────────────────────────────────────────────────────────────────
    private val _devices = MutableStateFlow<List<ScanResult>>(emptyList())
    val devices: StateFlow<List<ScanResult>> =
        _devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

//    private val _isRefreshing = MutableStateFlow(false)
//    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    // 주소별 연결 여부 스냅샷
    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    // SDK 스캔 결과 수집용 Job
    private var scanCollectJob: Job? = null

    companion object {
        private var instance: LightStickListViewModel? = null
        fun getInstance(): LightStickListViewModel? = instance
    }

    init {
        instance = this

        // 1) 실제 연결셋(서비스 디스커버리 완료 기준) 구독 → UI 스냅샷 갱신
        viewModelScope.launch {
            BleSdk.gattManager.connectedAddresses.collect { set ->
                _connectionStates.update { prev ->
                    val next = prev.toMutableMap()
                    // 연결된 주소는 true
                    set.forEach { next[it] = true }
                    // 이전에 알던 주소 중 지금 연결셋에 없는 건 false
                    (next.keys - set).forEach { next[it] = false }
                    next
                }
                _connectedDeviceCount.value = set.size
            }
        }

        // 2) 개별 연결 이벤트도 반영
        viewModelScope.launch {
            BleSdk.gattManager.connectionStateFlow.collect { state ->
                when (state) {
                    is BleConnectionState.Connected -> {
                        _connectionStates.update { it + (state.address to true) }
                    }
                    is BleConnectionState.Disconnected -> {
                        _connectionStates.update { it + (state.address to false) }
                    }
                    is BleConnectionState.Failed -> {
                        _connectionStates.update { it + (state.address to false) }
                    }
                    is BleConnectionState.Connecting, null -> Unit
                }
                _connectedDeviceCount.value = _connectionStates.value.count { it.value }
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _permissionGranted.value = granted
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Scan: SDK ScanManager (무인자 startScan + 결과 Flow 수집)
    //  - 권한 체크는 PermissionUtils 사용
    //  - 위험 호출은 try/catch(SecurityException)로 보강
    // ─────────────────────────────────────────────────────────────────────────────
    fun startScan(context: Context) {
        if (_isScanning.value) return
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) return

        _devices.value = emptyList()
        _isScanning.value = true

        // 1) SDK 스캔 시작
        try {
            BleSdk.scanManager.startScan()
        } catch (se: SecurityException) {
            Log.w("LightStickVM", "startScan permission error: ${se.message}")
            _isScanning.value = false
            return
        } catch (t: Throwable) {
            Log.w("LightStickVM", "startScan error: ${t.message}")
        }

        // 2) 결과 수집 (프로퍼티명은 SDK AAR에서 확인하여 필요시 변경)
        scanCollectJob?.cancel()
        scanCollectJob = viewModelScope.launch {
            try {
                // 예: scanResults / results / scanResultFlow 등 → 실제 SDK 공개 이름으로 교체
                BleSdk.scanManager.scanResults
                    .collect { results: List<ScanResult> ->
                        // 이름이 "…LS"로 끝나는 기기만 표시(기존 로직 유지). 필요 없으면 필터 제거.
                        _devices.value = results.filter { it.name?.endsWith("LS") == true }
                    }
            } catch (se: SecurityException) {
                Log.w("LightStickVM", "collect scanResults permission error: ${se.message}")
            } catch (t: Throwable) {
                Log.w("LightStickVM", "collect scanResults error: ${t.message}")
            }
        }

        // 3) 3초 후 자동 종료(기존 동작 유지)
        viewModelScope.launch {
            delay(3000)
            stopScan(context)
        }
    }

//    fun refreshScan(context: Context) {
//        _isRefreshing.value = true
//        startScan(context)
//        viewModelScope.launch {
//            delay(5000)
//            _isRefreshing.value = false
//        }
//    }

    fun stopScan(context: Context) {
        scanCollectJob?.cancel()
        scanCollectJob = null

        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
            _isScanning.value = false
            return
        }
        try {
            BleSdk.scanManager.stopScan()
        } catch (se: SecurityException) {
            Log.w("LightStickVM", "stopScan permission error: ${se.message}")
        } catch (t: Throwable) {
            Log.w("LightStickVM", "stopScan error: ${t.message}")
        }
        _isScanning.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Connect / Disconnect (주소 기반)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 스캔 결과(ScanResult)에서 바로 연결/해제 토글.
     */
    fun toggleConnection(context: Context, result: ScanResult) {
        toggleConnectionByAddress(context, result.address)
    }

    /**
     * 주소(address) 기반 연결/해제 토글.
     */
    fun toggleConnectionByAddress(context: Context, address: String) {
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            if (BleSdk.gattManager.isConnected(address)) {
                BleSdk.gattManager.disconnect(address)
            } else {
                BleSdk.gattManager.connect(address)
            }
        } catch (se: SecurityException) {
            Log.w("LightStickVM", "toggleConnection permission error: ${se.message}")
        } catch (t: Throwable) {
            Log.w("LightStickVM", "toggleConnection error: ${t.message}")
        }
    }

    /**
     * 외부에서 수동으로 연결 상태 스냅샷을 반영해야 할 때 사용(선택).
     */
    fun updateConnectionState(address: String, connected: Boolean) {
        _connectionStates.update { map ->
            map.toMutableMap().apply { this[address] = connected }
        }
        _connectedDeviceCount.value = _connectionStates.value.count { it.value }
    }
}
