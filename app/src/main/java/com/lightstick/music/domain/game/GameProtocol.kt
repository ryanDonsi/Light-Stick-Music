package com.lightstick.music.domain.game

import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.data.model.WandResult
import java.util.UUID

/**
 * 게임 BLE 프로토콜 상수 및 패킷 빌더
 *
 * 모든 패킷은 20바이트 리틀 엔디안 구조:
 *   [0-1]  effectIndex (uint16) = 0x0005 고정
 *   [2-3]  subIndex    (uint16) = 게임 모드 번호
 *   [4-5]  cmdIndex    (uint16) = 명령 종류
 *   [6-7]  level       (uint16) = 난이도
 *   [8-9]  option      (uint16) = 팀 설정
 *   [10-19] reserved   = 0x00
 */
object GameProtocol {

    // ─── Service & Characteristic UUIDs ──────────────────────────────────────

    // FE01 = 메인 서비스 (FF01~FF05 characteristic 포함)
    val SERVICE_UUID: UUID = UUID.fromString("0001FE01-0000-1000-8000-00805F9800C4")

    /**
     * FF01 — 게임 명령 Write (앱 → 중계기/응원봉)
     * 이펙트 전송과 동일한 characteristic; effectIndex=0x0005 로 게임 명령 구분.
     * FF03은 기기 펌웨어에 존재하지 않음 (SDK AAR 분석 확인).
     */
    val CHAR_GAME_CMD_UUID: UUID = UUID.fromString("0001FF01-0000-1000-8000-00805F9800C4")

    /** FF04 — 게임 결과 Notify (중계기/응원봉 → 앱) */
    val CHAR_GAME_RESULT_UUID: UUID = UUID.fromString("0001FF04-0000-1000-8000-00805F9800C4")

    /** CCCD descriptor (0x2902) — Notify 활성화용 */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ─── effectIndex 고정값 ───────────────────────────────────────────────────

    private const val EFFECT_INDEX: Int = 0x0005

    // ─── cmdIndex 값 ─────────────────────────────────────────────────────────

    const val CMD_READY: Int = 1
    const val CMD_STOP: Int = 3
    const val CMD_CLEAR: Int = 4
    const val CMD_RESULT: Int = 5

    // ─── option 값 ───────────────────────────────────────────────────────────

    /** Mode 3 팀 랜덤 배정 */
    const val OPTION_TEAM_RANDOM: Int = 0xFF

    // ─── Packet Builders ─────────────────────────────────────────────────────

    fun buildReadyPayload(mode: GameMode, difficulty: GameDifficulty): ByteArray {
        val option = if (mode == GameMode.TEAM_BATTLE) OPTION_TEAM_RANDOM else 0
        return buildPayload(
            subIndex = mode.subIndex,
            cmdIndex = CMD_READY,
            level = difficulty.level,
            option = option
        )
    }

    fun buildStopPayload(): ByteArray = buildPayload(
        subIndex = 0,
        cmdIndex = CMD_STOP,
        level = 0,
        option = 0
    )

    fun buildClearPayload(): ByteArray = buildPayload(
        subIndex = 0,
        cmdIndex = CMD_CLEAR,
        level = 0,
        option = 0
    )

    private fun buildPayload(
        subIndex: Int,
        cmdIndex: Int,
        level: Int,
        option: Int
    ): ByteArray {
        val buf = ByteArray(20)
        putU16(buf, 0, EFFECT_INDEX)
        putU16(buf, 2, subIndex)
        putU16(buf, 4, cmdIndex)
        putU16(buf, 6, level)
        putU16(buf, 8, option)
        // bytes 10–19 remain 0x00 (reserved)
        return buf
    }

    // ─── Result Packet Parser ─────────────────────────────────────────────────

    /**
     * FF04 Notify 20바이트 결과 패킷 파싱
     *
     * 결과 패킷 구조:
     *   [0-1]  effect_index = 0x0005
     *   [2-3]  sub_index    = 게임 모드
     *   [4-5]  cmd_index    = 0x0005 (RESULT)
     *   [6-7]  red_score
     *   [8-9]  blue_score
     *   [10-11] total_count
     *   [12-13] reserved
     *   [14-15] wand_id
     *   [16-19] reserved
     */
    fun parseResultPacket(data: ByteArray): ParsedGameResult? {
        if (data.size < 16) return null

        val effectIndex = getU16(data, 0)
        if (effectIndex != EFFECT_INDEX) return null

        val cmdIndex = getU16(data, 4)
        if (cmdIndex != CMD_RESULT) return null

        val subIndex = getU16(data, 2)
        val redScore = getU16(data, 6)
        val blueScore = getU16(data, 8)
        val totalCount = getU16(data, 10)
        val wandId = if (data.size >= 16) getU16(data, 14) else 0

        return ParsedGameResult(
            subIndex = subIndex,
            redScore = redScore,
            blueScore = blueScore,
            totalCount = totalCount,
            wandId = wandId
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun getU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

data class ParsedGameResult(
    val subIndex: Int,
    val redScore: Int,
    val blueScore: Int,
    val totalCount: Int,
    val wandId: Int
) {
    fun toWandResult(): WandResult = WandResult(
        wandId = wandId,
        redScore = redScore,
        blueScore = blueScore
    )
}
