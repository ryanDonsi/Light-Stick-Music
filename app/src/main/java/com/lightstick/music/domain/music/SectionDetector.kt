package com.lightstick.music.domain.music

interface SectionDetector {

    // V1 타입 (legacy — 순서 변경 금지, SectionMetaStorage ordinal 의존)
    // V2 타입 (V2 이후)
    enum class SectionType { INTRO, VERSE, CHORUS, BRIDGE, END, VOCAL, BEAT, BUILD, CLIMAX, BREAK, OUTRO }
    enum class ChangeStrength { NONE, MEDIUM, STRONG }

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val changeStrength: ChangeStrength,
        val beatTimesMs: LongArray,
        val beatMs: Long,
        val beatConfidence: Float,
        // 구간 특성값 — SectionDetectorV1 의 FeatureWindow 에서 끌어올린 값
        val energy: Float       = 0f,  // 평균 전체 에너지 (0~1)
        val peakEnergy: Float   = 0f,  // 구간 내 최대 에너지 (0~1)
        val lowRatio: Float     = 0f,  // 저역(~897Hz) / 전체 에너지 비율
        val midRatio: Float     = 0f,  // 중역(585~3020Hz) / 전체 에너지 비율
        val highRatio: Float    = 0f,  // 고역(3.6kHz↑) / 전체 에너지 비율 (여성보컬 존재감)
        val onsetDensity: Float = 0f,  // 비트 밀집도 (0~1, novelty > 0.12 비율)
        val periodicity: Float  = 0f   // 주기성 강도 (0~1)
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
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        durationMs: Long,
        hopMs: Long = 50L,
        highEnv: List<Float> = emptyList()
    ): List<Section>
}
