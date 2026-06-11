# BeatDetector V1 vs V2 — 알고리즘 비교 및 자동이펙트 생성 시간 분석

> 작성 기준: 2026-06-11  
> 테스트 환경: AutoTimelineGeneratorBeat_v1 (hopMs=20ms, PARALLEL_COUNT=3)

---

## 1. 개요

| 항목 | BeatDetectorV1 (V5 / 버전14) | BeatDetectorV2 (V11) |
|------|------------------------------|----------------------|
| 참조 알고리즘 | librosa `beat_track` | madmom 풀 파이프라인 |
| 입력 방식 | PCM FloatArray → IIR 엔벨로프 변환 | 스트리밍 PCM → STFT 직접 계산 |
| ODF | IIR 3밴드 에너지 (Low/Mid/Full) | SuperFlux (24밴드 로그 필터뱅크 + positive flux) |
| BPM 추정 | Autocorrelation + log-normal prior | DBN HMM Forward (Viterbi-max 근사) |
| Hop | 20ms (v1 제너레이터 설정값) | 10ms (madmom 기본값 고정) |
| 메모리 | PCM 전체 배열 보유 (10분 상한 적용) | 링버퍼만 유지 — PCM 미보관 |
| 하모닉 보정 | half-tempo ratio 체크 (autocorr 기반) | HARM_RATIOS + 910ms guard + combPriorScore |
| 외부 의존 | 없음 | JTransforms FFT 라이브러리 필요 |

---

## 2. 파이프라인 상세 비교

### 2-1. ODF (Onset Detection Function) 계산

#### BeatDetectorV1
```
PCM → IIR 필터 3단 분리 → hop마다 RMS → 정규화

  Low  : lowZ  += 0.12 × (sample - lowZ)          → 저역 에너지
  Mid  : midLP1 += 0.35 × (sample - midLP1)
         midLP2 += 0.08 × (sample - midLP2)
         midVal = |midLP1 - midLP2|                → 중역 에너지 (대역통과)
  Full : RMS(sample)                               → 전역 에너지
```
- 연산량: 샘플당 **덧셈 6회 + 곱셈 5회**
- 주파수 분해능 없음 (시간 도메인 IIR)

#### BeatDetectorV2
```
PCM(스트리밍) → ring buffer(2048) → hopSamples마다 FFT
→ log magnitude: log(1 + 1000|X|)
→ 로그 삼각 필터뱅크 24밴드 적용
→ 이전 프레임 ±1 bin max filter (madmom "maximum filter")
→ positive spectral flux = SuperFlux ODF

  SuperFlux(t) = Σ_b max(0, curBand[b] - prevBand[b])
```
- 연산량: hop마다 **2048-point FFT + 24밴드 필터링**
- 주파수 분해능: 27.5Hz ~ 16kHz 로그 분포 24밴드

**차이점 요약**: V1은 시간 도메인 에너지 추적으로 빠르지만 주파수 분해능이 낮음.  
V2는 스펙트럴 플럭스 기반으로 타악기성 onset을 더 선명하게 포착하나 STFT 비용 발생.

---

### 2-2. BPM 추정

#### BeatDetectorV1 — Autocorrelation + log-normal prior
```
score[lag] = autocorr[lag] × prior[lag]

  autocorr[lag] = mean(odf[i] × odf[i+lag])   // 모든 lag 연속 스윕
  prior[lag]    = exp(-0.5 × (log2(lag×hopMs / 500ms) / 1octave)²)

→ bestLag = argmax(score)
→ half-tempo 체크: autocorr[bestLag/2] / autocorr[bestLag] ≥ 0.60 이면 빠른 템포 선택
```
- 120 BPM(500ms) 중심 log-normal prior → 100~140 BPM 선호
- 비표준 BPM도 1프레임 단위 연속 스윕으로 정확히 탐지

#### BeatDetectorV2 — DBN HMM Forward
```
DBN Viterbi-max 근사:
  P(tempo | odf) = Forward pass (log-sum-exp)
  transitionLambda = 100   (템포 변화 억제 강도)
  observationLambda = 16   (SuperFlux 피크 선명도 적합값)

→ 910ms guard: r=0.5이고 resultMs < 910ms면 하모닉 보정 스킵
   (모든날=900ms/66BPM 보호, Kill This Love=910ms/130BPM 정상 보정)
→ combPriorScore: Gaussian prior (center=120BPM, σ=0.8) 로 후처리
```
- 음악 이론에 기반한 확률 모델로 템포 일관성 유지
- 고정 λ=100으로 급격한 템포 변화 억제

