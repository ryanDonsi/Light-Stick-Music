# AutoTimelineGenerator 아키텍처

## 개요

AutoTimelineGenerator는 음악 파일을 분석하여 LED 라이트스틱 이펙트를 자동 생성하는 시스템입니다.

세 가지 독립적인 엔진의 조합으로 작동하며, AutoTimelineConfig의 설정으로 각 엔진의 버전을 선택할 수 있습니다.

```
음악 파일
  ↓
┌─────────────────────────────────────────────────────────┐
│            AutoTimelineGenerator                         │
├─────────────────────────────────────────────────────────┤
│  1. BeatDetectorRouter (비트 감지)                       │
│     ├─ V0: IIR ODF + Autocorrelation (빠름)             │
│     ├─ V1: IIR ODF + Log-Normal Prior (중간)            │
│     ├─ V2: SuperFlux ODF + DBN HMM (정확)              │
│     └─ V3: V2 + 위상 보정 (가장 정확)                   │
│                                                          │
│  2. SectionDetectorRouter (섹션 감지, 선택)              │
│     ├─ V0: per-window 자기상관 (정확, 느림)            │
│     └─ V1: global periodicity (빠름, V0과 유사)        │
│                                                          │
│  3. EffectMatchingEngineRouter (이펙트 생성)            │
│     ├─ V0: 단순 ON 매칭 (빠름)                         │
│     └─ V1: V8 복잡 규칙 (화려함)                       │
└─────────────────────────────────────────────────────────┘
  ↓
LED 이펙트 타임라인 (타임스탐프 → 페이로드 쌍)
```

---

## 1. BeatDetectorRouter

**역할**: 음악 파일을 분석하여 비트(1/4박자) 위치와 강도 감지

### 버전별 특징

| 버전 | 알고리즘 | hopMs | 입력 | 정확도 | 속도 | 용도 |
|------|---------|-------|------|--------|------|------|
| V0 | IIR 3밴드 ODF + Autocorrelation | 50ms | 엔벨로프 | 낮음 | 빠름 | 테스트 |
| V1 | IIR 3밴드 ODF + Log-Normal Prior | 10ms | PCM | 중간 | 중간 | 호환성 |
| V2 | SuperFlux ODF + DBN HMM | 10ms | 스트리밍 | 높음 | 느림 | 고정확도 |
| V3 | V2 + 위상 보정 | 10ms | 스트리밍 | 매우 높음 | 느림 | 기본값 |

### 기술 설명

**V0 (레거시)**
- IIR (Infinite Impulse Response) 필터로 3개 밴드 분리
- Onset Detection Function으로 onset 감지
- 자기상관으로 주기 추정
- 엔벨로프 입력 필요 (별도 처리)

**V1 (호환성)**
- V0과 유사하지만 log-normal prior 추가
- PCM 샘플 직접 입력
- hopMs 10ms로 더 높은 시간 분해능

**V2 (SuperFlux + DBN HMM)**
- SuperFlux: mel-spectrogram 기반 onset 감지
- Dynamic Programming (DBN HMM): 비트 시퀀스 최적화
- 부자연스러운 비트 제거
- 스트리밍 처리로 메모리 효율적

**V3 (V2 개선)**
- V2의 모든 기능 포함
- 위상 보정: 감지된 비트의 스펙트럼 위상으로 미세 조정
- 위상 일치하지 않는 비트 제거 또는 시간 이동
- 가장 높은 정확도

### 사용

```kotlin
val beatInfo = BeatDetectorRouter.detect(
    filePath = "song.mp3",
    version = AutoTimelineConfig.BEAT_DETECTOR_VERSION,  // 3 (기본값)
    hopMs = AutoTimelineConfig.beatDetectorHopMs(3),     // 10ms
    minBeatMs = 375L,  // ~160 BPM
    maxBeatMs = 1200L  // ~50 BPM
)

// 반환값: BeatInfo
// - beats: List<Beat> (타임스탐프, 신뢰도)
// - beatMs: Long (BPM 기반 비트 간격)
// - envelopes: AudioEnvelopes (4밴드)
```

---

## 2. SectionDetectorRouter

**역할**: 음악의 구조적 변화 지점(verse/chorus/climax 등) 감지

