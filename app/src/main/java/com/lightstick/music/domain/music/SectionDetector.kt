package com.lightstick.music.domain.music

interface SectionDetector {

    enum class SectionType { INTRO, VERSE, CHORUS, BRIDGE, END }
    enum class ChangeStrength { NONE, MEDIUM, STRONG }

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val changeStrength: ChangeStrength,
        val beatTimesMs: LongArray,
        val beatMs: Long,
        val beatConfidence: Float
    )

    /**
     * 오디오 엔벨로프와 BeatDetector 결과를 받아 섹션 목록을 반환한다.
     *
     * @param lowEnv   저역 엔벨로프 (HOP_MS 단위 프레임)
     * @param midEnv   중역 엔벨로프
     * @param fullEnv  전역 엔벨로프
     * @param beats    BeatDetector가 반환한 TimedBeat 목록 (갭 채움 포함)
     * @param beatMs   전곡 글로벌 BPM (ms)
     * @param durationMs 전곡 길이 (ms)
     * @param hopMs    엔벨로프 프레임 간격 (기본 50ms)
     * @return 구간별 Section 목록 (시간 순, 전체 길이 커버)
     */
    fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        beats: List<BeatDetectorV11.TimedBeat>,
        beatMs: Long,
        durationMs: Long,
        hopMs: Long = 50L
    ): List<Section>
}
