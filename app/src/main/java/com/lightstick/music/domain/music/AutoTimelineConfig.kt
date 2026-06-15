package com.lightstick.music.domain.music

object AutoTimelineConfig {

    /**
     * 파일 캐시 버전 — 생성기·감지기 조합이 바뀔 때마다 증가시켜 기존 캐시를 무효화한다.
     */
    const val VERSION = 0

    /**
     * 비트 감지기 버전 — BeatDetectorRouter 를 통해 적용된다.
     *  1 : BeatDetectorV1 (IIR 3밴드 ODF + Autocorrelation + log-normal prior, PCM 입력, hopMs=10ms)
     *  2 : BeatDetectorV2 (SuperFlux ODF + DBN HMM, 스트리밍, hopMs=10ms)
     *  0 : BeatDetectorV0 (IIR 3밴드 ODF + Autocorrelation, 엔벨로프 입력, hopMs=50ms)
     */
    const val BEAT_DETECTOR_VERSION = 1

    /**
     * BeatDetector 버전별 hopMs — 엔벨로프 디코딩 및 detect() 호출에 공통 적용.
     *  V1 : 10ms (PCM 직접 입력 — detectPcm() 내부에서도 사용)
     *  V2 : 10ms (스트리밍 — SuperFlux 내부 고정값)
     *  V0 : 50ms (엔벨로프 입력)
     */
    fun beatDetectorHopMs(version: Int = BEAT_DETECTOR_VERSION): Long = when (version) {
        1    -> 10L   // PCM 경로
        2    -> 10L   // 스트리밍
        else -> 50L   // V0 (엔벨로프)
    }

    /**
     * 섹션 감지기 버전 — SectionDetectorRouter 를 통해 적용된다.
     *  0 : SectionDetectorV0 (슬라이딩 윈도우 + 비트 경계 정렬, STRIDE=1000ms, per-window autocorr)
     *  1 : SectionDetectorV1 (속도 최적화: STRIDE=2000ms, global periodicity, single-pass feature)
     */
    const val SECTION_DETECTOR_VERSION = 1

    /**
     * 타임라인 생성기 버전 (파일명 숫자와 일치)
     *  0 : Beat 감지 검증 모드 — 박자별 색상 ON (→ AutoTimelineGeneratorBeat_v0)
     *  1 : BeatDetectorV1(PCM) + Brightness 2:8 비율 (→ AutoTimelineGeneratorBeat_v1)
     *  2 : BeatDetector(버전별) + SectionDetector(버전별) + 모든 섹션 beat-ON 단일 이펙트 (→ AutoTimelineGeneratorBeat_v2)
     *  3 : BeatDetector(버전별) + SectionDetector(버전별) + V8 이펙트 룰 (→ AutoTimelineGeneratorBeat_v3)
     *  4 : BeatDetector(버전별) + SectionDetectorV2 + V8 이펙트 룰 (→ AutoTimelineGeneratorBeat_v4)
     *  6 : BeatDetector(버전별) + SectionDetector(버전별) + V8 확장 이펙트 (→ AutoTimelineGeneratorBeat_v6)
     */
    const val GENERATOR_VERSION = 0

    const val PALETTE_SIZE = 4
}