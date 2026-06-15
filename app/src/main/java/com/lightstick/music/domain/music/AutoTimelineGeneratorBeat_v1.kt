package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

/**
 * AutoTimelineGeneratorBeat_v1 — BeatDetectorV1(PCM) + Brightness(fade=100) 2:8 비율
 *
 * BeatDetectorV1(PCM 기반 FFT/IIR) 고정 사용.
 * 비트마다 ON(fade=100) → beatMs×0.2 후 ON(fade=0) 으로 소등 (2:8 비율).
 */
class AutoTimelineGeneratorBeat_v1 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION     = 1
        private const val MIN_BEAT_MS = 320L
        private const val MAX_BEAT_MS = 1200L

        private const val DETECTOR_VER = 1  // BeatDetectorV1 고정
        private const val HOP_MS       = 20L
    }

    // ──────────────────────────────────────────────────────────────
    // generate
    // ──────────────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total  = System.currentTimeMillis()
        Log.d(TAG, "v1 [PERF] generate() start file=$fileName musicId=$musicId")

        // ── 1+2. PCM decode + Beat detection (단일 pass) ─────────────
        val t0Decode = System.currentTimeMillis()
        val beatInfo = BeatDetectorRouter.detect(
            filePath  = musicPath,
            version   = DETECTOR_VER,
            hopMs     = HOP_MS,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )
        val durationMs = beatInfo.envelopes?.let { it.full.size.toLong() * HOP_MS }
            ?: (beatInfo.beats.lastOrNull()?.timeMs?.plus(beatInfo.beatMs) ?: 0L)
        Log.d(TAG, "v1 [PERF] decode+beat=${System.currentTimeMillis() - t0Decode}ms beatMs=${beatInfo.beatMs} beats=${beatInfo.beats.size} beatsPerBar=${beatInfo.beatsPerBar}")

        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = beatInfo.beatsPerBar

        if (beatInfo.beats.isNotEmpty()) {
            val first = beatInfo.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = beatInfo.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v1 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        if (beatInfo.beats.isNotEmpty()) {
            val synth  = beatInfo.beats.count { it.confidence <= 0.20f }
            val real   = beatInfo.beats.size - synth
            val sPct   = synth * 100 / beatInfo.beats.size
            Log.d(TAG, "v1 [A] quality[$fileName]: real=$real synth=$synth(${sPct}%) total=${beatInfo.beats.size}")

            val gapTh   = globalBeatMs * 3
            val bigGaps = (1 until beatInfo.beats.size).mapNotNull { i ->
                val gap = beatInfo.beats[i].timeMs - beatInfo.beats[i - 1].timeMs
                if (gap >= gapTh) "${beatInfo.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v1 [A] gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v1 [A] gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        // ── 3. 타임라인 빌드 ──────────────────────────────────────────
        val t0Build = System.currentTimeMillis()
        val frames = buildTimeline(beatInfo.beats, globalBeatMs, beatsPerBar, durationMs)
        Log.d(TAG, "v1 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v1 [PERF] total=${System.currentTimeMillis() - t0Total}ms  file=$fileName durationMs=$durationMs")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — 1/4박자마다 ON(fade=100) → beatMs×0.2 후 ON(동일 컬러, fade=60) [2:8 비율]
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        beatsPerBar: Int,
        durationMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip = 0

        // ON(fade=100) → beatMs×0.2 후 동일 컬러 fade=60 으로 감쇄 (2:8 비율)
        val dimDelayMs = (beatMs * 0.2).toLong().coerceAtLeast(20L)

        for ((beatIndex, beat) in beats.withIndex()) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) { rangeSkip++; continue }

            val color = when (beatIndex % beatsPerBar) {
                0    -> LSColor(255, 255, 255)  // White
                1    -> LSColor(255, 0,   255)  // Purple
                2    -> LSColor(255, 255, 0)    // Yellow
                else -> LSColor(0,   255, 255)  // Cyan
            }
            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0, fade = 100).toByteArray())

            val dimT = t + dimDelayMs
            if (dimT < durationMs) {
                frames.add(dimT to LSEffectPayload.Effects.on(color = color, transit = 0, fade = 60).toByteArray())
            }
        }

        Log.d(TAG, "v1 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size} dimDelayMs=$dimDelayMs")
        return frames.sortedBy { it.first }
    }

    fun getVersion(): Int = VERSION
}
