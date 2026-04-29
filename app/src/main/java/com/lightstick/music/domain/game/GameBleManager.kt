package com.lightstick.music.domain.game

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.game.GameResult
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
 * SDK [Device] 고수준 API를 사용:
 * - TX+RX: [Device.startGame] → FF03 READY 전송 + FF04 Notify 구독 일괄 처리
 * - TX: [Device.stopGame] / [Device.clearGame]
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

    private val _gameResultFlow = MutableSharedFlow<GameResult>(extraBufferCapacity = 32)
    val gameResultFlow: SharedFlow<GameResult> = _gameResultFlow.asSharedFlow()

    // ─── Active Device ────────────────────────────────────────────────────────

    @Volatile private var activeDevice: Device? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * SDK가 이미 연결한 기기 중 첫 번째를 게임 기기로 선택한다.
     *
     * FF04 Notify 구독은 [startGame] 호출 시 SDK가 자동으로 처리한다.
     */
    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        val current = _connectionState.value
        if (current is ConnectionState.Connected || current is ConnectionState.Connecting) return

        _connectionState.value = ConnectionState.Connecting

        val sdkDevices = try {
            LSBluetooth.connectedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "LSBluetooth.connectedDevices() 예외: ${e.message}")
            _connectionState.value = ConnectionState.Error("기기 조회 실패: ${e.message}")
            return
        }

        val sdkDevice = sdkDevices.firstOrNull() ?: run {
            Log.e(TAG, "연결된 기기 없음")
            _connectionState.value = ConnectionState.Error("연결된 응원봉이 없습니다")
            return
        }

        val device = Device(mac = sdkDevice.mac, name = sdkDevice.name)
        if (!device.isConnected()) {
            Log.e(TAG, "SDK 기기가 연결 상태가 아님 (mac=${device.mac})")
            _connectionState.value = ConnectionState.Error("기기가 연결되어 있지 않습니다")
            return
        }

        activeDevice = device
        _connectionState.value = ConnectionState.Connected
        Log.d(TAG, "Device selected: mac=${device.mac} name=${device.name}")
    }

    /** 게임 시작: FF04 Notify 구독 + READY 커맨드 전송을 SDK가 일괄 처리 */
    @SuppressLint("MissingPermission")
    fun startGame(mode: GameMode, difficulty: GameDifficulty): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "startGame() — activeDevice null")
            return false
        }
        val option = if (mode == GameMode.TEAM_BATTLE) Device.GAME_OPTION_RANDOM_TEAM else 0
        return device.startGame(
            mode   = mode.toSdkMode(),
            level  = difficulty.toSdkLevel(),
            option = option
        ) { result ->
            Log.d(TAG, "게임 결과 수신: mode=${result.mode} red=${result.redScore} blue=${result.blueScore} wandId=0x${result.wandId.toString(16)}")
            _gameResultFlow.tryEmit(result)
        }.also { ok ->
            Log.d(TAG, "startGame 결과: $ok (mode=$mode difficulty=$difficulty)")
        }
    }

    /** 게임 중지 커맨드 전송 */
    @SuppressLint("MissingPermission")
    fun stopGame(): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "stopGame() — activeDevice null")
            return false
        }
        return device.stopGame().also { ok ->
            Log.d(TAG, "stopGame 결과: $ok")
        }
    }

    /** 게임 초기화 커맨드 전송 및 FF04 Notify 구독 해제 */
    @SuppressLint("MissingPermission")
    fun clearGame(): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "clearGame() — activeDevice null")
            return false
        }
        return device.clearGame().also { ok ->
            Log.d(TAG, "clearGame 결과: $ok")
        }
    }

    /** FF04 Notify 구독 해제 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "disconnect()")
        activeDevice?.unsubscribeGameResults()
        activeDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
