package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AutoTimelineGeneratorBeat_v1
 *
 * 감지기: BeatDetector(BEAT_DETECTOR_VERSION) + SectionDetector(SECTION_DETECTOR_VERSION)
 * 이펙트: 모든 섹션 타입에 beat-ON 단일 패턴 적용 (섹션 타입별 팔레트 색상만 다름)
 *
 * V3 대비 차이:
 *  - assignFgEngine() → 항상 ON_PULSE 반환 (BREATH / STROBE / ON_TRANSIT_ROTATE 미사용)
 *  - bridgePhaseEngine() → 항상 ON_PULSE 반환
 *  - MusicStyleClassifier / climax 감지 / 발라드 모드 등 파이프라인 구조는 V3와 동일
 */
class AutoTimelineGeneratorBeat_v1 : AutoTimelineGenerator, SectionAwareGenerator {

    // ──────────────────────────────────────────────────────────────
    // Constants (V3와 동일)
    // ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val MIN_BEAT_MS = AutoTimelineConfig.MIN_BEAT_MS
        private const val MAX_BEAT_MS = AutoTimelineConfig.MAX_BEAT_MS

        private const val ON_TRANSIT = 2
        private const val MIN_TRAILING_SILENCE_MS = 1_500L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT
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
        val energyScore: Float = 0f, val relScore: Float = 0f,
        val beatTimesMs: List<Long> = emptyList()
    )

    private data class SectionGroup(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val annotatedBeats: List<SectionDetector.AnnotatedBeat>
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
        Log.d(TAG, "v2 [PERF] decode+beat=${System.currentTimeMillis() - t0Decode}ms frames=${fullEnv.size} hopMs=$effectiveHopMs beatMs=${beatInfo.beatMs} beats=${beatInfo.beats.size} beatDetectorVer=$detectorVer")

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
        // finalOffMs를 먼저 계산해 durationMs 이후 비트를 END로 재태깅
        val finalOffMs = detectLastMusicEndMs(fullEnv.toFloatArray(), effectiveHopMs, MIN_TRAILING_SILENCE_MS)
            .coerceIn(0L, durationMs)
        val t0Section         = System.currentTimeMillis()
        val detectedAnnotated = SectionDetectorRouter.detect(
            version    = AutoTimelineConfig.SECTION_DETECTOR_VERSION,
            lowEnv     = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beats      = beatInfoBeats,
            beatMs     = globalBeatMs, durationMs = durationMs, hopMs = effectiveHopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        ).map { ab ->
            if (ab.timeMs >= finalOffMs)
                SectionDetector.AnnotatedBeat(ab.timeMs, ab.confidence, SectionDetector.SectionType.END)
            else ab
        }
        val sectionGroups = groupAnnotatedBeats(detectedAnnotated, durationMs)
        Log.d(TAG, "v2 [PERF] sectionDetect=${System.currentTimeMillis() - t0Section}ms sections=${sectionGroups.size}")

        // ── 4. Music style + climax ───────────────────────────────
        val musicStyle = MusicStyleClassifier.classify(
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beatMs = globalBeatMs, beats = beatInfoBeats, hopMs = effectiveHopMs
        ).style
        Log.d(TAG, "v2 style=$musicStyle")

        // ── 5. Convert → V8Section (이펙트는 모두 ON_PULSE) ──────────
        val v8Sections = convertToV8Sections(sectionGroups, globalBeatMs)

        // ── 6. Frame building ─────────────────────────────────────
        val t0Build    = System.currentTimeMillis()

        val frames = buildFramesFromSections(
            palette     = palette,
            sections    = v8Sections,
            beatTimesMs = beatTimesMs,
            durationMs  = durationMs,
            finalOffMs  = finalOffMs,
            downbeatMs  = downbeatMs,
            beatsPerBar = beatsPerBar
        )
        Log.d(TAG, "v2 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v2 [PERF] total=${System.currentTimeMillis() - t0Total}ms  file=$fileName durationMs=$durationMs")

        // ── 7. SectionMeta for overlay ────────────────────────────
        val sectionMetas = sectionGroups.mapIndexed { idx, g ->
            val confidence = if (g.annotatedBeats.isNotEmpty())
                g.annotatedBeats.map { it.confidence }.average().toFloat() else 0.20f
            val changeStrength = when {
                g.annotatedBeats.size < 8 -> SectionDetector.ChangeStrength.MEDIUM
                else                      -> SectionDetector.ChangeStrength.STRONG
            }
            SectionMeta(
                startMs        = g.startMs,      endMs          = g.endMs,
                type           = g.type,         changeStrength = changeStrength,
                beatMs         = globalBeatMs,   beatConfidence = confidence,
                musicStyle     = if (idx == 0) musicStyle else null,
                beatTimesMs    = g.annotatedBeats.map { it.timeMs }
            )
        }

        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // Section 변환: SectionGroup → V8Section
    // 이펙트는 모든 섹션 타입에 ON_PULSE 고정
    // ──────────────────────────────────────────────────────────────

    private fun convertToV8Sections(
        groups: List<SectionGroup>,
        beatMs: Long
    ): List<V8Section> {
        if (groups.isEmpty()) return emptyList()

        return groups.map { g ->
            val beats = g.annotatedBeats.size

            val normalizedType = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 6 ->
                    SectionDetector.SectionType.VERSE
                else -> g.type
            }

            val change = when {
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
                beats < 8 -> ChangeLevel.MEDIUM
                else      -> ChangeLevel.STRONG
            }

            V8Section(
                startMs     = g.startMs,      endMs       = g.endMs,
                type        = normalizedType,
                engine      = if (normalizedType == SectionDetector.SectionType.END ||
                                  normalizedType == SectionDetector.SectionType.OUTRO) FgEngine.OFF_TRANSIT
                              else FgEngine.ON_PULSE,
                beatMs      = beatMs,          beats       = beats,
                source      = "beat-on",       change      = change,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
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
        finalOffMs: Long,
        downbeatMs: Long = 0L,
        beatsPerBar: Int = 4
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        for ((index, section) in sections.withIndex()) {
            // END/OUTRO: 비트 이펙트 없음 (GEN에서 별도 처리 가능)
            if (section.engine == FgEngine.OFF_TRANSIT) continue

            val effectiveBeats = section.beatTimesMs

            Log.d(TAG, "v2 section[$index] ${section.type} ${section.startMs}~${section.endMs} " +
                "engine=${section.engine} beats=${effectiveBeats.size}")

            // 모든 섹션에 1/4박 White-C1-C2-C3 패턴 적용
            for (t in effectiveBeats) {
                val beatInBar = beatInBar(t, downbeatMs, globalBeatMs = section.beatMs, beatsPerBar)
                val (color, fade) = beatSectionColorAndFade(beatInBar, palette)
                put(t, LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
            }
        }

        // finalOffMs 이후 프레임 제거 (경계 포함) → finalOffMs 위치는 off 페이로드로 덮어씀
        frameMap.keys.filter { it >= finalOffMs }.forEach { frameMap.remove(it) }
        frameMap[finalOffMs] = buildOffPayload()

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
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
    // Silence detection
    // ──────────────────────────────────────────────────────────────

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

    private fun groupAnnotatedBeats(
        annotated: List<SectionDetector.AnnotatedBeat>,
        durationMs: Long
    ): List<SectionGroup> {
        if (annotated.isEmpty()) return emptyList()
        val groups = mutableListOf<SectionGroup>()
        var groupStart = 0
        for (i in 1..annotated.size) {
            val isLast = (i == annotated.size)
            if (isLast || annotated[i].sectionType != annotated[groupStart].sectionType) {
                val beats  = annotated.subList(groupStart, i)
                val startMs = beats.first().timeMs
                val endMs   = if (isLast) durationMs else annotated[i].timeMs
                groups += SectionGroup(startMs, endMs, beats.first().sectionType, beats)
                groupStart = i
            }
        }
        return groups
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
