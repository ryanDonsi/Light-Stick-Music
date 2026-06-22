package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs

/**
 * EffectMatchingEngineV1 - 단순 beat-ON 패턴
 * 모든 섹션에 1/4박 White-C1-C2-C3 패턴 적용
 */
class EffectMatchingEngineV1 : EffectMatchingEngine {

    companion object {
        private const val ON_TRANSIT = 2
    }

    override fun convertToSections(
        groups: List<EffectMatchingEngine.SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<EffectMatchingEngine.Section> {
        if (groups.isEmpty()) return emptyList()

        return groups.map { g ->
            val beats = g.annotatedBeats.size

            val normalizedType = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 6 ->
                    SectionDetector.SectionType.VERSE
                else -> g.type
            }

            val change = when {
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 ->
                    EffectMatchingEngine.ChangeLevel.STRONG
                beats < 8 -> EffectMatchingEngine.ChangeLevel.MEDIUM
                else      -> EffectMatchingEngine.ChangeLevel.STRONG
            }

            EffectMatchingEngine.Section(
                startMs     = g.startMs,      endMs       = g.endMs,
                type        = normalizedType,
                engine      = if (normalizedType == SectionDetector.SectionType.END ||
                                  normalizedType == SectionDetector.SectionType.OUTRO)
                              EffectMatchingEngine.FgEngine.OFF_TRANSIT
                              else EffectMatchingEngine.FgEngine.ON_PULSE,
                beatMs      = beatMs,          beats       = beats,
                source      = "beat-on",       change      = change,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }
    }

    override fun buildFramesFromSections(
        palette: EffectMatchingEngine.Palette,
        sections: List<EffectMatchingEngine.Section>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        for ((index, section) in sections.withIndex()) {
            if (section.engine == EffectMatchingEngine.FgEngine.OFF_TRANSIT) continue

            val effectiveBeats = section.beatTimesMs

            for (t in effectiveBeats) {
                val beatInBar = beatInBar(t, downbeatMs, globalBeatMs = section.beatMs, beatsPerBar)
                val (color, fade) = beatSectionColorAndFade(beatInBar, palette)
                put(t, LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
            }
        }

        frameMap.keys.filter { it >= finalOffMs }.forEach { frameMap.remove(it) }
        frameMap[finalOffMs] = buildOffPayload()

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    override fun buildPalette(seed: Int): EffectMatchingEngine.Palette {
        val rawHue  = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val baseHue = (((rawHue % 360) + 360) % 360).toFloat()
        val cMain  = hsvToColor(baseHue,                 1.00f, 1.00f)
        val cStep1 = hsvToColor(wrap360(baseHue +  60f), 1.00f, 1.00f)
        val cStep2 = hsvToColor(wrap360(baseHue -  60f), 0.85f, 0.95f)
        val cStep3 = hsvToColor(wrap360(baseHue - 120f), 1.00f, 1.00f)
        val cDeep  = hsvToColor(baseHue,                 1.00f, 0.48f)
        val black  = LSColor(0, 0, 0); val white = LSColor(255, 255, 255)
        val colorGroup = listOf(cMain, cStep1, cStep2, cStep3)
        val cMainLuma  = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain
        return EffectMatchingEngine.Palette(
            black       = black, white = white,
            onPulseSets = listOf(EffectMatchingEngine.ColorSet(white, patternABg), EffectMatchingEngine.ColorSet(cMain, black)),
            blinkSets   = listOf(EffectMatchingEngine.ColorSet(cMain, black), EffectMatchingEngine.ColorSet(cStep1, black)),
            strokeSets  = listOf(EffectMatchingEngine.ColorSet(white, black)),
            breathSet   = EffectMatchingEngine.ColorSet(white, patternABg),
            bridgeSets  = listOf(EffectMatchingEngine.ColorSet(cStep2, black), EffectMatchingEngine.ColorSet(cMain, black)),
            chorusBg    = cDeep, colorGroup = colorGroup
        )
    }

    private fun beatInBar(tMs: Long, downbeatMs: Long, globalBeatMs: Long, beatsPerBar: Int): Int {
        if (globalBeatMs <= 0L || beatsPerBar <= 0) return 0
        val steps = Math.round((tMs - downbeatMs).toDouble() / globalBeatMs.toDouble())
        return (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
    }

    private fun beatSectionColorAndFade(beatInBar: Int, palette: EffectMatchingEngine.Palette): Pair<LSColor, Int> {
        if (beatInBar == 0) return palette.white to 100
        val paletteColor = palette.colorGroup.getOrElse(beatInBar - 1) { palette.colorGroup.first() }
        val fade = when (beatInBar) { 2 -> 100; else -> 35 }
        return paletteColor to fade
    }

    private fun buildOffPayload(): ByteArray = LSEffectPayload.Effects.off(transit = ON_TRANSIT).toByteArray()

    private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f

    private fun hsvToColor(h: Float, s: Float, v: Float): LSColor {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s; val x = c * (1f - abs((hh / 60f) % 2f - 1f)); val m = v - c
        val (rf, gf, bf) = when {
            hh < 60f  -> Triple(c, x, 0f); hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x); hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c); else      -> Triple(c, 0f, x)
        }
        return LSColor(
            ((rf + m) * 255f).toInt().coerceIn(0, 255),
            ((gf + m) * 255f).toInt().coerceIn(0, 255),
            ((bf + m) * 255f).toInt().coerceIn(0, 255)
        )
    }
}
