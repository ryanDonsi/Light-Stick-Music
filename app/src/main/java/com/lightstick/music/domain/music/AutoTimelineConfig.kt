package com.lightstick.music.domain.music

/**
 * AutoTimelineGeneratorBeat 설정 관리
 *
 * 세 가지 독립적인 감지/생성 엔진을 조합하여 다양한 이펙트 생성 방식을 지원한다.
 * 각 엔진은 독립적으로 업그레이드 가능하며, 조합을 통해 새로운 버전을 만들 수 있다.
 *
 * === 아키텍처 개요 ===
 *
 * 1. BEAT_DETECTOR (비트 감지 엔진)
 *    - 오디오 파일을 분석해 비트(1/4박자)의 위치와 강도를 감지
 *    - 버전별로 정확도·속도·메모리 트레이드오프가 다름
 *
 * 2. SECTION_DETECTOR (섹션 감지 엔진, 선택사항)
 *    - 음악의 구조적 변화 지점(verse/chorus/climax 등)을 감지
 *    - USE_SECTION_DETECTOR=true일 때만 실행 (오버헤드 있음)
 *    - 섹션별로 다른 이펙트 규칙 적용 가능
 *
 * 3. EFFECT_RULE (이펙트 생성 규칙)
 *    - 비트/섹션 정보로부터 실제 LED 이펙트 프레임 생성
 *    - 버전별로 복잡도와 다양성이 다름
 *    - 비트 감지만으로 작동하거나(v0) 섹션 정보를 활용(v3/v6) 가능
 *
 * === 버전 조합 예시 ===
 *
 * BEAT_DETECTOR_VERSION=3, SECTION_DETECTOR=OFF, EFFECT_RULE=0
 *  → 최신 비트 감지기 + 단순 비트 ON/OFF 이펙트 (빠름)
 *
 * BEAT_DETECTOR_VERSION=3, SECTION_DETECTOR=ON, EFFECT_RULE=3
 *  → 최신 감지기들 + V8 복잡 이펙트 규칙 (느리지만 화려움)
 *
 * BEAT_DETECTOR_VERSION=1, SECTION_DETECTOR=ON, EFFECT_RULE=1
 *  → 이전 감지기 + 단순 섹션 기반 이펙트 (중간)
 */
object AutoTimelineConfig {

    /**
     * 파일 캐시 버전 — 생성기·감지기 조합이 바뀔 때마다 증가시켜 기존 캐시를 무효화한다.
     * 이 값이 변경되면 모든 사용자의 기존 타임라인이 재생성된다.
     */
    const val VERSION = 0

    // =========================================================================
    // BEAT_DETECTOR: 비트 감지 엔진 버전
    // =========================================================================

    /**
     * 비트 감지기 버전 — BeatDetectorRouter 를 통해 적용된다.
     *
     * V0: IIR 3밴드 ODF (Onset Detection Function) + 자기상관 (Autocorrelation)
     *     입력: 엔벨로프 (사전 계산됨)
     *     hopMs: 50ms (낮은 분해능)
     *     정확도: 낮음, 속도: 빠름
     *     용도: 테스트/검증, 저사양 기기
     *     특징: ODF는 3개 밴드별로 개별 처리 후 합산
     *
     * V1: IIR 3밴드 ODF + 자기상관 + Log-Normal Prior
     *     입력: PCM (오디오 샘플 직접 입력)
     *     hopMs: 10ms (높은 분해능)
     *     정확도: 중간, 속도: 중간
     *     용도: 일반적인 음악 (발라드~댄스)
     *     특징: Prior를 이용해 비트 후보 확률 보정
     *     주의: PCM 버퍼 크기 제한 있음 (OOM 방지)
     *
     * V2: Dual ODF + Dynamic Programming
     *     입력: 스트리밍 (청크 단위 처리)
     *     hopMs: 10ms (높은 분해능)
     *     정확도: 높음, 속도: 느림
     *     용도: 고정확도 요구 (발라드, 복잡한 시간 서명)
     *     특징: odfTempo로 BPM 추정, odfTrack으로 DP 위상 추적
     *           Kick 밴드에 1.5배 가중치 적용 (드럼 강조)
     *           Hann window 캐싱으로 성능 최적화
     *     메모리: 낮음 (스트리밍 처리)
     *
     * 선택 기준:
     * - V0: 테스트용, 저사양 기기용
     * - V1: 호환성 유지, 레거시 지원
     * - V2: 최고 정확도 필요할 때 (추천, 현재 기본값)
     */
    const val BEAT_DETECTOR_VERSION = 0

