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

/**
 * AutoTimelineGeneratorBeat v3
 *
 * 감지기: BeatDetectorV2 (V11) + SectionDetectorV1 (고정)
 * 이펙트: V8 이펙트 매칭룰 (FgEngine 기반 — ON_PULSE / STROBE / BREATH / ON_TRANSIT_ROTATE 등)
 *
 * V2 대비 변경:
 *  - 단순 beat-ON 대신 V8의 섹션별 엔진(FgEngine) + 팔레트 기반 이펙트 적용
 *  - SectionDetectorV1의 energy 필드를 V8의 energyScore 로 활용
 *  - Climax 감지 / 발라드 모드 / Bridge phase engine 등 V8 규칙 전부 유지
 */
class AutoTimelineGeneratorBeat_v3 : AutoTimelineGenerator, SectionAwareGenerator {

    // ──────────────────────────────────────────────────────────────
    // Constants (V8 기준 유지)
    // ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val HOP_MS      = 50L
        private const val MIN_BEAT_MS = 290L
        private const val MAX_BEAT_MS = 1200L

        private const val ON_TRANSIT = 2

        private const val INTRO_PRESTART_TRANSIT_MS = 1_000L
        private const val MIN_TRAILING_SILENCE_MS   = 1_500L

        private const val ACTUAL_BEAT_USE_RATIO = 0.45f

        private const val CLIMAX_WINDOW_HALF_MS = 4_000L
        private const val CLIMAX_MIN_CV         = 0.35f
        private const val CLIMAX_MIN_PEAK_RATIO = 2.0f

        private const val SECTION_GAP_BREATH_THRESHOLD_MS = 2_000L

        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_PULSE_BG_TRANSIT     = 5
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT

