package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * EffectMatchingEngineV2 - V3의 이펙트 매칭 룰
 * 섹션 타입별 이펙트 엔진 할당 (ON_PULSE / STROBE / BREATH / ON_TRANSIT_ROTATE 등)
 */
class EffectMatchingEngineV2 : EffectMatchingEngine {

    companion object {
        private const val ON_TRANSIT = 2
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT
    }

    data class V8Section(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val engine: EffectMatchingEngine.FgEngine,
        val beatMs: Long, val beats: Int,
        val source: String, val change: EffectMatchingEngine.ChangeLevel,
        val energyScore: Float = 0f, val relScore: Float = 0f,
        val beatTimesMs: List<Long> = emptyList()
    )

    override fun buildPalette(seed: Int): EffectMatchingEngine.Palette {
        val rawHue  = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val baseHue = (((rawHue % 360) + 360) % 360).toFloat()
        val cMain  = hsvToColor(baseHue,                 1.00f, 1.00f)
        val cStep1 = hsvToColor(wrap360(baseHue +  30f), 1.00f, 1.00f)
        val cStep2 = hsvToColor(wrap360(baseHue -  30f), 1.00f, 1.00f)
        val cStep3 = hsvToColor(wrap360(baseHue +  60f), 1.00f, 1.00f)
        val cStep4 = hsvToColor(wrap360(baseHue -  60f), 1.00f, 1.00f)
        val cStep5 = hsvToColor(wrap360(baseHue + 120f), 1.00f, 1.00f)
        val cStep6 = hsvToColor(wrap360(baseHue - 120f), 1.00f, 1.00f)
        val cDeep  = hsvToColor(baseHue,                 1.00f, 0.48f)
        val black  = LSColor(0, 0, 0)
        val white = LSColor(255, 255, 255)
        val colorGroup = listOf(cMain, cStep3, cStep4, cStep5)
        val cMainLuma  = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain
        return EffectMatchingEngine.Palette(
            black       = black, white = white,
            onPulseSets = listOf(EffectMatchingEngine.ColorSet(cMain, patternABg), EffectMatchingEngine.ColorSet(cStep1, black)),
            blinkSets   = listOf(EffectMatchingEngine.ColorSet(cStep2, black), EffectMatchingEngine.ColorSet(cStep3, black)),
            strokeSets  = listOf(EffectMatchingEngine.ColorSet(white, black)),
            breathSet   = EffectMatchingEngine.ColorSet(cStep4, patternABg),
            bridgeSets  = listOf(EffectMatchingEngine.ColorSet(cStep6, black), EffectMatchingEngine.ColorSet(cMain, black)),
            chorusBg    = cDeep, colorGroup = colorGroup
        )
    }

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
        if (sectionGroups.isEmpty()) return emptyList()

        // 섹션을 V8 섹션으로 변환 (에너지 기반 엔진 할당)
        val globalBeatMs = if (beatTimesMs.size > 1)
            beatTimesMs[1] - beatTimesMs[0] else 500L

        val v8Sections = convertToV8Sections(sectionGroups, globalBeatMs, isBalladMode, emptyList(), durationMs, 10L)

