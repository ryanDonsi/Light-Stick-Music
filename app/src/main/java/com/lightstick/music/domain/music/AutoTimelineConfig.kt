package com.lightstick.music.domain.music

object AutoTimelineConfig {

    /**
     * 파일 캐시 버전 — 생성기·감지기 조합이 바뀔 때마다 증가시켜 기존 캐시를 무효화한다.
     */
    const val VERSION = 0

    /**
     * 비트 감지기 버전 — v0/v2 에서 BeatDetectorRouter 를 통해 적용된다.
     *  1 : BeatDetectorV1 (Autocorrelation + log-normal prior + half-tempo check)
     *  2 : BeatDetectorV2
     *  3 : BeatDetectorV3 (Fix 1~5)
     *  4 : BeatDetectorV4 (Fix A~E)
     *  5 : BeatDetectorV5 (DBN HMM)
     */
    const val BEAT_DETECTOR_VERSION = 1

    /**
     * BeatDetector 버전별 hopMs — 엔벨로프 디코딩 및 detect() 호출에 공통 적용.
     *  V1 : PCM 직접 입력 (hopMs는 BeatDetectorV1 내부에서만 사용)
     *  V2 : 스트리밍 (hopMs 불필요)
     *  V3 : 20ms
     *  V4 : 20ms
     *  V5 : 50ms (DBN HMM 설계 기준)
     */
    fun beatDetectorHopMs(version: Int = BEAT_DETECTOR_VERSION): Long = when (version) {
        1    -> 20L   // PCM 경로 — detectPcm() 내부에서 사용
        2    -> 10L   // 스트리밍 — SuperFlux 내부 고정값
        3    -> 20L
        4    -> 20L
        else -> 50L   // V5 (DBN HMM)
    }

    /**
     * 섹션 감지기 버전 — v2 에서 SectionDetectorRouter 를 통해 적용된다.
     *  1 : SectionDetectorV1 (슬라이딩 윈도우 특징 분석 + 비트 경계 정렬)
     */
    const val SECTION_DETECTOR_VERSION = 1

    /**
     * 타임라인 생성기 버전 (파일명 숫자와 일치)
     *  0 : Beat 감지 검증 모드 — 박자별 색상 ON (→ AutoTimelineGeneratorBeat_v0)
     *  1 : BeatDetectorV1(PCM) + Brightness 2:8 비율 (→ AutoTimelineGeneratorBeat_v1)
     *  3 : BeatDetectorV2 + SectionDetectorV1 (CLIMAX 포함) + V8 이펙트 룰 (→ AutoTimelineGeneratorBeat_v3)
     *  4 : BeatDetectorV2 + SectionDetectorV2 + V8 이펙트 룰 (→ AutoTimelineGeneratorBeat_v4)
     */
    const val GENERATOR_VERSION = 0

    const val PALETTE_SIZE = 4
}