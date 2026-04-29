package com.lightstick.music.domain.usecase.game

import android.content.Context
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
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
    fun sendReady(context: Context, mode: GameMode, difficulty: GameDifficulty): Boolean =
        gameBleManager.sendReady(mode, difficulty)

    fun sendStop(context: Context): Boolean =
        gameBleManager.sendStop()

    fun sendClear(context: Context): Boolean =
        gameBleManager.sendClear()
}
