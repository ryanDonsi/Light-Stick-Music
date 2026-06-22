package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs

/**
 * EffectMatchingEngineV0 — 단순 비트 ON 매칭
 *
 * 이펙트 생성 규칙:
 * - 각 비트마다 ON 이펙트 생성
 * - 색상은 downbeat 기준 beatInBar(0-3)에 따라 고정 4색 사용
 * - 팔레트 미사용 (고정 색상)
 * - 섹션 정보 미사용
 *
 * 특징:
 * - 매우 빠른 처리
 * - 단조로운 이펙트
 * - 메모리 효율적
 * - 비트 감지기 테스트용 적합
 *
 * 색상 규칙 (beatInBar 기반):
 *   0 → White (100% 밝기, 강박)
 *   1 → Purple (35% 밝기, 약박)
 *   2 → Yellow (100% 밝기, 중간박)
 *   3 → Cyan (35% 밝기, 약박)
 */
class EffectMatchingEngineV0 : EffectMatchingEngine {

    /**
     * 팔레트 생성
     * V0은 팔레트를 사용하지 않으므로 빈 팔레트 반환
     * (하위 호환성 유지)
     */
    override fun buildPalette(seed: Int): EffectMatchingEngine.Palette {
        val white = LSColor(255, 255, 255)
        val black = LSColor(0, 0, 0)
        val empty = EffectMatchingEngine.ColorSet(white, black)
        return EffectMatchingEngine.Palette(
            black = black, white = white,
            onPulseSets = listOf(empty),
            blinkSets = listOf(empty),
            strokeSets = listOf(empty),
            breathSet = empty,
            bridgeSets = listOf(empty),
            chorusBg = black,
            colorGroup = listOf(white, white, white, white)
        )
    }

    /**
     * 프레임 빌딩
     * 단순 비트 ON 매칭: 각 비트마다 ON 이펙트
     */
    override fun buildFrames(
        palette: EffectMatchingEngine.Palette,
        sectionGroups: List<EffectMatchingEngine.SectionGroup>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>(beatTimesMs.size)

        for (beatMs in beatTimesMs) {
            if (beatMs < 0 || beatMs >= durationMs) continue

            // beatInBar 계산: downbeat 기준
            val beatInBar = if (beatsPerBar > 0) {
                val steps = Math.round((beatMs - downbeatMs).toDouble() /
                    (if (beatTimesMs.size > 1) (beatTimesMs[1] - beatTimesMs[0]).toDouble() else 1000.0))
                (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
            } else 0

            // 색상 선택 (고정 4색)
            val color = when (beatInBar) {
                0 -> LSColor(255, 255, 255)      // White (강박)
                1 -> LSColor(255, 0, 255)        // Purple (약박)
                2 -> LSColor(255, 255, 0)        // Yellow (중간박)
                else -> LSColor(0, 255, 255)     // Cyan (약박)
            }

            // 밝기 (fade)
            val fade = when (beatInBar) {
                0, 2 -> 100  // 강박, 중간박
                else -> 35   // 약박
            }

            frames.add(beatMs to LSEffectPayload.Effects.on(
                color = color,
                transit = 0,
                fade = fade
            ).toByteArray())
        }

        // 음악 종료 위치에 OFF 추가
        if (finalOffMs < durationMs && finalOffMs >= 0) {
            frames.add(finalOffMs to LSEffectPayload.Effects.off(transit = 2).toByteArray())
        }

        return frames.sortedBy { it.first }
    }
}