---

### 2-3. Beat Tracking

양측 모두 **Ellis DP** 방식 공통 사용:
```
score(t) = localScore(t) - tightness × (log(t - prev) - log(beatMs))²
tightness = 100
```
- BPM 추정값을 comb-phase 위상 앵커와 함께 DP에 주입
- DP 실패(예상 비트 수의 25% 미만) 시 세그먼트 fallback

---

### 2-4. 메모리 사용 패턴

| 단계 | BeatDetectorV1 | BeatDetectorV2 |
|------|----------------|----------------|
| 오디오 버퍼 | PCM FloatArray 전체 (4 bytes/sample) | 링버퍼 2048 샘플만 유지 |
| 3분 곡(44100Hz 모노) | ~30 MB | ~32 KB |
| 10분 상한 적용 시 | 최대 ~100 MB | 최대 ~32 KB |
| ODF 배열 | durationMs/hopMs 프레임 | durationMs/10ms 프레임 |

→ V2가 메모리 효율 압도적으로 우수. V1은 `MediaFormat.KEY_DURATION` pre-alloc으로 OOM 방지.

---

## 3. 자동이펙트 생성 시간 비교

### 3-1. v1 제너레이터 (BeatDetectorV1, hopMs=20ms) — 2026-06-11 실측

| # | 파일명 | 곡 길이 | BPM | decode | beatDetect | total |
|---|--------|---------|-----|--------|-----------|-------|
| 1 | Entrance | 1:38 | 107 | 3,168ms | 577ms | **3,747ms** |
| 2 | My World | 1:47 | 107 | 3,366ms | 596ms | **3,965ms** |
| 3 | I'll Like You | 2:07 | 130 | 4,117ms | 726ms | **4,848ms** |
| 4 | IYKYK | 2:24 | 130 | 4,351ms | 791ms | **5,147ms** |
| 5 | Concerto Grosso (Vivaldi) | 2:27 | 111 | 4,659ms | 808ms | **5,474ms** |
| 6 | Lucky Girl Syndrome | 2:25 | 120 | 4,715ms | 796ms | **5,515ms** |
| 7 | Magnetic | 2:40 | 130 | 5,030ms | 893ms | **5,928ms** |
| 8 | Midnight Fiction | 2:48 | 130 | 5,337ms | 936ms | **6,282ms** |
| 9 | ILLIT Cherish | 3:01 | 142 | 5,746ms | 978ms | **6,731ms** |
| 10 | Dynamite | 3:22 | 120 | 6,313ms | 1,111ms | **7,428ms** |
| 11 | Tick-Tack | 2:09 | 130 | 7,204ms | 703ms | **7,910ms** |
| 12 | Good Goodbye | 3:43 | 100 | 7,021ms | 1,286ms | **8,316ms** |
| 13 | 02_Magnetic_ILLIT | 2:40 | 130 | 7,679ms | 871ms | **8,555ms** |
| 14 | TOMBOY | 4:01 | 150 | 7,388ms | 1,330ms | **8,725ms** |
| 15 | Stray Kids 神메뉴 | 3:06 | 157 | 8,553ms | 1,021ms | **9,580ms** |
| 16 | Pimple | 2:40 | 103 | 9,494ms | 884ms | **10,381ms** |
| 17 | Soda Pop (KPop Demon Hunters) | 2:50 | 125 | 9,463ms | 924ms | **10,391ms** |
| 18 | Attention | 3:00 | 103 | 9,215ms | 1,005ms | **10,224ms** |
| 19 | 모든 날, 모든 순간 | 3:30 | 66 | 9,613ms | 1,294ms | **10,910ms** |
| 20 | Kill This Love | 3:13 | 130 | 10,162ms | 1,046ms | **11,213ms** |
| 21 | Celebrity | 3:15 | 100 | 10,326ms | 1,067ms | **11,397ms** |
| 22 | iKON Love Scenario | 3:31 | 120 | 11,160ms | 1,155ms | **12,319ms** |
| 23 | Golden | 3:14 | 125 | 10,635ms | 1,189ms | **11,829ms** |
| 24 | The Drum cover (COOMO) | 3:07 | 130 | 11,109ms | 1,381ms | **12,504ms** |
| 25 | 지브리 OST피아노ver. (10분 상한) | 10:00↑ | 100 | 10,599ms | 2,026ms | **12,636ms** |
| 26 | 별 보러 가자 | 5:15 | 66 | 13,260ms | 1,777ms | **15,041ms** |
| 27 | Bach Brandenburg (클래식) | 5:33 | 100 | 15,889ms | 1,952ms | **17,858ms** |
| 28 | Alan Walker Top 20 (10분 상한) | 10:00↑ | 90 | 24,404ms | 3,551ms | **27,972ms** |

