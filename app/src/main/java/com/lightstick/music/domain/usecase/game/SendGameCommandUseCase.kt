package com.lightstick.music.domain.usecase.game

import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.data.model.Team
import com.lightstick.music.domain.game.GameBleManager
import javax.inject.Inject

/**
 * 게임 명령 전송 UseCase
 *
 * SDK [com.lightstick.device.Device] 기반의 [GameBleManager]를 통해
 * READY / STOP / CLEAR 커맨드를 전송합니다.
 */
class SendGameCommandUseCase @Inject constructor(
    private val gameBleManager: GameBleManager
) {
    fun sendReady(mode: GameMode, difficulty: GameDifficulty): Boolean =
        gameBleManager.startGame(mode, difficulty)

    fun sendWinner(mode: GameMode, winnerWandId: Int): Boolean =
        gameBleManager.sendWinner(mode, winnerWandId)

    fun sendStop(): Boolean =
        gameBleManager.stopGame()

    fun sendClear(): Boolean =
        gameBleManager.clearGame()

    /** Mode 4 팀 배정 시작(cmd=7) */
    fun sendTeamAssign(team: Team): Boolean =
        gameBleManager.startTeamAssign(team)

    /** Mode 4 팀 배정 종료(cmd=9) */
    fun sendTeamAssignEnd(team: Team): Boolean =
        gameBleManager.endTeamAssign(team)

    /** Mode 4 게임 시작(cmd=1, level=라운드 수, option=측정시간ms) */
    fun sendTeamReady(rounds: Int, difficulty: GameDifficulty): Boolean =
        gameBleManager.startTeamGame(rounds, difficulty)
}
