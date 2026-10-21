package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

/**
 * EffectMatchingEngineV1 - 섹션 타입 기반 이펙트 매칭 엔진
 * 섹션 타입별 이펙트 엔진 할당 (ON_PULSE / BREATH / ON_TRANSIT_ROTATE 등)
 */
class EffectMatchingEngineV1 : EffectMatchingEngine {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val ON_TRANSIT = 2
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT
        // OUTRO 페이드: 스텝 길이(ms)를 transit 값으로 변환하는 비율/범위.
        private const val OUTRO_TRANSIT_DIVISOR = 100L
        private const val OUTRO_TRANSIT_MIN = 2
        private const val OUTRO_TRANSIT_MAX = 60
        // OUTRO 페이드 한 스텝의 길이, 최대 스텝 수.
        private const val OUTRO_FADE_STEP_MS = 1_000L
        private const val OUTRO_FADE_MAX_STEPS = 8
    }

    data class ResolvedSection(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val engine: EffectMatchingEngine.FgEngine,
        val beatMs: Long, val beats: Int,
        val source: String, val change: EffectMatchingEngine.ChangeLevel,
        val energyScore: Float = 0f, val relScore: Float = 0f,
        val beatTimesMs: List<Long> = emptyList()
    )

    override fun buildPalette(
        seed: Int, musicStyle: MusicStyleClassifier.MusicStyle
    ): EffectMatchingEngine.Palette {
        val palette = PaletteCatalog.select(seed, musicStyle)
        Log.d(TAG, "[Palette] style=$musicStyle set=${palette.colorFamily}")
        return palette
    }

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
        if (sectionGroups.isEmpty()) return emptyList()

        // 섹션에 이펙트 엔진을 할당해 ResolvedSection으로 변환 (에너지 기반 엔진 할당)
        val globalBeatMs = if (beatTimesMs.size > 1)
            beatTimesMs[1] - beatTimesMs[0] else 500L

        val effectiveHopMs = if (hopMs > 0L) hopMs else 10L
        val resolvedSections = convertToResolvedSections(sectionGroups, globalBeatMs, isBalladMode, fullEnv, durationMs, effectiveHopMs)

        // 엔진 할당된 섹션으로부터 프레임 빌드
        return buildFramesFromSections(palette, resolvedSections, beatTimesMs, durationMs, isBalladMode, finalOffMs,
            downbeatMs, beatsPerBar, fullEnv, effectiveHopMs)
    }

    private fun convertToResolvedSections(
        groups: List<EffectMatchingEngine.SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<ResolvedSection> {
        if (groups.isEmpty()) return emptyList()

        val energies = groups.map { g -> computeGroupEnergy(g.startMs, g.endMs, fullEnv, durationMs, hopMs) }
        val lowTh    = percentile(energies, 0.35f)
        val highTh   = percentile(energies, 0.70f)
        val range    = (highTh - lowTh).coerceAtLeast(1e-6f)

        return groups.mapIndexed { i, g ->
            val energy   = energies[i]
            val beats    = g.annotatedBeats.size
            val relScore = ((energy - lowTh) / range).coerceIn(0f, 1f)

            val engine = SectionEngineCatalog.assign(g.type, isBalladMode = isBalladMode)

            val source = buildSourceName(g.type, beats)

            val change = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 20 ->
                    EffectMatchingEngine.ChangeLevel.STRONG
                beats < 8 -> EffectMatchingEngine.ChangeLevel.MEDIUM
                else      -> EffectMatchingEngine.ChangeLevel.STRONG
            }

            ResolvedSection(
                startMs     = g.startMs,      endMs       = g.endMs,
                type        = g.type,          engine      = engine,
                beatMs      = beatMs,          beats       = beats,
                source      = source,          change      = change,
                energyScore = energy,          relScore    = relScore,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }
    }

    private fun buildFramesFromSections(
        palette: EffectMatchingEngine.Palette,
        sections: List<ResolvedSection>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int,
        fullEnv: List<Float>,
        hopMs: Long
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

        // OUTRO 직전 섹션의 마지막 fg (OUTRO 페이드 시작색으로 사용).
        var lastFg: LSColor = palette.white

        for (section in sections) {
            lastRepeatKey = null

            val effectiveBeats = section.beatTimesMs

            if (section.engine == EffectMatchingEngine.FgEngine.OFF_TRANSIT) continue

            val enginePool  = SectionEngineCatalog.pool(section.type, isBalladMode)
            val barBlockLen = sectionBarBlockLen(section)
            val barMs = if (section.beatMs > 0L && beatsPerBar > 0) section.beatMs * beatsPerBar else 0L

            // VERSE(비발라드)는 bar 블록 해시 순환 대신, bar 에너지로 ON_PULSE/BLINK를 고른다.
            val verseBarEnergy: Map<Int, Float> =
                if (section.type == SectionDetector.SectionType.VERSE && enginePool.size > 1 && barMs > 0L) {
                    effectiveBeats.map { barIndexAt(it, downbeatMs, section.beatMs, beatsPerBar) }.distinct()
                        .associateWith { barIdx ->
                            val barStartMs = downbeatMs + barIdx.toLong() * barMs
                            computeGroupEnergy(barStartMs, barStartMs + barMs, fullEnv, durationMs, hopMs)
                        }
                } else emptyMap()
            // 상위 20%(0.80 percentile) 이상 에너지인 bar만 BLINK, 나머지는 ON_PULSE.
            val verseEnergyBlinkCutoff =
                if (verseBarEnergy.isNotEmpty()) percentile(verseBarEnergy.values.toList(), 0.80f) else 0f

            for ((beatIndex, t) in effectiveBeats.withIndex()) {
                val barIdx = barIndexAt(t, downbeatMs, section.beatMs, beatsPerBar)
                val beatEngine = when {
                    section.type == SectionDetector.SectionType.BRIDGE ->
                        SectionEngineCatalog.bridgePhaseEngine()
                    section.type == SectionDetector.SectionType.VERSE && verseBarEnergy.isNotEmpty() -> {
                        val energy = verseBarEnergy[barIdx] ?: 0f
                        if (energy >= verseEnergyBlinkCutoff) EffectMatchingEngine.FgEngine.BLINK
                        else EffectMatchingEngine.FgEngine.ON_PULSE
                    }
                    enginePool.size > 1 -> {
                        val blockIdx = Math.floorDiv(barIdx, barBlockLen)
                        enginePool[Math.floorMod(blockIdx, enginePool.size)]
                    }
                    else -> section.engine
                }

                val effectiveEngine = beatEngine

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, beatIndex, section.type, barIdx, isBalladMode)
                val bgNonNull = bg ?: LSColor(0, 0, 0)
                lastFg = fg

                val beatPeriod = when (effectiveEngine) {
                    EffectMatchingEngine.FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else            -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE  -> null
                    effectiveEngine == EffectMatchingEngine.FgEngine.ON_PULSE           -> null
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.VERSE -> 0
                    // 발라드 BRIDGE: SDK 랜덤딜레이로 기기별 파도 효과. 비발라드 BRIDGE: 결정적 beatPeriod.
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.BRIDGE && isBalladMode ->
                        msToBreathRandomDelay(section.beatMs)
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.BRIDGE -> beatPeriod
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH             -> msToBreathRandomDelay(section.beatMs)
                    else                                           -> null
                }
                // 발라드 BRIDGE만 SDK의 randomColor 플래그를 켠다.
                val beatRandomColor = if (effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                    section.type == SectionDetector.SectionType.BRIDGE && isBalladMode) 1 else 0
                val beatRotateTransit = if (effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0

                val skipOnPulseOdd = (beatEngine == EffectMatchingEngine.FgEngine.ON_PULSE && beatIndex % 2 != 0)

                val skipRepeat = if (skipOnPulseOdd) {
                    true
                } else if (effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
                    || effectiveEngine == EffectMatchingEngine.FgEngine.BREATH
                    || effectiveEngine == EffectMatchingEngine.FgEngine.FREEZE) {
                    val key = RepeatKey(effectiveEngine,
                        fg.r, fg.g, fg.b, bgNonNull.r, bgNonNull.g, bgNonNull.b,
                        beatPeriod ?: 0, beatRandomDelay ?: 0)
                    val dup = (key == lastRepeatKey); lastRepeatKey = key; dup
                } else {
                    lastRepeatKey = null; false
                }

                if (!skipRepeat) {
                    put(t, buildPayload(effectiveEngine, fg, bg, section.beatMs, beatPeriod,
                        beatRandomDelay, rotateTransit = beatRotateTransit, randomColor = beatRandomColor))
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
        val outroStartMs = sections.firstOrNull { it.type == SectionDetector.SectionType.OUTRO }?.startMs
        val outroLenMs = outroStartMs?.let { (finalOffMs - it).coerceAtLeast(0L) } ?: 0L

        // OUTRO 구간을 여러 스텝으로 나눠 lastFg를 점점 어둡게 만든다. 마지막 스텝은 실제 off.
        if (outroStartMs != null && outroLenMs > 0L) {
            val stepCount = (outroLenMs / OUTRO_FADE_STEP_MS).toInt().coerceIn(1, OUTRO_FADE_MAX_STEPS)
            val stepLenMs = outroLenMs / stepCount
            val stepTransit = (stepLenMs / OUTRO_TRANSIT_DIVISOR).toInt().coerceIn(OUTRO_TRANSIT_MIN, OUTRO_TRANSIT_MAX)
            Log.d(TAG, "EffectMatchingEngineV1 outro fade: outroStartMs=$outroStartMs finalOffMs=$finalOffMs " +
                "outroLenMs=$outroLenMs stepCount=$stepCount stepTransit=$stepTransit")
            for (i in 1..stepCount) {
                val tStep = outroStartMs + outroLenMs * i / stepCount
                if (i == stepCount) {
                    frameMap[tStep] = buildOffPayload(stepTransit)
                } else {
                    val fraction = 1f - i.toFloat() / stepCount
                    frameMap[tStep] = LSEffectPayload.Effects.on(color = scaleColor(lastFg, fraction), transit = stepTransit).toByteArray()
                }
            }
        } else {
            frameMap[finalOffMs] = buildOffPayload(OUTRO_TRANSIT_MIN)
        }

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun scaleColor(c: LSColor, factor: Float): LSColor {
        val f = factor.coerceIn(0f, 1f)
        return LSColor((c.r * f).toInt().coerceIn(0, 255), (c.g * f).toInt().coerceIn(0, 255), (c.b * f).toInt().coerceIn(0, 255))
    }

    private fun computeGroupEnergy(startMs: Long, endMs: Long, fullEnv: List<Float>, durationMs: Long, hopMs: Long): Float {
        if (fullEnv.isEmpty()) return 0f
        val startIdx = (startMs / hopMs).toInt().coerceIn(0, fullEnv.lastIndex)
        val endIdx   = (endMs   / hopMs).toInt().coerceAtMost(fullEnv.lastIndex)
        if (endIdx <= startIdx) return fullEnv.getOrElse(startIdx) { 0f }
        return fullEnv.subList(startIdx, endIdx + 1).average().toFloat()
    }

    /** 섹션마다 결정론적으로 2~5 사이의 bar 블록 길이를 고른다 (같은 곡이면 항상 같은 결과). */
    private fun sectionBarBlockLen(section: ResolvedSection): Int {
        val h = section.startMs * 2654435761L + section.type.ordinal * 97L
        return 2 + Math.floorMod(h, 4L).toInt()
    }

    /** downbeatMs를 기준으로 한 절대 bar 인덱스 (beatInBar와 달리 마디 내 위치가 아닌 마디 자체의 순번). */
    private fun barIndexAt(tMs: Long, downbeatMs: Long, globalBeatMs: Long, beatsPerBar: Int): Int {
        if (globalBeatMs <= 0L || beatsPerBar <= 0) return 0
        val steps = Math.round((tMs - downbeatMs).toDouble() / globalBeatMs.toDouble())
        return Math.floorDiv(steps, beatsPerBar.toLong()).toInt()
    }

    private fun buildSourceName(type: SectionDetector.SectionType, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.INTRO  -> "intro-breath"
            SectionDetector.SectionType.OUTRO  -> "outro-off"
            SectionDetector.SectionType.BREAK  -> "break-freeze"
            SectionDetector.SectionType.CLIMAX -> "climax-rotate"
            SectionDetector.SectionType.VERSE  -> "verse-on-pulse"
            SectionDetector.SectionType.CHORUS -> "chorus-rotate"
            SectionDetector.SectionType.BRIDGE -> "bridge-breath"
            SectionDetector.SectionType.END    -> "end-off"
        }

    /** EffectColors.fgCycle을 인덱스로 순환시켜 (fg, bg) 쌍을 뽑는다. */
    private fun pick(ec: EffectMatchingEngine.EffectColors, idx: Int): Pair<LSColor, LSColor?> =
        ec.fgCycle[Math.floorMod(idx, ec.fgCycle.size)] to ec.bg

    private fun colorsForEngine(
        palette: EffectMatchingEngine.Palette, engine: EffectMatchingEngine.FgEngine,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE,
        barIdx: Int = 0, isBalladMode: Boolean = false
    ): Pair<LSColor, LSColor?> = when (engine) {
        // ON_PULSE: VERSE(비발라드).
        EffectMatchingEngine.FgEngine.ON_PULSE -> pick(palette.onPulse, barIdx)
        // BLINK: VERSE(비발라드) 고에너지 bar.
        EffectMatchingEngine.FgEngine.BLINK -> pick(palette.blink, barIdx)
        // ON_TRANSIT_ROTATE: CHORUS.
        EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE -> pick(palette.chorus, beatIndex)
        // BREATH: 발라드 BRIDGE/VERSE/그 외로 구분.
        EffectMatchingEngine.FgEngine.BREATH -> when {
            sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> pick(palette.breathBridgeBallad, 0)
            sectionType == SectionDetector.SectionType.VERSE -> pick(palette.breathVerse, barIdx)
            else -> pick(palette.breathDefault, 0)
        }
        // FREEZE: BREAK.
        EffectMatchingEngine.FgEngine.FREEZE -> pick(palette.freeze, 0)
        EffectMatchingEngine.FgEngine.OFF_TRANSIT -> palette.black to palette.black
    }

    private fun buildPayload(
        engine: EffectMatchingEngine.FgEngine, fg: LSColor, bg: LSColor?, beatMs: Long,
        period: Int? = null, randomDelay: Int? = null, rotateTransit: Int = 0, randomColor: Int = 0
    ): ByteArray {
        val bgColor = bg ?: LSColor(0, 0, 0)
        return when (engine) {
            EffectMatchingEngine.FgEngine.ON_PULSE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            EffectMatchingEngine.FgEngine.BLINK ->
                LSEffectPayload.Effects.blink(period = period ?: msToBlinkPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay ?: 0).toByteArray()
            EffectMatchingEngine.FgEngine.BREATH ->
                LSEffectPayload.Effects.breath(period = period ?: msToBreathPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomColor = randomColor,
                    randomDelay = randomDelay ?: msToBreathRandomDelay(beatMs)).toByteArray()
            EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE ->
                LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
            // FREEZE: 애니메이션 없이 한 번 켜서 그대로 유지한다(transit=0, 이후 재전송 없음).
            EffectMatchingEngine.FgEngine.FREEZE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            EffectMatchingEngine.FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(transit: Int = ON_TRANSIT): ByteArray = LSEffectPayload.Effects.off(transit = transit).toByteArray()

    // period는 10ms 단위, FG+BG를 합친 한 사이클 전체 길이.
    private fun msToBlinkPeriod(beatMs: Long)        = (beatMs / 100L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long)       = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long)  = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }
}
