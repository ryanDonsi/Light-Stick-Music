package com.lightstick.music.domain.game

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 게임 모드 BLE 통신 관리자.
 *
 * SDK [Device] API 위에 구축:
 * - TX: [Device.sendGameReady] / [Device.sendGameStop] / [Device.sendGameClear]
 *       → [Device.sendRawGameCommand] → Facade.sendGameCommandTo → LCS_COLOR(FF01)
 * - RX: [Device.subscribeGameResults]
 *       → [Device.subscribeCharacteristic] → GattClient.setCharacteristicNotification → FF04 Notify
 *
 * 별도 BluetoothGatt 연결 없이 SDK가 관리하는 기존 연결을 공유한다.
 */
@Singleton
class GameBleManager @Inject constructor() {

    private val TAG = AppConstants.Feature.GAME_BLE_MANAGER

    // ─── Connection State ─────────────────────────────────────────────────────

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        object Connected    : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ─── Game Result Stream ───────────────────────────────────────────────────

    private val _gameResultFlow = MutableSharedFlow<ParsedGameResult>(extraBufferCapacity = 32)
    val gameResultFlow: SharedFlow<ParsedGameResult> = _gameResultFlow.asSharedFlow()

    // ─── Active Device ────────────────────────────────────────────────────────

    @Volatile private var activeDevice: Device? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * SDK가 이미 연결한 기기 중 첫 번째를 게임 기기로 선택하고 FF04 Notify를 구독한다.
     *
     * 별도 GATT 연결을 생성하지 않으며, SDK의 기존 연결을 재사용한다.
     */
    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "▶ connect()  현재상태=${_connectionState.value}")

        val current = _connectionState.value
        if (current is ConnectionState.Connected || current is ConnectionState.Connecting) {
            Log.d(TAG, "  이미 $current → 재연결 스킵")
            return
        }

        // SDK 연결 기기 목록 조회
        val sdkDevices = try {
            LSBluetooth.connectedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "  LSBluetooth.connectedDevices() 예외: ${e.message}")
            emptyList()
        }

        Log.d(TAG, "  LSBluetooth 연결 기기 수: ${sdkDevices.size}")
        sdkDevices.forEachIndexed { i, dev ->
            Log.d(TAG, "    [$i] mac=${dev.mac}  name=${dev.name ?: "(없음)"}")
        }

        val sdkDevice = sdkDevices.firstOrNull() ?: run {
            Log.e(TAG, "  ✗ 연결된 기기 없음")
            _connectionState.value = ConnectionState.Error("연결된 응원봉이 없습니다")
            return
        }

        val device = Device(mac = sdkDevice.mac, name = sdkDevice.name)
        Log.d(TAG, "  타겟: mac=${device.mac}  name=${device.name}")

        if (!device.isConnected()) {
            Log.e(TAG, "  ✗ SDK 기기가 연결 상태가 아님 (mac=${device.mac})")
            _connectionState.value = ConnectionState.Error("기기가 연결되어 있지 않습니다")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "  FF04 Notify 구독 시작 → Connecting 상태")

        val ok = device.subscribeGameResults(
            onResult = { parsed ->
                Log.d(TAG, "▶ 게임 결과 수신: mode=${parsed.subIndex}  red=${parsed.redScore}  blue=${parsed.blueScore}  wandId=0x${parsed.wandId.toString(16)}")
                Log.d(TAG, GameProtocol.dumpPacket(
                    data = byteArrayOf(
                        0x05, 0x00,
                        parsed.subIndex.toByte(), 0x00,
                        0x05, 0x00,
                        parsed.redScore.toByte(), 0x00,
                        parsed.blueScore.toByte(), 0x00,
                        parsed.totalCount.toByte(), 0x00,
                        0x00, 0x00,
                        parsed.wandId.toByte(), (parsed.wandId shr 8).toByte(),
                        0x00, 0x00, 0x00, 0x00
                    ),
                    direction = "RX"
                ))
                _gameResultFlow.tryEmit(parsed)
            },
            onSubscribed = { result ->
                result.fold(
                    onSuccess = {
                        Log.d(TAG, "  ✓ FF04 Notify 구독 완료 → Connected 상태")
                        activeDevice = device
                        _connectionState.value = ConnectionState.Connected
                    },
                    onFailure = { t ->
                        Log.e(TAG, "  ✗ FF04 Notify 구독 실패: ${t.message}")
                        _connectionState.value = ConnectionState.Error("게임 결과 구독 실패: ${t.message}")
                    }
                )
            }
        )

        if (!ok) {
            Log.e(TAG, "  ✗ subscribeGameResults() false 반환")
            _connectionState.value = ConnectionState.Error("게임 결과 구독 요청 실패")
        }
    }

    /** 게임 시작 커맨드 전송 */
    @SuppressLint("MissingPermission")
    fun sendReady(mode: GameMode, difficulty: GameDifficulty): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "▶ sendReady() — activeDevice null")
            return false
        }
        val payload = GameProtocol.buildReadyPayload(mode, difficulty)
        Log.d(TAG, GameProtocol.dumpPacket(payload, "TX"))
        return device.sendGameReady(mode, difficulty).also { ok ->
            Log.d(TAG, "  sendReady 결과: $ok")
        }
    }

    /** 게임 중지 커맨드 전송 */
    @SuppressLint("MissingPermission")
    fun sendStop(): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "▶ sendStop() — activeDevice null")
            return false
        }
        val payload = GameProtocol.buildStopPayload()
        Log.d(TAG, GameProtocol.dumpPacket(payload, "TX"))
        return device.sendGameStop().also { ok ->
            Log.d(TAG, "  sendStop 결과: $ok")
        }
    }

    /** 게임 초기화 커맨드 전송 */
    @SuppressLint("MissingPermission")
    fun sendClear(): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "▶ sendClear() — activeDevice null")
            return false
        }
        val payload = GameProtocol.buildClearPayload()
        Log.d(TAG, GameProtocol.dumpPacket(payload, "TX"))
        return device.sendGameClear().also { ok ->
            Log.d(TAG, "  sendClear 결과: $ok")
        }
    }

    /** FF04 Notify 구독 해제 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "▶ disconnect()")
        activeDevice?.unsubscribeGameResults()
        activeDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
