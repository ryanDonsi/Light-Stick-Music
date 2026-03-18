package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class AutoTimelineGeneratorBeat_v7 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION = 8
        private const val HOP_MS = 50L

        private const val MIN_BEAT_MS = 250L
        private const val MAX_BEAT_MS = 900L
        private const val DEFAULT_BEAT_MS = 450L

        private const val WINDOW_MS = 2_000L
        private const val STRIDE_MS = 1_000L
        private const val MIN_SECTION_MS = 4_000L

        private const val ON_TRANSIT = 2
        private const val COLOR_HOLD_MS = 5_000L

        private const val LOW_ENERGY_TH = 0.18f
        private const val HIGH_ENERGY_TH = 0.52f
        private const val HIGH_DENSITY_TH = 0.48f
        private const val STRONG_PERIODICITY_TH = 0.40f

        private const val SECTION_STRONG_CHANGE_TH = 0.24f
        private const val SECTION_MEDIUM_CHANGE_TH = 0.14f

        private const val BEAT_PEAK_PERCENTILE = 0.80f
        private const val MIN_PEAK_DISTANCE_MS = 180L
    }

    private enum class EnvMode {
        LOW, MID, FULL
    }

    private enum class EngineMode {
        ON_PULSE,
        BLINK,
        STROBE,
        BREATH,
        ON_TRANSIT,
        OFF_TRANSIT
    }

    enum class SectionType {
        INTRO,
        VERSE,
        CHORUS,
        BRIDGE,
        END
    }

    private enum class ChangeStrength {
        NONE,
        MEDIUM,
        STRONG
    }

    data class Palette(
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor,
        val c5: LSColor,
        val white: LSColor,
        val black: LSColor,
        val size: Int
    )

    private data class FeatureWindow(
        val startMs: Long,
        val endMs: Long,
        val energy: Float,
        val lowRatio: Float,
        val onsetDensity: Float,
        val periodicity: Float,
        val beatMsHint: Long,
        val sectionType: SectionType,
        val changeStrength: ChangeStrength
    )

    private data class SectionInfo(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val changeStrength: ChangeStrength,
        val beatTimesMs: LongArray,
        val beatMs: Long,
        val beatConfidence: Float,
        val source: String,
        val engineMode: EngineMode
    )

    private data class EnginePlan(
        val mode: EngineMode,
        val period: Int = 0,
        val transit: Int = ON_TRANSIT,
        val randomDelay: Int = 0,
        val sustain: Boolean = false
    )

    private data class HoldColors(
        val fg: LSColor,
        val bg: LSColor
    )

    private data class TimelineState(
        val mode: EngineMode? = null,
        val period: Int = -1,
        val transit: Int = -1,
        val fg: LSColor? = null,
        val bg: LSColor? = null,
        val sectionType: SectionType? = null
    )

    fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int = 4
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "v8 generate() start file=$musicPath musicId=$musicId paletteSize=$paletteSize")

        val pSize = paletteSize.coerceIn(3, 5)
        val palette = buildPalette(musicId, pSize)

        val lowEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.LOW)
        val midEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.MID)
        val fullEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.FULL)

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "v8 env empty -> return empty")
            return emptyList()
        }

        val durationMs = fullEnv.size.toLong() * HOP_MS
        val globalBeat = detectGlobalBeat(lowEnv, midEnv, fullEnv)
        val windows = buildFeatureWindows(lowEnv, midEnv, fullEnv, globalBeat.beatMs, durationMs)
        val sections = buildSectionsFromWindows(windows, durationMs)
        val resolvedSections = detectSectionBeats(
            sections = sections,
            lowEnv = lowEnv,
            midEnv = midEnv,
            fullEnv = fullEnv,
            globalBeatMs = globalBeat.beatMs,
            durationMs = durationMs
        )

        val frames = buildTimeline(
            sections = resolvedSections,
            palette = palette,
            musicId = musicId
        )

        Log.d(TAG, "v8 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // ------------------------------------------------------------
    // Global beat
    // ------------------------------------------------------------

    private data class GlobalBeatResult(
        val beatMs: Long,
        val confidence: Float
    )

    private fun detectGlobalBeat(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>
    ): GlobalBeatResult {
        val novelty = computeNovelty(
            lowEnv = lowEnv,
            midEnv = midEnv,
            fullEnv = fullEnv
        )
        val beatMs = estimateBeatMsByAutocorr(
            novelty = novelty,
            hopMs = HOP_MS,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )

        val periodicity = estimatePeriodicityStrength(
            novelty = novelty,
            beatMs = beatMs,
            hopMs = HOP_MS
        )

        Log.d(TAG, "global beat beatMs=$beatMs confidence=$periodicity")
        return GlobalBeatResult(
            beatMs = beatMs,
            confidence = periodicity
        )
    }

    // ------------------------------------------------------------
    // Windows / Section analysis
    // ------------------------------------------------------------

    private fun buildFeatureWindows(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        globalBeatMs: Long,
        durationMs: Long
    ): List<FeatureWindow> {
        val windows = ArrayList<FeatureWindow>()
        val windowFrames = max(1, (WINDOW_MS / HOP_MS).toInt())
        val strideFrames = max(1, (STRIDE_MS / HOP_MS).toInt())

        val novelty = computeNovelty(lowEnv, midEnv, fullEnv)

        var startIdx = 0
        var prev: FeatureWindow? = null

        while (startIdx < fullEnv.size) {
            val endIdx = min(fullEnv.size, startIdx + windowFrames)
            if (endIdx <= startIdx) break

            val startMs = startIdx.toLong() * HOP_MS
            val endMs = min(durationMs, endIdx.toLong() * HOP_MS)

            val lowSlice = lowEnv.subList(startIdx, endIdx)
            val midSlice = midEnv.subList(startIdx, endIdx)
            val fullSlice = fullEnv.subList(startIdx, endIdx)
            val novSlice = novelty.copyOfRange(startIdx, endIdx)

            val energy = average(fullSlice)
            val lowRatio = average(lowSlice) / max(0.0001f, average(fullSlice))
            val onsetDensity = densityAbove(novSlice, 0.12f)
            val beatMsHint = estimateBeatMsByAutocorr(
                novelty = novSlice,
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = MAX_BEAT_MS
            )
            val periodicity = estimatePeriodicityStrength(novSlice, beatMsHint, HOP_MS)
            val type = classifySectionType(
                startMs = startMs,
                endMs = endMs,
                durationMs = durationMs,
                energy = energy,
                lowRatio = lowRatio,
                onsetDensity = onsetDensity,
                periodicity = periodicity
            )

            val dummy = FeatureWindow(
                startMs = startMs,
                endMs = endMs,
                energy = energy,
                lowRatio = lowRatio,
                onsetDensity = onsetDensity,
                periodicity = periodicity,
                beatMsHint = beatMsHint,
                sectionType = type,
                changeStrength = ChangeStrength.NONE
            )

            val change = estimateChangeStrength(prev, dummy)
            val win = dummy.copy(changeStrength = change)
            windows += win
            prev = win
            startIdx += strideFrames
        }

        return windows
    }

    private fun classifySectionType(
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        energy: Float,
        lowRatio: Float,
        onsetDensity: Float,
        periodicity: Float
    ): SectionType {
        val introLimit = min(18_000L, (durationMs * 0.12f).toLong())
        val endLimit = max(8_000L, (durationMs * 0.08f).toLong())

        if (startMs < introLimit) {
            return if (energy < LOW_ENERGY_TH && periodicity < STRONG_PERIODICITY_TH) {
                SectionType.INTRO
            } else {
                SectionType.VERSE
            }
        }

        if (endMs >= durationMs - endLimit) {
            return if (energy < LOW_ENERGY_TH || onsetDensity < 0.14f) {
                SectionType.END
            } else {
                SectionType.VERSE
            }
        }

        return when {
            energy < LOW_ENERGY_TH && onsetDensity < 0.12f -> SectionType.BRIDGE
            energy >= HIGH_ENERGY_TH && onsetDensity >= HIGH_DENSITY_TH -> SectionType.CHORUS
            lowRatio < 0.88f && onsetDensity < 0.18f -> SectionType.BRIDGE
            else -> SectionType.VERSE
        }
    }

    private fun estimateChangeStrength(prev: FeatureWindow?, cur: FeatureWindow): ChangeStrength {
        if (prev == null) return ChangeStrength.STRONG
        val score =
            abs(cur.energy - prev.energy) * 0.35f +
                    abs(cur.onsetDensity - prev.onsetDensity) * 0.35f +
                    abs(cur.lowRatio - prev.lowRatio) * 0.10f +
                    abs(cur.periodicity - prev.periodicity) * 0.10f +
                    if (cur.sectionType != prev.sectionType) 0.20f else 0f

        return when {
            score >= SECTION_STRONG_CHANGE_TH -> ChangeStrength.STRONG
            score >= SECTION_MEDIUM_CHANGE_TH -> ChangeStrength.MEDIUM
            else -> ChangeStrength.NONE
        }
    }

    private fun buildSectionsFromWindows(
        windows: List<FeatureWindow>,
        durationMs: Long
    ): List<FeatureWindow> {
        if (windows.isEmpty()) return emptyList()

        val merged = ArrayList<FeatureWindow>()
        var cur = windows.first()

        for (i in 1 until windows.size) {
            val next = windows[i]
            val shouldSplit =
                next.changeStrength == ChangeStrength.STRONG ||
                        next.sectionType != cur.sectionType

            if (shouldSplit) {
                merged += cur.copy(endMs = next.startMs)
                cur = next.copy(startMs = next.startMs)
            } else {
                cur = cur.copy(
                    endMs = next.endMs,
                    energy = (cur.energy + next.energy) * 0.5f,
                    lowRatio = (cur.lowRatio + next.lowRatio) * 0.5f,
                    onsetDensity = (cur.onsetDensity + next.onsetDensity) * 0.5f,
                    periodicity = (cur.periodicity + next.periodicity) * 0.5f,
                    beatMsHint = normalizeBeatMsAgainstGlobal(cur.beatMsHint, next.beatMsHint)
                )
            }
        }
        merged += cur.copy(endMs = durationMs)

        return normalizeSections(merged, durationMs)
    }

    private fun normalizeSections(
        sections: List<FeatureWindow>,
        durationMs: Long
    ): List<FeatureWindow> {
        if (sections.isEmpty()) return emptyList()

        val sorted = sections.sortedBy { it.startMs }
        val nonOverlap = ArrayList<FeatureWindow>()

        for (s in sorted) {
            val fixedStart = if (nonOverlap.isEmpty()) 0L else max(nonOverlap.last().endMs, s.startMs)
            val fixedEnd = min(durationMs, max(fixedStart + 1L, s.endMs))
            if (fixedEnd <= fixedStart) continue

            val fixed = s.copy(
                startMs = fixedStart,
                endMs = fixedEnd
            )

            if (nonOverlap.isNotEmpty()) {
                val prev = nonOverlap.last()
                if (fixed.endMs - fixed.startMs < MIN_SECTION_MS &&
                    prev.sectionType == fixed.sectionType
                ) {
                    nonOverlap[nonOverlap.lastIndex] = prev.copy(
                        endMs = fixed.endMs,
                        energy = (prev.energy + fixed.energy) * 0.5f,
                        lowRatio = (prev.lowRatio + fixed.lowRatio) * 0.5f,
                        onsetDensity = (prev.onsetDensity + fixed.onsetDensity) * 0.5f,
                        periodicity = (prev.periodicity + fixed.periodicity) * 0.5f,
                        beatMsHint = normalizeBeatMsAgainstGlobal(prev.beatMsHint, fixed.beatMsHint)
                    )
                    continue
                }
            }

            nonOverlap += fixed
        }

        if (nonOverlap.isNotEmpty()) {
            val last = nonOverlap.last()
            if (last.endMs < durationMs) {
                nonOverlap[nonOverlap.lastIndex] = last.copy(endMs = durationMs)
            }
        }

        return nonOverlap
    }

    // ------------------------------------------------------------
    // Section beat detect
    // ------------------------------------------------------------

    private fun detectSectionBeats(
        sections: List<FeatureWindow>,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        globalBeatMs: Long,
        durationMs: Long
    ): List<SectionInfo> {
        val out = ArrayList<SectionInfo>()

        for ((idx, s) in sections.withIndex()) {
            val startIdx = (s.startMs / HOP_MS).toInt().coerceIn(0, fullEnv.lastIndex)
            val endIdx = (s.endMs / HOP_MS).toInt().coerceIn(startIdx + 1, fullEnv.size)

            val lowSlice = lowEnv.subList(startIdx, endIdx)
            val midSlice = midEnv.subList(startIdx, endIdx)
            val fullSlice = fullEnv.subList(startIdx, endIdx)

            val novelty = computeNovelty(lowSlice, midSlice, fullSlice)
            val localBeatTimes = pickBeatsFromNovelty(
                novelty = novelty,
                hopMs = HOP_MS,
                sectionStartMs = s.startMs,
                sectionEndMs = s.endMs
            )

            val localBeatMsRaw = estimateBeatIntervalMs(localBeatTimes)
            val localBeatMs = normalizeBeatMsAgainstGlobal(globalBeatMs, localBeatMsRaw)
            val confidence = estimatePeriodicityStrength(novelty, localBeatMs, HOP_MS)

            val source: String
            val beatTimes: LongArray
            val finalBeatMs: Long

            if (localBeatTimes.size >= 3 && confidence >= 0.22f) {
                beatTimes = localBeatTimes
                finalBeatMs = localBeatMs
                source = "local"
            } else {
                finalBeatMs = globalBeatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
                beatTimes = buildGridBeatsAligned(
                    sectionStartMs = s.startMs,
                    sectionEndMs = s.endMs,
                    beatMs = finalBeatMs,
                    novelty = novelty,
                    noveltyStartMs = s.startMs
                )
                source = when (s.sectionType) {
                    SectionType.BRIDGE -> "sparse-global-breath"
                    else -> "sparse-global-blink"
                }
            }

            val engineMode = chooseSectionStartEngine(
                sectionType = s.sectionType,
                beatMs = finalBeatMs,
                beatConfidence = confidence,
                source = source,
                changeStrength = s.changeStrength
            )

            Log.d(
                TAG,
                "section beat idx=$idx ${s.startMs}~${s.endMs} type=${s.sectionType} " +
                        "beats=${beatTimes.size} beatMs=$finalBeatMs source=$source engine=$engineMode change=${s.changeStrength}"
            )

            out += SectionInfo(
                index = idx,
                startMs = s.startMs,
                endMs = s.endMs,
                type = s.sectionType,
                changeStrength = s.changeStrength,
                beatTimesMs = beatTimes,
                beatMs = finalBeatMs,
                beatConfidence = confidence,
                source = source,
                engineMode = engineMode
            )
        }

        return out
    }

    private fun chooseSectionStartEngine(
        sectionType: SectionType,
        beatMs: Long,
        beatConfidence: Float,
        source: String,
        changeStrength: ChangeStrength
    ): EngineMode {
        if (sectionType == SectionType.END) return EngineMode.OFF_TRANSIT
        if (sectionType == SectionType.BRIDGE) return EngineMode.BREATH
        if (sectionType == SectionType.INTRO) {
            return if (beatConfidence >= 0.45f) EngineMode.BLINK else EngineMode.ON_TRANSIT
        }

        if (changeStrength != ChangeStrength.STRONG) {
            return EngineMode.ON_PULSE
        }

        return when (sectionType) {
            SectionType.CHORUS -> {
                when {
                    beatMs <= 340L -> EngineMode.STROBE
                    source.contains("sparse", ignoreCase = true) -> EngineMode.BLINK
                    else -> EngineMode.BLINK
                }
            }
            else -> EngineMode.ON_PULSE
        }
    }

    // ------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------

    private fun buildTimeline(
        sections: List<SectionInfo>,
        palette: Palette,
        musicId: Int
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>()
        var state = TimelineState()
        val usedTimestamps = HashSet<Long>()

        for (section in sections) {
            val hold = colorsForSection(musicId, palette, section.startMs, section.type)
            val startPlan = planSectionStartEngine(section, hold)

            if (startPlan.mode != EngineMode.ON_PULSE) {
                val shouldSend = shouldSendSustain(
                    last = state,
                    next = startPlan,
                    fg = hold.fg,
                    bg = hold.bg,
                    sectionType = section.type,
                    forceByChange = true
                )

                if (shouldSend && usedTimestamps.add(section.startMs)) {
                    frames += section.startMs to buildEnginePayload(
                        plan = startPlan,
                        fg = hold.fg,
                        bg = hold.bg
                    )

                    logSectionStart(
                        t = section.startMs,
                        sectionType = section.type,
                        mode = startPlan.mode,
                        fg = hold.fg,
                        bg = hold.bg,
                        period = startPlan.period,
                        transit = startPlan.transit,
                        randomDelay = startPlan.randomDelay,
                        sustain = startPlan.sustain
                    )

                    state = TimelineState(
                        mode = startPlan.mode,
                        period = startPlan.period,
                        transit = startPlan.transit,
                        fg = hold.fg,
                        bg = hold.bg,
                        sectionType = section.type
                    )
                }
            }

            val unitMs = (section.beatMs / 10L).coerceAtLeast(1L)
            val fgMs = unitMs * 3L

            for (beat in section.beatTimesMs) {
                if (beat < section.startMs || beat >= section.endMs) continue

                val pulse = pickPulseHoldColors(musicId, palette, beat, section.type)
                val pulsePlan = EnginePlan(
                    mode = EngineMode.ON_PULSE,
                    transit = ON_TRANSIT
                )

                if (usedTimestamps.add(beat)) {
                    frames += beat to buildEnginePayload(
                        plan = pulsePlan,
                        fg = pulse.fg,
                        bg = pulse.bg
                    )

                    logBeat(
                        t = beat,
                        sectionType = section.type,
                        mode = EngineMode.ON_PULSE,
                        fg = pulse.fg,
                        bg = pulse.bg,
                        period = 0,
                        transit = ON_TRANSIT,
                        randomDelay = 0,
                        unitMs = unitMs,
                        fgMs = fgMs,
                        sustain = false
                    )
                }

                val restoreT = beat + fgMs
                if (restoreT < section.endMs && usedTimestamps.add(restoreT)) {
                    frames += restoreT to LSEffectPayload.Effects.on(
                        color = pulse.bg,
                        transit = ON_TRANSIT
                    ).toByteArray()

                    logBgRestore(
                        t = restoreT,
                        sectionType = section.type,
                        bg = pulse.bg,
                        transit = ON_TRANSIT
                    )
                }
            }

            Log.d(
                TAG,
                "timeline section idx=${section.index} ${section.startMs}~${section.endMs} " +
                        "section=${section.type} rule=${describeRule(section)} beats=${section.beatTimesMs.size} " +
                        "beatMs=${section.beatMs} source=${section.source}"
            )
        }

        return frames.sortedBy { it.first }
    }

    private fun planSectionStartEngine(
        section: SectionInfo,
        hold: HoldColors
    ): EnginePlan {
        return when (section.type) {
            SectionType.INTRO -> {
                if (section.beatConfidence >= 0.45f) {
                    EnginePlan(
                        mode = EngineMode.BLINK,
                        period = msToBlinkPeriod(section.beatMs),
                        sustain = true
                    )
                } else {
                    EnginePlan(
                        mode = EngineMode.ON_TRANSIT,
                        transit = ON_TRANSIT,
                        sustain = true
                    )
                }
            }

            SectionType.BRIDGE -> {
                EnginePlan(
                    mode = EngineMode.BREATH,
                    period = msToBreathPeriod(section.beatMs),
                    randomDelay = randomDelayFromBeat(section.beatMs),
                    sustain = true
                )
            }

            SectionType.CHORUS -> {
                when (section.engineMode) {
                    EngineMode.STROBE -> EnginePlan(
                        mode = EngineMode.STROBE,
                        period = msToStrobePeriod(section.beatMs),
                        sustain = true
                    )
                    EngineMode.BLINK -> EnginePlan(
                        mode = EngineMode.BLINK,
                        period = msToBlinkPeriod(section.beatMs),
                        sustain = true
                    )
                    else -> EnginePlan(mode = EngineMode.ON_PULSE)
                }
            }

            SectionType.END -> {
                EnginePlan(
                    mode = EngineMode.OFF_TRANSIT,
                    transit = ON_TRANSIT,
                    sustain = true
                )
            }

            SectionType.VERSE -> {
                if (section.source.contains("breath", ignoreCase = true) &&
                    section.beatConfidence < 0.20f
                ) {
                    EnginePlan(
                        mode = EngineMode.BREATH,
                        period = msToBreathPeriod(section.beatMs),
                        randomDelay = randomDelayFromBeat(section.beatMs),
                        sustain = true
                    )
                } else {
                    EnginePlan(mode = EngineMode.ON_PULSE)
                }
            }
        }
    }

    private fun shouldSendSustain(
        last: TimelineState,
        next: EnginePlan,
        fg: LSColor,
        bg: LSColor,
        sectionType: SectionType,
        forceByChange: Boolean
    ): Boolean {
        if (forceByChange) {
            if (last.mode == next.mode &&
                last.period == next.period &&
                last.transit == next.transit &&
                last.fg == fg &&
                last.bg == bg &&
                last.sectionType == sectionType
            ) {
                return false
            }
            return true
        }

        return !(last.mode == next.mode &&
                last.period == next.period &&
                last.transit == next.transit &&
                last.fg == fg &&
                last.bg == bg &&
                last.sectionType == sectionType)
    }

    private fun buildEnginePayload(
        plan: EnginePlan,
        fg: LSColor,
        bg: LSColor
    ): ByteArray {
        return when (plan.mode) {
            EngineMode.ON_PULSE,
            EngineMode.ON_TRANSIT -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = plan.transit
                ).toByteArray()
            }

            EngineMode.BLINK -> {
                LSEffectPayload.Effects.blink(
                    period = plan.period.coerceAtLeast(1),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            EngineMode.STROBE -> {
                LSEffectPayload.Effects.strobe(
                    period = plan.period.coerceAtLeast(1),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            EngineMode.BREATH -> {
                LSEffectPayload.Effects.breath(
                    period = plan.period.coerceAtLeast(1),
                    color = fg,
                    backgroundColor = bg,
                    randomDelay = plan.randomDelay.coerceIn(0, 10)
                ).toByteArray()
            }

            EngineMode.OFF_TRANSIT -> {
                LSEffectPayload.Effects.off(
                    transit = plan.transit
                ).toByteArray()
            }
        }
    }

    // ------------------------------------------------------------
    // Logs
    // ------------------------------------------------------------

    private fun logBeat(
        t: Long,
        sectionType: SectionType,
        mode: EngineMode,
        fg: LSColor,
        bg: LSColor,
        period: Int,
        transit: Int,
        randomDelay: Int,
        unitMs: Long,
        fgMs: Long,
        sustain: Boolean
    ) {
        val extra = buildString {
            when (mode) {
                EngineMode.ON_PULSE -> {
                    append(" transit=$transit")
                    append(" unitMs=$unitMs fgMs=$fgMs")
                }
                EngineMode.BLINK,
                EngineMode.STROBE -> {
                    append(" period=$period")
                    append(" sustain=$sustain")
                }
                EngineMode.BREATH -> {
                    append(" period=$period")
                    append(" randomDelay=$randomDelay")
                    append(" sustain=$sustain")
                }
                EngineMode.ON_TRANSIT,
                EngineMode.OFF_TRANSIT -> {
                    append(" transit=$transit")
                    append(" sustain=$sustain")
                }
            }
        }

        Log.d(
            TAG,
            "timeline add t=${t}ms section=$sectionType type=BEAT mode=$mode " +
                    "fg=${colorToString(fg)} bg=${colorToString(bg)}$extra"
        )
    }

    private fun logSectionStart(
        t: Long,
        sectionType: SectionType,
        mode: EngineMode,
        fg: LSColor,
        bg: LSColor,
        period: Int,
        transit: Int,
        randomDelay: Int,
        sustain: Boolean
    ) {
        val extra = buildString {
            when (mode) {
                EngineMode.BLINK,
                EngineMode.STROBE -> append(" period=$period")
                EngineMode.BREATH -> {
                    append(" period=$period")
                    append(" randomDelay=$randomDelay")
                }
                EngineMode.ON_PULSE,
                EngineMode.ON_TRANSIT,
                EngineMode.OFF_TRANSIT -> append(" transit=$transit")
            }
            append(" sustain=$sustain")
        }

        Log.d(
            TAG,
            "timeline add t=${t}ms section=$sectionType type=SECTION_START mode=$mode " +
                    "fg=${colorToString(fg)} bg=${colorToString(bg)}$extra"
        )
    }

    private fun logBgRestore(
        t: Long,
        sectionType: SectionType,
        bg: LSColor,
        transit: Int
    ) {
        Log.d(
            TAG,
            "timeline add t=${t}ms section=$sectionType type=BEAT_BG " +
                    "restore bg=${colorToString(bg)} transit=$transit"
        )
    }

    private fun colorToString(c: LSColor): String = "(${c.r},${c.g},${c.b})"

    private fun describeRule(section: SectionInfo): String {
        return when (section.type) {
            SectionType.INTRO -> "INTRO -> ON_TRANSIT / BLINK"
            SectionType.VERSE -> "VERSE -> ON_PULSE base"
            SectionType.CHORUS -> "CHORUS -> ON_PULSE base + BLINK/STROBE start"
            SectionType.BRIDGE -> "BRIDGE -> BREATH start + ON_PULSE if beats"
            SectionType.END -> "END -> OFF_TRANSIT"
        }
    }

    // ------------------------------------------------------------
    // Beat helpers
    // ------------------------------------------------------------

    private fun computeNovelty(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>
    ): FloatArray {
        val n = FloatArray(fullEnv.size)
        for (i in 1 until fullEnv.size) {
            val dLow = max(0f, lowEnv[i] - lowEnv[i - 1])
            val dMid = max(0f, midEnv[i] - midEnv[i - 1])
            val dFull = max(0f, fullEnv[i] - fullEnv[i - 1])
            n[i] = dLow * 0.45f + dMid * 0.35f + dFull * 0.20f
        }
        normalize01InPlace(n)
        smoothInPlace(n, 2)
        return n
    }

    private fun estimateBeatMsByAutocorr(
        novelty: FloatArray,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Long {
        if (novelty.size < 8) return DEFAULT_BEAT_MS

        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, min((maxBeatMs / hopMs).toInt(), novelty.size - 1))

        var bestLag = (DEFAULT_BEAT_MS / hopMs).toInt().coerceIn(minLag, maxLag)
        var bestScore = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var s = 0.0
            var i = lag
            while (i < novelty.size) {
                s += novelty[i] * novelty[i - lag]
                i++
            }
            if (s > bestScore) {
                bestScore = s
                bestLag = lag
            }
        }

        return (bestLag.toLong() * hopMs).coerceIn(minBeatMs, maxBeatMs)
    }

    private fun estimatePeriodicityStrength(
        novelty: FloatArray,
        beatMs: Long,
        hopMs: Long
    ): Float {
        if (novelty.isEmpty()) return 0f
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag >= novelty.size) return 0f

        var ac = 0f
        var raw = 0f
        for (i in lag until novelty.size) {
            ac += novelty[i] * novelty[i - lag]
        }
        for (v in novelty) raw += v * v

        return if (raw <= 1e-6f) 0f else (ac / raw).coerceIn(0f, 1f)
    }

    private fun pickBeatsFromNovelty(
        novelty: FloatArray,
        hopMs: Long,
        sectionStartMs: Long,
        sectionEndMs: Long
    ): LongArray {
        if (novelty.size < 5) return LongArray(0)

        val sorted = novelty.toList().sorted()
        val thIndex = (sorted.size * BEAT_PEAK_PERCENTILE).toInt().coerceIn(0, sorted.lastIndex)
        val th = sorted[thIndex]

        val beats = ArrayList<Long>()
        var lastBeat = Long.MIN_VALUE

        for (i in 2 until novelty.size - 2) {
            val v = novelty[i]
            if (v < th) continue

            val isPeak = v >= novelty[i - 1] &&
                    v >= novelty[i + 1] &&
                    v >= novelty[i - 2] &&
                    v >= novelty[i + 2]

            if (!isPeak) continue

            val t = sectionStartMs + i.toLong() * hopMs
            if (t < sectionStartMs || t >= sectionEndMs) continue
            if (t - lastBeat < MIN_PEAK_DISTANCE_MS) continue

            beats += t
            lastBeat = t
        }

        return beats.toLongArray()
    }

    private fun estimateBeatIntervalMs(beatTimes: LongArray): Long {
        if (beatTimes.size < 2) return DEFAULT_BEAT_MS
        val diffs = ArrayList<Long>()

        for (i in 1 until beatTimes.size) {
            val d = beatTimes[i] - beatTimes[i - 1]
            if (d >= MIN_PEAK_DISTANCE_MS) diffs += d
        }

        if (diffs.isEmpty()) return DEFAULT_BEAT_MS
        diffs.sort()
        val mid = diffs.size / 2
        return if (diffs.size % 2 == 1) diffs[mid] else (diffs[mid - 1] + diffs[mid]) / 2L
    }

    private fun normalizeBeatMsAgainstGlobal(
        globalBeatMs: Long,
        rawBeatMs: Long
    ): Long {
        var beatMs = rawBeatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val g = globalBeatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        val ratio = beatMs.toFloat() / g.toFloat()
        beatMs = when {
            ratio in 0.45f..0.65f -> (beatMs * 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            ratio in 1.70f..2.20f -> (beatMs / 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            else -> beatMs
        }

        return beatMs
    }

    private fun buildGridBeatsAligned(
        sectionStartMs: Long,
        sectionEndMs: Long,
        beatMs: Long,
        novelty: FloatArray,
        noveltyStartMs: Long
    ): LongArray {
        val offset = estimatePhaseOffsetMs(novelty, HOP_MS, beatMs)
        var first = noveltyStartMs + offset

        while (first > sectionStartMs) {
            first -= beatMs
        }
        while (first + beatMs <= sectionStartMs) {
            first += beatMs
        }

        val out = ArrayList<Long>()
        var t = first
        while (t < sectionEndMs) {
            if (t >= sectionStartMs) out += t
            t += beatMs
        }
        return out.toLongArray()
    }

    private fun estimatePhaseOffsetMs(
        novelty: FloatArray,
        hopMs: Long,
        beatMs: Long
    ): Long {
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag <= 1 || novelty.isEmpty()) return 0L

        var bestOffset = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (offset in 0 until lag) {
            var s = 0.0
            var i = offset
            while (i < novelty.size) {
                s += novelty[i]
                i += lag
            }
            if (s > bestScore) {
                bestScore = s
                bestOffset = offset
            }
        }

        return bestOffset.toLong() * hopMs
    }

    // ------------------------------------------------------------
    // Palette / colors
    // ------------------------------------------------------------

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        val rnd = Random(seed)

        val c1 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c2 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c3 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c4 = hsvToColor(rnd.nextFloat() * 360f, 0.75f, 0.9f)
        val c5 = hsvToColor(rnd.nextFloat() * 360f, 0.70f, 0.95f)

        return Palette(
            c1 = c1,
            c2 = c2,
            c3 = c3,
            c4 = c4,
            c5 = c5,
            white = LSColor(255, 255, 255),
            black = LSColor(0, 0, 0),
            size = paletteSize
        )
    }

    private fun colorsForSection(
        musicId: Int,
        palette: Palette,
        tMs: Long,
        sectionType: SectionType
    ): HoldColors {
        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + seg * 97 + sectionType.ordinal * 37)

        val fg = when (sectionType) {
            SectionType.CHORUS -> listOf(palette.white, palette.c1, palette.c2)[rnd.nextInt(3)]
            SectionType.BRIDGE -> listOf(palette.white, palette.c3)[rnd.nextInt(2)]
            SectionType.END -> palette.black
            else -> listOf(palette.c1, palette.c2, palette.c3, palette.white)[rnd.nextInt(4)]
        }

        val bg = when (sectionType) {
            SectionType.CHORUS -> listOf(palette.c4, palette.black)[rnd.nextInt(2)]
            else -> palette.black
        }

        return HoldColors(fg = fg, bg = bg)
    }

    private fun pickPulseHoldColors(
        musicId: Int,
        palette: Palette,
        tMs: Long,
        sectionType: SectionType
    ): HoldColors {
        val seg = (tMs / 1_500L).toInt()
        val rnd = Random(musicId * 31_415 + seg * 271 + sectionType.ordinal * 11)

        val fg = when (sectionType) {
            SectionType.CHORUS -> listOf(palette.c1, palette.c2, palette.white)[rnd.nextInt(3)]
            SectionType.BRIDGE -> listOf(palette.white, palette.c3)[rnd.nextInt(2)]
            else -> listOf(palette.c1, palette.c2, palette.c3, palette.white)[rnd.nextInt(4)]
        }

        val bg = when (sectionType) {
            SectionType.CHORUS -> listOf(palette.black, palette.c4)[rnd.nextInt(2)]
            else -> palette.black
        }

        return HoldColors(fg = fg, bg = bg)
    }

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

    // ------------------------------------------------------------
    // Period helpers
    // ------------------------------------------------------------

    private fun msToBlinkPeriod(beatMs: Long): Int {
        return (beatMs / 10L).toInt().coerceIn(1, 255)
    }

    private fun msToStrobePeriod(beatMs: Long): Int {
        return (beatMs / 10L).toInt().coerceIn(1, 255)
    }

    private fun msToBreathPeriod(beatMs: Long): Int {
        return (beatMs / 20L).toInt().coerceIn(1, 255)
    }

    private fun randomDelayFromBeat(beatMs: Long): Int {
        return when {
            beatMs <= 300L -> 3
            beatMs <= 380L -> 4
            beatMs <= 450L -> 5
            beatMs <= 550L -> 6
            beatMs <= 650L -> 7
            beatMs <= 800L -> 8
            else -> 9
        }.coerceIn(3, 10)
    }

    // ------------------------------------------------------------
    // Decode / envelope
    // ------------------------------------------------------------

    private fun decodeEnvelopeInternal(
        musicPath: String,
        hopMs: Int,
        mode: EnvMode
    ): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(musicPath)

            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                extractor.release()
                return emptyList()
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                extractor.release()
                return emptyList()
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples = max(1, sampleRate * hopMs / 1000)

            val out = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            var sawInputEOS = false
            var sawOutputEOS = false
            val pcmWindow = ArrayList<Float>(hopSamples)

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val timeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            val mono = pcm16ToMonoFloat(chunk, channelCount)
                            val filtered = when (mode) {
                                EnvMode.FULL -> mono
                                EnvMode.LOW -> lowBandProxy(mono)
                                EnvMode.MID -> midBandProxy(mono)
                            }

                            for (v in filtered) {
                                pcmWindow += v
                                if (pcmWindow.size >= hopSamples) {
                                    out += rms(pcmWindow)
                                    pcmWindow.clear()
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            normalizeEnvelope(out)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeEnvelopeInternal fail mode=$mode path=$musicPath: ${t.message}")
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            emptyList()
        }
    }

    private fun pcm16ToMonoFloat(bytes: ByteArray, channels: Int): List<Float> {
        if (bytes.isEmpty()) return emptyList()

        val out = ArrayList<Float>(bytes.size / 2 / max(1, channels))
        var i = 0

        while (i + 1 < bytes.size) {
            var sum = 0f
            var count = 0

            for (c in 0 until channels) {
                val idx = i + c * 2
                if (idx + 1 < bytes.size) {
                    val lo = bytes[idx].toInt() and 0xFF
                    val hi = bytes[idx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    val signed = if (sample > 32767) sample - 65536 else sample
                    sum += signed / 32768f
                    count++
                }
            }

            out += if (count == 0) 0f else sum / count.toFloat()
            i += channels * 2
        }

        return out
    }

    private fun lowBandProxy(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val lp = onePoleLowPass(src, 0.12f)
        return lp.map { abs(it) }
    }

    private fun midBandProxy(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val lp1 = onePoleLowPass(src, 0.35f)
        val lp2 = onePoleLowPass(src, 0.08f)
        return List(src.size) { i -> abs(lp1[i] - lp2[i]) }
    }

    private fun onePoleLowPass(src: List<Float>, alpha: Float): List<Float> {
        if (src.isEmpty()) return emptyList()

        val out = ArrayList<Float>(src.size)
        var y = 0f
        for (x in src) {
            y += alpha * (x - y)
            out += y
        }
        return out
    }

    private fun rms(src: List<Float>): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f
        for (x in src) sum += x * x
        return sqrt(sum / src.size.toFloat())
    }

    private fun normalizeEnvelope(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val smooth = movingAverage(src, 5)
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src

        val out = ArrayList<Float>(src.size)
        val half = window / 2

        for (i in src.indices) {
            var sum = 0f
            var count = 0
            val s = max(0, i - half)
            val e = min(src.lastIndex, i + half)

            for (j in s..e) {
                sum += src[j]
                count++
            }

            out += if (count == 0) 0f else sum / count.toFloat()
        }

        return out
    }

    // ------------------------------------------------------------
    // Utils
    // ------------------------------------------------------------

    private fun average(src: List<Float>): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f
        for (v in src) sum += v
        return sum / src.size.toFloat()
    }

    private fun densityAbove(src: FloatArray, th: Float): Float {
        if (src.isEmpty()) return 0f
        var count = 0
        for (v in src) if (v >= th) count++
        return count.toFloat() / src.size.toFloat()
    }

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.size < win + 2) return
        val copy = x.copyOf()
        for (i in x.indices) {
            var s = 0f
            var c = 0
            val a = (i - win).coerceAtLeast(0)
            val b = (i + win).coerceAtMost(x.lastIndex)
            for (j in a..b) {
                s += copy[j]
                c++
            }
            x[i] = s / max(1, c)
        }
    }

    fun getVersion(): Int = VERSION
}