        // V8 섹션으로부터 프레임 빌드
        return buildFramesFromSections(palette, v8Sections, beatTimesMs, durationMs, isBalladMode, finalOffMs, downbeatMs, beatsPerBar)
    }

    private fun convertToV8Sections(
        groups: List<EffectMatchingEngine.SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<V8Section> {
        if (groups.isEmpty()) return emptyList()

        val energies = groups.map { g -> computeGroupEnergy(g.startMs, g.endMs, fullEnv, durationMs, hopMs) }
        val lowTh    = percentile(energies, 0.35f)
        val highTh   = percentile(energies, 0.70f)
        val range    = (highTh - lowTh).coerceAtLeast(1e-6f)

        return groups.mapIndexed { i, g ->
            val energy   = energies[i]
            val beats    = g.annotatedBeats.size
            val relScore = ((energy - lowTh) / range).coerceIn(0f, 1f)

            val normalizedType = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 6 ->
                    SectionDetector.SectionType.VERSE
                else -> g.type
            }

            val engine = assignFgEngine(normalizedType, relScore, beats, globalBeatMs = beatMs,
                isBalladMode = isBalladMode)

            val source = buildSourceName(normalizedType, engine, beats)

            val change = when {
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 ->
                    EffectMatchingEngine.ChangeLevel.STRONG
                beats < 8 -> EffectMatchingEngine.ChangeLevel.MEDIUM
                else      -> EffectMatchingEngine.ChangeLevel.STRONG
            }

            V8Section(
                startMs     = g.startMs,      endMs       = g.endMs,
                type        = normalizedType,  engine      = engine,
                beatMs      = beatMs,          beats       = beats,
                source      = source,          change      = change,
                energyScore = energy,          relScore    = relScore,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }
    }

    private fun buildFramesFromSections(
        palette: EffectMatchingEngine.Palette,
        sections: List<V8Section>,
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

        data class RepeatKey(
            val engine: EffectMatchingEngine.FgEngine,
            val fgR: Int, val fgG: Int, val fgB: Int,
            val bgR: Int, val bgG: Int, val bgB: Int,
            val period: Int, val randomDelay: Int
        )
        var lastRepeatKey: RepeatKey? = null

        val sameTypeCountMap = mutableMapOf<SectionDetector.SectionType, Int>()

        for ((index, section) in sections.withIndex()) {
            val sameTypeIdx = sameTypeCountMap.getOrDefault(section.type, 0)
            sameTypeCountMap[section.type] = sameTypeIdx + 1
            lastRepeatKey = null

            val effectiveBeats = section.beatTimesMs

            if (section.engine == EffectMatchingEngine.FgEngine.OFF_TRANSIT) continue

            for ((beatIndex, t) in effectiveBeats.withIndex()) {
                val beatEngine = if (section.type == SectionDetector.SectionType.BRIDGE)
                    bridgePhaseEngine(beatIndex, effectiveBeats.size, section.beatMs, section.relScore, isBalladMode)
                else section.engine

                val effectiveEngine = beatEngine

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, sameTypeIdx, beatIndex, section.type)
                val bgNonNull = bg ?: LSColor(0, 0, 0)

                val beatPeriod = when (effectiveEngine) {
                    EffectMatchingEngine.FgEngine.STROBE -> 1
                    EffectMatchingEngine.FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else            -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == EffectMatchingEngine.FgEngine.STROBE             -> 1
                    effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE  -> null
                    effectiveEngine == EffectMatchingEngine.FgEngine.ON_PULSE           -> null
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.VERSE -> 0
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.BRIDGE -> beatPeriod
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH             -> msToBreathRandomDelay(section.beatMs)
                    else                                           -> null
                }
                val beatRotateTransit = if (effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0

                val skipOnPulseOdd = (beatEngine == EffectMatchingEngine.FgEngine.ON_PULSE && beatIndex % 2 != 0)

                val skipRepeat = if (skipOnPulseOdd) {
                    true
                } else if (effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
                    || effectiveEngine == EffectMatchingEngine.FgEngine.STROBE
                    || effectiveEngine == EffectMatchingEngine.FgEngine.BREATH) {
                    val key = RepeatKey(effectiveEngine,
                        fg.r, fg.g, fg.b, bgNonNull.r, bgNonNull.g, bgNonNull.b,
                        beatPeriod ?: 0, beatRandomDelay ?: 0)
                    val dup = (key == lastRepeatKey); lastRepeatKey = key; dup
                } else {
                    lastRepeatKey = null; false
                }

                if (!skipRepeat) {
                    put(t, buildPayload(effectiveEngine, fg, bg, section.beatMs, beatPeriod,
                        beatRandomDelay, rotateTransit = beatRotateTransit))
                }

                if (beatEngine == EffectMatchingEngine.FgEngine.ON_PULSE && !skipOnPulseOdd) {
                    val holdMs = minOf(ON_PULSE_ACCENT_HOLD_MS * 2L, section.beatMs * 44L / 100L).coerceAtLeast(60L)
                    val offT   = minOf(section.endMs - 1L, t + holdMs)
                    if (offT > t)
                        put(offT, LSEffectPayload.Effects.off(transit = 3).toByteArray())
                }
            }
        }

        frameMap.keys.filter { it >= finalOffMs }.forEach { frameMap.remove(it) }
        frameMap[finalOffMs] = buildOffPayload()

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun computeGroupEnergy(startMs: Long, endMs: Long, fullEnv: List<Float>, durationMs: Long, hopMs: Long): Float {
        if (fullEnv.isEmpty()) return 0f
        val startIdx = (startMs / hopMs).toInt().coerceIn(0, fullEnv.lastIndex)
        val endIdx   = (endMs   / hopMs).toInt().coerceAtMost(fullEnv.lastIndex)
        if (endIdx <= startIdx) return fullEnv.getOrElse(startIdx) { 0f }
        return fullEnv.subList(startIdx, endIdx + 1).average().toFloat()
    }

    private fun assignFgEngine(
        type: SectionDetector.SectionType,
        rel: Float, beats: Int, globalBeatMs: Long,
        isBalladMode: Boolean
    ): EffectMatchingEngine.FgEngine = when (type) {
        SectionDetector.SectionType.INTRO  -> EffectMatchingEngine.FgEngine.BREATH
        SectionDetector.SectionType.OUTRO  -> EffectMatchingEngine.FgEngine.OFF_TRANSIT
        SectionDetector.SectionType.BREAK  -> EffectMatchingEngine.FgEngine.BREATH

        SectionDetector.SectionType.CLIMAX -> when {
            globalBeatMs <= 300L -> EffectMatchingEngine.FgEngine.STROBE
            rel >= 0.60f         -> EffectMatchingEngine.FgEngine.STROBE
            else                 -> EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
        }

        SectionDetector.SectionType.VERSE  -> if (isBalladMode) EffectMatchingEngine.FgEngine.BREATH else EffectMatchingEngine.FgEngine.ON_PULSE
        SectionDetector.SectionType.CHORUS -> EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
        SectionDetector.SectionType.BRIDGE -> EffectMatchingEngine.FgEngine.BREATH
        SectionDetector.SectionType.END    -> EffectMatchingEngine.FgEngine.OFF_TRANSIT

        // INST: 무보컬 반주 — VOCAL과 동일하게 에너지 기반으로 판단(보컬만 없을 뿐 편성은 비슷)
        SectionDetector.SectionType.INST   -> when {
            isBalladMode         -> EffectMatchingEngine.FgEngine.BREATH
            rel >= 0.55f         -> EffectMatchingEngine.FgEngine.ON_PULSE
            else                 -> EffectMatchingEngine.FgEngine.BREATH
        }
        // SOLO: 리드 악기가 도드라지는 하이라이트 구간 — CHORUS와 같은 강조 이펙트
        SectionDetector.SectionType.SOLO   -> EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
    }

    private fun buildSourceName(type: SectionDetector.SectionType, engine: EffectMatchingEngine.FgEngine, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.INTRO  -> "intro-breath"
            SectionDetector.SectionType.OUTRO  -> "outro-off"
            SectionDetector.SectionType.BREAK  -> "break-breath"
            SectionDetector.SectionType.CLIMAX -> if (engine == EffectMatchingEngine.FgEngine.STROBE) "climax-strobe" else "climax-rotate"
            SectionDetector.SectionType.VERSE  -> "verse-on-pulse"
            SectionDetector.SectionType.CHORUS -> "chorus-rotate"
            SectionDetector.SectionType.BRIDGE -> "bridge-breath"
            SectionDetector.SectionType.END    -> "end-off"
            SectionDetector.SectionType.INST   -> if (engine == EffectMatchingEngine.FgEngine.BREATH) "inst-breath" else "inst-pulse"
            SectionDetector.SectionType.SOLO   -> "solo-rotate"
        }

    private fun bridgePhaseEngine(
        beatIndex: Int, totalBeats: Int, beatMs: Long, relScore: Float, isBalladMode: Boolean
    ): EffectMatchingEngine.FgEngine {
        if (isBalladMode || relScore < 0.1f) return EffectMatchingEngine.FgEngine.BREATH
        if (totalBeats <= 0) return EffectMatchingEngine.FgEngine.STROBE
        val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)
        return when {
            totalBeats < 8  -> EffectMatchingEngine.FgEngine.STROBE
            totalBeats < 16 -> {
                val phase = beatIndex.toFloat() / totalBeats
                if (phase < strobeEntry) EffectMatchingEngine.FgEngine.BREATH else EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
            }
            else -> {
                val phase      = beatIndex.toFloat() / totalBeats
                val rotateEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
                if (phase < rotateEntry) EffectMatchingEngine.FgEngine.BREATH else EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
            }
        }
    }

    private fun colorsForEngine(
        palette: EffectMatchingEngine.Palette, engine: EffectMatchingEngine.FgEngine, sectionIndex: Int,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE
    ): Pair<LSColor, LSColor?> {
        val isPatternA = (sectionIndex % 2 == 0)
        val effectiveColors: List<LSColor> = when (sectionType) {
            SectionDetector.SectionType.CHORUS -> listOf(palette.white) + palette.colorGroup.take(3)
            SectionDetector.SectionType.VERSE  -> palette.colorGroup.take(3)
            else -> palette.colorGroup
        }
        val groupColor = effectiveColors[beatIndex % effectiveColors.size]
        return when (engine) {
            EffectMatchingEngine.FgEngine.ON_PULSE -> {
                val set = if (isPatternA) palette.onPulseSets[0] else palette.onPulseSets[1]
                set.fg to set.bg
            }
            EffectMatchingEngine.FgEngine.BLINK -> {
                val set = palette.blinkSets[beatIndex % palette.blinkSets.size]
                set.fg to set.bg
            }
            EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE ->
                if (sectionType == SectionDetector.SectionType.BRIDGE) {
                    val set = palette.bridgeSets[beatIndex % palette.bridgeSets.size]
                    set.fg to set.bg
                } else {
                    val bg = if (sectionType == SectionDetector.SectionType.CHORUS) palette.chorusBg else palette.black
                    groupColor to bg
                }
            EffectMatchingEngine.FgEngine.STROBE -> {
                val set = palette.strokeSets[beatIndex % palette.strokeSets.size]
                set.fg to set.bg
            }
            EffectMatchingEngine.FgEngine.BREATH -> palette.breathSet.fg to palette.breathSet.bg
            // OFF_TRANSIT 섹션은 buildFramesFromSections에서 이미 continue로 걸러져 여기 도달하지 않음
            EffectMatchingEngine.FgEngine.OFF_TRANSIT -> palette.black to palette.black
        }
    }

    private fun buildPayload(
        engine: EffectMatchingEngine.FgEngine, fg: LSColor, bg: LSColor?, beatMs: Long,
        period: Int? = null, randomDelay: Int? = null, rotateTransit: Int = 0
    ): ByteArray {
        val bgColor = bg ?: LSColor(0, 0, 0)
        return when (engine) {
            EffectMatchingEngine.FgEngine.ON_PULSE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            EffectMatchingEngine.FgEngine.BLINK ->
                LSEffectPayload.Effects.blink(period = period ?: msToBlinkPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay ?: 0).toByteArray()
            EffectMatchingEngine.FgEngine.STROBE ->
                LSEffectPayload.Effects.strobe(period = period ?: msToStrobePeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay ?: 0).toByteArray()
            EffectMatchingEngine.FgEngine.BREATH ->
                LSEffectPayload.Effects.breath(period = period ?: msToBreathPeriod(beatMs),
                    color = fg, backgroundColor = bgColor,
                    randomDelay = randomDelay ?: msToBreathRandomDelay(beatMs)).toByteArray()
            EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE ->
                LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
            EffectMatchingEngine.FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(): ByteArray = LSEffectPayload.Effects.off(transit = ON_TRANSIT).toByteArray()

    private fun msToBlinkPeriod(beatMs: Long)        = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToStrobePeriod(beatMs: Long)       = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long)       = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long)  = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

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