    /**
     * BeatDetector 버전별 hopMs (Hop Size)
     * — Envelope 디코딩 및 detect() 호출에 공통 적용.
     *
     * hopMs는 두 가지 의미:
     * 1. 시간 해상도: 작을수록 정확하지만 처리량 증가
     * 2. Envelope 배열 길이: 같은 음악에서 hopMs가 작으면 배열이 4배 길어짐
     *
     * V0 (50ms):
     *   - 음악 디코딩과 동시에 엔벨로프 추출 (비효율적이지만 호환)
     *   - 프레임 수: 1000ms 음악 → 20개 엔벨로프 값
     *
     * V1 (10ms):
     *   - PCM 경로 전용 (detectPcm 내부에서도 10ms 고정)
     *   - 프레임 수: 1000ms 음악 → 100개 엔벨로프 값
     *
     * V2 (10ms):
     *   - 스트리밍 처리, Dual ODF 내부에서 고정
     *   - 청크 단위로 처리해 메모리 효율적
     *   - V3는 V2로 통합됨 (legacy: version 3 요청시 V2로 리다이렉트)
     */
    fun beatDetectorHopMs(version: Int = BEAT_DETECTOR_VERSION): Long = when (version) {
        1, 2, 3 -> 10L  // V1, V2 (V3는 V2로 리다이렉트)
        else    -> 50L   // V0
    }

    // =========================================================================
    // SECTION_DETECTOR: 섹션 감지 엔진 버전
    // =========================================================================

    /**
     * 섹션 감지기 버전 — SectionDetectorRouter 를 통해 적용된다.
     * USE_SECTION_DETECTOR=false이면 무시된다.
     *
     * V0: 슬라이딩 윈도우 + 비트 경계 정렬 + 자동 상관
     *     STRIDE: 1000ms (1초 슬라이드 간격)
     *     특징: per-window 자기상관 계산, 느림
     *     정확도: 중간, 속도: 느림
     *     메모리: 높음
     *     용도: 레거시, 호환성 유지
     *
     *     알고리즘 개요:
     *     1. 전체 음악을 1초씩 슬라이딩 윈도우로 분할
     *     2. 각 윈도우별로 자기상관 계산 (periodicity 감지)
     *     3. 각 비트와 그 다음 비트 사이의 차이 계산
     *     4. 큰 변화를 섹션 변경점으로 표시
     *     5. 변경점을 가장 가까운 비트 경계에 정렬
     *
     * V1: 속도 최적화 + 글로벌 Periodicity + 단일 패스
     *     STRIDE: 2000ms (2초 슬라이드 간격, 더 성김)
     *     특징: 전체 곡의 주기성 먼저 계산, 그 기준으로 변화 감지
     *     정확도: V0와 유사 또는 약간 높음, 속도: 2-3배 빠름
     *     메모리: 낮음
     *     용도: 현재 기본값 (V1 권장)
     *
     *     개선사항:
     *     1. Global periodicity 사전 계산 (곡 전체 특성 파악)
     *     2. 각 윈도우별 계산 시 이미 알려진 주기로 정규화
     *     3. Single-pass feature extraction (계산량 1/3)
     *     4. STRIDE를 2배로 늘려도 정확도 손실 미미
     *
     * 선택 기준:
     * - V0: 최고 정확도 필요 (느려도 괜찮음)
     * - V1: 일반적인 경우 (추천, 더 빠르고 정확도 비슷)
     *
     * 섹션 감지 결과 예시 (INTRO -> VERSE -> CHORUS -> BRIDGE -> CLIMAX -> OUTRO):
     *   INTRO(0~5s): 1-2개 비트, 낮은 에너지, 숨쉬기 이펙트
     *   VERSE(5~15s): 8-16개 비트, 보통 에너지, 펄스 이펙트
     *   CHORUS(15~25s): 8-16개 비트, 높은 에너지, 로테이션 이펙트
     *   등등...
     */
    const val SECTION_DETECTOR_VERSION = 1

