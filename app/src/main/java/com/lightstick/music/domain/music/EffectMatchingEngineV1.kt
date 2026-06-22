package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs

/**
 * EffectMatchingEngineV1 — V8 기반 고급 이펙트 매칭
 *
 * 이펙트 생성 규칙:
 * - V8(Vision 8) 이펙트 규칙 기반
 * - 섹션별로 다른 이펙트 엔진(FgEngine) 할당
 * - 팔레트 기반 색상 관리 (seed 기반 4색 생성)
 * - 음악 스타일 분석 (발라드/팝/댄스 등)
 * - 에너지 기반 섹션 분류
 *
 * 섹션별 엔진 할당:
 * - INTRO/OUTRO: BREATH (부드러운 호흡)
 * - VERSE: ON_PULSE or BREATH (음성 중심)
 * - CHORUS: ON_TRANSIT_ROTATE (회전 전환)
 * - CLIMAX: STROBE (급작스러운 변화)
 * - BUILD: ON_TRANSIT_ROTATE (상승감)
 * - BEAT: ON_PULSE or BLINK (비트 강조)
 * - BRIDGE: BREATH or ON_TRANSIT_ROTATE (시간 변화)
 * - BREAK: BREATH (휴식)
 * - END: OFF_TRANSIT (부드러운 종료)
 *
 * 특징:
 * - 복잡한 이펙트, 높은 시각미
 * - 느린 처리 (섹션 분석 + 복잡 계산)
 * - 메모리 중간 (섹션 정보 저장)
 * - 영상 재생/공연용 적합
 */
class EffectMatchingEngineV1 : EffectMatchingEngine {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
    }

    /**
     * 팔레트 생성 (V8 규칙)
     * seed 기반 HSV → RGB 변환으로 조화로운 4색 생성
     */
    override fun buildPalette(seed: Int): EffectMatchingEngine.Palette {
        val rawHue = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val baseHue = (((rawHue % 360) + 360) % 360).toFloat()

        val cMain = hsvToColor(baseHue, 1.00f, 1.00f)
        val cStep1 = hsvToColor(wrap360(baseHue + 60f), 1.00f, 1.00f)
        val cStep2 = hsvToColor(wrap360(baseHue - 60f), 0.85f, 0.95f)
        val cStep3 = hsvToColor(wrap360(baseHue - 120f), 1.00f, 1.00f)
        val cDeep = hsvToColor(baseHue, 1.00f, 0.48f)

        val black = LSColor(0, 0, 0)
        val white = LSColor(255, 255, 255)
        val colorGroup = listOf(cMain, cStep1, cStep2, cStep3)

        val cMainLuma = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain

        return EffectMatchingEngine.Palette(
            black = black, white = white,
            onPulseSets = listOf(
                EffectMatchingEngine.ColorSet(white, patternABg),
                EffectMatchingEngine.ColorSet(cMain, black)
            ),
            blinkSets = listOf(
                EffectMatchingEngine.ColorSet(cMain, black),
                EffectMatchingEngine.ColorSet(cStep1, black)
            ),
            strokeSets = listOf(EffectMatchingEngine.ColorSet(white, black)),
            breathSet = EffectMatchingEngine.ColorSet(white, patternABg),
            bridgeSets = listOf(
                EffectMatchingEngine.ColorSet(cStep2, black),
                EffectMatchingEngine.ColorSet(cMain, black)
            ),
            chorusBg = cDeep,
            colorGroup = colorGroup
        )
    }

    /**
     * 프레임 빌딩 (V8 규칙 적용)
     * 
     * TODO: V8 이펙트 생성 로직 구현
     * 현재는 skeleton으로 비트마다 ON 생성
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
        if (sectionGroups.isEmpty() || beatTimesMs.isEmpty()) {
            Log.w(TAG, "EffectMatchingEngineV1: empty input")
            return emptyList()
        }

        val frames = ArrayList<Pair<Long, ByteArray>>(beatTimesMs.size)

        for (beatMs in beatTimesMs) {
            if (beatMs >= 0 && beatMs < durationMs) {
                frames.add(beatMs to LSEffectPayload.Effects.on(
                    color = palette.white,
                    transit = 0
                ).toByteArray())
            }
        }

        if (finalOffMs >= 0 && finalOffMs < durationMs) {
            frames.add(finalOffMs to LSEffectPayload.Effects.off(transit = 2).toByteArray())
        }

        Log.d(TAG, "EffectMatchingEngineV1: generated ${frames.size} frames")
        return frames.sortedBy { it.first }
    }

    // =========================================================================
    // 유틸리티
    // =========================================================================

    private fun hsvToColor(h: Float, s: Float, v: Float): LSColor {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return LSColor(
            ((rf + m) * 255f).toInt().coerceIn(0, 255),
            ((gf + m) * 255f).toInt().coerceIn(0, 255),
            ((bf + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f
}