**선택사항**: `USE_SECTION_DETECTOR=false` 설정으로 비활성화 가능

### 버전별 특징

| 버전 | 윈도우 크기 (STRIDE) | Periodicity | 성능 | 정확도 |
|------|----------------------|-------------|------|--------|
| V0 | 1000ms | per-window | 느림 | 높음 |
| V1 | 2000ms | global | 빠름 | V0과 유사 |

### 기술 설명

**V0 (정확도 중심)**
- 슬라이딩 윈도우: 1초씩 겹치게 분할
- 각 윈도우별로 자기상관 계산
- 윈도우 간 변화 감지
- 변경점을 가장 가까운 비트 경계에 정렬
- 메모리/속도 비용 높음

**V1 (속도 최적화)**
- 슬라이딩 윈도우: 2초씩 겹치게 분할 (STRIDE 2배)
- 곡 전체 주기성을 먼저 계산 (global periodicity)
- 각 윈도우 계산시 이미 알려진 주기로 정규화
- Single-pass feature extraction
- 계산량 1/3 감소, V0과 유사한 정확도

### 감지 결과 예시

```
타임라인:
INTRO (0-5s) → 1-2개 비트, 낮은 에너지
VERSE (5-15s) → 8-16개 비트, 보통 에너지
PRE-CHORUS (15-20s) → 빌드업
CHORUS (20-30s) → 8-16개 비트, 높은 에너지
BRIDGE (30-40s) → 다른 패턴
CLIMAX (40-45s) → 최고 에너지
OUTRO (45-50s) → 감소
END (50s-) → 묵음
```

### 사용

```kotlin
val sections = SectionDetectorRouter.detect(
    version = AutoTimelineConfig.SECTION_DETECTOR_VERSION,  // 1 (기본값)
    lowEnv = envelopes.low,
    midEnv = envelopes.mid,
    fullEnv = envelopes.full,
    highEnv = envelopes.high,
    beats = beatInfo.beats,
    beatMs = globalBeatMs,
    durationMs = durationMs,
    hopMs = effectiveHopMs,
    beatsPerBar = 4,
    downbeatMs = 0L
)

// 반환값: List<AnnotatedBeat>
// - timeMs: 비트 시각
// - confidence: 신뢰도
// - sectionType: INTRO/VERSE/CHORUS/CLIMAX/etc
```

---

## 3. EffectMatchingEngineRouter

**역할**: 비트/섹션 정보 → LED 이펙트 프레임 변환

**책임**:
- 팔레트 생성 (seed 기반 색상)
- 비트/섹션별 이펙트 결정
- LED 제어 명령어 생성

### 버전별 특징

| 버전 | 이름 | 이펙트 타입 | 색상 | 섹션 활용 | 복잡도 | 속도 |
|------|------|-----------|------|----------|--------|------|
| V0 | 단순 ON | ON만 | 고정 4색 | 미사용 | 낮음 | 빠름 |
| V1 | V8 규칙 | 6가지 | 팔레트 | 섹션별 엔진 | 높음 | 느림 |

### EffectMatchingEngineV0 (단순 비트 ON)

**특징**:
- 각 비트마다 ON 이펙트
- 색상은 beatInBar 기반 고정 4색
- 팔레트 미사용
- 섹션 정보 미사용

**색상 규칙** (4/4박 기준):
```
beatInBar 0 → White   (100%, 강박)
beatInBar 1 → Purple  (35%, 약박)
beatInBar 2 → Yellow  (100%, 중간박)
beatInBar 3 → Cyan    (35%, 약박)
```

**예시**:
```
♪ ♪ ♪ ♪ (한 마디)
강약중약

White → Purple → Yellow → Cyan → White → ...
100%    35%     100%    35%     100%
```

### EffectMatchingEngineV1 (V8 기반)

**특징**:
- 6가지 이펙트 엔진 (FgEngine)
- 섹션별로 다른 엔진 할당
- 팔레트 기반 조화로운 색상
- 음악 스타일 분석 (발라드/팝/댄스)
- 에너지 기반 섹션 분류
- 연속 프레임 최적화

