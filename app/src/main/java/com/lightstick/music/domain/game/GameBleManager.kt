package com.lightstick.music.domain.game

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 게임 BLE 관리자
 *
 * FF03/FF04 GATT Characteristic으로 게임 명령을 전송하고 결과를 수신합니다.
 *
 * 통신 흐름:
 *   앱 → FF03 Write  → 중계기/응원봉 (게임 명령)
 *   앱 ← FF04 Notify ← 중계기/응원봉 (게임 결과)
 *
 * 연결 전략:
 *   LSBluetooth SDK가 관리하는 연결된 기기의 MAC을 재사용하여
 *   네이티브 BluetoothGatt로 별도 GATT 클라이언트를 연결합니다.
 */
@Singleton
class GameBleManager @Inject constructor() {

    private val TAG = "GameBleManager"

    // ─── Connection State ─────────────────────────────────────────────────────

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ─── Game Result Stream ───────────────────────────────────────────────────

    private val _gameResultFlow = MutableSharedFlow<ParsedGameResult>(extraBufferCapacity = 32)
    val gameResultFlow: SharedFlow<ParsedGameResult> = _gameResultFlow.asSharedFlow()

    // ─── GATT Handle ─────────────────────────────────────────────────────────

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var gameResultChar: BluetoothGattCharacteristic? = null
    @Volatile private var gameCmdChar: BluetoothGattCharacteristic? = null

    // ─── GATT Callback ───────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services")
                    _connectionState.value = ConnectionState.Connected
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.Error("서비스 검색 실패 ($status)")
                return
            }

            gameCmdChar = gatt.getService(GameProtocol.SERVICE_UUID)
                ?.getCharacteristic(GameProtocol.CHAR_GAME_CMD_UUID)

            gameResultChar = gatt.getService(GameProtocol.SERVICE_UUID)
                ?.getCharacteristic(GameProtocol.CHAR_GAME_RESULT_UUID)

            if (gameCmdChar == null || gameResultChar == null) {
                Log.w(TAG, "Game characteristics not found — possibly legacy firmware")
                _connectionState.value = ConnectionState.Error("게임 기능 미지원 기기입니다")
                return
            }

            subscribeToNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun subscribeToNotifications(gatt: BluetoothGatt) {
            val char = gameResultChar ?: return
            gatt.setCharacteristicNotification(char, true)

            val cccd = char.getDescriptor(GameProtocol.CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "CCCD descriptor not found on FF04")
                return
            }

            val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enableValue) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            Log.d(TAG, "FF04 CCCD subscribe: $ok")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "FF04 Notify 구독 완료")
            } else {
                Log.w(TAG, "CCCD write failed: $status")
            }
        }

        // API 33+: value가 콜백 파라미터로 직접 전달됩니다.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == GameProtocol.CHAR_GAME_RESULT_UUID) {
                handleGameResult(value)
            }
        }

        // API 31–32 fallback: value를 characteristic에서 읽습니다.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == GameProtocol.CHAR_GAME_RESULT_UUID
            ) {
                handleGameResult(characteristic.value ?: return)
            }
        }

        private fun handleGameResult(data: ByteArray) {
            val parsed = GameProtocol.parseResultPacket(data) ?: return
            Log.d(TAG, "Game result: mode=${parsed.subIndex} red=${parsed.redScore} blue=${parsed.blueScore} wand=0x${parsed.wandId.toString(16)}")
            _gameResultFlow.tryEmit(parsed)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "FF03 Write 성공")
            } else {
                Log.w(TAG, "FF03 Write 실패: $status")
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * 연결된 LSBluetooth 기기를 대상으로 게임 GATT 연결을 맺습니다.
     * 이미 연결 중이거나 Connected 상태면 재시도하지 않습니다.
     */
    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            _connectionState.value = ConnectionState.Error("BLUETOOTH_CONNECT 권한 없음")
            return
        }

        val current = _connectionState.value
        if (current is ConnectionState.Connected || current is ConnectionState.Connecting) return

        val mac = resolveTargetMac() ?: run {
            _connectionState.value = ConnectionState.Error("연결된 응원봉이 없습니다")
            return
        }

        val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth가 비활성화되어 있습니다")
            return
        }

        val remoteDevice = try {
            bluetoothAdapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid MAC $mac: ${e.message}")
            _connectionState.value = ConnectionState.Error("기기 주소 오류")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Game GATT connecting to $mac")

        gatt = remoteDevice.connectGatt(context, false, gattCallback)
    }

    /**
     * 게임 명령 페이로드를 FF03 Characteristic에 Write합니다.
     */
    @SuppressLint("MissingPermission")
    fun sendCommand(context: Context, payload: ByteArray): Boolean {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return false

        val char = gameCmdChar ?: run {
            Log.w(TAG, "Game cmd characteristic not ready")
            return false
        }
        val activeGatt = gatt ?: return false

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                char,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(char)
        }
        Log.d(TAG, "FF03 Write issued: $ok (${payload.size}B)")
        return ok
    }

    /** GATT 연결 해제 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        cleanup()
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun resolveTargetMac(): String? =
        try { LSBluetooth.connectedDevices().firstOrNull()?.mac } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        gameCmdChar = null
        gameResultChar = null
    }
}
