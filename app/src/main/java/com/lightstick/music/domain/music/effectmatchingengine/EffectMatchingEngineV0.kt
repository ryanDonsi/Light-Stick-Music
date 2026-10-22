package com.lightstick.music.domain.music.effectmatchingengine

import com.lightstick.music.domain.music.genredetector.GenreDetector
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

/**
 * EffectMatchingEngineV0 — 단순 비트 ON 매칭. 비트마다 beatInBar(0-3) 기준 고정 4색을
 * 켠다. 팔레트/섹션 정보는 쓰지 않는다. 비트 감지기 테스트용.
 */
class EffectMatchingEngineV0 : EffectMatchingEngine {

    /** V0은 팔레트를 사용하지 않으므로 빈(고정) 팔레트 반환. */
    override fun buildPalette(seed: Int, musicStyle: GenreDetector.MusicStyle): EffectMatchingEngine.Palette {
        val white = LSColor(255, 255, 255)
        val black = LSColor(0, 0, 0)
        val trivial = EffectMatchingEngine.EffectColors(listOf(white), black)
        return EffectMatchingEngine.Palette(
            black = black, white = white,
            colorFamily = "NONE", musicStyle = musicStyle,
            onPulse = trivial, blink = trivial, chorus = trivial,
            breathVerse = trivial, breathBridgeBallad = trivial, breathDefault = trivial, freeze = trivial
        )
    }

    /** 단순 비트 ON 매칭: 각 비트마다 ON 이펙트 생성. */
    override fun buildFrames(
        palette: EffectMatchingEngine.Palette,
        sectionGroups: List<EffectMatchingEngine.SectionGroup>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int,
        fullEnv: List<Float>,
        hopMs: Long
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