**통계 (v1 / BeatDetectorV1)**

| 항목 | 값 |
|------|-----|
| 총 처리 곡 수 | 28곡 (OOM 없음) |
| 최단 | 3.7초 (Entrance, 1:38) |
| 최장 | 28.0초 (Alan Walker, 10분 상한) |
| 평균 | **9.8초/곡** |
| decode 비율 | 전체 시간의 약 85% |
| beatDetect 비율 | 전체 시간의 약 12% |
| build 비율 | < 1% |

---

### 3-2. BeatDetectorV1 vs V2 예상 성능 비교

> V2 실측 데이터는 BEAT_DETECTOR_VERSION=2 설정 후 별도 측정 필요.  
> 아래는 알고리즘 특성 기반 이론적 비교.

| 항목 | BeatDetectorV1 | BeatDetectorV2 |
|------|----------------|----------------|
| **decode 단계** | PCM FloatArray 전체 저장 → 느림 (3~24초) | 스트리밍 (PCM 미저장) → 빠름 예상 |
| **ODF 계산 비용** | IIR 필터 (저비용) — decode와 분리 | STFT 2048-pt (고비용) — decode와 동시 처리 |
| **beatDetect 단계** | Autocorrelation O(N²) sweep | DBN HMM Forward O(N×tempo_states) |
| **메모리** | ~30MB / 3분 곡 | ~32KB (링버퍼만) |
| **BPM 정확도** | 비표준 BPM 연속 스윕으로 높음 | 확률 모델 기반 템포 일관성 높음 |
| **하모닉 보정** | 단순 ratio 체크 (60%) | 910ms guard + combPriorScore (정교함) |
| **문제 사례** | 140+ BPM 곡 half-tempo 오류 가능 (ratio=0.60 보정) | Kill This Love 910ms guard로 해결됨 |

---

## 4. 이펙트 생성 로직 차이

| 항목 | v0 제너레이터 (GENERATOR_VERSION=0) | v1 제너레이터 (GENERATOR_VERSION=1) |
|------|--------------------------------------|--------------------------------------|
| 제너레이터 클래스 | AutoTimelineGeneratorBeat_v0 | AutoTimelineGeneratorBeat_v1 |
| 비트 감지기 | AutoTimelineConfig.BEAT_DETECTOR_VERSION (가변) | BeatDetectorV1 고정 |
| Hop | 20ms (엔벨로프 방식) | 20ms (PCM→IIR 변환) |
| ON 이벤트 | ON(fade=강박100/약박35) — 박자 위치별 강약 | ON(fade=100) → beatMs×0.2 후 ON(fade=60) |
| 밝기 패턴 | 강박/약박 구분 (다운비트 기반) | 2:8 비율 fade (템포 적응형) |
| 색상 | downbeatMs 기반 마디 내 위치 결정 | beatIndex % beatsPerBar 단순 순환 |
| 저장 버전 | `timeline_<id>_v0.bin` | `timeline_<id>_v1.bin` |

---

## 5. 선택 가이드

| 상황 | 권장 |
|------|------|
| 일반 K-POP, 표준 BPM (80~160) | **V1** — 빠르고 안정적 |
| 클래식, 비표준 BPM (66 BPM 등) | **V1** — 연속 스윕으로 정확 |
| 타악기 중심, 빠른 변화 패턴 | **V2** 고려 — SuperFlux onset 감지 우수 |
| 메모리 제약 환경 | **V2** — PCM 미보관 스트리밍 |
| 빠른 초기화 속도 필요 | **V2** — decode 단계 빠름 (스트리밍) |
