package com.lightstick.music.domain.music

/**
 * SectionDetector 버전 라우터.
 * AutoTimelineConfig.SECTION_DETECTOR_VERSION 값에 따라 감지기를 선택한다.
 *
 * 버전별 파일 대응:
 *  0 : SectionDetectorV0 (슬라이딩 윈도우 + 비트 경계 정렬, STRIDE=1000ms, per-window autocorr)
 *  1 : SectionDetectorV1 (속도 최적화: STRIDE=2000ms, global periodicity, single-pass feature)
 *  2 : SectionDetectorV2
 */
object SectionDetectorRouter {

    fun detect(
        version: Int,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        highEnv: List<Float>,
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        durationMs: Long,
        hopMs: Long,
        beatsPerBar: Int = 4,
        downbeatMs: Long = 0L
    ): List<SectionDetector.AnnotatedBeat> = when (version) {
        0    -> SectionDetectorV0().detect(
            lowEnv     = lowEnv, midEnv     = midEnv,
            fullEnv    = fullEnv, highEnv   = highEnv,
            beats      = beats,  beatMs     = beatMs,
            durationMs = durationMs,  hopMs = hopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        )
        2    -> SectionDetectorV2().detect(
            lowEnv     = lowEnv, midEnv     = midEnv,
            fullEnv    = fullEnv, highEnv   = highEnv,
            beats      = beats,  beatMs     = beatMs,
            durationMs = durationMs,  hopMs = hopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        )
        else -> SectionDetectorV1().detect(
            lowEnv     = lowEnv, midEnv     = midEnv,
            fullEnv    = fullEnv, highEnv   = highEnv,
            beats      = beats,  beatMs     = beatMs,
            durationMs = durationMs,  hopMs = hopMs,
            beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        )
    }
}
