package com.lightstick.music.domain.game

import android.Manifest
import androidx.annotation.RequiresPermission
import com.lightstick.device.Device
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode

/**
 * [Device]에 게임 모드 커맨드 API를 추가하는 확장 함수 모음.
 *
 * SDK [Device]는 게임 모드를 직접 알지 못하므로, 앱 도메인 타입([GameMode], [GameDifficulty])을
 * [GameProtocol] 패킷으로 변환한 뒤 [Device.sendRawGameCommand]로 전달한다.
 *
 * 수신 구독은 [Device.subscribeCharacteristic] + [GameProtocol.CHAR_GAME_RESULT_UUID]를
 * 사용하며, [GameProtocol.parseResultPacket]으로 패킷을 파싱한다.
 */

// ─── TX ──────────────────────────────────────────────────────────────────────

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun Device.sendGameReady(mode: GameMode, difficulty: GameDifficulty): Boolean =
    sendRawGameCommand(GameProtocol.buildReadyPayload(mode, difficulty))

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun Device.sendGameStop(): Boolean =
    sendRawGameCommand(GameProtocol.buildStopPayload())

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun Device.sendGameClear(): Boolean =
    sendRawGameCommand(GameProtocol.buildClearPayload())

// ─── RX ──────────────────────────────────────────────────────────────────────

/**
 * FF04 게임 결과 Notify 구독.
 *
 * @param onResult   파싱 성공한 [ParsedGameResult] 수신 시 호출.
 * @param onSubscribed CCCD 쓰기 결과 콜백 (기본값: 무시).
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun Device.subscribeGameResults(
    onResult: (ParsedGameResult) -> Unit,
    onSubscribed: (Result<Unit>) -> Unit = {}
): Boolean = subscribeCharacteristic(
    serviceUuid  = GameProtocol.SERVICE_UUID,
    charUuid     = GameProtocol.CHAR_GAME_RESULT_UUID,
    listener     = { bytes ->
        val parsed = GameProtocol.parseResultPacket(bytes)
        if (parsed != null) onResult(parsed)
    },
    onSubscribed = onSubscribed
)

/**
 * FF04 게임 결과 Notify 구독 해제.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun Device.unsubscribeGameResults(
    onResult: (Result<Unit>) -> Unit = {}
): Boolean = unsubscribeCharacteristic(
    serviceUuid = GameProtocol.SERVICE_UUID,
    charUuid    = GameProtocol.CHAR_GAME_RESULT_UUID,
    onResult    = onResult
)
