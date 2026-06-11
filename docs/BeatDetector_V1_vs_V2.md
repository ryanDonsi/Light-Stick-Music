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

---

## 6. 실측 정확도 비교 — V1 vs V2 (HOP20)

> 측정 기준: 2026-06-11  
> 비교 대상: BeatDetectorV1 (`BEAT_DETECTOR_VERSION=5`) vs BeatDetectorV2 HOP20ms  
> 공통 분석 곡: 23곡 × 3엔진 = 69건 / tolerance_ms = 70ms

### 6-1. 데이터 개요

| 항목 | V1 | V2 (HOP20) |
|------|-----|-----------|
| 분석 레코드 | 69건 (23곡 × 3엔진) | 84건 (28곡 × 3엔진) |
| 공통 비교 쌍 | 69건 | 69건 |
| 엔진 | beat_transformer, librosa, madmom | 동일 |

---

### 6-2. 엔진별 평균 F1 비교 (공통 23곡)

| 엔진 | V1 F1 | V2 F1 | Δ F1 | V1 Recall | V2 Recall | Δ Recall |
|------|-------|-------|------|-----------|-----------|---------|
| Beat Transformer | 0.6492 | **0.8124** | **+0.163** | 71.4% | **82.6%** | +11.3%p |
| Madmom | 0.7706 | **0.8258** | +0.055 | 79.1% | **79.9%** | +0.8%p |
| Librosa | **0.7565** | 0.7806 | +0.024 | **75.8%** | 74.8% | -1.0%p |
| **전체 평균** | 0.7254 | **0.8063** | **+0.081** | 75.4% | **79.1%** | +3.7%p |

---

### 6-3. BPM 오차 평균 비교

| 엔진 | V1 오차 | V2 오차 | 방향 |
|------|---------|---------|------|
| Beat Transformer | 11.0 BPM | **8.5 BPM** | ✅ 개선 |
| Madmom | **7.1 BPM** | 11.0 BPM | ❌ 소폭 악화 |
| Librosa | **1.5 BPM** | 17.5 BPM | ❌ 대폭 악화 |

> Librosa BPM 오차가 1.5 → 17.5로 급등. HOP 크기 변경으로 인한 주파수 해상도 변화 및 배속 혼동 가능성 있음.

---

### 6-4. 등급(Grade) 분포 비교 (공통 69건)

| 등급 | V1 | V2 | 변화 |
|------|----|----|------|
| **S** | 32 | **37** | +5 |
| A | 12 | 11 | -1 |
| B | 9 | 11 | +2 |
| C | 8 | 4 | -4 |
| D | 8 | 6 | -2 |

엔진별 주요 변화:
- **Beat Transformer**: S 4 → **14** (+10), D 5 → 3
- **Madmom**: S 13 → **15** (+2)
- **Librosa**: S **15** → 8 (-7), B 1 → **8** (+7) — 고BPM 곡에서 배속 혼동

---

### 6-5. 곡별 상세 비교 — Beat Transformer (F1 변화순)

| 곡명 | BPM GT | V1 F1 | V2 F1 | Δ F1 | Grade 변화 |
|------|--------|-------|-------|------|-----------|
| Pimple | 103.4 | 0.008 | **0.954** | **+0.946** | D → S |
| iKON - 사랑을 했다 | 117.5 | 0.404 | **0.993** | **+0.589** | D → S |
| Midnight Fiction | 129.2 | 0.523 | **0.931** | +0.408 | C → S |
| 별 보러 가자 | 66.3 | 0.526 | **0.902** | +0.376 | C → S |
| Golden | 123.0 | 0.435 | **0.805** | +0.369 | D → A |
| TOMBOY | 73.8 | 0.583 | **0.926** | +0.343 | C → S |
| Stray Kids 神메뉴 | 78.3 | 0.572 | **0.906** | +0.334 | C → S |
| Tick-Tack | 129.2 | 0.652 | **0.982** | +0.330 | B → S |
| Soda Pop | 123.0 | 0.711 | **0.948** | +0.237 | B → S |
| ILLIT - Cherish | 143.6 | 0.280 | 0.559 | +0.280 | D → C |
| Dynamite | 117.5 | 0.824 | **0.984** | +0.160 | A → S |
| Good Goodbye | 99.4 | 0.729 | **0.894** | +0.164 | B → A |
| Almond Chocolate | 107.7 | 0.933 | **0.993** | +0.060 | S → S |
| Lucky Girl Syndrome | 117.5 | 0.897 | **0.967** | +0.070 | S → S |
| 모든 날, 모든 순간 | 66.3 | 0.829 | **0.874** | +0.046 | A → A |
| IYKYK | 129.2 | **0.920** | 0.978 | +0.059 | S → S |
| My World | 107.7 | 0.924 | **0.957** | +0.033 | S → S |
| Kill This Love | 129.2 | **0.810** | 0.671 | -0.138 | A → B |
| Entrance | 107.7 | 0.447 | 0.332 | -0.115 | D → D |
| Celebrity | 99.4 | **0.798** | 0.289 | **-0.509** | A → D |
| Magnetic | 129.2 | **0.646** | 0.000 | **-0.646** | B → D |