    // =========================================================================
    // EFFECT_RULE: 이펙트 생성 규칙 버전
    // =========================================================================

    /**
     * 이펙트 규칙 버전 — AutoTimelineGeneratorBeat 에서 이펙트 생성 방식을 결정한다.
     *
     * V0: 단순 비트 ON/OFF (단색 강조)
     *     이펙트 타입: ON (20%) + OFF (80%) 반복
     *     색상 규칙:
     *       - downbeat(0): White (100% 밝기)
     *       - beatInBar 1: Purple (35% 밝기)
     *       - beatInBar 2: Yellow (100% 밝기)
     *       - beatInBar 3: Cyan (35% 밝기)
     *     섹션 정보: 미사용
     *     복잡도: 낮음, 속도: 매우 빠름
     *     이펙트 품질: 기본, 단조로움
     *     메모리: 매우 낮음
     *     용도: 비트 감지 테스트, 저사양 기기, 빠른 처리 필요
     *
     *     특징:
     *     - 모든 비트에 동일한 규칙 적용
     *     - 팔레트 색상 없음 (고정 색상)
     *     - 계산 오버헤드 거의 없음
     *
     * V1/V2: 단일 색상 비트 이펙트 (섹션 기반)
     *     이펙트 타입: ON (각 비트마다)
     *     색상 규칙:
     *       - 각 비트마다 ON 이펙트 (색상: 팔레트 기반)
     *       - downbeat 규칙 (beatInBar)과 동일하지만 팔레트 활용
     *     섹션 정보: 사용 (섹션별 다른 색상 조합)
     *     복잡도: 낮음, 속도: 빠름
     *     이펙트 품질: 중간, 팔레트 활용으로 조화로운 색상
     *     메모리: 낮음
     *     용도: 일반적인 음악 재생, 색상 다양성 원할 때
     *
     *     특징:
     *     - 팔레트: baseHue 기반 4색 생성 (complementary color)
     *     - 섹션별 색상 로테이션
     *     - 단순하지만 시각적 만족도 높음
     *
     * V3: V8 이펙트 규칙 (고급, 섹션 기반)
     *     이펙트 타입: ON_PULSE, STROBE, BREATH, ON_TRANSIT_ROTATE, BLINK, OFF_TRANSIT
     *     섹션별 엔진 할당 (FgEngine):
     *       - INTRO/OUTRO: BREATH (부드러운 호흡 효과)
     *       - VERSE/VOCAL: ON_PULSE or BREATH (음성 강조)
     *       - CHORUS: ON_TRANSIT_ROTATE (회전 전환 효과)
     *       - CLIMAX: STROBE or ON_TRANSIT_ROTATE (급작스러운 변화)
     *       - BUILD: ON_TRANSIT_ROTATE (상승감)
     *       - BEAT: ON_PULSE or BLINK (비트 강조)
     *       - BREAK: BREATH (휴식)
     *       - END: OFF_TRANSIT (부드러운 종료)
     *
     *     색상 규칙:
     *       - 섹션 타입별로 다른 색상 세트 사용
     *       - beatInBar 기반 패턴 (4/4박 강박 강조)
     *       - 에너지 레벨에 따라 엔진 세부 조정
     *
     *     특수 처리:
     *       - Climax 감지: 에너지 + 섹션 길이 기반
     *       - Ballad 모드: 느린 템포곡은 BREATH 중심으로 변경
     *       - Bridge phase engine: 브릿지 구간에서 비트별로 엔진 변경
     *       - 음악 종료 감지: 마지막 묵음 구간 자동 OFF
     *       - 연속 이펙트 최적화: 같은 이펙트 반복 제거 (프레임 중복 방지)
     *
     *     복잡도: 높음, 속도: 느림
     *     이펙트 품질: 매우 높음, 영상미 우수
     *     메모리: 중간
     *     용도: 음악 영상화, 라이브 디스플레이, 공연용 (추천)
     *
     *     V8이란? (본래 출처)
     *     V8은 이전에 별도로 개발된 이펙트 엔진의 8번째 버전
     *     "8개의 섹션 타입별 엔진 조합" 또는 "8가지 이펙트 타입"을 지원
     *     현재 AutoTimelineGeneratorBeat v3에 통합됨
     *
     * V6: V8 확장 이펙트 (V3과 유사, 향후 확장 예정)
     *     현재: V3과 동일한 구현
     *     향후: 추가 이펙트 타입 또는 AI 기반 엔진 추가 예정
     *
     * 선택 기준:
     * - V0: 빠른 처리 필요, 테스트용
     * - V1: 일반적인 사용 (권장, 속도와 품질 균형)
     * - V3: 최고 품질 (느려도 괜찮음)
     * - V6: 미래 확장용
     */
    const val EFFECT_RULE_VERSION = 2

