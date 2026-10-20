package com.lightstick.music.domain.music

/**
 * AutoTimelineGeneratorBeat 설정 관리.
 * 비트 감지기 / 섹션 감지기 / 이펙트 생성 규칙 세 엔진의 버전을 조합해 지정한다.
 */
object AutoTimelineConfig {

    /** 파일 캐시 버전 — 생성기·감지기 조합이 바뀌면 올려서 기존 캐시를 무효화한다. */
    const val VERSION = 1

    // =========================================================================
    // BEAT_DETECTOR: 비트 감지 엔진 버전
    // =========================================================================

    /** 비트 감지기 버전 — BeatDetectorRouter 를 통해 적용된다. */
    const val BEAT_DETECTOR_VERSION = 3

    /** BeatDetector 버전별 hopMs (Hop Size) — Envelope 디코딩 및 detect() 호출에 공통 적용. */
    fun beatDetectorHopMs(version: Int = BEAT_DETECTOR_VERSION): Long = when (version) {
        1, 2, 3 -> 10L
        else -> 50L   // V0
    }

    // =========================================================================
    // SECTION_DETECTOR: 섹션 감지 엔진 버전
    // =========================================================================

    /**
     * 섹션 감지기 버전 — SectionDetectorRouter 를 통해 적용된다.
     * USE_SECTION_DETECTOR=false이면 무시된다.
     */
    const val SECTION_DETECTOR_VERSION = 3

    // =========================================================================
    // EFFECT_RULE: 이펙트 생성 규칙 버전
    // =========================================================================

    /** 이펙트 규칙 버전 — AutoTimelineGeneratorBeat 에서 이펙트 생성 방식을 결정한다. */
    const val EFFECT_RULE_VERSION = 1

    const val PALETTE_SIZE = 4

    /** 비트 감지기 공통 BPM 탐색 범위 — 모든 생성기에서 공유 */
    const val MIN_BEAT_MS = 375L   // ~160 BPM (BeatDetector 내부 하한과 일치)
    const val MAX_BEAT_MS = 1200L  // ~50 BPM
}