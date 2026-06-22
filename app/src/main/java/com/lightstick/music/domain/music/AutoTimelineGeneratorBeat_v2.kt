package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log

/**
 * AutoTimelineGeneratorBeat v2
 *
 * 감지기: BeatDetector(BEAT_DETECTOR_VERSION) + SectionDetector(SECTION_DETECTOR_VERSION)
 * 이펙트: EffectMatchingEngineV2 사용 (FgEngine 기반 — ON_PULSE / STROBE / BREATH / ON_TRANSIT_ROTATE 등)
 */
class AutoTimelineGeneratorBeat_v2 : AutoTimelineGenerator, SectionAwareGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val HOP_MS      = 50L
        private val MIN_BEAT_MS = AutoTimelineConfig.MIN_BEAT_MS
        private val MAX_BEAT_MS = AutoTimelineConfig.MAX_BEAT_MS

        private const val MIN_TRAILING_SILENCE_MS   = 1_500L

        // IIR 필터 계수
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
        private const val HIGH_ALPHA    = 0.40f
    }

    private val effectEngine = EffectMatchingEngineV2()

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

        // ── 4. Music style + ballad mode ──────────────────────────
        val musicStyle = MusicStyleClassifier.classify(
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beatMs = globalBeatMs, beats = beatInfoBeats, hopMs = effectiveHopMs
        ).style
        val isBalladMode = musicStyle == MusicStyleClassifier.MusicStyle.BALLAD
                        || musicStyle == MusicStyleClassifier.MusicStyle.HIPHOP_RNB
        Log.d(TAG, "v2 style=$musicStyle balladMode=$isBalladMode")

        // ── 5. Convert → V8Section via EffectMatchingEngineV2 ─────
        val palette2 = effectEngine.buildPalette(musicId)
        val v8Sections = effectEngine.convertToV8Sections(
            groups = sectionGroups, beatMs = globalBeatMs, isBalladMode = isBalladMode,
            fullEnv = fullEnv, durationMs = durationMs, hopMs = effectiveHopMs
        )

        // ── 6. Frame building via EffectMatchingEngineV2 ──────────
        val t0Build    = System.currentTimeMillis()

        val frames = effectEngine.buildFramesFromSections(
            palette         = palette2,
            sections        = v8Sections,
            beatTimesMs     = beatTimesMs,
            durationMs      = durationMs,
            isBalladMode    = isBalladMode,
            finalOffMs      = finalOffMs,
            downbeatMs      = downbeatMs,
            beatsPerBar     = beatsPerBar
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
    // Utility
    // ──────────────────────────────────────────────────────────────

    private fun groupAnnotatedBeats(
        annotated: List<SectionDetector.AnnotatedBeat>,
        durationMs: Long
    ): List<EffectMatchingEngine.SectionGroup> {
        if (annotated.isEmpty()) return emptyList()
        val groups = mutableListOf<EffectMatchingEngine.SectionGroup>()
        var groupStart = 0
        for (i in 1..annotated.size) {
            val isLast = (i == annotated.size)
            if (isLast || annotated[i].sectionType != annotated[groupStart].sectionType) {
                val beats  = annotated.subList(groupStart, i)
                val startMs = beats.first().timeMs
                val endMs   = if (isLast) durationMs else annotated[i].timeMs
                groups += EffectMatchingEngine.SectionGroup(startMs, endMs, beats.first().sectionType, beats)
                groupStart = i
            }
        }
        return groups
    }
}
