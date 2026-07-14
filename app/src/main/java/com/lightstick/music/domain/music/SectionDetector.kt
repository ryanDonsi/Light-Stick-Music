package com.lightstick.music.domain.music

interface SectionDetector {

    // SectionMetaStorage/AutoTimelineStorage는 type.ordinal을 정수로 직렬화하고,
    // 저장된 파일의 version 필드가 AutoTimelineConfig.VERSION과 다르면 그냥 버리고
    // 새로 생성한다(SectionMetaStorage.load/AutoTimelineStorage.load의 `v != version`
    // 체크). 그래서 이 enum 순서를 바꿀 때는 반드시 AutoTimelineConfig.VERSION을
    // 올려서 예전 ordinal로 저장된 파일이 새 순서로 잘못 해석되지 않도록 한다.
    // VOCAL/BEAT/BUILD는 어떤 SectionDetector도 실제로 생성하지 않는 죽은 타입이라
    // 제거했다 (2026-07: VERSION 0 → 1).
    enum class SectionType { INTRO, VERSE, CHORUS, BRIDGE, END, CLIMAX, BREAK, OUTRO, INST, SOLO }
    enum class ChangeStrength { NONE, MEDIUM, STRONG }

    /**
     * SectionDetector의 1차 반환 타입.
     * BeatDetector 비트에 섹션 분석 정보(sectionType)만 추가한 형태.
     */
    data class AnnotatedBeat(
        val timeMs:      Long,
        val confidence:  Float,
        val sectionType: SectionType
    )

    /**
     * 내부 구간 특성값 보유용 — SectionMeta/SectionMetaStorage 에서 사용.
     * detect() 반환 타입은 AnnotatedBeat 로 변경되었으나,
     * 에너지·비율 등 구간 특성값이 필요한 컨텍스트에서는 이 클래스를 내부적으로 유지한다.
     */
    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val changeStrength: ChangeStrength,
        // 구간 특성값
        val energy: Float       = 0f,
        val peakEnergy: Float   = 0f,
        val lowRatio: Float     = 0f,
        val midRatio: Float     = 0f,
        val highRatio: Float    = 0f,
        val onsetDensity: Float = 0f,
        val periodicity: Float  = 0f
    )

    /**
     * 오디오 엔벨로프와 BeatDetector 결과를 받아
     * 각 비트에 섹션 타입이 태깅된 AnnotatedBeat 목록을 반환한다.
     *
     * @param beats    BeatDetector가 반환한 Beat 목록 — 이 순서 그대로 반환됨
     * @return beats 와 1:1 대응하는 AnnotatedBeat 목록
     */
    fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        durationMs: Long,
        hopMs: Long = 50L,
        highEnv: List<Float> = emptyList(),
        beatsPerBar: Int = 4,
        downbeatMs: Long = 0L
    ): List<AnnotatedBeat>
}
