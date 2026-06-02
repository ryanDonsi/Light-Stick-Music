package com.lightstick.music.domain.music

/**
 * BeatDetector 버전 라우터.
 *
 * AutoTimelineConfig.BEAT_DETECTOR_VERSION 값에 따라 감지기를 선택하고
 * 결과를 [BeatInfo] 로 정규화한다.
 *
 * 버전별 지원 필드:
 *  V8, V9  — beatTimesMs / beatMs 만 존재. downbeatMs·beatsPerBar 는 기본값(4/4) 사용.
 *  V10     — beats(TimedBeat) + downbeatOffsetMs + timeSignature 포함.
 *  V11(V2) — V10 과 동일 구조 + debugSegments.
 */
object BeatDetectorRouter {

    /** 생성기에서 공통으로 사용하는 정규화 결과 */
    data class BeatInfo(
        val beats: List<Beat>,
        val beatMs: Long,
        val beatsPerBar: Int,
        val downbeatMs: Long
    ) {
        data class Beat(val timeMs: Long, val confidence: Float)
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }
    }

    fun detect(
        version: Int,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo = when (version) {

        8 -> {
            val r = BeatDetectorV8.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV8.Params(hopMs = hopMs, minBeatMs = minBeatMs, maxBeatMs = maxBeatMs))
            BeatInfo(
                beats       = r.beatTimesMs.map { BeatInfo.Beat(it, 1f) },
                beatMs      = r.beatMs,
                beatsPerBar = 4,
                downbeatMs  = r.beatTimesMs.firstOrNull() ?: 0L
            )
        }

        9 -> {
            val r = BeatDetectorV9.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV9.Params(hopMs = hopMs, minBeatMs = minBeatMs, maxBeatMs = maxBeatMs))
            BeatInfo(
                beats       = r.beatTimesMs.map { BeatInfo.Beat(it, 1f) },
                beatMs      = r.beatMs,
                beatsPerBar = 4,
                downbeatMs  = r.beatTimesMs.firstOrNull() ?: 0L
            )
        }

        10 -> {
            val r = BeatDetectorV10.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV10.Params(hopMs = hopMs, minBeatMs = minBeatMs, maxBeatMs = maxBeatMs))
            BeatInfo(
                beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        }

        else -> { // 11 (BeatDetectorV2 = V11)
            val r = BeatDetectorV2.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV2.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs,
                    maxBeatMs         = maxBeatMs,
                    minPeakDistanceMs = 140L,
                    onsetSmoothWindow = 3,
                    peakThresholdK    = 0.28f,
                    minPeakAbs        = 0.07f,
                    snapToleranceMs   = 130L,
                    chainToleranceMs  = 150L,
                    minChainCount     = 3,
                    continuityBonus   = 0.08f
                ))
            BeatInfo(
                beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        }
    }
}
