package com.lightstick.music.data.model

import com.lightstick.game.GameLevel as SdkGameLevel
import com.lightstick.game.GameMode as SdkGameMode

enum class GameMode(
    val subIndex: Int,
    val nameKr: String,
    val descKr: String,
    val winConditionKr: String,
    val defaultDifficulty: GameDifficulty = GameDifficulty.NORMAL
) {
    SPEED_REACTION(
        subIndex = 1,
        nameKr = "개인전(Speed)",
        descKr = "LED가 켜지면 빠르게 흔드세요",
        winConditionKr = "제한 시간 내 5회 먼저 성공",
        defaultDifficulty = GameDifficulty.NORMAL
    ),
    TEMPO(
        subIndex = 2,
        nameKr = "개인전(Tempo)",
        descKr = "LED 리듬에 맞춰 흔드세요",
        winConditionKr = "리듬에 맞춰 5회 연속 성공",
        defaultDifficulty = GameDifficulty.NORMAL
    ),
    TEAM_BATTLE(
        subIndex = 3,
        nameKr = "팀전(Random)",
        descKr = "내 팀 색 신호에 맞춰 흔드세요",
        winConditionKr = "5라운드 팀 점수 합산 승리",
        defaultDifficulty = GameDifficulty.EASY
    ),
    MANUAL_TEAM(
        subIndex = 4,
        nameKr = "팀전(Speed)",
        descKr = "팀 배정 후 LED가 켜지면 최대한 많이 흔드세요",
        winConditionKr = "라운드 합산 점수가 높은 팀 승리",
        defaultDifficulty = GameDifficulty.NORMAL
    );

    fun toSdkMode(): SdkGameMode = when (this) {
        SPEED_REACTION -> SdkGameMode.SPEED_REACTION
        TEMPO          -> SdkGameMode.TEMPO
        TEAM_BATTLE    -> SdkGameMode.TEAM_BATTLE
        MANUAL_TEAM    -> SdkGameMode.TEAM_SIMULTANEOUS
    }

    companion object {
        fun fromSdkMode(sdkMode: SdkGameMode): GameMode? =
            entries.find { it.toSdkMode() == sdkMode }
    }
}

enum class GameDifficulty(val level: Int, val nameKr: String) {
    EASY(1, "쉬움"),
    NORMAL(2, "보통"),
    HARD(3, "어려움");

    fun toSdkLevel(): SdkGameLevel = when (this) {
        EASY   -> SdkGameLevel.EASY
        NORMAL -> SdkGameLevel.NORMAL
        HARD   -> SdkGameLevel.HARD
    }

    /** Mode 4 라운드당 측정 시간(ms) — easy=8000 / normal=5000 / hard=3000 */
    val teamMeasureMs: Int
        get() = when (this) {
            EASY   -> 8000
            NORMAL -> 5000
            HARD   -> 3000
        }
}

/** Mode 4 수동 팀 배정 — teamId는 프로토콜 값(0=RED/1=BLUE)과 동일 */
enum class Team(val teamId: Int, val nameKr: String) {
    RED(0, "홍팀"),
    BLUE(1, "청팀")
}

sealed class GameState {
    /** 기기 미연결 또는 게임 대기 */
    object Idle : GameState()
    /** READY 전송 완료, 응원봉 2초 카운트다운 중 */
    object Ready : GameState()
    /** 게임 진행 중 */
    object Playing : GameState()
    /** 결과 수신 완료 */
    data class Finished(val summary: GameResultSummary) : GameState()
    /** 오류 발생 */
    data class Error(val message: String) : GameState()
    /**
     * Mode 4 전용 — [team] 수동 배정 대기 중.
     * [endSent]가 false면 응원봉 확정을 기다리며 "배정 종료" 버튼 입력 대기 중,
     * true면 TEAM_ASSIGN_END를 보내고 집계 Notify(cmd=8) 응답을 기다리는 중(재전송 방지).
     * [confirmedCount]는 집계 Notify 수신 후 확정 인원수.
     */
    data class TeamAssigning(
        val team: Team,
        val endSent: Boolean = false,
        val confirmedCount: Int? = null
    ) : GameState()
}

data class WandResult(
    /** 응원봉 고유 ID (MAC 하위 2바이트) */
    val wandId: Int,
    /** Red팀 점수 또는 개인 점수 */
    val redScore: Int,
    /** Blue팀 점수 (Mode1·2에서는 항상 0) */
    val blueScore: Int,
    /** Notify 수신 시각 */
    val receivedAt: Long = System.currentTimeMillis()
)

data class GameResultSummary(
    val mode: GameMode,
    val wandResults: List<WandResult>,
    val totalWandCount: Int,
    /** Red팀 누적 점수 합계 */
    val totalRedScore: Int,
    /** Blue팀 누적 점수 합계 */
    val totalBlueScore: Int
) {
    /** Mode 1·2: 개인 우승자 (score==5, 빠른 수신 순) */
    val soloWinner: WandResult?
        get() = wandResults
            .filter { it.redScore == 5 && it.wandId != 0 && it.wandId != 0xFFFF }
            .minByOrNull { it.receivedAt }

    /** Mode 3: 팀 승자 */
    val teamWinner: TeamWinner
        get() = when {
            totalRedScore > totalBlueScore -> TeamWinner.RED
            totalBlueScore > totalRedScore -> TeamWinner.BLUE
            else -> TeamWinner.DRAW
        }

    /** Mode 1·2: 순위 (score 높은 순, 동점이면 수신 시각 빠른 순) */
    val rankedResults: List<WandResult>
        get() = wandResults
            .filter { it.wandId != 0 && it.wandId != 0xFFFF }
            .sortedWith(compareByDescending<WandResult> { it.redScore }
                .thenBy { it.receivedAt })
}

enum class TeamWinner { RED, BLUE, DRAW }
