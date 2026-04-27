package com.lightstick.music.domain.game

import android.annotation.SuppressLint
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
            val stateStr = when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING   -> "CONNECTING"
                else -> "UNKNOWN($newState)"
            }
            // status=0(GATT_SUCCESS), 8=연결끊김, 19=원격종료, 22=로컬종료, 133=연결타임아웃/기기없음
            Log.d(TAG, "▶ onConnectionStateChange  status=$status  newState=$stateStr")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "  GATT 물리 연결 완료 → discoverServices() 호출")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "  GATT 연결 해제 (status=$status) → 상태 초기화")
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "▶ onServicesDiscovered  status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "  서비스 탐색 실패 (status=$status) → Error 상태")
                _connectionState.value = ConnectionState.Error("서비스 검색 실패 ($status)")
                return
            }

            // ── 발견된 전체 서비스/특성 덤프 ──────────────────────────────────
            val svcList = gatt.services
            Log.d(TAG, "  발견된 서비스 수: ${svcList.size}")
            svcList.forEach { svc ->
                Log.d(TAG, "  ┌ Service: ${svc.uuid}  (type=${svc.type})")
                svc.characteristics.forEach { c ->
                    val props = buildPropsString(c.properties)
                    Log.d(TAG, "  │  ├ Char: ${c.uuid}  props=0x${c.properties.toString(16)} [$props]")
                    c.descriptors.forEach { d ->
                        Log.d(TAG, "  │  │   └ Desc: ${d.uuid}")
                    }
                }
                Log.d(TAG, "  └──")
            }

            // ── 타겟 서비스 / Char 탐색 ────────────────────────────────────────
            Log.d(TAG, "  [탐색] SERVICE_UUID      = ${GameProtocol.SERVICE_UUID}")
            Log.d(TAG, "  [탐색] CHAR_GAME_CMD     = ${GameProtocol.CHAR_GAME_CMD_UUID}")
            Log.d(TAG, "  [탐색] CHAR_GAME_RESULT  = ${GameProtocol.CHAR_GAME_RESULT_UUID}")

            val targetSvc = gatt.getService(GameProtocol.SERVICE_UUID)
            Log.d(TAG, "  getService(SERVICE_UUID) → ${if (targetSvc != null) "발견" else "null (폴백 전체 탐색)"}")

            gameCmdChar = targetSvc?.getCharacteristic(GameProtocol.CHAR_GAME_CMD_UUID)
                ?: findCharInAllServices(gatt, GameProtocol.CHAR_GAME_CMD_UUID).also {
                    if (it != null) Log.d(TAG, "  CMD char → 폴백 탐색으로 발견 (service=${it.service?.uuid})")
                }

            gameResultChar = targetSvc?.getCharacteristic(GameProtocol.CHAR_GAME_RESULT_UUID)
                ?: findCharInAllServices(gatt, GameProtocol.CHAR_GAME_RESULT_UUID).also {
                    if (it != null) Log.d(TAG, "  RESULT char → 폴백 탐색으로 발견 (service=${it.service?.uuid})")
                }

            Log.d(TAG, "  gameCmdChar    = ${gameCmdChar?.uuid ?: "null ← 미발견"}")
            Log.d(TAG, "  gameResultChar = ${gameResultChar?.uuid ?: "null ← 미발견"}")

            if (gameCmdChar == null || gameResultChar == null) {
                val missing = buildList {
                    if (gameCmdChar == null)    add("FF01(cmd=${GameProtocol.CHAR_GAME_CMD_UUID})")
                    if (gameResultChar == null) add("FF04(result=${GameProtocol.CHAR_GAME_RESULT_UUID})")
                }
                Log.e(TAG, "  ✗ 필요 Characteristic 미발견: $missing")
                Log.e(TAG, "  ✗ 위 '전체 서비스 덤프'에서 FF01/FF04 UUID가 있는지 확인하세요")
                _connectionState.value = ConnectionState.Error("게임 기능 미지원 기기입니다 (${missing.joinToString()})")
                return
            }

            Log.d(TAG, "  ✓ 필요 Characteristic 모두 발견 → Connected 상태")
            _connectionState.value = ConnectionState.Connected
            subscribeToNotifications(gatt)
        }

        private fun findCharInAllServices(
            gatt: BluetoothGatt,
            charUuid: java.util.UUID
        ): BluetoothGattCharacteristic? {
            gatt.services.forEach { svc ->
                svc.getCharacteristic(charUuid)?.let { return it }
            }
            return null
        }

        @SuppressLint("MissingPermission")
        private fun subscribeToNotifications(gatt: BluetoothGatt) {
            val char = gameResultChar ?: return
            Log.d(TAG, "▶ subscribeToNotifications  char=${char.uuid}")

            val notifyEnabled = gatt.setCharacteristicNotification(char, true)
            Log.d(TAG, "  setCharacteristicNotification → $notifyEnabled")

            val cccd = char.getDescriptor(GameProtocol.CCCD_UUID)
            if (cccd == null) {
                Log.e(TAG, "  ✗ CCCD descriptor(${GameProtocol.CCCD_UUID}) 없음 → Notify 불가")
                Log.e(TAG, "  등록된 Descriptor 목록: ${char.descriptors.map { it.uuid }}")
                return
            }
            Log.d(TAG, "  CCCD descriptor 발견: ${cccd.uuid}")

            val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(cccd, enableValue)
                Log.d(TAG, "  writeDescriptor(API33+) → result=$result")
                result == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                val result = gatt.writeDescriptor(cccd)
                Log.d(TAG, "  writeDescriptor(legacy) → result=$result")
                result
            }
            Log.d(TAG, "  CCCD write 전송 결과: $ok")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "▶ onDescriptorWrite  desc=${descriptor.uuid}  status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "  ✓ FF04 Notify 구독 완료")
            } else {
                Log.e(TAG, "  ✗ CCCD write 실패 (status=$status) → Notify 수신 불가")
            }
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "▶ onCharacteristicChanged(API33+)  char=${characteristic.uuid}  len=${value.size}")
            Log.d(TAG, "  raw: ${value.toHexString()}")
            if (characteristic.uuid == GameProtocol.CHAR_GAME_RESULT_UUID) {
                handleGameResult(value)
            } else {
                Log.d(TAG, "  UUID 불일치 — 무시 (expected=${GameProtocol.CHAR_GAME_RESULT_UUID})")
            }
        }

        // API 31–32 fallback
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: run {
                Log.w(TAG, "▶ onCharacteristicChanged(legacy)  value=null → 무시")
                return
            }
            Log.d(TAG, "▶ onCharacteristicChanged(legacy)  char=${characteristic.uuid}  len=${value.size}")
            Log.d(TAG, "  raw: ${value.toHexString()}")
            if (characteristic.uuid == GameProtocol.CHAR_GAME_RESULT_UUID) {
                handleGameResult(value)
            }
        }

        private fun handleGameResult(data: ByteArray) {
            Log.d(TAG, "▶ handleGameResult  len=${data.size}")
            val parsed = GameProtocol.parseResultPacket(data)
            if (parsed == null) {
                Log.w(TAG, "  ✗ 파싱 실패 — effectIndex 또는 cmdIndex 불일치")
                if (data.size >= 6) {
                    val effectIndex = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    val subIndex    = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                    val cmdIndex    = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
                    Log.w(TAG, "  effectIndex=0x${effectIndex.toString(16)}  subIndex=$subIndex  cmdIndex=$cmdIndex")
                }
                return
            }
            Log.d(TAG, "  ✓ 파싱 성공: mode=${parsed.subIndex}  red=${parsed.redScore}  blue=${parsed.blueScore}  wandId=0x${parsed.wandId.toString(16)}")
            _gameResultFlow.tryEmit(parsed)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "▶ onCharacteristicWrite  char=${characteristic.uuid}  status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "  ✓ Write 성공")
            } else {
                Log.e(TAG, "  ✗ Write 실패 (status=$status)")
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "▶ connect()  현재상태=${_connectionState.value}")

        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.e(TAG, "  ✗ BLUETOOTH_CONNECT 권한 없음")
            _connectionState.value = ConnectionState.Error("BLUETOOTH_CONNECT 권한 없음")
            return
        }

        val current = _connectionState.value
        if (current is ConnectionState.Connected || current is ConnectionState.Connecting) {
            Log.d(TAG, "  이미 $current → 재연결 스킵")
            return
        }

        // SDK 연결 기기 목록 전체 출력
        val sdkDevices = try { LSBluetooth.connectedDevices() } catch (e: Exception) {
            Log.e(TAG, "  LSBluetooth.connectedDevices() 예외: ${e.message}")
            emptyList()
        }
        Log.d(TAG, "  LSBluetooth 연결 기기 수: ${sdkDevices.size}")
        sdkDevices.forEachIndexed { i, dev ->
            Log.d(TAG, "    [$i] mac=${dev.mac}  name=${dev.name ?: "(없음)"}")
        }

        val mac = sdkDevices.firstOrNull()?.mac ?: run {
            Log.e(TAG, "  ✗ 연결된 기기 없음 → Error")
            _connectionState.value = ConnectionState.Error("연결된 응원봉이 없습니다")
            return
        }
        Log.d(TAG, "  타겟 MAC: $mac")

        val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "  ✗ BluetoothAdapter 비활성화")
            _connectionState.value = ConnectionState.Error("Bluetooth가 비활성화되어 있습니다")
            return
        }
        Log.d(TAG, "  BluetoothAdapter 활성화 확인 ✓")

        val remoteDevice = try {
            bluetoothAdapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            Log.e(TAG, "  ✗ getRemoteDevice($mac) 예외: ${e.message}")
            _connectionState.value = ConnectionState.Error("기기 주소 오류")
            return
        }
        Log.d(TAG, "  remoteDevice: name=${remoteDevice.name}  bondState=${remoteDevice.bondState}")

        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "  connectGatt() 호출 → Connecting 상태")
        gatt = remoteDevice.connectGatt(context, false, gattCallback)
        Log.d(TAG, "  gatt 인스턴스 생성됨: ${gatt != null}")
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(context: Context, payload: ByteArray): Boolean {
        Log.d(TAG, "▶ sendCommand()  len=${payload.size}  hex=${payload.toHexString()}")

        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.e(TAG, "  ✗ 권한 없음")
            return false
        }
        val char = gameCmdChar ?: run {
            Log.e(TAG, "  ✗ gameCmdChar null — 아직 연결되지 않음")
            return false
        }
        val activeGatt = gatt ?: run {
            Log.e(TAG, "  ✗ gatt null")
            return false
        }

        Log.d(TAG, "  char=${char.uuid}  props=0x${char.properties.toString(16)} [${buildPropsString(char.properties)}]")
        Log.d(TAG, "  API level=${Build.VERSION.SDK_INT}")

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = activeGatt.writeCharacteristic(
                char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            Log.d(TAG, "  writeCharacteristic(API33+) → result=$result")
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            val result = activeGatt.writeCharacteristic(char)
            Log.d(TAG, "  writeCharacteristic(legacy) → result=$result")
            result
        }
        Log.d(TAG, "  sendCommand 결과: $ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "▶ disconnect()")
        gatt?.disconnect()
        cleanup()
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        Log.d(TAG, "▶ cleanup()")
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        gameCmdChar = null
        gameResultChar = null
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildPropsString(props: Int): String = buildList {
        if (props and 0x02 != 0) add("READ")
        if (props and 0x04 != 0) add("WRITE_NO_RSP")
        if (props and 0x08 != 0) add("WRITE")
        if (props and 0x10 != 0) add("NOTIFY")
        if (props and 0x20 != 0) add("INDICATE")
    }.joinToString("|")

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