---

### 6-6. 전체 엔진 통합 — 상위 개선 / 하락 TOP 5

**V2에서 가장 많이 개선된 곡 (TOP 5)**

| 곡명 | 엔진 | BPM GT | V1 F1 | V2 F1 | Δ F1 | Grade |
|------|------|--------|-------|-------|------|-------|
| Pimple | Madmom | 105.3 | 0.000 | **0.984** | **+0.984** | D → S |
| Pimple | Beat Transformer | 103.4 | 0.008 | **0.954** | +0.946 | D → S |
| Pimple | Librosa | 105.5 | 0.066 | **0.897** | +0.832 | D → A |
| iKON - 사랑을 했다 | Beat Transformer | 117.5 | 0.404 | **0.993** | +0.589 | D → S |
| iKON - 사랑을 했다 | Madmom | 117.7 | 0.424 | **0.989** | +0.566 | C → S |

**V2에서 가장 많이 하락한 곡 (TOP 5)**

| 곡명 | 엔진 | BPM GT | V1 F1 | V2 F1 | Δ F1 | Grade |
|------|------|--------|-------|-------|------|-------|
| Magnetic | Madmom | 130.4 | **0.886** | 0.006 | **-0.880** | S → D |
| Magnetic | Beat Transformer | 129.2 | **0.646** | 0.000 | **-0.646** | B → D |
| Celebrity | Madmom | 100.0 | **0.868** | 0.327 | -0.541 | S → D |
| Celebrity | Beat Transformer | 99.4 | **0.798** | 0.289 | -0.509 | A → D |
| Entrance | Librosa | 105.5 | **0.708** | 0.346 | -0.361 | A → D |

---

### 6-7. V1 우세 / V2 우세 분류 (Δ F1 ≥ 0.02 기준, 전체 69건)

| 분류 | 건수 | 비율 |
|------|------|------|
| **V2 우세** (개선) | 42건 | **60.9%** |
| V1 우세 (하락) | 16건 | 23.2% |
| 동등 (±0.02 이내) | 11건 | 15.9% |

---

### 6-8. 종합 해석

**V2 HOP20의 강점**
- 전체 평균 F1 **+0.08 향상** (0.725 → 0.806), 60.9%의 곡에서 개선
- Beat Transformer 엔진에서 가장 큰 수혜: F1 +0.163, Recall +11.3%p, S등급 4 → 14
- Pimple (D→S), iKON 사랑을 했다 (D→S), 별 보러 가자 (C→S) 등 문제곡 대폭 개선

**V2 HOP20의 문제점**
- **Magnetic** (BPM ~130): 3엔진 모두 F1 0.6~0.9 → 0.0~0.006으로 붕괴 — 이상 케이스, 원인 조사 필요
- **Celebrity** (BPM ~100): Beat Transformer·Madmom에서 A/S → D 급락
- **Librosa** 고BPM 곡 (TOMBOY 148, 神메뉴 157, Kill This Love 133): S → B 하락, BPM 오차 1.5 → 17.5 BPM 급등 — HOP 변경으로 인한 배속 혼동 의심
- **Entrance**: 3엔진 전부 소폭 하락
