package com.lightstick.music.domain.music

object AutoTimelineConfig {

    /**
     * 파일 캐시 버전 — 생성기·감지기 조합이 바뀔 때마다 증가시켜 기존 캐시를 무효화한다.
     */
    const val VERSION = 50

    /**
     * 타임라인 생성기 버전
     *  0 : beat 검증 모드 — BeatDetector + 박자별 색상 ON (→ AutoTimelineGeneratorBeat_v0)
     * 12 : v0 + SectionDetectorV1, 섹션 타입별 색상 + 박자 강약 fade (→ AutoTimelineGeneratorBeat_v2)
     */
    const val GENERATOR_VERSION = 12

    /**
     * 비트 감지기 버전 — v0/v2 에서 BeatDetectorRouter 를 통해 적용된다.
     *  8  : BeatDetectorV8
     *  9  : BeatDetectorV9
     * 10  : BeatDetectorV10 (downbeat + timeSignature 지원)
     * 11  : BeatDetectorV2 = V11 (현재 기본값)
     */
    const val BEAT_DETECTOR_VERSION = 11

    /**
     * 섹션 감지기 버전 — v2 에서 SectionDetectorRouter 를 통해 적용된다.
     *  1 : SectionDetectorV1 (슬라이딩 윈도우 특징 분석 + 비트 경계 정렬)
     */
    const val SECTION_DETECTOR_VERSION = 1

    const val PALETTE_SIZE = 4
}