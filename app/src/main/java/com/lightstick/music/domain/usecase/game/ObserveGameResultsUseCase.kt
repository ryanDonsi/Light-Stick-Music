package com.lightstick.music.domain.usecase.game

import com.lightstick.music.domain.game.GameBleManager
import com.lightstick.music.domain.game.ParsedGameResult
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * 게임 결과 관찰 UseCase
 *
 * FF04 Notify로 수신된 게임 결과 패킷을 스트림으로 제공합니다.
 */
class ObserveGameResultsUseCase @Inject constructor(
    private val gameBleManager: GameBleManager
) {
    operator fun invoke(): SharedFlow<ParsedGameResult> = gameBleManager.gameResultFlow
}
