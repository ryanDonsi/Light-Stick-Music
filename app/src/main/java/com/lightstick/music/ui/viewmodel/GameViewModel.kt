package com.lightstick.music.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.core.util.Log
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.data.model.GameResultSummary
import com.lightstick.music.data.model.GameState
import com.lightstick.music.data.model.WandResult
import com.lightstick.music.domain.game.GameBleManager
import com.lightstick.music.domain.game.GameProtocol
import com.lightstick.music.domain.game.ParsedGameResult
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.domain.usecase.game.ObserveGameResultsUseCase
import com.lightstick.music.domain.usecase.game.SendGameCommandUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    application: Application,
    private val gameBleManager: GameBleManager,
    private val sendGameCommandUseCase: SendGameCommandUseCase,
    private val observeGameResultsUseCase: ObserveGameResultsUseCase
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = AppConstants.Feature.VM_GAME
        private const val RESULT_COLLECT_WINDOW_MS = 4_000L
        private const val AUTO_START_DELAY_MS = 2_000L
    }

    // ─── UI State ─────────────────────────────────────────────────────────────

    private val _selectedMode = MutableStateFlow<GameMode?>(null)
    val selectedMode: StateFlow<GameMode?> = _selectedMode.asStateFlow()

    private val _selectedDifficulty = MutableStateFlow(GameDifficulty.NORMAL)
    val selectedDifficulty: StateFlow<GameDifficulty> = _selectedDifficulty.asStateFlow()

    private val _gameState = MutableStateFlow<GameState>(GameState.Idle)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    val bleConnectionState = gameBleManager.connectionState

    // ─── Countdown ────────────────────────────────────────────────────────────

    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    // ─── Accumulated Results ──────────────────────────────────────────────────

    private val collectedResults = mutableListOf<WandResult>()
    private var resultCollectJob: Job? = null

    // ─── Init ─────────────────────────────────────────────────────────────────

    init {
        observeGameResults()
    }

    // ─── Public Actions ───────────────────────────────────────────────────────

    fun selectMode(mode: GameMode) {
        _selectedMode.value = mode
        _selectedDifficulty.value = mode.defaultDifficulty
        if (_gameState.value is GameState.Finished || _gameState.value is GameState.Error) {
            _gameState.value = GameState.Idle
        }
    }

    fun selectDifficulty(difficulty: GameDifficulty) {
        _selectedDifficulty.value = difficulty
    }

    /** BLE 연결 (화면 진입 시 호출) */
    fun connectIfNeeded() {
        val ctx = getApplication<Application>()
        gameBleManager.connect(ctx)
    }

    /** 게임 시작 (READY 전송) */
    fun startGame() {
        val mode = _selectedMode.value ?: return
        val difficulty = _selectedDifficulty.value
        val ctx = getApplication<Application>()

        collectedResults.clear()
        resultCollectJob?.cancel()

        val ok = sendGameCommandUseCase.sendReady(ctx, mode, difficulty)
        if (!ok) {
            _gameState.value = GameState.Error("명령 전송 실패. 기기 연결을 확인하세요.")
            return
        }

        _gameState.value = GameState.Ready
        Log.d(TAG, "READY sent: mode=$mode difficulty=$difficulty")

        // 응원봉은 READY 수신 후 약 2초 뒤 Auto-START — UI 카운트다운
        viewModelScope.launch {
            for (sec in AUTO_START_DELAY_MS.toInt() / 1000 downTo 1) {
                _countdownSeconds.value = sec
                delay(1_000L)
            }
            _countdownSeconds.value = 0
            _gameState.value = GameState.Playing
        }
    }

    /** 게임 강제 중지 */
    fun stopGame() {
        val ctx = getApplication<Application>()
        sendGameCommandUseCase.sendStop(ctx)
        resultCollectJob?.cancel()
        _gameState.value = GameState.Idle
        Log.d(TAG, "STOP sent")
    }

    /** 결과 화면 → 다시 모드 선택으로 */
    fun resetToIdle() {
        val ctx = getApplication<Application>()
        sendGameCommandUseCase.sendClear(ctx)
        collectedResults.clear()
        resultCollectJob?.cancel()
        _gameState.value = GameState.Idle
        Log.d(TAG, "CLEAR sent, back to Idle")
    }

    // ─── Result Collection ────────────────────────────────────────────────────

    private fun observeGameResults() {
        viewModelScope.launch {
            observeGameResultsUseCase().collect { parsed ->
                if (_gameState.value !is GameState.Playing &&
                    _gameState.value !is GameState.Ready) return@collect

                onResultReceived(parsed)
            }
        }
    }

    private fun onResultReceived(parsed: ParsedGameResult) {
        val wandResult = parsed.toWandResult()

        // 유효하지 않은 wand_id 무시
        if (wandResult.wandId == 0 || wandResult.wandId == 0xFFFF) return

        // 중복 제거 (같은 wandId 는 먼저 받은 결과 우선)
        if (collectedResults.none { it.wandId == wandResult.wandId }) {
            collectedResults.add(wandResult)
        }

        Log.d(TAG, "Result collected: wand=0x${wandResult.wandId.toString(16)} red=${wandResult.redScore} total=${collectedResults.size}")

        // 첫 결과 수신 시 취합 타이머 시작
        if (resultCollectJob == null || resultCollectJob?.isCompleted == true) {
            resultCollectJob = viewModelScope.launch {
                delay(RESULT_COLLECT_WINDOW_MS)
                finalizeResults(parsed.subIndex)
            }
        }
    }

    private fun finalizeResults(subIndex: Int) {
        val mode = GameMode.entries.find { it.subIndex == subIndex }
            ?: _selectedMode.value
            ?: return

        val results = collectedResults.toList()
        if (results.isEmpty()) {
            _gameState.value = GameState.Error("결과를 수신하지 못했습니다")
            return
        }

        val totalRed = results.sumOf { it.redScore }
        val totalBlue = results.sumOf { it.blueScore }

        val summary = GameResultSummary(
            mode = mode,
            wandResults = results,
            totalWandCount = results.size,
            totalRedScore = totalRed,
            totalBlueScore = totalBlue
        )

        _gameState.value = GameState.Finished(summary)
        Log.d(TAG, "Game finished: mode=$mode red=$totalRed blue=$totalBlue count=${results.size}")
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        gameBleManager.disconnect()
    }
}
