package com.lightstick.music.data.model

// ─── Game Mode ───────────────────────────────────────────────────────────────

enum class GameMode(
    val subIndex: Int,
    val nameKr: String,
    val descKr: String,
    val winConditionKr: String,
    val defaultDifficulty: GameDifficulty = GameDifficulty.NORMAL
) {
    SPEED_REACTION(
        subIndex = 1,
        nameKr = "Speed Reaction",
        descKr = "LED가 켜지면 빠르게 흔드세요",
        winConditionKr = "제한 시간 내 5회 먼저 성공",
        defaultDifficulty = GameDifficulty.NORMAL
    ),
    TEMPO(
        subIndex = 2,
        nameKr = "Tempo",
        descKr = "LED 리듬에 맞춰 흔드세요",
        winConditionKr = "20초 내 5회 연속 성공",
        defaultDifficulty = GameDifficulty.NORMAL
    ),
    TEAM_BATTLE(
        subIndex = 3,
        nameKr = "Team Battle",
        descKr = "내 팀 색 신호에 맞춰 흔드세요",
        winConditionKr = "5라운드 팀 점수 합산 승리",
        defaultDifficulty = GameDifficulty.EASY
    );
}

// ─── Difficulty ───────────────────────────────────────────────────────────────

enum class GameDifficulty(val level: Int, val nameKr: String) {
    EASY(1, "쉬움"),
    NORMAL(2, "보통"),
    HARD(3, "어려움")
}

// ─── Game State ───────────────────────────────────────────────────────────────

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
}

// ─── Game Result Packet (per wand) ───────────────────────────────────────────

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

// ─── Aggregated Result Summary ────────────────────────────────────────────────

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