        // IIR 필터 계수 (V2와 동일: HIGH 밴드 포함)
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
        private const val HIGH_ALPHA    = 0.40f
    }

    // ──────────────────────────────────────────────────────────────
    // V8 이펙트 타입 정의
    // ──────────────────────────────────────────────────────────────

    enum class FgEngine { ON_PULSE, BLINK, STROBE, BREATH, ON_TRANSIT_ROTATE, OFF_TRANSIT }

    enum class ChangeLevel { MEDIUM, STRONG }

    data class ColorSet(val fg: LSColor, val bg: LSColor)

    data class Palette(
        val black: LSColor, val white: LSColor,
        val onPulseSets: List<ColorSet>,
        val blinkSets:   List<ColorSet>,
        val strokeSets:  List<ColorSet>,
        val breathSet:   ColorSet,
        val bridgeSets:  List<ColorSet>,
        val chorusBg:    LSColor,
        val colorGroup:  List<LSColor>
    )

    // V8 이펙트 빌딩에 사용하는 내부 섹션 표현
    data class V8Section(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val engine: FgEngine,
        val beatMs: Long, val beats: Int,
        val source: String, val change: ChangeLevel,
        val energyScore: Float = 0f, val relScore: Float = 0f
    )

    // ──────────────────────────────────────────────────────────────
    // Entry points
    // ──────────────────────────────────────────────────────────────

    override fun generate(musicPath: String, musicId: Int, paletteSize: Int): List<Pair<Long, ByteArray>> =
        generateWithSections(musicPath, musicId, paletteSize).first

    override fun generateWithSections(
        musicPath: String, musicId: Int, paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> {

        Log.d(TAG, "v3 start file=$musicPath musicId=$musicId detector=BeatDetectorV2+SectionDetectorV1")

        val palette    = buildPalette(musicId)
        val (lowEnv, midEnv, fullEnv, highEnv) = decodeAllEnvelopes(musicPath)

        if (lowEnv.isEmpty()) {
            Log.w(TAG, "v3 env empty"); return Pair(emptyList(), emptyList())
        }

        val durationMs = fullEnv.size.toLong() * HOP_MS

        // ── 1. Beat detection (BeatDetectorV2 고정) ───────────────
        val beatResult = BeatDetectorV2.detect(lowEnv, midEnv, fullEnv,
            BeatDetectorV2.Params(
                hopMs             = HOP_MS,
                minBeatMs         = MIN_BEAT_MS,
                maxBeatMs         = MAX_BEAT_MS,
                minPeakDistanceMs = 140L,
                onsetSmoothWindow = 3,
                peakThresholdK    = 0.28f,
                minPeakAbs        = 0.07f,
                snapToleranceMs   = 130L,
                chainToleranceMs  = 150L,
                minChainCount     = 3,
                continuityBonus   = 0.08f
            ))
        val beatInfoBeats = beatResult.beats.map { BeatDetectorRouter.BeatInfo.Beat(it.timeMs, it.confidence) }
        val globalBeatMs = beatResult.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            .let { if (it > 900L) it / 2L else it }
        val beatTimesMs = beatInfoBeats.map { it.timeMs }.filter { it in 0..durationMs }

        if (beatTimesMs.isEmpty()) {
            Log.w(TAG, "v3 beat detect FAIL"); return Pair(emptyList(), emptyList())
        }
        Log.d(TAG, "v3 beats=${beatTimesMs.size} beatMs=$globalBeatMs")

        // ── 2. Section detection (SectionDetectorV1 고정) ─────────
        val detectedSections = SectionDetectorV1().detect(
            lowEnv     = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beats      = beatInfoBeats,
            beatMs     = globalBeatMs, durationMs = durationMs, hopMs = HOP_MS
        )
        Log.d(TAG, "v3 sections=${detectedSections.size}")

        // ── 3. Music style + climax ───────────────────────────────
        val styleResult  = MusicStyleClassifier.classify(
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beatMs = globalBeatMs, beats = beatInfoBeats, hopMs = HOP_MS
        )
        val musicStyle    = styleResult.style
        // BALLAD / HIPHOP_RNB 는 느리고 부드러운 이펙트 계열로 처리
        val isBalladMode  = musicStyle == MusicStyleClassifier.MusicStyle.BALLAD
                         || musicStyle == MusicStyleClassifier.MusicStyle.HIPHOP_RNB
        // EDM / DANCE_POP 는 클라이맥스 감지 필요, 나머지는 불필요
        val needsClimax   = musicStyle == MusicStyleClassifier.MusicStyle.EDM
                         || musicStyle == MusicStyleClassifier.MusicStyle.DANCE_POP
                         || musicStyle == MusicStyleClassifier.MusicStyle.ROCK
                         || musicStyle == MusicStyleClassifier.MusicStyle.POP
        val climaxMoments = if (!needsClimax) emptyList()
                            else detectClimaxPeakMoments(fullEnv, durationMs, globalBeatMs)
        Log.d(TAG, "v3 style=$musicStyle balladMode=$isBalladMode climax=${climaxMoments.size}")

        // ── 4. Convert → V8Section with FgEngine assignment ───────
        val v8Sections = convertToV8Sections(detectedSections, globalBeatMs, climaxMoments, isBalladMode)

        // ── 5. Frame building (V8 규칙) ───────────────────────────
        val finalOffMs = detectLastMusicEndMs(fullEnv.toFloatArray(), HOP_MS, MIN_TRAILING_SILENCE_MS)
            .coerceIn(0L, durationMs)

        val frames = buildFramesFromSections(
            palette         = palette,
            sections        = v8Sections,
            beatTimesMs     = beatTimesMs,
            durationMs      = durationMs,
            climaxMoments   = climaxMoments,
            isBalladMode    = isBalladMode,
            finalOffMs      = finalOffMs
        )
        Log.d(TAG, "v3 frames=${frames.size}")

        // ── 6. SectionMeta for overlay ────────────────────────────
        val sectionMetas = detectedSections.map { s ->
            SectionMeta(
                startMs        = s.startMs,    endMs          = s.endMs,
                type           = s.type,       changeStrength = s.changeStrength,
                beatMs         = s.beatMs,     beatConfidence = s.beatConfidence,
                energy         = s.energy,     peakEnergy     = s.peakEnergy,
                lowRatio       = s.lowRatio,   midRatio       = s.midRatio,
                highRatio      = s.highRatio,  onsetDensity   = s.onsetDensity,
                periodicity    = s.periodicity
            )
        }

        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // Section 변환: SectionDetector.Section → V8Section
    // ──────────────────────────────────────────────────────────────

    private fun convertToV8Sections(
        detected: List<SectionDetector.Section>,
        beatMs: Long,
        climaxMoments: List<Long>,
        isBalladMode: Boolean
    ): List<V8Section> {
        if (detected.isEmpty()) return emptyList()

        val energies = detected.map { it.energy }
        val lowTh    = percentile(energies, 0.35f)
        val highTh   = percentile(energies, 0.70f)
        val range    = (highTh - lowTh).coerceAtLeast(1e-6f)

        return detected.map { s ->
            val beats    = estimateBeatCount(s.startMs, s.endMs, beatMs)
            val relScore = ((s.energy - lowTh) / range).coerceIn(0f, 1f)
            val midMs    = (s.startMs + s.endMs) / 2L
            val isClimax = climaxMoments.any { abs(it - midMs) <= 6_000L }

            val normalizedType = when {
                s.type == SectionDetector.SectionType.BRIDGE && beats < 6 ->
                    SectionDetector.SectionType.VERSE
                else -> s.type
            }

            val engine = assignFgEngine(normalizedType, relScore, beats, globalBeatMs = beatMs,
                isClimax = isClimax, isBalladMode = isBalladMode)

            val source = buildSourceName(normalizedType, engine, beats)

            val change = when {
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
                beats < 8 -> ChangeLevel.MEDIUM
                else      -> ChangeLevel.STRONG
            }

            V8Section(
                startMs     = s.startMs, endMs     = s.endMs,
                type        = normalizedType,  engine    = engine,
                beatMs      = beatMs,          beats     = beats,
                source      = source,          change    = change,
                energyScore = s.energy,        relScore  = relScore
            )
        }
    }

    /** V8의 buildContentSection 엔진 할당 규칙 */
    private fun assignFgEngine(
        type: SectionDetector.SectionType,
        rel: Float, beats: Int, globalBeatMs: Long,
        isClimax: Boolean, isBalladMode: Boolean
    ): FgEngine = when (type) {
        SectionDetector.SectionType.VERSE -> when {
            isBalladMode -> if (rel < 0.55f) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            rel < 0.10f && beats < 8 -> FgEngine.BREATH
            rel < 0.75f -> FgEngine.ON_PULSE
            else        -> FgEngine.ON_TRANSIT_ROTATE
        }
        SectionDetector.SectionType.CHORUS -> when {
            isBalladMode && (isClimax || rel >= 0.65f) -> FgEngine.ON_TRANSIT_ROTATE
            isBalladMode -> FgEngine.BREATH
            globalBeatMs <= 290L -> FgEngine.STROBE
            isClimax     -> FgEngine.STROBE
            rel >= 0.40f -> FgEngine.ON_TRANSIT_ROTATE
            else         -> FgEngine.ON_PULSE
        }
        SectionDetector.SectionType.BRIDGE -> when {
            isBalladMode -> FgEngine.BREATH
            beats < 8   -> FgEngine.ON_TRANSIT_ROTATE
            else        -> FgEngine.ON_PULSE
        }
        SectionDetector.SectionType.INTRO -> FgEngine.BREATH
        SectionDetector.SectionType.END   -> FgEngine.OFF_TRANSIT
    }

    private fun buildSourceName(type: SectionDetector.SectionType, engine: FgEngine, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.VERSE  -> when (engine) {
                FgEngine.BREATH            -> "verse-breath"
                FgEngine.ON_TRANSIT_ROTATE -> "verse-rotate"
                else                       -> "verse-on-pulse"
            }
            SectionDetector.SectionType.CHORUS -> when (engine) {
                FgEngine.STROBE            -> "chorus-strobe"
                FgEngine.ON_TRANSIT_ROTATE -> "chorus-rotate"
                FgEngine.BREATH            -> "chorus-breath"
                else                       -> "chorus-on-pulse"
            }
            SectionDetector.SectionType.BRIDGE -> when {
                beats < 8  -> "bridge-rotate"
                beats < 16 -> "bridge-breath-rotate"
                else       -> "bridge-breath-rotate-long"
            }
            SectionDetector.SectionType.INTRO -> "intro-breath"
            SectionDetector.SectionType.END   -> "end-off"
        }

    // ──────────────────────────────────────────────────────────────
    // Frame building (V8 로직 유지)
    // ──────────────────────────────────────────────────────────────

    private fun buildFramesFromSections(
        palette: Palette,
        sections: List<V8Section>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        climaxMoments: List<Long>,
        isBalladMode: Boolean,
        finalOffMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        fun isNearClimax(tMs: Long) = climaxMoments.any { abs(it - tMs) <= CLIMAX_WINDOW_HALF_MS }

        data class RepeatKey(
            val engine: FgEngine,
            val fgR: Int, val fgG: Int, val fgB: Int,
            val bgR: Int, val bgG: Int, val bgB: Int,
            val period: Int, val randomDelay: Int
        )
        var lastRepeatKey: RepeatKey? = null

        put(0L, buildOffPayload())

        var prevSectionEndMs  = 0L
        val sameTypeCountMap  = mutableMapOf<SectionDetector.SectionType, Int>()

        for ((index, section) in sections.withIndex()) {
            val sameTypeIdx = sameTypeCountMap.getOrDefault(section.type, 0)
            sameTypeCountMap[section.type] = sameTypeIdx + 1
            lastRepeatKey = null

            val interGapMs = if (index > 0) (section.startMs - prevSectionEndMs).coerceAtLeast(0L) else 0L
            val insertTransitionBreath = interGapMs >= SECTION_GAP_BREATH_THRESHOLD_MS &&
                section.engine != FgEngine.BREATH && section.engine != FgEngine.OFF_TRANSIT

            val actualBeats    = beatTimesMs.filter { it >= section.startMs && it < section.endMs }
            val effectiveBeats = buildSectionBeatGrid(section, actualBeats)

            Log.d(TAG, "v3 section[$index] ${section.type} ${section.startMs}~${section.endMs} " +
                "engine=${section.engine} beats=${effectiveBeats.size} ballad=$isBalladMode")

            if (section.engine == FgEngine.OFF_TRANSIT) {
                put(section.startMs, buildOffPayload())
                prevSectionEndMs = section.endMs; continue
            }

            if (section.engine == FgEngine.BREATH) {
                val (fg, bg) = colorsForEngine(palette, FgEngine.BREATH, sameTypeIdx)
                val delay = if (section.type == SectionDetector.SectionType.VERSE) 0
                            else msToBreathRandomDelay(section.beatMs)
                put(section.startMs, buildPayload(FgEngine.BREATH, fg, bg, section.beatMs,
                    randomDelay = delay))
                prevSectionEndMs = section.endMs; continue
            }

            if (effectiveBeats.isEmpty()) {
                val (fg, bg) = colorsForEngine(palette, section.engine, sameTypeIdx)
                val rotateTransit = if (section.engine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0
                put(section.startMs, buildPayload(section.engine, fg, bg, section.beatMs,
                    rotateTransit = rotateTransit))
                continue
            }

            val firstBeat  = effectiveBeats.first()
            val coverGapMs = firstBeat - section.startMs

            val sectionNearClimax = if (section.engine == FgEngine.STROBE) {
                climaxMoments.any { c ->
                    c + CLIMAX_WINDOW_HALF_MS >= section.startMs &&
                    c - CLIMAX_WINDOW_HALF_MS <= section.endMs
                }
            } else false

            if (insertTransitionBreath) {
                val (mFg, mBg) = palette.white to palette.breathSet.bg
                put(section.startMs, buildPayload(FgEngine.BREATH, mFg, mBg, section.beatMs,
                    randomDelay = msToBreathRandomDelay(section.beatMs)))
            } else if (coverGapMs > 0L && section.type != SectionDetector.SectionType.INTRO) {
                val longThMs = section.beatMs * 3L / 2L
                if (coverGapMs <= longThMs) {
                    val coverEngine = when (section.engine) {
                        FgEngine.ON_TRANSIT_ROTATE, FgEngine.STROBE -> FgEngine.BREATH
                        else -> section.engine
                    }
                    val (cvFg, cvBg) = colorsForEngine(palette, coverEngine, sameTypeIdx)
                    put(section.startMs, buildPayload(coverEngine, cvFg, cvBg, section.beatMs))
                } else {
                    var fillT = section.startMs; var fillIdx = 0
                    while (fillT < firstBeat) {
                        val (cvFg, cvBg) = colorsForEngine(palette, section.engine, sameTypeIdx, fillIdx, section.type)
                        val fillRotateTransit = if (section.engine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                            ON_ROTATE_BALLAD_TRANSIT else 0
                        val fillPeriod = if (section.engine == FgEngine.STROBE)
                            (if (sectionNearClimax) 1 else msToStrobePeriod(section.beatMs)) else null
                        put(fillT, buildPayload(section.engine, cvFg, cvBg, section.beatMs,
                            period = fillPeriod, rotateTransit = fillRotateTransit))
                        if (section.engine == FgEngine.ON_PULSE) {
                            val offT = min(firstBeat, fillT + section.beatMs * 3L / 10L)
                            if (offT > fillT)
                                put(offT, buildPayload(FgEngine.ON_PULSE, cvBg, cvBg, section.beatMs))
                        }
                        fillT += section.beatMs; fillIdx++
                    }
                }
            }

            for ((beatIndex, t) in effectiveBeats.withIndex()) {
                if (beatIndex == 0 && section.type == SectionDetector.SectionType.INTRO) {
                    val (introFg, _) = colorsForEngine(palette, FgEngine.BREATH, sameTypeIdx)
                    put(section.startMs, buildPayload(FgEngine.BREATH, introFg, LSColor(0, 0, 0), section.beatMs,
                        randomDelay = 3))
                    continue
                }

                val nearClimax = if (section.engine == FgEngine.STROBE) sectionNearClimax
                                 else isNearClimax(t)

                val beatEngine = if (section.type == SectionDetector.SectionType.BRIDGE)
                    bridgePhaseEngine(beatIndex, effectiveBeats.size, section.beatMs, section.relScore, isBalladMode)
                else section.engine

                val effectiveEngine = when {
                    beatEngine == FgEngine.STROBE && !nearClimax -> FgEngine.BREATH
                    else -> beatEngine
                }

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, sameTypeIdx, beatIndex, section.type)
                val bgNonNull = bg ?: LSColor(0, 0, 0)

                val beatPeriod = when (effectiveEngine) {
                    FgEngine.STROBE -> 1
                    FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else            -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == FgEngine.STROBE && nearClimax   -> 1
                    effectiveEngine == FgEngine.ON_TRANSIT_ROTATE      -> null
                    effectiveEngine == FgEngine.ON_PULSE               -> null
                    effectiveEngine == FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.VERSE -> 0
                    effectiveEngine == FgEngine.BREATH                 -> msToBreathRandomDelay(section.beatMs)
                    else                                               -> null
                }
                val beatRotateTransit = if (effectiveEngine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0

                val skipOnPulseOdd = (beatEngine == FgEngine.ON_PULSE && beatIndex % 2 != 0)

                val skipRepeat = if (skipOnPulseOdd) {
                    true
                } else if (effectiveEngine == FgEngine.ON_TRANSIT_ROTATE
                    || effectiveEngine == FgEngine.STROBE
                    || effectiveEngine == FgEngine.BREATH) {
                    val key = RepeatKey(effectiveEngine,
                        fg.r, fg.g, fg.b, bgNonNull.r, bgNonNull.g, bgNonNull.b,
                        beatPeriod ?: 0, beatRandomDelay ?: 0)
                    val dup = (key == lastRepeatKey); lastRepeatKey = key; dup
                } else {
                    lastRepeatKey = null; false
                }

                if (!skipRepeat) {
                    put(t, buildPayload(effectiveEngine, fg, bg, section.beatMs, beatPeriod,
                        beatRandomDelay ?: 0, rotateTransit = beatRotateTransit))
                }

                if (beatEngine == FgEngine.ON_PULSE && !skipOnPulseOdd) {
                    val holdMs = minOf(ON_PULSE_ACCENT_HOLD_MS * 2L, section.beatMs * 44L / 100L).coerceAtLeast(60L)
                    val offT   = minOf(section.endMs - 1L, t + holdMs)
                    if (offT > t)
                        put(offT, LSEffectPayload.Effects.on(color = bg, transit = ON_TRANSIT).toByteArray())
                }
            }

            prevSectionEndMs = section.endMs
        }

        if (finalOffMs < durationMs) frameMap.keys.filter { it > finalOffMs }.forEach { frameMap.remove(it) }
        if (frameMap.keys.none { it >= finalOffMs }) {
            frameMap[finalOffMs] = buildOffPayload()
        }

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    // ──────────────────────────────────────────────────────────────
    // Beat grid (V8와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun buildSectionBeatGrid(section: V8Section, actualBeats: List<Long>): List<Long> {
        if (section.endMs <= section.startMs || section.beatMs <= 0L) return emptyList()
        val expected = estimateBeatCount(section.startMs, section.endMs, section.beatMs)
        val minRequired = (expected * ACTUAL_BEAT_USE_RATIO).toInt().coerceAtLeast(2)
        if (actualBeats.size >= minRequired) return fillBeatGaps(actualBeats.sorted(), section.beatMs, section.endMs)
        val grid = ArrayList<Long>(); var t = section.startMs
        while (t < section.endMs) { grid += t; t += section.beatMs }
        if (actualBeats.isEmpty()) return grid
        val snapMs = section.beatMs / 4L
        return grid.map { g ->
            val closest = actualBeats.minByOrNull { abs(it - g) }
            if (closest != null && abs(closest - g) <= snapMs) closest else g
        }.distinct().sorted()
    }

    private fun fillBeatGaps(beats: List<Long>, beatMs: Long, sectionEndMs: Long): List<Long> {
        if (beats.size < 2 || beatMs <= 0L) return beats
        val gapTh = beatMs * 3L / 2L
        val out = ArrayList<Long>(beats.size * 2); out += beats.first()
        for (i in 1 until beats.size) {
            val prev = beats[i - 1]; val cur = beats[i]; val gap = cur - prev
            if (gap > gapTh) {
                val fillCount = ((gap + beatMs / 2L) / beatMs).toInt() - 1
                if (fillCount > 0) {
                    val step = gap / (fillCount + 1).toLong()
                    for (k in 1..fillCount) { val interp = prev + step * k; if (interp < sectionEndMs) out += interp }
                }
            }
            out += cur
        }
        return out.distinct().sorted()
    }

    // ──────────────────────────────────────────────────────────────
    // Payload builder (V8와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun buildPayload(
        engine: FgEngine, fg: LSColor, bg: LSColor?, beatMs: Long,
        period: Int? = null, randomDelay: Int = 0, rotateTransit: Int = 0
    ): ByteArray {
        val bgColor = bg ?: LSColor(0, 0, 0)
        return when (engine) {
            FgEngine.ON_PULSE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            FgEngine.BLINK ->
                LSEffectPayload.Effects.blink(period = period ?: msToBlinkPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay).toByteArray()
            FgEngine.STROBE ->
                LSEffectPayload.Effects.strobe(period = period ?: msToStrobePeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay).toByteArray()
            FgEngine.BREATH ->
                LSEffectPayload.Effects.breath(period = period ?: msToBreathPeriod(beatMs),
                    color = fg, backgroundColor = bgColor,
                    randomDelay = randomDelay.takeIf { it > 0 } ?: 5).toByteArray()
            FgEngine.ON_TRANSIT_ROTATE ->
                LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
            FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(): ByteArray = LSEffectPayload.Effects.off(transit = ON_TRANSIT).toByteArray()

    // ──────────────────────────────────────────────────────────────
    // Bridge phase engine (V8와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun bridgePhaseEngine(
        beatIndex: Int, totalBeats: Int, beatMs: Long, relScore: Float, isBalladMode: Boolean
    ): FgEngine {
        if (isBalladMode || relScore < 0.1f) return FgEngine.BREATH
        if (totalBeats <= 0) return FgEngine.STROBE
        val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)
        return when {
            totalBeats < 8  -> FgEngine.STROBE
            totalBeats < 16 -> {
                val phase = beatIndex.toFloat() / totalBeats
                if (phase < strobeEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
            else -> {
                val phase      = beatIndex.toFloat() / totalBeats
                val rotateEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
                if (phase < rotateEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Palette & color (V8와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun buildPalette(seed: Int): Palette {
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
        return Palette(
            black       = black, white = white,
            onPulseSets = listOf(ColorSet(white, patternABg), ColorSet(cMain, black)),
            blinkSets   = listOf(ColorSet(cMain, black), ColorSet(cStep1, black)),
            strokeSets  = listOf(ColorSet(white, black)),
            breathSet   = ColorSet(white, patternABg),
            bridgeSets  = listOf(ColorSet(cStep2, black), ColorSet(cMain, black)),
            chorusBg    = cDeep, colorGroup = colorGroup
        )
    }

    private fun colorsForEngine(
        palette: Palette, engine: FgEngine, sectionIndex: Int,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE
    ): Pair<LSColor, LSColor> {
        val isPatternA = (sectionIndex % 2 == 0)
        val effectiveColors: List<LSColor> = when (sectionType) {
            SectionDetector.SectionType.CHORUS -> listOf(palette.white) + palette.colorGroup.take(3)
            SectionDetector.SectionType.VERSE  -> palette.colorGroup.take(3)
            SectionDetector.SectionType.BRIDGE -> listOf(
                palette.colorGroup.getOrElse(2) { palette.colorGroup[0] },
                palette.colorGroup[0], palette.white
            )
            else -> palette.colorGroup
        }
        val groupColor   = effectiveColors[beatIndex   % effectiveColors.size]
        val sectionColor = effectiveColors[sectionIndex % effectiveColors.size]
        return when (engine) {
            FgEngine.ON_PULSE ->
                if (isPatternA) palette.white to palette.onPulseSets[0].bg
                else            sectionColor  to palette.black
            FgEngine.BLINK, FgEngine.ON_TRANSIT_ROTATE -> groupColor to palette.black
            FgEngine.STROBE  -> palette.white to palette.black
            FgEngine.BREATH  -> palette.breathSet.fg to palette.breathSet.bg
            else ->
                if (isPatternA) palette.bridgeSets[0].fg to palette.black
                else            groupColor               to palette.black
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Climax / ballad / silence detection (V8와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun detectClimaxPeakMoments(fullEnv: List<Float>, durationMs: Long, beatMs: Long): List<Long> {
        if (fullEnv.size < 8) return emptyList()
        val scoreArray = FloatArray(fullEnv.size)
        for (i in 2 until fullEnv.size - 2) {
            val energy   = fullEnv[i]
            val rise     = max(0f, fullEnv[i] - fullEnv[i - 1])
            val localAvg = (fullEnv[i-2] + fullEnv[i-1] + fullEnv[i+1] + fullEnv[i+2]) / 4f
            scoreArray[i] = energy * 0.50f + rise * 0.30f + max(0f, energy - localAvg) * 0.20f
        }
        val scoreList = scoreArray.toList().filter { it > 0f }
        if (scoreList.isEmpty()) return emptyList()
        val envMean  = scoreList.average().toFloat()
        val envStd   = sqrt(scoreList.fold(0f) { acc, v -> acc + (v - envMean) * (v - envMean) } / scoreList.size)
        val cv       = if (envMean > 0f) envStd / envMean else 0f
        val peakRatio = (scoreList.max()) / envMean.coerceAtLeast(1e-6f)
        if (cv < CLIMAX_MIN_CV || peakRatio < CLIMAX_MIN_PEAK_RATIO) return emptyList()
        val candidates = ArrayList<Pair<Long, Float>>()
        for (i in 2 until scoreArray.size - 2) {
            val sc = scoreArray[i]; if (sc <= 0f) continue
            if (sc >= scoreArray[i-1] && sc >= scoreArray[i-2] && sc >= scoreArray[i+1] && sc >= scoreArray[i+2])
                candidates += (i.toLong() * HOP_MS) to sc
        }
        val p90 = scoreList.sorted().let { it[(it.lastIndex * 0.90f).toInt().coerceIn(0, it.lastIndex)] }
        val strong = candidates.filter { it.second >= p90 * 1.18f && it.second >= envMean + envStd * 1.30f }
            .sortedByDescending { it.second }
        val minGapMs = max(800L, beatMs * 4L)
        val selected = ArrayList<Pair<Long, Float>>()
        for (c in strong) {
            if (selected.none { abs(it.first - c.first) < minGapMs }) selected += c
            if (selected.size >= 3) break
        }
        return selected.sortedBy { it.first }.map { it.first.coerceIn(0L, durationMs) }
    }

    private fun detectLastMusicEndMs(frames: FloatArray, hopMs: Long, minTrailingSilenceMs: Long): Long {
        if (frames.isEmpty()) return 0L
        val totalMs = frames.size * hopMs
        val smooth = FloatArray(frames.size)
        for (i in frames.indices) {
            var sum = 0f; var cnt = 0
            for (k in -4..4) { val j = i + k; if (j in frames.indices) { sum += frames[j]; cnt++ } }
            smooth[i] = if (cnt > 0) sum / cnt else frames[i]
        }
        val threshold = max((smooth.maxOrNull() ?: 0f) * 0.03f, 0.01f)
        for (i in smooth.indices.reversed()) {
            if (smooth[i] >= threshold) {
                val lastActiveMs = (i + 1) * hopMs
                return if (totalMs - lastActiveMs >= minTrailingSilenceMs) lastActiveMs else totalMs
            }
        }
        return totalMs
    }

    // ──────────────────────────────────────────────────────────────
    // Period helpers (V8와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun msToBlinkPeriod(beatMs: Long)        = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToStrobePeriod(beatMs: Long)       = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long)       = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long)  = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    // ──────────────────────────────────────────────────────────────
    // 오디오 디코딩 (V2와 동일: HIGH 밴드 포함)
    // ──────────────────────────────────────────────────────────────

    private data class Envelopes(
        val low: List<Float>, val mid: List<Float>,
        val full: List<Float>, val high: List<Float>
    )

    private fun decodeAllEnvelopes(musicPath: String): Envelopes {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(musicPath)
            var trackIndex = -1; var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release(); return Envelopes(emptyList(), emptyList(), emptyList(), emptyList())
            }
            extractor.selectTrack(trackIndex)
            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = (sampleRate.toLong() * HOP_MS / 1000L).toInt().coerceAtLeast(1)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false; var sawOutputEOS = false
            val est = (sampleRate.toLong() * 300L / hopSamples).toInt()
            val outLow  = ArrayList<Float>(est); val outMid  = ArrayList<Float>(est)
            val outFull = ArrayList<Float>(est); val outHigh = ArrayList<Float>(est)
            var lowZ = 0f; var midLP1 = 0f; var midLP2 = 0f; var highLP = 0f
            var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var highSumSq = 0f
            var winPos = 0
            val stepBytes = channelCount * 2
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!; buf.clear()
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else { codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && bufferInfo.size > 0) {
                            buf.position(bufferInfo.offset); buf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size); buf.get(bytes)
                            var byteIdx = 0
                            while (byteIdx + stepBytes <= bytes.size) {
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    val lo = bytes[byteIdx + c * 2].toInt() and 0xFF
                                    val hi = bytes[byteIdx + c * 2 + 1].toInt()
                                    monoSum += (hi shl 8 or lo).toShort().toFloat()
                                }
                                val mono = monoSum / channelCount / 32768f
                                lowZ   += LOW_ALPHA     * (mono - lowZ)
                                midLP1 += MID_LP1_ALPHA * (mono - midLP1)
                                midLP2 += MID_LP2_ALPHA * (mono - midLP2)
                                highLP += HIGH_ALPHA    * (mono - highLP)
                                val lowV  = abs(lowZ); val midV = abs(midLP1 - midLP2)
                                val fullV = abs(mono); val highV = abs(mono - highLP)
                                lowSumSq += lowV * lowV; midSumSq += midV * midV
                                fullSumSq += fullV * fullV; highSumSq += highV * highV
                                winPos++
                                if (winPos >= hopSamples) {
                                    val n = hopSamples.toFloat()
                                    outLow  += sqrt(lowSumSq  / n); outMid  += sqrt(midSumSq  / n)
                                    outFull += sqrt(fullSumSq / n); outHigh += sqrt(highSumSq / n)
                                    lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; highSumSq = 0f; winPos = 0
                                }
                                byteIdx += stepBytes
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
            codec.stop(); codec.release(); extractor.release()
            if (winPos > 0) {
                val n = winPos.toFloat()
                outLow += sqrt(lowSumSq / n); outMid += sqrt(midSumSq / n)
                outFull += sqrt(fullSumSq / n); outHigh += sqrt(highSumSq / n)
            }
            Envelopes(normalize(outLow), normalize(outMid), normalize(outFull), normalize(outHigh))
        } catch (t: Throwable) {
            Log.e(TAG, "v3 decode fail: ${t.message}")
            try { codec?.stop() } catch (_: Throwable) {}; try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            Envelopes(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────

    private fun normalize(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val smooth = movingAverage(src, 5)
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src
        val out = ArrayList<Float>(src.size); val half = window / 2
        for (i in src.indices) {
            var sum = 0f; var cnt = 0
            val s = max(0, i - half); val e = min(src.lastIndex, i + half)
            for (j in s..e) { sum += src[j]; cnt++ }
            out += if (cnt == 0) 0f else sum / cnt.toFloat()
        }
        return out
    }

    private fun estimateBeatCount(startMs: Long, endMs: Long, beatMs: Long): Int {
        if (endMs <= startMs || beatMs <= 0L) return 0
        return max(1, ((endMs - startMs) / beatMs).toInt())
    }

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
