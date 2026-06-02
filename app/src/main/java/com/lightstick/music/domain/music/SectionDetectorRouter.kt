package com.lightstick.music.domain.music

/**
 * SectionDetector 버전 라우터.
 * AutoTimelineConfig.SECTION_DETECTOR_VERSION 값에 따라 감지기를 선택한다.
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
        hopMs: Long
    ): List<SectionDetector.Section> = when (version) {
        2    -> SectionDetectorV2().detect(
            lowEnv     = lowEnv, midEnv     = midEnv,
            fullEnv    = fullEnv, highEnv   = highEnv,
            beats      = beats,  beatMs     = beatMs,
            durationMs = durationMs,  hopMs = hopMs
        )
        else -> SectionDetectorV1().detect(
            lowEnv     = lowEnv, midEnv     = midEnv,
            fullEnv    = fullEnv, highEnv   = highEnv,
            beats      = beats,  beatMs     = beatMs,
            durationMs = durationMs,  hopMs = hopMs
        )
    }
}
