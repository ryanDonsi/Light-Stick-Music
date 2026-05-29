package com.lightstick.music.domain.music

object AutoTimelineConfig {

    /**
     * 파일 캐시 버전 — 생성기·감지기 조합이 바뀔 때마다 증가시켜 기존 캐시를 무효화한다.
     */
    const val VERSION = 17

    /**
     * 타임라인 생성기 버전 (6 ~ 11)
     *  6 : 비트 검증 모드 — globalBeatMs 균등 그리드, 20% ON / 80% OFF (전 구간 빈틈 없음)
     *  7 : 비트 검증 모드 — BeatDetectorV11 실제 감지 시각 사용, 20% ON / 80% OFF
     *  8 : 에너지 기반 섹션 분류 + ON_PULSE 비트 연출 + BeatDetectorV8
     *  9 : v8 기반 + BeatDetectorV9 적용
     * 10 : v9 기반 + phrase accent, ON_ROTATE 추가
     * 11 : v8 기반 + BeatDetectorV11, fade 10%/90% 패턴
     */
    const val GENERATOR_VERSION = 7

    /**
     * 비트 감지기 버전 (8 ~ 11)
     * GENERATOR_VERSION = 7 일 때만 적용된다.
     * (v8 ~ v11 생성기는 각자 고정된 감지기를 내장한다.)
     *
     * 11 : V9 기반 + 정박자 최우선 감지, 다운비트 그리드 재정렬
     */
    const val BEAT_DETECTOR_VERSION = 11

    /**
     * 섹션 감지기 버전
     *  1 : 슬라이딩 윈도우 특징 분석 + 비트 경계 정렬
     */
    const val SECTION_DETECTOR_VERSION = 1

    const val PALETTE_SIZE = 4
}