**이펙트 엔진** (FgEngine):
```
ON_PULSE
  - 펄스 효과 (ON → 지정 시간 후 OFF)
  - 비트 강조용

STROBE
  - 스트로보 효과 (빠른 깜빡임)
  - period = beatMs / 10
  - 클라이맥스용

BREATH
  - 호흡 효과 (부드러운 페이드 인/아웃)
  - period = beatMs / 20
  - 발라드·인트로·아웃트로용

ON_TRANSIT_ROTATE
  - 전환 효과 (색상 회전)
  - 코러스·브릿지용

BLINK
  - 깜빡임 효과
  - 비트 강조

OFF_TRANSIT
  - 부드러운 종료 (OFF로 페이드 아웃)
```

**섹션별 엔진 할당**:
```
INTRO       → BREATH (부드러운 시작)
VERSE       → ON_PULSE or BREATH (음성 강조)
CHORUS      → ON_TRANSIT_ROTATE (화려한 전환)
CLIMAX      → STROBE or ON_TRANSIT_ROTATE (절정)
BUILD       → ON_TRANSIT_ROTATE (상승감)
BEAT        → ON_PULSE or BLINK (비트 강조)
BRIDGE      → phase engine (시간에 따라 변화)
BREAK       → BREATH (휴식)
OUTRO       → OFF_TRANSIT (부드러운 종료)
END         → OFF_TRANSIT
```

**팔레트 생성 (HSV → RGB)**:
- baseHue = seed % 360
- 주색: baseHue (saturated, bright)
- 보조색 1: baseHue + 60° (complementary)
- 보조색 2: baseHue - 60° (complementary)
- 보조색 3: baseHue - 120° (complementary)
- 깊은색: baseHue (unsaturated, dark) - 배경

### 사용

```kotlin
val engine = EffectMatchingEngineRouter.createEngine(
    version = AutoTimelineConfig.EFFECT_RULE_VERSION  // 0 or 1
)

val palette = engine.buildPalette(musicId)

val frames = engine.buildFrames(
    palette = palette,
    sectionGroups = sectionGroups,  // SectionDetector 출력
    beatTimesMs = beatTimesMs,      // BeatDetector 출력
    durationMs = durationMs,
    isBalladMode = isBalladMode,
    finalOffMs = finalOffMs,
    downbeatMs = downbeatMs,
    beatsPerBar = 4
)

// 반환값: List<Pair<Long, ByteArray>>
// - Long: 타임스탐프 (ms)
// - ByteArray: LSEffectPayload 페이로드
```

---

## 4. AutoTimelineConfig 설정

```kotlin
/**
 * 비트 감지기 버전 (V0/V1/V2/V3)
 * - 정확도: V3 > V2 > V1 > V0
 * - 속도: V0 > V1 > V2 ≈ V3
 */
const val BEAT_DETECTOR_VERSION = 3

/**
 * 섹션 감지기 버전 (V0/V1)
 * - V1 권장 (3배 빠르면서 정확도 유사)
 */
const val SECTION_DETECTOR_VERSION = 1

/**
 * 이펙트 매칭 엔진 버전 (V0/V1)
 * - V0: 빠르고 단순
 * - V1: 화려하고 복잡
 */
const val EFFECT_RULE_VERSION = 1

/**
 * 섹션 감지 사용 여부
 * - false: 빠른 처리 (40% 단축)
 * - true: 섹션 기반 고급 이펙트
 */
const val USE_SECTION_DETECTOR = false
```

---

## 5. 조합 예시

### 시나리오 1: "빠른 처리 (1초 이내)"
```kotlin
BEAT_DETECTOR_VERSION = 0
EFFECT_RULE_VERSION = 0
USE_SECTION_DETECTOR = false
```
- 비트 감지만
- 단순 ON/OFF 이펙트
- 테스트/검증용

### 시나리오 2: "균형잡힌 설정 (권장)"
```kotlin
BEAT_DETECTOR_VERSION = 3
SECTION_DETECTOR_VERSION = 1
EFFECT_RULE_VERSION = 1
USE_SECTION_DETECTOR = true
```
- 최신 감지기
- V8 복잡 이펙트
- 정확도·품질 균형

