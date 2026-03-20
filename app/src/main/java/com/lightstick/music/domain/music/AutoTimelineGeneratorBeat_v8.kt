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
import kotlin.math.sqrt
import kotlin.random.Random

class AutoTimelineGeneratorBeat_v8 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION = 8
        private const val HOP_MS = 50L

        private const val MIN_BEAT_MS = 350L
        private const val MAX_BEAT_MS = 900L

        private const val ON_TRANSIT = 2
        private const val COLOR_HOLD_MS = 2_000L

        private const val INTRO_PRESTART_TRANSIT_MS = 1_000L
        private const val MIN_SECTION_MS = 1_000L
        private const val SECTION_MERGE_GAP_MS = 600L
    }

    private enum class EnvMode {
        LOW, MID, FULL
    }

    enum class FgEngine {
        ON_PULSE,
        BLINK,
        STROBE,
        BREATH,
        ON_TRANSIT_ROTATE,
        OFF_TRANSIT
    }

    enum class SectionType {
        INTRO,
        VERSE,
        CHORUS,
        BRIDGE,
        END
    }

    enum class ChangeLevel {
        LOW,
        MEDIUM,
        STRONG
    }

    data class Palette(
        val black: LSColor,
        val white: LSColor,
        val verseFg: List<LSColor>,
        val chorusFg: List<LSColor>,
        val chorusBg: List<LSColor>,
        val bridgeRotate: List<LSColor>,
        val bridgeBreathFg: List<LSColor>,
        val size: Int
    )

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val engine: FgEngine,
        val beatMs: Long,
        val beats: Int,
        val source: String,
        val change: ChangeLevel
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
            Log.w(TAG, "env empty -> return empty")
            return emptyList()
        }

        val envSize = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        if (envSize <= 0) {
            Log.w(TAG, "envSize=0 -> return empty")
            return emptyList()
        }

        val durationMs = envSize.toLong() * HOP_MS

        val detect = KPopBeatDetectorV7.detect(
            lowEnv = lowEnv.take(envSize),
            midEnv = midEnv.take(envSize),
            fullEnv = fullEnv.take(envSize),
            params = KPopBeatDetectorV7.Params(
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = MAX_BEAT_MS,
                minPeakDistanceMs = 180L,
                onsetSmoothWindow = 3,
                segmentMs = 20_000L,
                peakThresholdK = 0.55f,
                minPeakAbs = 0.08f,
                snapToleranceMs = 120L,
                chainToleranceMs = 140L,
                minChainCount = 3
            )
        )

        val beatTimes = detect.beatTimesMs
            .filter { it in 0..durationMs }
            .sorted()

        if (beatTimes.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return emptyList()
        }

        Log.d(
            TAG,
            "beat detect OK source=FULL totalBeats=${beatTimes.size} " +
                    "beatMs=${detect.beatMs} first=${beatTimes.firstOrNull()} last=${beatTimes.lastOrNull()}"
        )

        val beatMs = detect.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        val firstMusicMs = detectFirstMusicStartMs(
            energyFrames = fullEnv.take(envSize).toFloatArray(),
            hopMs = HOP_MS
        ).coerceIn(0L, durationMs)

        val introEndMs = when {
            firstMusicMs <= 0L -> 0L
            firstMusicMs <= INTRO_PRESTART_TRANSIT_MS -> 0L
            else -> firstMusicMs - INTRO_PRESTART_TRANSIT_MS
        }

        val forceTransitFromZero = firstMusicMs <= INTRO_PRESTART_TRANSIT_MS

        Log.d(
            TAG,
            "intro tuning firstMusicMs=$firstMusicMs introEndMs=$introEndMs " +
                    "forceTransitFromZero=$forceTransitFromZero durationMs=$durationMs"
        )

        val sections = buildSections(
            beatMs = beatMs,
            fullEnv = fullEnv.take(envSize),
            firstMusicMs = firstMusicMs,
            durationMs = durationMs
        )

        sections.forEachIndexed { idx, s ->
            Log.d(
                TAG,
                "section beat idx=$idx ${s.startMs}~${s.endMs} " +
                        "type=${s.type} beats=${s.beats} beatMs=${s.beatMs} " +
                        "source=${s.source} engine=${s.engine} change=${s.change}"
            )
        }

        val climaxMoments = detectClimaxPeakMoments(
            fullEnv = fullEnv.take(envSize),
            durationMs = durationMs,
            beatMs = beatMs
        )
        Log.d(TAG, "climax moments=${climaxMoments.joinToString()}")

        val frames = buildFramesFromSections(
            musicId = musicId,
            palette = palette,
            sections = sections,
            beatTimes = beatTimes,
            durationMs = durationMs,
            climaxMoments = climaxMoments
        )

        Log.d(TAG, "v8 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    private fun buildSections(
        beatMs: Long,
        fullEnv: List<Float>,
        firstMusicMs: Long,
        durationMs: Long
    ): List<Section> {
        val raw = ArrayList<Section>()

        if (firstMusicMs > 0L) {
            val introStartMs = when {
                firstMusicMs <= INTRO_PRESTART_TRANSIT_MS -> 0L
                else -> firstMusicMs - INTRO_PRESTART_TRANSIT_MS
            }

            raw += Section(
                startMs = introStartMs,
                endMs = firstMusicMs,
                type = SectionType.INTRO,
                engine = FgEngine.ON_TRANSIT_ROTATE,
                beatMs = beatMs,
                beats = estimateBeatCount(introStartMs, firstMusicMs, beatMs),
                source = "intro-prestart-transit",
                change = ChangeLevel.STRONG
            )
        }

        val contentStartMs = firstMusicMs
        if (contentStartMs >= durationMs) {
            return raw.filter { it.endMs > it.startMs }
        }

        val winMs = (beatMs * 4L).coerceAtLeast(2_000L)
        val windows = ArrayList<Triple<Long, Long, Float>>()

        var t = contentStartMs
        while (t < durationMs) {
            val e = min(durationMs, t + winMs)
            val score = sectionEnergyScore(fullEnv, t, e)
            windows += Triple(t, e, score)
            t = e
        }

        if (windows.isEmpty()) {
            raw += Section(
                startMs = contentStartMs,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = beatMs,
                beats = estimateBeatCount(contentStartMs, durationMs, beatMs),
                source = "end-protected",
                change = ChangeLevel.STRONG
            )
            return raw.filter { it.endMs > it.startMs }
        }

        val scores = windows.map { it.third }
        val lowTh = percentile(scores, 0.35f)
        val highTh = percentile(scores, 0.70f)

        val contentSections = ArrayList<Section>()
        var currentStart = windows.first().first
        var currentEnd = windows.first().second
        var currentType = classifyType(windows.first().third, lowTh, highTh)

        for (i in 1 until windows.size) {
            val w = windows[i]
            val type = classifyType(w.third, lowTh, highTh)

            if (type == currentType || w.first - currentEnd <= SECTION_MERGE_GAP_MS) {
                if (type == currentType) {
                    currentEnd = w.second
                } else {
                    contentSections += buildContentSection(
                        startMs = currentStart,
                        endMs = currentEnd,
                        type = currentType,
                        beatMs = beatMs
                    )
                    currentStart = w.first
                    currentEnd = w.second
                    currentType = type
                }
            } else {
                contentSections += buildContentSection(
                    startMs = currentStart,
                    endMs = currentEnd,
                    type = currentType,
                    beatMs = beatMs
                )
                currentStart = w.first
                currentEnd = w.second
                currentType = type
            }
        }

        contentSections += buildContentSection(
            startMs = currentStart,
            endMs = currentEnd,
            type = currentType,
            beatMs = beatMs
        )

        val merged = mergeSmallSections(contentSections, beatMs)
        raw += adjustBridges(merged)

        val lastEnd = raw.maxOfOrNull { it.endMs } ?: 0L
        if (lastEnd < durationMs) {
            raw += Section(
                startMs = lastEnd,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = beatMs,
                beats = estimateBeatCount(lastEnd, durationMs, beatMs),
                source = "end-protected",
                change = ChangeLevel.STRONG
            )
        }

        return raw
            .map { s ->
                val clampedStart = s.startMs.coerceIn(0L, durationMs)
                val clampedEnd = s.endMs.coerceIn(0L, durationMs)
                s.copy(
                    startMs = clampedStart,
                    endMs = clampedEnd,
                    beats = estimateBeatCount(clampedStart, clampedEnd, s.beatMs)
                )
            }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
    }

    private fun buildContentSection(
        startMs: Long,
        endMs: Long,
        type: SectionType,
        beatMs: Long
    ): Section {
        val beats = estimateBeatCount(startMs, endMs, beatMs)

        val normalizedType = when {
            type == SectionType.BRIDGE && beats < 6 -> SectionType.VERSE
            else -> type
        }

        val engine = when (normalizedType) {
            SectionType.VERSE -> FgEngine.ON_PULSE
            SectionType.CHORUS -> FgEngine.ON_PULSE
            SectionType.BRIDGE -> if (beats < 20) FgEngine.ON_TRANSIT_ROTATE else FgEngine.BREATH
            SectionType.INTRO -> FgEngine.ON_TRANSIT_ROTATE
            SectionType.END -> FgEngine.OFF_TRANSIT
        }

        val source = when (normalizedType) {
            SectionType.VERSE -> "verse-on-pulse-black-bg"
            SectionType.CHORUS -> "chorus-on-pulse-color-bg"
            SectionType.BRIDGE -> if (beats < 20) "bridge-on-transit-rotate-x4" else "bridge-breath-black-bg"
            SectionType.INTRO -> "intro-prestart-transit"
            SectionType.END -> "end-protected"
        }

        val change = when {
            normalizedType == SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
            beats < 8 -> ChangeLevel.MEDIUM
            else -> ChangeLevel.STRONG
        }

        return Section(
            startMs = startMs,
            endMs = endMs,
            type = normalizedType,
            engine = engine,
            beatMs = beatMs,
            beats = beats,
            source = source,
            change = change
        )
    }

    private fun adjustBridges(sections: List<Section>): List<Section> {
        return sections.map { s ->
            if (s.type == SectionType.BRIDGE) {
                if (s.beats < 6) {
                    s.copy(
                        type = SectionType.VERSE,
                        engine = FgEngine.ON_PULSE,
                        source = "verse-on-pulse-black-bg",
                        change = ChangeLevel.MEDIUM
                    )
                } else if (s.beats < 20) {
                    s.copy(
                        engine = FgEngine.ON_TRANSIT_ROTATE,
                        source = "bridge-on-transit-rotate-x4",
                        change = ChangeLevel.STRONG
                    )
                } else {
                    s.copy(
                        engine = FgEngine.BREATH,
                        source = "bridge-breath-black-bg",
                        change = ChangeLevel.STRONG
                    )
                }
            } else {
                s
            }
        }
    }

    private fun mergeSmallSections(sections: List<Section>, beatMs: Long): List<Section> {
        if (sections.isEmpty()) return emptyList()

        val out = ArrayList<Section>()
        var cur = sections.first()

        for (i in 1 until sections.size) {
            val next = sections[i]
            val curDur = cur.endMs - cur.startMs

            if (curDur < MIN_SECTION_MS || cur.beats <= 2) {
                cur = cur.copy(
                    endMs = next.endMs,
                    beats = estimateBeatCount(cur.startMs, next.endMs, beatMs)
                )
            } else {
                out += cur
                cur = next
            }
        }

        out += cur
        return out
    }

    private fun classifyType(score: Float, lowTh: Float, highTh: Float): SectionType {
        val bridgeTh = lowTh * 0.85f

        return when {
            score >= highTh -> SectionType.CHORUS
            score <= bridgeTh -> SectionType.BRIDGE
            else -> SectionType.VERSE
        }
    }

    private fun sectionEnergyScore(fullEnv: List<Float>, startMs: Long, endMs: Long): Float {
        val s = (startMs / HOP_MS).toInt().coerceIn(0, fullEnv.lastIndex)
        val e = (endMs / HOP_MS).toInt().coerceIn(s + 1, fullEnv.size)

        var sum = 0f
        var diffSum = 0f
        var maxV = 0f
        var prev = fullEnv[s]

        for (i in s until e) {
            val v = fullEnv[i]
            sum += v
            diffSum += abs(v - prev)
            maxV = max(maxV, v)
            prev = v
        }

        val n = max(1, e - s).toFloat()
        val mean = sum / n
        val activity = diffSum / n

        return (mean * 0.65f + activity * 0.20f + maxV * 0.15f).coerceIn(0f, 1f)
    }

    private fun detectClimaxPeakMoments(
        fullEnv: List<Float>,
        durationMs: Long,
        beatMs: Long
    ): List<Long> {
        if (fullEnv.size < 8) return emptyList()

        data class PeakCandidate(
            val tMs: Long,
            val score: Float
        )

        val scoreArray = FloatArray(fullEnv.size) { 0f }
        for (i in 2 until fullEnv.size - 2) {
            val energy = fullEnv[i]
            val rise = max(0f, fullEnv[i] - fullEnv[i - 1])
            val localAvg = (
                    fullEnv[i - 2] +
                            fullEnv[i - 1] +
                            fullEnv[i + 1] +
                            fullEnv[i + 2]
                    ) / 4f
            val contrast = max(0f, energy - localAvg)
            scoreArray[i] = energy * 0.50f + rise * 0.30f + contrast * 0.20f
        }

        val scoreList = scoreArray.toList().filter { it > 0f }
        if (scoreList.isEmpty()) return emptyList()

        val candidates = ArrayList<PeakCandidate>()
        for (i in 2 until scoreArray.size - 2) {
            val score = scoreArray[i]
            if (score <= 0f) continue

            val isLocalPeak =
                score >= scoreArray[i - 1] &&
                        score >= scoreArray[i - 2] &&
                        score >= scoreArray[i + 1] &&
                        score >= scoreArray[i + 2]

            if (isLocalPeak) {
                candidates += PeakCandidate(
                    tMs = i.toLong() * HOP_MS,
                    score = score
                )
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val sortedScores = scoreList.sorted()
        val mean = scoreList.average().toFloat()
        val p90 = sortedScores[(sortedScores.lastIndex * 0.90f).toInt().coerceIn(0, sortedScores.lastIndex)]
        val variance = scoreList.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / scoreList.size.toFloat()
        val std = sqrt(variance)

        val strongCandidates = candidates
            .filter {
                it.score >= p90 * 1.18f &&
                        it.score >= mean + std * 1.30f
            }
            .sortedByDescending { it.score }

        if (strongCandidates.isEmpty()) return emptyList()

        val minGapMs = max(800L, beatMs * 4L)
        val selected = ArrayList<PeakCandidate>()

        for (c in strongCandidates) {
            val tooClose = selected.any { abs(it.tMs - c.tMs) < minGapMs }
            if (!tooClose) {
                selected += c
            }
            if (selected.size >= 3) break
        }

        return selected
            .sortedBy { it.tMs }
            .map { it.tMs.coerceIn(0L, durationMs) }
    }

    private fun buildSectionBeatGrid(
        section: Section,
        actualBeats: List<Long>
    ): List<Long> {
        if (section.endMs <= section.startMs || section.beatMs <= 0L) {
            return emptyList()
        }

        val out = ArrayList<Long>()
        var t = section.startMs

        while (t < section.endMs) {
            out += t
            t += section.beatMs
        }

        return out
    }

    private fun buildFramesFromSections(
        musicId: Int,
        palette: Palette,
        sections: List<Section>,
        beatTimes: List<Long>,
        durationMs: Long,
        climaxMoments: List<Long>
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimes.size * 4 + sections.size + 8)

        fun putFrame(
            t: Long,
            payload: ByteArray,
            section: Section,
            frameType: String,
            engine: FgEngine,
            fg: LSColor? = null,
            bg: LSColor? = null,
            transit: Int? = null,
            period: Int? = null,
            randomDelay: Int? = null,
            note: String? = null
        ) {
            if (t < 0L) return

            if (frameMap.containsKey(t)) {
                Log.w(
                    TAG,
                    "timeline overwrite t=${t}ms type=$frameType " +
                            "section=${section.type} engine=$engine source=${section.source}"
                )
            }

            frameMap[t] = payload

            logTimelineFrame(
                t = t,
                section = section,
                frameType = frameType,
                engine = engine,
                fg = fg,
                bg = bg,
                transit = transit,
                period = period,
                randomDelay = randomDelay,
                note = note
            )
        }

        for ((index, section) in sections.withIndex()) {
            val actualSectionBeats = beatTimes.filter { it >= section.startMs && it < section.endMs }
            val effectiveSectionBeats = buildSectionBeatGrid(section, actualSectionBeats)

            Log.d(
                TAG,
                "section timeline idx=$index " +
                        "type=${section.type} range=${section.startMs}~${section.endMs} " +
                        "section.beats=${section.beats} actualSectionBeats=${actualSectionBeats.size} " +
                        "gridSectionBeats=${effectiveSectionBeats.size} " +
                        "engine=${section.engine} source=${section.source}"
            )

            if (section.engine == FgEngine.OFF_TRANSIT) {
                putFrame(
                    t = section.startMs,
                    payload = buildOffPayload(),
                    section = section,
                    frameType = "SECTION_OFF",
                    engine = FgEngine.OFF_TRANSIT,
                    transit = ON_TRANSIT
                )
                continue
            }

            if (section.engine == FgEngine.BREATH) {
                val (fg, bg) = colorsForSectionBeat(
                    palette = palette,
                    musicId = musicId,
                    sectionIndex = index,
                    sectionType = section.type,
                    tMs = section.startMs
                )

                putFrame(
                    t = section.startMs,
                    payload = buildPayload(section.engine, fg, bg, section.beatMs),
                    section = section,
                    frameType = "SECTION_START",
                    engine = FgEngine.BREATH,
                    fg = fg,
                    bg = bg,
                    period = msToBreathPeriod(section.beatMs),
                    randomDelay = 5
                )
                continue
            }

            if (effectiveSectionBeats.isEmpty()) {
                val (fg, bg) = colorsForSectionBeat(
                    palette = palette,
                    musicId = musicId,
                    sectionIndex = index,
                    sectionType = section.type,
                    tMs = section.startMs
                )

                putFrame(
                    t = section.startMs,
                    payload = buildPayload(section.engine, fg, bg, section.beatMs),
                    section = section,
                    frameType = "SECTION_START",
                    engine = section.engine,
                    fg = fg,
                    bg = bg,
                    transit = if (
                        section.engine == FgEngine.ON_PULSE ||
                        section.engine == FgEngine.ON_TRANSIT_ROTATE
                    ) ON_TRANSIT else null,
                    period = when (section.engine) {
                        FgEngine.BLINK -> msToBlinkPeriod(section.beatMs)
                        FgEngine.STROBE -> msToStrobePeriod(section.beatMs)
                        FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                        else -> null
                    },
                    note = "no-effective-beats"
                )
                continue
            }

            for ((beatIndex, t) in effectiveSectionBeats.withIndex()) {
                val (fg, bg) = colorsForSectionBeat(
                    palette = palette,
                    musicId = musicId,
                    sectionIndex = index,
                    sectionType = section.type,
                    tMs = t
                )

                if (beatIndex == 0 &&
                    section.type == SectionType.INTRO &&
                    section.source == "intro-prestart-transit"
                ) {
                    putFrame(
                        t = section.startMs,
                        payload = LSEffectPayload.Effects.on(
                            color = fg,
                            transit = ON_TRANSIT
                        ).toByteArray(),
                        section = section,
                        frameType = "INTRO_PRESTART",
                        engine = FgEngine.ON_TRANSIT_ROTATE,
                        fg = fg,
                        transit = ON_TRANSIT,
                        note = if (actualSectionBeats.isEmpty()) "grid-intro" else "actual-intro"
                    )
                }

                val isClimaxBeat = climaxMoments.any { peakMs ->
                    val climaxStart = peakMs
                    val climaxEnd = peakMs + section.beatMs * 2L
                    t in climaxStart until climaxEnd
                }

                if (isClimaxBeat) {
                    putFrame(
                        t = t,
                        payload = LSEffectPayload.Effects.strobe(
                            period = 2,
                            color = palette.white,
                            backgroundColor = palette.black
                        ).toByteArray(),
                        section = section,
                        frameType = "CLIMAX_STROBE",
                        engine = FgEngine.STROBE,
                        fg = palette.white,
                        bg = palette.black,
                        period = 2,
                        note = "climax-2beats beatIndex=$beatIndex"
                    )
                    continue
                }

                if (section.type == SectionType.BRIDGE && section.beats < 20) {
                    val step = max(1L, section.beatMs / 4L)
                    val rotateColors = rotateBridgeColors(
                        palette = palette,
                        musicId = musicId,
                        sectionIndex = index,
                        tMs = t,
                        count = 4
                    )

                    for (sub in 0 until 4) {
                        val subT = t + step * sub
                        if (subT >= section.endMs) break

                        val subFg = rotateColors[sub]

                        putFrame(
                            t = subT,
                            payload = LSEffectPayload.Effects.on(
                                color = subFg,
                                transit = ON_TRANSIT
                            ).toByteArray(),
                            section = section,
                            frameType = "BRIDGE_ROTATE",
                            engine = FgEngine.ON_TRANSIT_ROTATE,
                            fg = subFg,
                            transit = ON_TRANSIT,
                            note = buildString {
                                append("beatIndex=$beatIndex sub=$sub")
                                append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                            }
                        )
                    }
                } else {
                    putFrame(
                        t = t,
                        payload = buildPayload(section.engine, fg, bg, section.beatMs),
                        section = section,
                        frameType = "BEAT_FG",
                        engine = section.engine,
                        fg = fg,
                        bg = bg,
                        transit = if (
                            section.engine == FgEngine.ON_PULSE ||
                            section.engine == FgEngine.ON_TRANSIT_ROTATE
                        ) ON_TRANSIT else null,
                        period = when (section.engine) {
                            FgEngine.BLINK -> msToBlinkPeriod(section.beatMs)
                            FgEngine.STROBE -> msToStrobePeriod(section.beatMs)
                            FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                            else -> null
                        },
                        randomDelay = if (section.engine == FgEngine.BREATH) 5 else null,
                        note = buildString {
                            append("beatIndex=$beatIndex")
                            append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                        }
                    )

                    if (section.engine == FgEngine.ON_PULSE) {
                        val offT = min(section.endMs, t + (section.beatMs * 3L / 10L))
                        if (offT > t) {
                            putFrame(
                                t = offT,
                                payload = LSEffectPayload.Effects.on(
                                    color = bg,
                                    transit = ON_TRANSIT
                                ).toByteArray(),
                                section = section,
                                frameType = "BEAT_BG",
                                engine = FgEngine.ON_PULSE,
                                fg = bg,
                                transit = ON_TRANSIT,
                                note = buildString {
                                    append("restore beatIndex=$beatIndex")
                                    append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                                }
                            )
                        }
                    }
                }
            }
        }

        if (frameMap.keys.none { it >= durationMs }) {
            val endSection = Section(
                startMs = durationMs,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = 0L,
                beats = 0,
                source = "final-off",
                change = ChangeLevel.STRONG
            )

            putFrame(
                t = durationMs,
                payload = buildOffPayload(),
                section = endSection,
                frameType = "FINAL_OFF",
                engine = FgEngine.OFF_TRANSIT,
                transit = ON_TRANSIT
            )
        }

        Log.d(TAG, "timeline final uniqueFrames=${frameMap.size}")

        return frameMap.entries
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

    private fun buildPayload(
        engine: FgEngine,
        fg: LSColor,
        bg: LSColor,
        beatMs: Long
    ): ByteArray {
        return when (engine) {
            FgEngine.ON_PULSE -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgEngine.BLINK -> {
                LSEffectPayload.Effects.blink(
                    period = msToBlinkPeriod(beatMs),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.STROBE -> {
                LSEffectPayload.Effects.strobe(
                    period = msToStrobePeriod(beatMs),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.BREATH -> {
                LSEffectPayload.Effects.breath(
                    period = msToBreathPeriod(beatMs),
                    color = fg,
                    backgroundColor = bg,
                    randomDelay = 5
                ).toByteArray()
            }

            FgEngine.ON_TRANSIT_ROTATE -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(): ByteArray {
        return LSEffectPayload.Effects.off(
            transit = ON_TRANSIT
        ).toByteArray()
    }

    private fun msToBlinkPeriod(beatMs: Long): Int {
        return (beatMs / 10L).toInt().coerceIn(1, 255)
    }

    private fun msToStrobePeriod(beatMs: Long): Int {
        return (beatMs / 10L).toInt().coerceIn(1, 255)
    }

    private fun msToBreathPeriod(beatMs: Long): Int {
        return (beatMs / 20L).toInt().coerceIn(1, 255)
    }

    private fun colorsEqual(a: LSColor, b: LSColor): Boolean {
        return a.r == b.r && a.g == b.g && a.b == b.b
    }

    private fun wrap360(h: Float): Float {
        return ((h % 360f) + 360f) % 360f
    }

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        val baseHue = ((seed * 53) % 360).toFloat()

        val c1 = hsvToColor(baseHue, 0.85f, 0.95f)
        val c2 = hsvToColor(wrap360(baseHue + 18f), 0.60f, 1.00f)
        val c3 = hsvToColor(wrap360(baseHue - 18f), 0.85f, 0.80f)
        val c4 = hsvToColor(wrap360(baseHue + 30f), 0.75f, 0.90f)
        val c5 = hsvToColor(wrap360(baseHue - 30f), 0.70f, 0.90f)

        val verseFg = listOf(c1, c2, c3, LSColor(255, 255, 255))
        val chorusFg = listOf(c1, c2, LSColor(255, 255, 255))
        val chorusBg = if (paletteSize >= 5) listOf(c4, c5) else listOf(c4)
        val bridgeRotate = if (paletteSize >= 5) listOf(c1, c2, c4, c5) else listOf(c1, c2, c3, c4)
        val bridgeBreathFg = listOf(c2, c3, LSColor(255, 255, 255))

        return Palette(
            black = LSColor(0, 0, 0),
            white = LSColor(255, 255, 255),
            verseFg = verseFg,
            chorusFg = chorusFg,
            chorusBg = chorusBg,
            bridgeRotate = bridgeRotate,
            bridgeBreathFg = bridgeBreathFg,
            size = paletteSize
        )
    }

    private fun pickFromList(
        items: List<LSColor>,
        musicId: Int,
        sectionIndex: Int,
        tMs: Long,
        salt: Int = 0
    ): LSColor {
        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + sectionIndex * 271 + seg * 97 + salt * 31)
        return items[rnd.nextInt(items.size)]
    }

    private fun colorsForSectionBeat(
        palette: Palette,
        musicId: Int,
        sectionIndex: Int,
        sectionType: SectionType,
        tMs: Long
    ): Pair<LSColor, LSColor> {
        return when (sectionType) {
            SectionType.VERSE -> {
                val bg = palette.black
                val fg = pickFromList(
                    items = palette.verseFg.filterNot { colorsEqual(it, bg) },
                    musicId = musicId,
                    sectionIndex = sectionIndex,
                    tMs = tMs,
                    salt = 1
                )
                fg to bg
            }

            SectionType.CHORUS -> {
                val bg = pickFromList(
                    items = palette.chorusBg,
                    musicId = musicId,
                    sectionIndex = sectionIndex,
                    tMs = tMs,
                    salt = 2
                )
                val fgCandidates = palette.chorusFg.filterNot { colorsEqual(it, bg) }
                val fg = pickFromList(
                    items = if (fgCandidates.isNotEmpty()) fgCandidates else palette.chorusFg,
                    musicId = musicId,
                    sectionIndex = sectionIndex,
                    tMs = tMs,
                    salt = 3
                )
                fg to bg
            }

            SectionType.BRIDGE -> {
                val bg = palette.black
                val fg = pickFromList(
                    items = palette.bridgeBreathFg.filterNot { colorsEqual(it, bg) },
                    musicId = musicId,
                    sectionIndex = sectionIndex,
                    tMs = tMs,
                    salt = 4
                )
                fg to bg
            }

            SectionType.INTRO -> {
                val bg = palette.black
                val fg = pickFromList(
                    items = palette.verseFg.filterNot { colorsEqual(it, bg) },
                    musicId = musicId,
                    sectionIndex = sectionIndex,
                    tMs = tMs,
                    salt = 5
                )
                fg to bg
            }

            SectionType.END -> palette.black to palette.black
        }
    }

    private fun safeIndex(x: Int, size: Int): Int {
        if (size <= 0) return 0
        return ((x % size) + size) % size
    }

    private fun rotateBridgeColors(
        palette: Palette,
        musicId: Int,
        sectionIndex: Int,
        tMs: Long,
        count: Int = 4
    ): List<LSColor> {
        val base = palette.bridgeRotate.filterNot { colorsEqual(it, palette.black) }
        if (base.isEmpty()) return List(count) { palette.white }

        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val start = safeIndex((musicId * 37) + (sectionIndex * 11) + seg, base.size)

        val out = ArrayList<LSColor>(count)
        for (i in 0 until count) {
            out += base[safeIndex(start + i, base.size)]
        }
        return out
    }

    private fun colorToString(c: LSColor): String {
        return "(${c.r},${c.g},${c.b})"
    }

    private fun logTimelineFrame(
        t: Long,
        section: Section,
        frameType: String,
        engine: FgEngine,
        fg: LSColor? = null,
        bg: LSColor? = null,
        transit: Int? = null,
        period: Int? = null,
        randomDelay: Int? = null,
        note: String? = null
    ) {
        val extra = buildString {
            fg?.let { append(" fg=${colorToString(it)}") }
            bg?.let { append(" bg=${colorToString(it)}") }
            transit?.let { append(" transit=$it") }
            period?.let { append(" period=$it") }
            randomDelay?.let { append(" randomDelay=$it") }
            note?.let { append(" note=$it") }
        }

        Log.d(
            TAG,
            "timeline add t=${t}ms type=$frameType " +
                    "section=${section.type} engine=$engine source=${section.source} beats=${section.beats}$extra"
        )
    }

    private fun estimateBeatCount(startMs: Long, endMs: Long, beatMs: Long): Int {
        if (endMs <= startMs || beatMs <= 0L) return 0
        return max(1, ((endMs - startMs) / beatMs).toInt())
    }

    private fun detectFirstMusicStartMs(
        energyFrames: FloatArray,
        hopMs: Long
    ): Long {
        if (energyFrames.isEmpty()) return 0L

        val smooth = FloatArray(energyFrames.size)
        for (i in energyFrames.indices) {
            var sum = 0f
            var count = 0
            for (k in -2..2) {
                val j = i + k
                if (j in energyFrames.indices) {
                    sum += energyFrames[j]
                    count++
                }
            }
            smooth[i] = if (count > 0) sum / count else energyFrames[i]
        }

        val noiseWindow = ((1000L / hopMs).toInt())
            .coerceAtLeast(1)
            .coerceAtMost(smooth.size)

        var noiseSum = 0f
        for (i in 0 until noiseWindow) {
            noiseSum += smooth[i]
        }
        val noiseFloor = noiseSum / noiseWindow.toFloat()

        val threshold = max(noiseFloor * 2.2f, 0.015f)
        val needRun = ((250L / hopMs).toInt()).coerceAtLeast(2)

        var run = 0
        for (i in smooth.indices) {
            if (smooth[i] >= threshold) {
                run++
                if (run >= needRun) {
                    val startFrame = (i - run + 1).coerceAtLeast(0)
                    return startFrame * hopMs
                }
            } else {
                run = 0
            }
        }

        return 0L
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = (sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
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
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
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

    fun getVersion(): Int = VERSION
}