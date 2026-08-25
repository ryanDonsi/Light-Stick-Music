package com.lightstick.music.domain.game

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.game.GameCmd
import com.lightstick.game.GameResult
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.data.model.Team
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
 * - TX+RX: [Device.sendGameCmd] → FF03 명령 전송 + (onResult 전달 시) FF04 Notify 구독 일괄 처리
 * - TX: [Device.sendGameCmd]([GameCmd.STOP]/[GameCmd.CLEAR])
 */
@Singleton
class GameBleManager @Inject constructor() {

    private val TAG = AppConstants.Feature.GAME_BLE_MANAGER

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        object Connected    : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isGameModeSupported = MutableStateFlow(false)
    val isGameModeSupported: StateFlow<Boolean> = _isGameModeSupported.asStateFlow()

    private val _gameResultFlow = MutableSharedFlow<GameResult>(extraBufferCapacity = 32)
    val gameResultFlow: SharedFlow<GameResult> = _gameResultFlow.asSharedFlow()

    @Volatile private var activeDevice: Device? = null

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
        _isGameModeSupported.value = device.supportsGameMode()
        _connectionState.value = ConnectionState.Connected
    }

    /** 게임 시작: READY(cmd=1) 커맨드 전송 + FF04 Notify 구독을 SDK가 일괄 처리 */
    @SuppressLint("MissingPermission")
    fun startGame(mode: GameMode, difficulty: GameDifficulty): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "startGame() — activeDevice null")
            return false
        }
        val option = if (mode == GameMode.TEAM_BATTLE) Device.GAME_OPTION_RANDOM_TEAM else 0
        return device.sendGameCmd(
            cmd    = GameCmd.START,
            mode   = mode.toSdkMode(),
            level  = difficulty.toSdkLevel().value,
            option = option,
            wandId = 0
        ) { result ->
            _gameResultFlow.tryEmit(result)
        }
    }

    /** Mode 4 팀 배정 시작: TEAM_ASSIGN(cmd=7) 전송 — 미배정 응원봉이 해당 팀 색으로 블링크 */
    @SuppressLint("MissingPermission")
    fun startTeamAssign(team: Team): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "startTeamAssign() — activeDevice null")
            return false
        }
        return device.sendGameCmd(
            cmd    = GameCmd.TEAM_ASSIGN,
            mode   = GameMode.MANUAL_TEAM.toSdkMode(),
            level  = team.teamId
        ) { result ->
            _gameResultFlow.tryEmit(result)
        }
    }

    /** Mode 4 팀 배정 종료: TEAM_ASSIGN_END(cmd=9) 전송 — 중계기가 집계 인원수를 FF04로 Notify */
    @SuppressLint("MissingPermission")
    fun endTeamAssign(team: Team): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "endTeamAssign() — activeDevice null")
            return false
        }
        return device.sendGameCmd(
            cmd   = GameCmd.TEAM_ASSIGN_END,
            mode  = GameMode.MANUAL_TEAM.toSdkMode(),
            level = team.teamId
        )
    }

    /** Mode 4 게임 시작: READY(cmd=1) — level=라운드 수, option=라운드당 측정시간(ms) */
    @SuppressLint("MissingPermission")
    fun startTeamGame(rounds: Int, difficulty: GameDifficulty): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "startTeamGame() — activeDevice null")
            return false
        }
        return device.sendGameCmd(
            cmd    = GameCmd.START,
            mode   = GameMode.MANUAL_TEAM.toSdkMode(),
            level  = rounds,
            option = difficulty.teamMeasureMs
        ) { result ->
            _gameResultFlow.tryEmit(result)
        }
    }

    /** WINNER(cmd=6) 커맨드 전송 (Mode 1·2 전용) */
    @SuppressLint("MissingPermission")
    fun sendWinner(mode: GameMode, winnerWandId: Int): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "sendWinner() — activeDevice null")
            return false
        }
        return device.sendGameCmd(
            cmd    = GameCmd.WINNER,
            mode   = mode.toSdkMode(),
            wandId = winnerWandId
        )
    }

    /** 게임 중지(cmd=3) 커맨드 전송 */
    @SuppressLint("MissingPermission")
    fun stopGame(): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "stopGame() — activeDevice null")
            return false
        }
        return device.sendGameCmd(cmd = GameCmd.STOP)
    }

    /** 게임 초기화(cmd=4) 커맨드 전송 */
    @SuppressLint("MissingPermission")
    fun clearGame(): Boolean {
        val device = activeDevice ?: run {
            Log.e(TAG, "clearGame() — activeDevice null")
            return false
        }
        return device.sendGameCmd(cmd = GameCmd.CLEAR)
    }

    /** FF04 Notify 구독 해제 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        activeDevice?.clearNotifyGameResults()
        activeDevice = null
        _isGameModeSupported.value = false
        _connectionState.value = ConnectionState.Disconnected
    }
}
