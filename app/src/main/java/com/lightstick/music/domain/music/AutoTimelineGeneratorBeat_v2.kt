package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AutoTimelineGeneratorBeat v2
 *
 * 감지기: BeatDetector(BEAT_DETECTOR_VERSION) + SectionDetector(SECTION_DETECTOR_VERSION)
 * 이펙트: 모든 섹션 타입에 beat-ON 단일 패턴 적용 (섹션 타입별 팔레트 색상만 다름)
 *
 * V3 대비 차이:
 *  - assignFgEngine() → 항상 ON_PULSE 반환 (BREATH / STROBE / ON_TRANSIT_ROTATE 미사용)
 *  - bridgePhaseEngine() → 항상 ON_PULSE 반환
 *  - MusicStyleClassifier / climax 감지 / 발라드 모드 등 파이프라인 구조는 V3와 동일
 */
class AutoTimelineGeneratorBeat_v2 : AutoTimelineGenerator, SectionAwareGenerator {

    // ──────────────────────────────────────────────────────────────
    // Constants (V3와 동일)
    // ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val HOP_MS      = 50L
        private const val MIN_BEAT_MS = 430L
        private const val MAX_BEAT_MS = 1000L

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

        // IIR 필터 계수 (V3와 동일)
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
        private const val HIGH_ALPHA    = 0.40f
    }

    // ──────────────────────────────────────────────────────────────
    // V8 이펙트 타입 정의 (V3와 동일 — ON_PULSE만 사용)
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

        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total  = System.currentTimeMillis()
        Log.d(TAG, "v2 [PERF] start file=$fileName musicId=$musicId")

        val palette    = buildPalette(musicId)

        val detectorVer    = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        // ── 1. BeatDetector + Envelope 단일 decode ────────────────────
        val t0Decode = System.currentTimeMillis()
        val beatInfo = BeatDetectorRouter.detect(
            filePath  = musicPath,
            version   = detectorVer,
            hopMs     = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )
        val envelopes = beatInfo.envelopes
        if (envelopes == null || envelopes.full.isEmpty()) {
            Log.w(TAG, "v2 env empty"); return Pair(emptyList(), emptyList())
        }
        val lowEnv  = envelopes.low
        val midEnv  = envelopes.mid
        val fullEnv = envelopes.full
        val highEnv = envelopes.high
        Log.d(TAG, "v2 [PERF] decode+beat=${System.currentTimeMillis() - t0Decode}ms frames=${fullEnv.size} hopMs=$effectiveHopMs beatMs=${beatInfo.beatMs} beats=${beatInfo.beats.size}")

        val durationMs = fullEnv.size.toLong() * effectiveHopMs
        val beatInfoBeats = beatInfo.beats
        val globalBeatMs  = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar   = beatInfo.beatsPerBar
        val downbeatMs    = beatInfo.downbeatMs
        val beatTimesMs   = beatInfoBeats.map { it.timeMs }.filter { it in 0..durationMs }

        if (beatTimesMs.isEmpty()) {
            Log.w(TAG, "v2 beat detect FAIL"); return Pair(emptyList(), emptyList())
        }

        // ── 3. Section detection ─────────────────────────────────────
        val t0Section        = System.currentTimeMillis()
        val detectedSections = SectionDetectorRouter.detect(
            version    = AutoTimelineConfig.SECTION_DETECTOR_VERSION,
            lowEnv     = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beats      = beatInfoBeats,
            beatMs     = globalBeatMs, durationMs = durationMs, hopMs = effectiveHopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        )
        Log.d(TAG, "v2 [PERF] sectionDetect=${System.currentTimeMillis() - t0Section}ms sections=${detectedSections.size}")

        // ── 4. Music style + climax ───────────────────────────────
        val styleResult  = MusicStyleClassifier.classify(
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beatMs = globalBeatMs, beats = beatInfoBeats, hopMs = effectiveHopMs
        )
        val musicStyle    = styleResult.style
        val isBalladMode  = musicStyle == MusicStyleClassifier.MusicStyle.BALLAD
                         || musicStyle == MusicStyleClassifier.MusicStyle.HIPHOP_RNB
        val needsClimax   = musicStyle == MusicStyleClassifier.MusicStyle.EDM
                         || musicStyle == MusicStyleClassifier.MusicStyle.DANCE_POP
                         || musicStyle == MusicStyleClassifier.MusicStyle.ROCK
                         || musicStyle == MusicStyleClassifier.MusicStyle.POP
        val climaxMoments = if (!needsClimax) emptyList()
                            else detectClimaxPeakMoments(fullEnv, durationMs, globalBeatMs, effectiveHopMs)
        Log.d(TAG, "v2 style=$musicStyle balladMode=$isBalladMode climax=${climaxMoments.size}")

        // ── 5. Convert → V8Section (이펙트는 모두 ON_PULSE) ──────────
        val v8Sections = convertToV8Sections(detectedSections, globalBeatMs, climaxMoments, isBalladMode)

        // ── 6. Frame building ─────────────────────────────────────
        val t0Build    = System.currentTimeMillis()
        val finalOffMs = detectLastMusicEndMs(fullEnv.toFloatArray(), effectiveHopMs, MIN_TRAILING_SILENCE_MS)
            .coerceIn(0L, durationMs)

        val frames = buildFramesFromSections(
            palette         = palette,
            sections        = v8Sections,
            beatTimesMs     = beatTimesMs,
            durationMs      = durationMs,
            climaxMoments   = climaxMoments,
            isBalladMode    = isBalladMode,
            finalOffMs      = finalOffMs,
            downbeatMs      = downbeatMs,
            beatsPerBar     = beatsPerBar
        )
        Log.d(TAG, "v2 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v2 [PERF] total=${System.currentTimeMillis() - t0Total}ms  file=$fileName durationMs=$durationMs")

        // ── 7. SectionMeta for overlay ────────────────────────────
        val sectionMetas = detectedSections.mapIndexed { idx, s ->
            val sectionBeats = beatInfoBeats.filter { it.timeMs >= s.startMs && it.timeMs < s.endMs }
            val confidence   = if (sectionBeats.isNotEmpty())
                sectionBeats.map { it.confidence }.average().toFloat() else 0.20f
            SectionMeta(
                startMs        = s.startMs,    endMs          = s.endMs,
                type           = s.type,       changeStrength = s.changeStrength,
                beatMs         = globalBeatMs, beatConfidence = confidence,
                energy         = s.energy,     peakEnergy     = s.peakEnergy,
                lowRatio       = s.lowRatio,   midRatio       = s.midRatio,
                highRatio      = s.highRatio,  onsetDensity   = s.onsetDensity,
                periodicity    = s.periodicity,
                musicStyle     = if (idx == 0) musicStyle else null
            )
        }

        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // Section 변환: SectionDetector.Section → V8Section
    // 이펙트는 모든 섹션 타입에 ON_PULSE 고정
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

            val normalizedType = when {
                s.type == SectionDetector.SectionType.BRIDGE && beats < 6 ->
                    SectionDetector.SectionType.VERSE
                else -> s.type
            }

            val change = when {
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
                beats < 8 -> ChangeLevel.MEDIUM
                else      -> ChangeLevel.STRONG
            }

            V8Section(
                startMs     = s.startMs,    endMs       = s.endMs,
                type        = normalizedType, engine    = FgEngine.ON_PULSE,
                beatMs      = beatMs,        beats     = beats,
                source      = "beat-on",     change    = change,
                energyScore = s.energy,      relScore  = relScore
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Frame building — 모든 섹션에 BEAT 섹션과 동일한 1/4박 패턴 적용
    // ──────────────────────────────────────────────────────────────

    private fun buildFramesFromSections(
        palette: Palette,
        sections: List<V8Section>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        climaxMoments: List<Long>,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long = 0L,
        beatsPerBar: Int = 4
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        put(0L, buildOffPayload())

        for ((index, section) in sections.withIndex()) {
            val actualBeats    = beatTimesMs.filter { it >= section.startMs && it < section.endMs }
            val effectiveBeats = buildSectionBeatGrid(section, actualBeats)

            Log.d(TAG, "v2 section[$index] ${section.type} ${section.startMs}~${section.endMs} " +
                "beats=${effectiveBeats.size}")

            // 모든 섹션에 1/4박 White-C1-C2-C3 패턴 적용
            for (t in effectiveBeats) {
                val beatInBar = beatInBar(t, downbeatMs, globalBeatMs = section.beatMs, beatsPerBar)
                val (color, fade) = beatSectionColorAndFade(beatInBar, palette)
                put(t, LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
            }
        }

        if (finalOffMs < durationMs) frameMap.keys.filter { it > finalOffMs }.forEach { frameMap.remove(it) }
        if (frameMap.keys.none { it >= finalOffMs }) {
            frameMap[finalOffMs] = buildOffPayload()
        }

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    // ──────────────────────────────────────────────────────────────
    // Beat grid (V3와 동일)
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
    // BEAT 섹션 전용 헬퍼 (V3와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun beatInBar(tMs: Long, downbeatMs: Long, globalBeatMs: Long, beatsPerBar: Int): Int {
        if (globalBeatMs <= 0L || beatsPerBar <= 0) return 0
        val steps = Math.round((tMs - downbeatMs).toDouble() / globalBeatMs.toDouble())
        return (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
    }

    private fun beatSectionColorAndFade(beatInBar: Int, palette: Palette): Pair<LSColor, Int> {
        if (beatInBar == 0) return palette.white to 100
        val paletteColor = palette.colorGroup.getOrElse(beatInBar - 1) { palette.colorGroup.first() }
        val fade = when (beatInBar) { 2 -> 100; else -> 35 }
        return paletteColor to fade
    }

    // ──────────────────────────────────────────────────────────────
    // Payload builder
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
    // Palette & color (V3와 동일)
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
    // Climax / silence detection (V3와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun detectClimaxPeakMoments(fullEnv: List<Float>, durationMs: Long, beatMs: Long, hopMs: Long = HOP_MS): List<Long> {
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
                candidates += (i.toLong() * hopMs) to sc
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
    // Period helpers (V3와 동일)
    // ──────────────────────────────────────────────────────────────

    private fun msToBlinkPeriod(beatMs: Long)        = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToStrobePeriod(beatMs: Long)       = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long)       = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long)  = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    // ──────────────────────────────────────────────────────────────
    // Utility (V3와 동일)
    // ──────────────────────────────────────────────────────────────

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
