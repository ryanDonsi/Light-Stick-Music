package com.lightstick.music.domain.music

object AutoTimelineConfig {

    /**
     * 파일 캐시 버전 — 생성기·감지기 조합이 바뀔 때마다 증가시켜 기존 캐시를 무효화한다.
     */
    const val VERSION = 0

    /**
     * 비트 감지기 버전 — BeatDetectorRouter 를 통해 적용된다.
     *  0 : BeatDetectorV0 (IIR 3밴드 ODF + Autocorrelation, 엔벨로프 입력, hopMs=50ms)
     *  1 : BeatDetectorV1 (IIR 3밴드 ODF + Autocorrelation + log-normal prior, PCM 입력, hopMs=10ms)
     *  2 : BeatDetectorV2 (SuperFlux ODF + DBN HMM, 스트리밍, hopMs=10ms)
     *  3 : BeatDetectorV3 (SuperFlux ODF + DBN HMM, 스트리밍, 위상 보정, hopMs=10ms)
     */
    const val BEAT_DETECTOR_VERSION = 3

    /**
     * BeatDetector 버전별 hopMs — 엔벨로프 디코딩 및 detect() 호출에 공통 적용.
     *  V0 : 50ms (엔벨로프 입력)
     *  V1 : 10ms (PCM 직접 입력 — detectPcm() 내부에서도 사용)
     *  V2 : 10ms (스트리밍 — SuperFlux 내부 고정값)
     *  V3 : 10ms (스트리밍 — SuperFlux 내부 고정값)
     */
    fun beatDetectorHopMs(version: Int = BEAT_DETECTOR_VERSION): Long = when (version) {
        1    -> 10L   // PCM 경로
        2    -> 10L   // 스트리밍
        3    -> 10L
        else -> 50L   // V0 (엔벨로프)
    }

    /**
     * 섹션 감지기 버전 — SectionDetectorRouter 를 통해 적용된다.
     *  0 : SectionDetectorV0 (슬라이딩 윈도우 + 비트 경계 정렬, STRIDE=1000ms, per-window autocorr)
     *  1 : SectionDetectorV1 (속도 최적화: STRIDE=2000ms, global periodicity, single-pass feature)
     */
    const val SECTION_DETECTOR_VERSION = 1

    /**
     * 이펙트 규칙 버전 — AutoTimelineGeneratorBeat 에서 이펙트 생성 방식을 결정한다.
     *  0 : 단순 ON/OFF (비트별 색상 ON 20%, OFF 80%)
     *  1 : 단일 색상 비트 이펙트 (BeatDetector + SectionDetector, 모든 섹션 beat-ON)
     *  3 : V8 이펙트 규칙 (ON_PULSE, STROBE, BREATH, ON_TRANSIT_ROTATE 등)
     *  6 : V8 확장 이펙트
     */
    const val EFFECT_RULE_VERSION = 1

    /**
     * 섹션 감지 사용 여부
     *  false: 비트 감지만 사용 (빠른 처리)
     *  true: BeatDetector + SectionDetector 함께 사용 (고급 이펙트)
     */
    const val USE_SECTION_DETECTOR = false

    /**
     * 타임라인 생성기 버전 (호환성 유지용 — 내부적으로는 위 설정들의 조합으로 결정)
     *  0 : Beat 감지 검증 모드 (EFFECT_RULE_VERSION=0, USE_SECTION_DETECTOR=false)
     *  1 : 단일 색상 비트 (EFFECT_RULE_VERSION=1, USE_SECTION_DETECTOR=true)
     *  2 : 단일 색상 비트 (EFFECT_RULE_VERSION=1, USE_SECTION_DETECTOR=true)
     *  3 : V8 이펙트 (EFFECT_RULE_VERSION=3, USE_SECTION_DETECTOR=true)
     *  6 : V8 확장 (EFFECT_RULE_VERSION=6, USE_SECTION_DETECTOR=true)
     */
    const val GENERATOR_VERSION = 2

    const val PALETTE_SIZE = 4

    /** 비트 감지기 공통 BPM 탐색 범위 — 모든 generator에서 공유 */
    const val MIN_BEAT_MS = 375L   // ~160 BPM (BeatDetector 내부 하한과 일치)
    const val MAX_BEAT_MS = 1200L  // ~50 BPM

    /**
     * GENERATOR_VERSION에서 EFFECT_RULE_VERSION으로 변환
     * (하위 호환성 유지)
     */
    fun effectRuleVersionForGenerator(generatorVersion: Int = GENERATOR_VERSION): Int = when (generatorVersion) {
        0 -> 0
        1, 2 -> 1
        3 -> 3
        6 -> 6
        else -> EFFECT_RULE_VERSION
    }

    /**
     * GENERATOR_VERSION에서 USE_SECTION_DETECTOR로 변환
     * (하위 호환성 유지)
     */
    fun useSectionDetectorForGenerator(generatorVersion: Int = GENERATOR_VERSION): Boolean = when (generatorVersion) {
        0 -> false
        else -> true
    }
}