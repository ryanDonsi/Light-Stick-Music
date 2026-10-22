package com.lightstick.music.domain.music.sectiondetector

import com.lightstick.music.domain.music.beatdetector.BeatDetectorRouter

interface SectionDetector {

    // 섹션 타입. ordinal이 바이너리로 직렬화되므로 순서 변경 시 AutoTimelineConfig.VERSION을 올려야 한다.
    enum class SectionType { INTRO, VERSE, CHORUS, BRIDGE, END, CLIMAX, BREAK, OUTRO }
    enum class ChangeStrength { NONE, MEDIUM, STRONG }

    /**
     * SectionDetector의 1차 반환 타입.
     * BeatDetector 비트에 섹션 분석 정보(sectionType)와 비트 순간의 로컬 특성값을 추가한 형태.
     * section으로 시작하는 필드는 이 비트가 속한 구간 전체의 집계값이다.
     */
    data class AnnotatedBeat(
        val timeMs:      Long,
        val confidence:  Float,
        val sectionType: SectionType,
        val localEnergy:   Float = 0f,
        val onsetStrength: Float = 0f,
        val lowRatio:      Float = 0f,
        val highRatio:     Float = 0f,
        val beatInBar:     Int   = 0,
        val sectionEnergy:       Float = 0f,
        val sectionPeakEnergy:   Float = 0f,
        val sectionLowRatio:     Float = 0f,
        val sectionMidRatio:     Float = 0f,
        val sectionHighRatio:    Float = 0f,
        val sectionOnsetDensity: Float = 0f,
        val sectionPeriodicity:  Float = 0f
    )

    /** 구간 특성값 보유 클래스 — SectionMeta/SectionMetaStorage 에서 사용. */
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
