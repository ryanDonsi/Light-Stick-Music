package com.lightstick.music.domain.music

/**
 * BeatDetector 버전 라우터.
 *
 * AutoTimelineConfig.BEAT_DETECTOR_VERSION 값에 따라 감지기를 선택하고
 * 결과를 [BeatInfo] 로 정규화한다.
 *
 * 버전별 파일 대응:
 *  1 : BeatDetectorV1 (Autocorrelation + log-normal prior + half-tempo check)
 *  2 : BeatDetectorV2 = V11
 *  3 : BeatDetectorV3 = V12 (Fix 1~5)
 *  4 : BeatDetectorV4 = V13 (Fix A~E)
 *  5 : BeatDetectorV5 = V14 (DBN HMM)
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

    // =========================================================================
    // PCM 입력 오버로드 — version 1: V1(IIR 엔벨로프 변환 후 detect), version 2: V2(madmom SuperFlux)
    // version 3~5: envelope 입력 detect() 사용
    // =========================================================================

    fun detectPcm(
        version: Int,
        monoSamples: FloatArray,
        sampleRate: Int,
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo = when (version) {

        1 -> {
            val r = BeatDetectorV1.detectPcm(monoSamples, sampleRate,
                BeatDetectorV1.Params(
                    hopMs     = 50L,
                    minBeatMs = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs = maxBeatMs.coerceAtMost(1000L)
                ))
            BeatInfo(
                beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        }

        else -> { // 2: BeatDetectorV2 (madmom SuperFlux)
            val r = BeatDetectorV2.detect(monoSamples, sampleRate,
                BeatDetectorV2.Params(
                    minBeatMs = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs = maxBeatMs.coerceAtMost(1000L)
                ))
            BeatInfo(
                beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        }
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

        1 -> {
            val r = BeatDetectorV1.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV1.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
                    minPeakDistanceMs = 120L,
                    onsetSmoothWindow = 3
                ))
            BeatInfo(
                beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        }

        3 -> {
            val r = BeatDetectorV3.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV3.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs.coerceAtLeast(430L),
                    maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
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

        4 -> {
            val r = BeatDetectorV4.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV4.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
                    minPeakDistanceMs = 120L,
                    onsetSmoothWindow = 3,
                    peakThresholdK    = 0.22f,
                    minPeakAbs        = 0.04f,
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

        else -> { // 5: BeatDetectorV5 (DBN HMM)
            val r = BeatDetectorV5.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV5.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
                    minPeakDistanceMs = 120L,
                    onsetSmoothWindow = 3
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