    // =========================================================================
    // ORCHESTRATION: 엔진 선택 및 조합
    // =========================================================================

    // 섹션 감지는 항상 활성화 (성능 최적화로 inlining하지 않음)
    // SectionDetectorRouter 실행 (오버헤드 ~30-50%)
    // - 섹션별 다른 이펙트 규칙 적용
    // - 이펙트 다양성 높음
    // - 음악 구조 시각화 가능

    // =========================================================================
    // LEGACY: 하위 호환성 (GENERATOR_VERSION)
    // =========================================================================

    /**
     * 타임라인 생성기 버전 (호환성 유지용)
     *
     * 내부적으로는 위의 (EFFECT_RULE_VERSION, USE_SECTION_DETECTOR) 조합으로 결정되지만
     * 기존 코드 호환성을 위해 GENERATOR_VERSION도 유지한다.
     *
     * 매핑:
     *  0 → EFFECT_RULE_VERSION=0, USE_SECTION_DETECTOR=false
     *      (v0: 비트 검증 모드, 가장 빠름)
     *
     *  1 → EFFECT_RULE_VERSION=1, USE_SECTION_DETECTOR=true
     *      (v1: 섹션+단순 비트, 첫 섹션 기반 버전)
     *
     *  2 → EFFECT_RULE_VERSION=1, USE_SECTION_DETECTOR=true
     *      (v2: v1과 동일 로직, 최적화만 다름, 현재 기본값)
     *
     *  3 → EFFECT_RULE_VERSION=3, USE_SECTION_DETECTOR=true
     *      (v3: V8 이펙트 규칙, 고급 버전)
     *
     *  6 → EFFECT_RULE_VERSION=6, USE_SECTION_DETECTOR=true
     *      (v6: V8 확장, 미래 예약)
     *
     * 주의: GENERATOR_VERSION과 새 설정을 동시에 바꾸지 말 것
     * → VERSION이 증가되어 캐시가 무효화됨
     */
    const val GENERATOR_VERSION = 2

    const val PALETTE_SIZE = 4

    /** 비트 감지기 공통 BPM 탐색 범위 — 모든 생성기에서 공유 */
    const val MIN_BEAT_MS = 375L   // ~160 BPM (BeatDetector 내부 하한과 일치)
    const val MAX_BEAT_MS = 1200L  // ~50 BPM

    /**
     * GENERATOR_VERSION에서 EFFECT_RULE_VERSION으로 변환 (하위 호환성)
     */
    fun effectRuleVersionForGenerator(generatorVersion: Int = GENERATOR_VERSION): Int = when (generatorVersion) {
        0 -> 0
        1, 2 -> 1
        3 -> 3
        6 -> 6
        else -> EFFECT_RULE_VERSION
    }

    /**
     * GENERATOR_VERSION에서 USE_SECTION_DETECTOR로 변환 (하위 호환성)
     */
    fun useSectionDetectorForGenerator(generatorVersion: Int = GENERATOR_VERSION): Boolean = when (generatorVersion) {
        0 -> false
        else -> true
    }
}