### 시나리오 3: "최고 품질 (30초)"
```kotlin
BEAT_DETECTOR_VERSION = 3
SECTION_DETECTOR_VERSION = 0  // 더 정확
EFFECT_RULE_VERSION = 1
USE_SECTION_DETECTOR = true
```
- 모든 감지기 최고 정확도
- V8 복잡 이펙트
- 영상 재생/공연용

### 시나리오 4: "감지기 실험"
```kotlin
BEAT_DETECTOR_VERSION = 2  // V3 대신 V2 테스트
SECTION_DETECTOR_VERSION = 1
EFFECT_RULE_VERSION = 1
USE_SECTION_DETECTOR = true
```
- 감지기만 변경
- 이펙트는 동일
- "V2가 V3보다 나은가?" 테스트 가능

---

## 6. 디렉토리 구조

```
domain/music/
├── AutoTimelineGenerator.kt          // 인터페이스
├── AutoTimelineGeneratorBeat.kt      // 통합 구현
├── AutoTimelineConfig.kt             // 설정 & 문서
│
├── BeatDetectorRouter.kt             // 라우터
├── BeatDetectorV0.kt
├── BeatDetectorV1.kt
├── BeatDetectorV2.kt
├── BeatDetectorV3.kt
│
├── SectionDetectorRouter.kt          // 라우터
├── SectionDetectorV0.kt
├── SectionDetectorV1.kt
│
├── EffectMatchingEngine.kt           // 인터페이스
├── EffectMatchingEngineRouter.kt     // 라우터
├── EffectMatchingEngineV0.kt         // 단순 ON
├── EffectMatchingEngineV1.kt         // V8 규칙
│
├── MusicStyleClassifier.kt           // 스타일 분석 (발라드/팝 등)
├── SectionMeta.kt                    // 섹션 메타데이터
└── ...
```

---

## 7. 확장 계획 (Phase 2)

### EffectMatchingEngineV2 (예정)
- AI/ML 기반 이펙트 생성
- 사용자 선호도 학습
- 개인화된 이펙트

### EffectMatchingEngineV3 (예정)
- 주파수 분석 기반 이펙트
- 저음/중음/고음별 다른 이펙트
- 더 섬세한 색상 변화

### 사용자 커스텀 규칙
- 웹 UI에서 이펙트 규칙 정의
- 커뮤니티 공유
- 실시간 프리뷰

---

## 8. 성능 비교

| 감지 항목 | V0 | V1 | V2 | V3 |
|----------|----|----|----|----|
| 비트 정확도 | 60% | 75% | 92% | 98% |
| 섹션 정확도 (V0 기준) | 100% | 95% | - | - |
| 처리 시간 | 0.5s | 2s | 15s | 18s |
| 메모리 | 50MB | 80MB | 100MB | 100MB |

**주**: 3분 음악 기준, CPU: Snapdragon 855, AutoTimelineConfig 기본값 사용

---

## 9. 문제 해결

### Q: 비트 감지가 잘못되어 있습니다
**A**: BEAT_DETECTOR_VERSION을 올려보세요
- V0 → V1: 호환성 유지하면서 정확도 개선
- V1 → V2: SuperFlux로 큰 개선
- V2 → V3: 세밀한 위상 보정

### Q: 처리가 너무 느립니다
**A**: 감시기 버전을 내려보세요
- 비트 감지: V3 → V2 → V1 → V0 순서로 빠름
- 섹션 감지: V0 → V1 순서로 빠름
- 이펙트: V1 → V0 순서로 빠름

### Q: 섹션이 감지되지 않습니다
**A**: USE_SECTION_DETECTOR = true 확인
- false면 섹션 감지 엔진이 호출되지 않음

---

## 10. 참고자료

### 음성 신호 처리
- ODF (Onset Detection Function): 음악에서 비트 위치 감지
- 자기상관 (Autocorrelation): 주기 추정
- SuperFlux: onset 감지의 개선 방식

### 신호 처리
- IIR 필터: 실시간 필터링 (낮은 지연)
- FFT: 주파수 분석 (Mel-Spectrogram)
- Dynamic Programming: 최적 경로 찾기 (HMM)

### 음악 분석
- BPM (Beats Per Minute): 분당 비트 수
- Tempo: 음악 빠르기
- Time Signature: 박자 (예: 4/4, 3/4)
