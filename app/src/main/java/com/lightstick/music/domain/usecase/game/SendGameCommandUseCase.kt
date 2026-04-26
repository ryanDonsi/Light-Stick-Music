package com.lightstick.music.domain.usecase.game

import android.content.Context
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.domain.game.GameBleManager
import com.lightstick.music.domain.game.GameProtocol
import javax.inject.Inject

/**
 * 게임 명령 전송 UseCase
 *
 * FF03 Characteristic에 READY / STOP / CLEAR 페이로드를 Write합니다.
 */
class SendGameCommandUseCase @Inject constructor(
    private val gameBleManager: GameBleManager
) {
    fun sendReady(context: Context, mode: GameMode, difficulty: GameDifficulty): Boolean {
        val payload = GameProtocol.buildReadyPayload(mode, difficulty)
        return gameBleManager.sendCommand(context, payload)
    }

    fun sendStop(context: Context): Boolean {
        val payload = GameProtocol.buildStopPayload()
        return gameBleManager.sendCommand(context, payload)
    }

    fun sendClear(context: Context): Boolean {
        val payload = GameProtocol.buildClearPayload()
        return gameBleManager.sendCommand(context, payload)
    }
}
