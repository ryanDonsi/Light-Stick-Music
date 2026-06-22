package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AutoTimelineGeneratorBeat — 통합 이펙트 생성기
 *
 * AutoTimelineConfig의 설정에 따라 동작:
 *  - BEAT_DETECTOR_VERSION: 비트 감지기 선택
 *  - SECTION_DETECTOR_VERSION: 섹션 감지기 선택 (USE_SECTION_DETECTOR=true일 때만)
 *  - EFFECT_RULE_VERSION: 이펙트 생성 규칙
 *      0 = v0 (간단한 비트 ON/OFF, 섹션 없음)
 *      1 = v1/v2 (섹션+단순 비트)
 *      3 = v3 (V8 이펙트 규칙)
 *      6 = v6 (V8 확장)
 *  - USE_SECTION_DETECTOR: 섹션 감지 사용 여부 (0 제외)
 *
 * 이전: 각 버전별 파일 (v0, v1, v2, v3, v6)
 * 현재: 단일 클래스 + 설정 조합으로 모든 버전 지원
 */
class AutoTimelineGeneratorBeat : AutoTimelineGenerator, SectionAwareGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val MIN_BEAT_MS = AutoTimelineConfig.MIN_BEAT_MS
        private const val MAX_BEAT_MS = AutoTimelineConfig.MAX_BEAT_MS
        private const val MIN_TRAILING_SILENCE_MS = 1_500L

        private const val ON_TRANSIT = 2
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT

        // v0용 색상 세그먼트
        private const val COLOR_SEGMENT_MS_V0 = 5_000L
    }

    // ──────────────────────────────────────────────────────────────
    // V8 이펙트 타입 (v3/v6용)
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
    // 진입점
    // ──────────────────────────────────────────────────────────────

    override fun generate(musicPath: String, musicId: Int, paletteSize: Int): List<Pair<Long, ByteArray>> {
        val effectRuleVersion = AutoTimelineConfig.EFFECT_RULE_VERSION
        val useSectionDetector = AutoTimelineConfig.USE_SECTION_DETECTOR

        Log.d(TAG, "generate() effectRule=$effectRuleVersion useSection=$useSectionDetector")

        return when {
            effectRuleVersion == 0 -> generateV0(musicPath, musicId, paletteSize)
            useSectionDetector && (effectRuleVersion == 1 || effectRuleVersion == 3 || effectRuleVersion == 6) ->
                generateWithSections(musicPath, musicId, paletteSize).first
            else -> generateV0(musicPath, musicId, paletteSize)
        }
    }

    override fun generateWithSections(
        musicPath: String, musicId: Int, paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> {
        val effectRuleVersion = AutoTimelineConfig.EFFECT_RULE_VERSION

        return when (effectRuleVersion) {
            1, 2 -> generateV1(musicPath, musicId, paletteSize)
            3 -> generateV3(musicPath, musicId, paletteSize)
            6 -> generateV6(musicPath, musicId, paletteSize)
            else -> generateV1(musicPath, musicId, paletteSize)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // v0: 간단한 비트 ON/OFF (섹션 없음)
    // ──────────────────────────────────────────────────────────────

    private fun generateV0(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total = System.currentTimeMillis()
        Log.d(TAG, "v0 generate() start file=$fileName musicId=$musicId")

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        val beatInfo = BeatDetectorRouter.detect(
            filePath = musicPath,
            version = detectorVer,
            hopMs = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )

        val durationMs = beatInfo.envelopes?.let { it.full.size.toLong() * effectiveHopMs }
            ?: (beatInfo.beats.lastOrNull()?.timeMs?.plus(beatInfo.beatMs) ?: 0L)

        if (beatInfo.beats.isEmpty()) {
            Log.w(TAG, "v0 beat detect FAIL")
            return emptyList()
        }

        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar = beatInfo.beatsPerBar
        val downbeatMs = beatInfo.downbeatMs

        val frames = ArrayList<Pair<Long, ByteArray>>()
        for (beat in beatInfo.beats) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) continue

            val beatInBar = if (globalBeatMs > 0L) {
                val steps = Math.round((t - downbeatMs).toDouble() / globalBeatMs.toDouble())
                (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
            } else 0

            val color = when (beatInBar) {
                0 -> LSColor(255, 255, 255)  // White
                1 -> LSColor(255, 0, 255)    // Purple
                2 -> LSColor(255, 255, 0)    // Yellow
                else -> LSColor(0, 255, 255) // Cyan
            }
            val fade = when (beatInBar) {
                0, 2 -> 100
                else -> 35
            }

            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
        }

        Log.d(TAG, "v0 [PERF] total=${System.currentTimeMillis() - t0Total}ms frames=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // v1/v2: 섹션 감지 + 단순 비트 이펙트
    // ──────────────────────────────────────────────────────────────

    private fun generateV1(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total = System.currentTimeMillis()
        Log.d(TAG, "v1 [PERF] start file=$fileName musicId=$musicId")

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        val beatInfo = BeatDetectorRouter.detect(
            filePath = musicPath,
            version = detectorVer,
            hopMs = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )

        val envelopes = beatInfo.envelopes
        if (envelopes == null || envelopes.full.isEmpty()) {
            Log.w(TAG, "v1 env empty")
            return Pair(emptyList(), emptyList())
        }

        val lowEnv = envelopes.low
        val midEnv = envelopes.mid
        val fullEnv = envelopes.full
        val highEnv = envelopes.high

        val durationMs = fullEnv.size.toLong() * effectiveHopMs
        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar = beatInfo.beatsPerBar
        val downbeatMs = beatInfo.downbeatMs
        val beatTimesMs = beatInfo.beats.map { it.timeMs }.filter { it in 0..durationMs }

        if (beatTimesMs.isEmpty()) {
            Log.w(TAG, "v1 beat detect FAIL")
            return Pair(emptyList(), emptyList())
        }

        // 섹션 감지
        val finalOffMs = detectLastMusicEndMs(fullEnv.toFloatArray(), effectiveHopMs, MIN_TRAILING_SILENCE_MS)
            .coerceIn(0L, durationMs)

        val detectedAnnotated = SectionDetectorRouter.detect(
            version = AutoTimelineConfig.SECTION_DETECTOR_VERSION,
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beats = beatInfo.beats,
            beatMs = globalBeatMs, durationMs = durationMs, hopMs = effectiveHopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        ).map { ab ->
            if (ab.timeMs >= finalOffMs)
                SectionDetector.AnnotatedBeat(ab.timeMs, ab.confidence, SectionDetector.SectionType.END)
            else ab
        }

        val sectionGroups = groupAnnotatedBeats(detectedAnnotated, durationMs)

        // 비트 프레임 생성 (단순 ON/OFF)
        val frames = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip = 0

        for (beat in beatTimesMs) {
            val beatInBar = if (globalBeatMs > 0L) {
                val steps = Math.round((beat - downbeatMs).toDouble() / globalBeatMs.toDouble())
                (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
            } else 0

            val color = when (beatInBar) {
                0 -> LSColor(255, 255, 255)
                1 -> LSColor(255, 0, 255)
                2 -> LSColor(255, 255, 0)
                else -> LSColor(0, 255, 255)
            }
            val fade = when (beatInBar) {
                0, 2 -> 100
                else -> 35
            }

            frames.add(beat to LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
        }

        // 섹션 메타
        val sectionMetas = sectionGroups.mapIndexed { idx, g ->
            val confidence = if (g.annotatedBeats.isNotEmpty())
                g.annotatedBeats.map { it.confidence }.average().toFloat() else 0.20f
            val changeStrength = when {
                g.annotatedBeats.size < 8 -> SectionDetector.ChangeStrength.MEDIUM
                else -> SectionDetector.ChangeStrength.STRONG
            }
            SectionMeta(
                startMs = g.startMs, endMs = g.endMs,
                type = g.type, changeStrength = changeStrength,
                beatMs = globalBeatMs, beatConfidence = confidence,
                musicStyle = null,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }

        Log.d(TAG, "v1 [PERF] total=${System.currentTimeMillis() - t0Total}ms frames=${frames.size}")
        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // v3: 섹션 감지 + V8 이펙트 규칙
    // ──────────────────────────────────────────────────────────────

    private fun generateV3(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total = System.currentTimeMillis()
        Log.d(TAG, "v3 [PERF] start file=$fileName musicId=$musicId")

        val palette = buildPalette(musicId)

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        val beatInfo = BeatDetectorRouter.detect(
            filePath = musicPath,
            version = detectorVer,
            hopMs = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )

        val envelopes = beatInfo.envelopes
        if (envelopes == null || envelopes.full.isEmpty()) {
            Log.w(TAG, "v3 env empty")
            return Pair(emptyList(), emptyList())
        }

        val lowEnv = envelopes.low
        val midEnv = envelopes.mid
        val fullEnv = envelopes.full
        val highEnv = envelopes.high

        val durationMs = fullEnv.size.toLong() * effectiveHopMs
        val beatInfoBeats = beatInfo.beats
        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar = beatInfo.beatsPerBar
        val downbeatMs = beatInfo.downbeatMs
        val beatTimesMs = beatInfoBeats.map { it.timeMs }.filter { it in 0..durationMs }

        if (beatTimesMs.isEmpty()) {
            Log.w(TAG, "v3 beat detect FAIL")
            return Pair(emptyList(), emptyList())
        }

        // 섹션 감지 및 음악 스타일
        val finalOffMs = detectLastMusicEndMs(fullEnv.toFloatArray(), effectiveHopMs, MIN_TRAILING_SILENCE_MS)
            .coerceIn(0L, durationMs)

        val detectedAnnotated = SectionDetectorRouter.detect(
            version = AutoTimelineConfig.SECTION_DETECTOR_VERSION,
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beats = beatInfoBeats,
            beatMs = globalBeatMs, durationMs = durationMs, hopMs = effectiveHopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        ).map { ab ->
            if (ab.timeMs >= finalOffMs)
                SectionDetector.AnnotatedBeat(ab.timeMs, ab.confidence, SectionDetector.SectionType.END)
            else ab
        }

        val sectionGroups = groupAnnotatedBeats(detectedAnnotated, durationMs)

        val musicStyle = MusicStyleClassifier.classify(
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beatMs = globalBeatMs, beats = beatInfoBeats, hopMs = effectiveHopMs
        ).style

        val isBalladMode = musicStyle == MusicStyleClassifier.MusicStyle.BALLAD
                        || musicStyle == MusicStyleClassifier.MusicStyle.HIPHOP_RNB

        val v8Sections = convertToV8Sections(sectionGroups, globalBeatMs, isBalladMode,
            fullEnv = fullEnv, durationMs = durationMs, hopMs = effectiveHopMs)

        val frames = buildFramesFromSections(
            palette = palette,
            sections = v8Sections,
            beatTimesMs = beatTimesMs,
            durationMs = durationMs,
            isBalladMode = isBalladMode,
            finalOffMs = finalOffMs,
            downbeatMs = downbeatMs,
            beatsPerBar = beatsPerBar
        )

        val sectionMetas = sectionGroups.mapIndexed { idx, g ->
            val confidence = if (g.annotatedBeats.isNotEmpty())
                g.annotatedBeats.map { it.confidence }.average().toFloat() else 0.20f
            val changeStrength = when {
                g.annotatedBeats.size < 8 -> SectionDetector.ChangeStrength.MEDIUM
                else -> SectionDetector.ChangeStrength.STRONG
            }
            SectionMeta(
                startMs = g.startMs, endMs = g.endMs,
                type = g.type, changeStrength = changeStrength,
                beatMs = globalBeatMs, beatConfidence = confidence,
                musicStyle = if (idx == 0) musicStyle else null,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }

        Log.d(TAG, "v3 [PERF] total=${System.currentTimeMillis() - t0Total}ms frames=${frames.size}")
        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // v6: v3과 유사하지만 V8 확장 이펙트 (현재는 v3과 동일)
    // ──────────────────────────────────────────────────────────────

    private fun generateV6(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> =
        generateV3(musicPath, musicId, paletteSize)

    // ──────────────────────────────────────────────────────────────
    // V8 섹션 변환 (v3/v6 공용)
    // ──────────────────────────────────────────────────────────────

    private fun convertToV8Sections(
        groups: List<SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<V8Section> {
        if (groups.isEmpty()) return emptyList()

        val energies = groups.map { g -> computeGroupEnergy(g.startMs, g.endMs, fullEnv, durationMs, hopMs) }
        val lowTh = percentile(energies, 0.35f)
        val highTh = percentile(energies, 0.70f)
        val range = (highTh - lowTh).coerceAtLeast(1e-6f)

        return groups.mapIndexed { i, g ->
            val energy = energies[i]
            val beats = g.annotatedBeats.size
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
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
                beats < 8 -> ChangeLevel.MEDIUM
                else -> ChangeLevel.STRONG
            }

            V8Section(
                startMs = g.startMs, endMs = g.endMs,
                type = normalizedType, engine = engine,
                beatMs = beatMs, beats = beats,
                source = source, change = change,
                energyScore = energy, relScore = relScore,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }
    }

    private fun computeGroupEnergy(startMs: Long, endMs: Long, fullEnv: List<Float>, durationMs: Long, hopMs: Long): Float {
        if (fullEnv.isEmpty()) return 0f
        val startIdx = (startMs / hopMs).toInt().coerceIn(0, fullEnv.lastIndex)
        val endIdx = (endMs / hopMs).toInt().coerceAtMost(fullEnv.lastIndex)
        if (endIdx <= startIdx) return fullEnv.getOrElse(startIdx) { 0f }
        return fullEnv.subList(startIdx, endIdx + 1).average().toFloat()
    }

    private fun assignFgEngine(
        type: SectionDetector.SectionType,
        rel: Float, beats: Int, globalBeatMs: Long,
        isBalladMode: Boolean
    ): FgEngine = when (type) {
        SectionDetector.SectionType.INTRO -> FgEngine.BREATH
        SectionDetector.SectionType.OUTRO -> FgEngine.OFF_TRANSIT
        SectionDetector.SectionType.BREAK -> FgEngine.BREATH

        SectionDetector.SectionType.CLIMAX -> when {
            globalBeatMs <= 300L -> FgEngine.STROBE
            rel >= 0.60f -> FgEngine.STROBE
            else -> FgEngine.ON_TRANSIT_ROTATE
        }
        SectionDetector.SectionType.BUILD -> FgEngine.ON_TRANSIT_ROTATE

        SectionDetector.SectionType.BEAT -> when {
            isBalladMode -> FgEngine.BREATH
            globalBeatMs <= 350L -> FgEngine.BLINK
            else -> FgEngine.ON_PULSE
        }
        SectionDetector.SectionType.VOCAL -> when {
            isBalladMode -> FgEngine.BREATH
            rel >= 0.55f -> FgEngine.ON_PULSE
            else -> FgEngine.BREATH
        }

        SectionDetector.SectionType.VERSE -> if (isBalladMode) FgEngine.BREATH else FgEngine.ON_PULSE
        SectionDetector.SectionType.CHORUS -> FgEngine.ON_TRANSIT_ROTATE
        SectionDetector.SectionType.BRIDGE -> FgEngine.BREATH
        SectionDetector.SectionType.END -> FgEngine.OFF_TRANSIT
    }

    private fun buildSourceName(type: SectionDetector.SectionType, engine: FgEngine, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.INTRO -> "intro-breath"
            SectionDetector.SectionType.OUTRO -> "outro-off"
            SectionDetector.SectionType.BREAK -> "break-breath"
            SectionDetector.SectionType.CLIMAX -> if (engine == FgEngine.STROBE) "climax-strobe" else "climax-rotate"
            SectionDetector.SectionType.BUILD -> "build-rotate"
            SectionDetector.SectionType.BEAT -> when (engine) {
                FgEngine.BLINK -> "beat-blink"
                else -> "beat-pulse"
            }
            SectionDetector.SectionType.VOCAL -> if (engine == FgEngine.BREATH) "vocal-breath" else "vocal-pulse"
            SectionDetector.SectionType.VERSE -> "verse-on-pulse"
            SectionDetector.SectionType.CHORUS -> "chorus-rotate"
            SectionDetector.SectionType.BRIDGE -> "bridge-breath"
            SectionDetector.SectionType.END -> "end-off"
        }

    // ──────────────────────────────────────────────────────────────
    // 프레임 빌딩 (v3/v6 공용)
    // ──────────────────────────────────────────────────────────────

    private fun buildFramesFromSections(
        palette: Palette,
        sections: List<V8Section>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long = 0L,
        beatsPerBar: Int = 4
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        data class RepeatKey(
            val engine: FgEngine,
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

            if (section.type == SectionDetector.SectionType.BEAT) {
                for (t in effectiveBeats) {
                    val beatInBar = beatInBar(t, downbeatMs, globalBeatMs = section.beatMs, beatsPerBar)
                    val (color, fade) = beatSectionColorAndFade(beatInBar, palette)
                    put(t, LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
                }
                continue
            }

            if (section.engine == FgEngine.OFF_TRANSIT) continue

            for ((beatIndex, t) in effectiveBeats.withIndex()) {
                val beatEngine = if (section.type == SectionDetector.SectionType.BRIDGE)
                    bridgePhaseEngine(beatIndex, effectiveBeats.size, section.beatMs, section.relScore, isBalladMode)
                else section.engine

                val effectiveEngine = beatEngine

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, sameTypeIdx, beatIndex, section.type)
                val bgNonNull = bg ?: LSColor(0, 0, 0)

                val beatPeriod = when (effectiveEngine) {
                    FgEngine.STROBE -> 1
                    FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == FgEngine.STROBE -> 1
                    effectiveEngine == FgEngine.ON_TRANSIT_ROTATE -> null
                    effectiveEngine == FgEngine.ON_PULSE -> null
                    effectiveEngine == FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.VERSE -> 0
                    effectiveEngine == FgEngine.BREATH -> msToBreathRandomDelay(section.beatMs)
                    else -> null
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
                    val offT = minOf(section.endMs - 1L, t + holdMs)
                    if (offT > t)
                        put(offT, LSEffectPayload.Effects.off(transit = 3).toByteArray())
                }
            }
        }

        frameMap.keys.filter { it >= finalOffMs }.forEach { frameMap.remove(it) }
        frameMap[finalOffMs] = buildOffPayload()

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

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

    private fun bridgePhaseEngine(
        beatIndex: Int, totalBeats: Int, beatMs: Long, relScore: Float, isBalladMode: Boolean
    ): FgEngine {
        if (isBalladMode || relScore < 0.1f) return FgEngine.BREATH
        if (totalBeats <= 0) return FgEngine.STROBE
        val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)
        return when {
            totalBeats < 8 -> FgEngine.STROBE
            totalBeats < 16 -> {
                val phase = beatIndex.toFloat() / totalBeats
                if (phase < strobeEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
            else -> {
                val phase = beatIndex.toFloat() / totalBeats
                val rotateEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
                if (phase < rotateEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 팔레트 및 색상
    // ──────────────────────────────────────────────────────────────

    private fun buildPalette(seed: Int): Palette {
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
        return Palette(
            black = black, white = white,
            onPulseSets = listOf(ColorSet(white, patternABg), ColorSet(cMain, black)),
            blinkSets = listOf(ColorSet(cMain, black), ColorSet(cStep1, black)),
            strokeSets = listOf(ColorSet(white, black)),
            breathSet = ColorSet(white, patternABg),
            bridgeSets = listOf(ColorSet(cStep2, black), ColorSet(cMain, black)),
            chorusBg = cDeep, colorGroup = colorGroup
        )
    }

    private fun colorsForEngine(
        palette: Palette, engine: FgEngine, sectionIndex: Int,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE
    ): Pair<LSColor, LSColor> {
        val isPatternA = (sectionIndex % 2 == 0)
        val effectiveColors: List<LSColor> = when (sectionType) {
            SectionDetector.SectionType.CHORUS -> listOf(palette.white) + palette.colorGroup.take(3)
            SectionDetector.SectionType.VERSE -> palette.colorGroup.take(3)
            SectionDetector.SectionType.BRIDGE -> listOf(
                palette.colorGroup.getOrElse(2) { palette.colorGroup[0] },
                palette.colorGroup[0], palette.white
            )
            else -> palette.colorGroup
        }
        val groupColor = effectiveColors[beatIndex % effectiveColors.size]
        val sectionColor = effectiveColors[sectionIndex % effectiveColors.size]
        return when (engine) {
            FgEngine.ON_PULSE ->
                if (isPatternA) palette.white to palette.onPulseSets[0].bg
                else sectionColor to palette.black
            FgEngine.BLINK, FgEngine.ON_TRANSIT_ROTATE -> groupColor to palette.black
            FgEngine.STROBE -> palette.white to palette.black
            FgEngine.BREATH -> palette.breathSet.fg to palette.breathSet.bg
            else ->
                if (isPatternA) palette.bridgeSets[0].fg to palette.black
                else groupColor to palette.black
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 유틸리티
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
                val beats = annotated.subList(groupStart, i)
                val startMs = beats.first().timeMs
                val endMs = if (isLast) durationMs else annotated[i].timeMs
                groups += SectionGroup(startMs, endMs, beats.first().sectionType, beats)
                groupStart = i
            }
        }
        return groups
    }

    private fun msToBlinkPeriod(beatMs: Long) = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToStrobePeriod(beatMs: Long) = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long) = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long) = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f

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
}
