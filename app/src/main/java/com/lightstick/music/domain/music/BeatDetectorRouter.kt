package com.lightstick.music.domain.music

/**
 * BeatDetector 버전 라우터.
 *
 * AutoTimelineConfig.BEAT_DETECTOR_VERSION 값에 따라 감지기를 선택하고
 * 결과를 [BeatInfo] 로 정규화한다.
 *
 * 버전별 파일 대응:
 *  1 : BeatDetectorV1 (IIR 3밴드 ODF + Autocorrelation + log-normal prior, PCM 입력, hopMs=10ms)
 *  2 : BeatDetectorV2 (SuperFlux ODF + DBN HMM, 스트리밍, hopMs=10ms)
 *  0 : BeatDetectorV0 (IIR 3밴드 ODF + Autocorrelation, 엔벨로프 입력, hopMs=50ms)
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
    // PCM 입력 오버로드 — version 1 전용 (V1: IIR 엔벨로프 변환 후 detect)
    // version 2: detectFile() 사용 (스트리밍 ODF, PCM 불필요)
    // version 5: envelope 입력 detect() 사용 (BeatDetectorV0)
    // =========================================================================

    fun detectPcm(
        monoSamples: FloatArray,
        sampleRate: Int,
        minBeatMs: Long,
        maxBeatMs: Long,
        hopMs: Long = 50L
    ): BeatInfo {
        val r = BeatDetectorV1.detectPcm(monoSamples, sampleRate,
            BeatDetectorV1.Params(
                hopMs     = hopMs,
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L)
            ))
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
        )
    }

    // =========================================================================
    // 파일 경로 입력 오버로드 — version 2: V2(madmom 스트리밍 SuperFlux + DBN)
    // =========================================================================

    fun detectFile(
        musicPath: String,
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo {
        val r = BeatDetectorV2.detect(musicPath,
            BeatDetectorV2.Params(
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L)
            ))
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
        )
    }

    fun detect(
        version: Int,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo =
        // version 1: envelope 경로 (detectPcm이 기본이지만 envelope detect도 지원)
        // version 0(else): BeatDetectorV0
        if (version == 1) {
            val r = BeatDetectorV1.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV1.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
                    minPeakDistanceMs = 120L,
                    onsetSmoothWindow = 3
                ))
            BeatInfo(
                beats       = r.beats.map { b -> BeatInfo.Beat(b.timeMs, b.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        } else {
            val r = BeatDetectorV0.detect(lowEnv, midEnv, fullEnv,
                BeatDetectorV0.Params(
                    hopMs             = hopMs,
                    minBeatMs         = minBeatMs.coerceAtLeast(375L),
                    maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
                    minPeakDistanceMs = 120L,
                    onsetSmoothWindow = 3
                ))
            BeatInfo(
                beats       = r.beats.map { b -> BeatInfo.Beat(b.timeMs, b.confidence) },
                beatMs      = r.beatMs,
                beatsPerBar = r.timeSignature.beatsPerBar,
                downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
            )
        }
}
