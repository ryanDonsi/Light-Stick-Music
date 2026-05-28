# BeatDetectorV9 상세 분석 문서

> 대상 파일: `BeatDetectorV9.kt`  
> hopMs = 50ms (기본), segmentMs = 20,000ms (20초 단위 처리)

---

## 목차

1. [비트 분석 기본 방법론](#1-비트-분석-기본-방법론)  
2. [비트 보정 로직 상세](#2-비트-보정-로직-상세)  
3. [점수 산출 및 소스 선택](#3-점수-산출-및-소스-선택)  
4. [개선 방향](#4-개선-방향)

---

## 1. 비트 분석 기본 방법론

### 1.1 전체 처리 파이프라인

```
┌─────────────────────────────────────────────────────────────────┐
│  오디오 파일 (PCM)                                               │
└────────────────────────┬────────────────────────────────────────┘
                         │ IIR 필터 (AutoTimelineGeneratorBeat_v9)
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
       lowEnv         midEnv         fullEnv
   (20~300 Hz)    (300~3kHz)     (전 대역 RMS)
   베이스/킥       스네어/HH        전체 에너지
          │              │              │
          └──────────────┴──────────────┘
                         │
                  ┌──────▼──────┐
                  │ estimateGlobalBpm()  ← ① 전곡 글로벌 BPM 추정
                  │ (LOW_MID 혼합 ODF    (LOW 55% + MID 45%)
                  │  전곡 autocorr)
                  └──────┬──────┘
                         │ globalBeatMs (nullable)
                         ▼
          ┌──────────────────────────────┐
          │    20초 세그먼트 루프         │
          │                              │
          │  buildSourceOrder()  ← ④    │  소스 우선순위 결정 (6종)
          │       ↓                      │
          │  6개 소스 순서대로 시도       │
          │    combineSource()           │
          │       ↓                      │
          │  detectSingleSource()        │
          │    ② computeOdf()           │  smooth → posDiff → localNorm
          │    autoCorrelateBeat()       │  자기상관 BPM 추정
          │    ③ globalBeatMs 폴백      │  autocorr 실패 시 전곡 BPM
          │    snapPeaksToGrid()         │  그리드 스냅
          │    keepConsistentChain()     │  체인 유지
          │       ↓                      │
          │  best Trial 선택             │
          └──────────────┬───────────────┘
                         │ 세그먼트 비트 + 절대 시간 변환
                         ▼
                  dedupeCloseBeats()         중복 제거 (minDist=140ms)
                         ↓
                  estimateMedianInterval()   ⑤ hop 그리드 mode → rawBeatMs
                         ↓
                  globalBeatMs 우선 판정     17% 이내이면 globalBeatMs 채택
                         ↓
                  DetectResult (beatTimesMs, beatMs)
```

---

### 1.2 주파수 대역 소스 (BeatSource)

비트 감지는 단일 신호가 아닌 **6개 소스 조합**을 순서대로 시도한다.

| BeatSource | 구성 | 비율 | 주로 잘 잡히는 장르 |
|---|---|---|---|
| LOW | lowEnv 단독 | — | EDM, 힙합 (킥 중심) |
| LOW_MID | low×0.55 + mid×0.45 | — | 팝, 록 (킥+스네어) |
| MID | midEnv 단독 | — | 어쿠스틱, 보컬 중심 |
| FULL | fullEnv 단독 | — | 전 대역 균형 |
| MID_FULL | mid×0.60 + full×0.40 | — | 전자음악 |
| LOW_FULL | low×0.60 + full×0.40 | — | 저음 강조 팝 |

**소스 우선순위 결정 (`buildSourceOrder`)**

```
각 소스의 "분산(variance)" 기준으로 내림차순 정렬
단, LOW/LOW_MID에는 BASS_BONUS(0.003) 추가 → 킥드럼 신호 우선 탐지

점수식:
  LOW       = var(low) + 0.003
  LOW_MID   = (var(low)+var(mid))×0.5 + min(var(low),var(mid))×0.2 + 0.003
  MID       = var(mid)
  FULL      = var(full)
  MID_FULL  = (var(mid)+var(full))×0.5 + min(var(mid),var(full))×0.2
  LOW_FULL  = (var(low)+var(full))×0.5 + min(var(low),var(full))×0.2
```

분산이 높은 소스 = 에너지 변동이 큰 소스 = 비트가 더 뚜렷하게 나타남.

---

### 1.3 ODF(Onset Detection Function) 계산

```kotlin
computeOdf(env, smoothWindow=5, normWindow=60):
  1. movingAverage(env, 5)      // 스무딩: 노이즈 제거
  2. positiveDiff(smooth)       // 양의 증분: 상승 구간만 추출
  3. localNormalize(diff, 60)   // 지역 정규화: 조용한 구간 강조
```

```
원본 에너지 신호 (lowEnv):
  ▄▄████▄▄▄▄████▄▄▄  (킥 타이밍에 에너지 급상승)

① 스무딩 후 (movingAverage, window=5):
  ▃▅███▅▃▃▃▅███▅▃▃▃  (날카로운 노이즈 제거)

② 양의 증분 (positiveDiff):
  0▂█▄000 0▂█▄000   (에너지가 올라가는 순간만 남김)
  ↑ onset = 에너지 상승 = 비트 후보

③ localNormalize (window=60프레임=3초):
  조용한 구간:             큰 에너지 구간:
  ─────────                ─────────────────
  ▁▁▂▁▁▁▂▁  원본          ████▄▄████▄▄████
       ↓ 지역 최대값 기준     ↓ 지역 최대값 기준
  ▄▄█▄▄▄█▄  정규화         ▄▄█▄▄▄▄▄▄▄▄▄▄▄  정규화
  (작아도 살려냄)           (큰 피크 하나만 부각 × → 고름)
```

**globalNormalize(V8) vs localNormalize(V9) 비교**

```
곡 구조: [조용한 VERSE ─────────][강한 CHORUS ────────]

V8 (normalize01 — 전역 최대값 기준):
  VERSE 구간  CHORUS 구간
  ▁▁▁▁▁▁▁▁   ████████████  ← VERSE onset이 0에 수렴
  → autocorr 피크 미달 → VERSE 비트 감지 실패

V9 (localNormalize — 슬라이딩 윈도우 기준):
  VERSE 구간  CHORUS 구간
  ▄▄▄▄▄▄▄▄   ████████████  ← VERSE도 지역 기준으로 정규화
  → 조용한 구간에서도 비트 패턴 유지 → 신뢰도 향상
```

---

### 1.4 자기상관함수(Autocorrelation) 기반 BPM 추정

비트가 규칙적으로 반복된다면, ODF를 lag만큼 밀었을 때 원본과의 곱합이 최대가 된다.

```
ODF:   ▄ 0 0 ▄ 0 0 ▄ 0 0 ▄  (beatMs = 3 hop = 150ms)

lag=1: 0 ▄ 0 0 ▄ 0 0 ▄ 0 0
곱합:  0  → 불일치

lag=3: ▄ 0 0 ▄ 0 0 ▄ 0 0 ▄  ← 원본과 정확히 겹침
곱합:  ▄×▄ 0×0 0×0 ... → MAX ★

→ bestLag = 3 hop = 150ms → BPM = 60000 / 150 = 400 BPM
```

```
탐색 범위: minBeatMs=290ms ~ maxBeatMs=1200ms
           (약 50 BPM ~ 207 BPM)

corrArray[lag] = Σ(onset[i] × onset[i+lag]) / count

신뢰도 조건:
  bestValue   ≥ 0.015  (절대 강도)
  confidence  ≥ 0.012  (1위 - 2위 차이 + 1위)
```

---

## 2. 비트 보정 로직 상세

### 2.1 고조파 접기 (Harmonic Folding)

자기상관함수는 비트 주기의 **정수배 lag**에서도 피크가 생긴다.  
실제 BPM의 2배(빠름) 또는 절반(느림)을 잘못 선택하는 문제를 방지한다.

```
실제 비트: ♩ ♩ ♩ ♩  (beatMs = 500ms, BPM=120)

자기상관 함수 피크:
  lag=500ms  ████████  ← 실제 비트 주기 (1/1)
  lag=1000ms ████████  ← 2배 주기 (1/2 BPM = 60)
  lag=250ms  ████      ← 절반 주기 (2배 BPM = 240)
```

**4가지 고조파 접기 케이스:**

```
┌──────────────────────────────────────────────────────────────┐
│ Case ①: 절반 접기 (/2)                                       │
│                                                              │
│  bestLag=1000ms (60BPM)  halfLag=500ms                       │
│  corrArray[halfLag] / corrArray[bestLag] ≥ 0.40  → 채택     │
│                                                              │
│  의미: 실제 120BPM인데 60BPM으로 오검출된 경우 보정          │
│                                                              │
│  ♩ ♩ ♩ ♩  →  ♩ ♩  잘못 검출  →  /2  →  ♩ ♩ ♩ ♩  복원  │
│                                                              │
│  2단계 접기: halfLag에서도 quarterLag 확인 (ratio ≥ 0.40)   │
│  예: 1200ms → 600ms → 300ms 까지 축소                        │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Case ②: 1/3 접기 (/3)                                        │
│                                                              │
│  bestLag=900ms (67BPM)  thirdLag=300ms                       │
│  corrArray[thirdLag] / corrArray[bestLag] ≥ 0.35  → 채택   │
│                                                              │
│  의미: 3박자(왈츠) 등에서 실제 비트의 3배 lag을 잡은 경우   │
│                                                              │
│  ♩♩♩  ♩♩♩  (3/4박자)  → 900ms 오검출 → /3 → 300ms 복원   │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Case ③: 2/3 배율 (×2/3)                                     │
│                                                              │
│  bestLag=600ms  twoThirdLag=400ms                            │
│  ttValue / bestValue ≥ 0.75  → 채택                         │
│  조건: twoThirdBeatMs ≤ maxBeatMs×0.65                      │
│                                                              │
│  의미: 8비트 패턴에서 3/4 지점이 강한 경우 보정             │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Case ④: 2배 확장 (×2)                                        │
│                                                              │
│  bestLag=350ms (171BPM)  doubleLag=700ms                     │
│  doubleValue / bestValue ≥ 0.80                              │
│  조건: bestLag > maxBeatMs×0.55 (너무 빠른 값만 적용)        │
│                                                              │
│  의미: 실제 2박에 1번인데 매 박을 잡은 경우 → 2배로 늘림    │
│                                                              │
│  ♩♪♩♪  (350ms) 오검출  →  ×2  →  ♩  ♩  (700ms) 복원     │
└──────────────────────────────────────────────────────────────┘
```

**고조파 접기 판정 흐름:**

```
bestLag 결정
    │
    ├─ halfLag ≥ minLag && corr[halfLag]/bestValue ≥ 0.40?
    │       │Yes
    │       ├─ quarterLag ≥ minLag && corr[quarterLag]/halfValue ≥ 0.40?
    │       │       │Yes → return quarterLag (가장 세밀한 비트)
    │       │       └No  → return halfLag
    │
    ├─ thirdLag ≥ minLag && corr[thirdLag]/bestValue ≥ 0.35?
    │       └Yes → return thirdLag
    │
    ├─ twoThirdBeatMs 범위 내 && ttValue/bestValue ≥ 0.75?
    │       └Yes → return twoThirdLag
    │
    ├─ bestLag > maxBeatMs×0.55 && doubleValue/bestValue ≥ 0.80?
    │       └Yes → return doubleLag
    │
    └─ 접기 없음 → return bestLag (원본)
```

---

### 2.2 글로벌 BPM 폴백 (3단계 계층)

세그먼트 단위 autocorr이 실패하더라도 비트를 추정할 수 있도록 3단계 폴백을 구성한다.

```
┌─────────────────────────────────────────────────────────────┐
│  detectSingleSource() 내 BPM 결정 흐름                       │
│                                                             │
│  1단계: 세그먼트 autocorr                                    │
│    autoCorrelateBeat(onset, hopMs, min, max)                │
│    성공(acResult != null) ─────────────────────────────► ① │
│    실패                                                      │
│       │                                                     │
│  2단계: 전곡 globalBeatMs 폴백                               │
│    globalBeatMs != null ────────────────────────────────► ② │
│    (acPeak = 0.5 로 가정)                                   │
│    null                                                      │
│       │                                                     │
│  3단계: rawPeak 간격 중앙값 폴백                              │
│    rawPeakMedianInterval(rawPeaks) ─────────────────────► ③ │
│    실패 → "autocorr weak" 반환                              │
└─────────────────────────────────────────────────────────────┘

① acResult 사용:  beatMs = acResult.first, acPeak = acResult.second
② globalBeatMs:   beatMs = globalBeatMs,   acPeak = 0.5 (assumed)
③ rawPeak median: 피크 간 간격 중앙값 → snap → chain → score
```

**글로벌 BPM 추정 (`estimateGlobalBpm`)**

```
전곡 처리:
  LOW×0.55 + MID×0.45  →  computeOdf(normWindow=80프레임=4초)
                       →  autoCorrelateBeat(전곡 전체)
                       →  globalBeatMs 반환 (nullable)

세그먼트보다 넓은 윈도우(80 vs 60):
  전곡 ODF는 에너지 범위가 더 넓으므로
  더 넓은 지역 윈도우로 평탄화해야 안정적
```

---

### 2.3 그리드 스냅 (snapPeaksToGrid)

Autocorr로 얻은 `beatMs`를 격자(grid)로 삼아 실제 피크들을 정렬한다.

```
beatMs = 500ms (10 hop), snapToleranceMs = 150ms (3 hop)

rawPeaks (ODF 피크 위치):
  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
  0  1  2  3  4  5  6  7  8  9  10 11 12 13 14   (hop 인덱스)
        ↑        ↑           ↑        ↑
      peak      peak        peak     peak

anchor = 첫 번째 peak (hop=2):
  그리드: ... 2-10=−8, 2, 2+10=12, 2+20=22 ...
  허용범위 ±3hop:
                                    ±3      ±3
  hop=2 → snap hit ✓    hop=7(±3→4~10) → peak at 7 hit ✓
  hop=12 → peak at 11 hit ✓    ...

최고 누적 onset 점수를 가진 anchor 선택
→ bestGrid = 스냅된 비트 프레임 목록
```

**여러 anchor 시도:**

```
anchor A로 시도:  스냅 적중 = 4개, 누적 onset = 3.2
anchor B로 시도:  스냅 적중 = 5개, 누적 onset = 4.7  ← 선택
anchor C로 시도:  스냅 적중 = 3개, 누적 onset = 2.1
```

---

### 2.4 체인 유지 (keepConsistentChain)

스냅된 비트 중 **간격이 일관된 것만** 골라내어 불규칙 잡음을 제거한다.

```
expectedBeatMs = 500ms, chainToleranceMs = 170ms

스냅된 비트 시퀀스 (ms):
  0    510   990   1510  1700  2010  2500
  │     │     │     │     │     │     │
  ├─500─┤ 480 ├─520─┤ 190 ├─310─┤─490─┤

허용 조건:
  ① |diff - 500| ≤ 170    → 330ms~670ms  (1배 간격)
  ② |diff - 1000| ≤ 102   → 898ms~1102ms (2배 간격, tol×0.6)
  ③ |diff - 250| ≤ 136    → 114ms~386ms  (0.5배 간격, tol×0.8)

결과:
  0  → 510  diff=510 ✓(①)
  510 → 990  diff=480 ✓(①)
  990 → 1510 diff=520 ✓(①)
  1510 → 1700 diff=190 ✗ (탈락)
  1510 → 2010 diff=500 ✓(①)
  2010 → 2500 diff=490 ✓(①)

→ 체인: [0, 510, 990, 1510, 2010, 2500]  (1700 제거됨)
```

---

### 2.5 finalBeatMs 결정 — hop 그리드 Mode

세그먼트 비트 병합 후 전체 비트 간격의 **최빈값**을 BPM으로 채택한다.

```
이유: hop 양자화 노이즈로 median이 편향되는 문제 해결
예시: 실제 beatMs=400ms, hopMs=50ms

비트 간격 목록 (ms): [400, 450, 400, 400, 400, 450, 400]
  median = 400  → 괜찮아 보이지만...

간격이 [400, 450, 400, 450, 400, 450, 400]으로 섞이면:
  median = 450  → 편향 발생 ✗

V9 hop 그리드 binning + mode:
  bin = (interval / 50) × 50  → [400, 450, 400, 450, 400, 450, 400]
  mode: 400ms(4회), 450ms(3회)  → 400ms 채택 ✓
```

**globalBeatMs 우선 판정:**

```
조건: globalBeatMs < rawBeatMs
   && (rawBeatMs - globalBeatMs) < rawBeatMs / 6  (17% 이내)

예시:
  rawBeatMs   = 450ms (hop 양자화 편향)
  globalBeatMs = 400ms (전곡 autocorr)
  차이 = 50ms, 50 < 450/6 = 75  → globalBeatMs = 400ms 채택

의미: 드문 비트(33개)의 경우 hop 스텝 1개 차이가 BPM 오차를 만들 때
     전곡 autocorr이 더 신뢰도 높음
```

---

### 2.6 중복 비트 제거 (dedupeCloseBeats)

세그먼트 경계에서 겹치는 비트를 최소 간격(140ms) 기준으로 제거한다.

```
세그먼트 0 마지막 비트:  19850ms
세그먼트 1 첫 비트:      19900ms
차이: 50ms < 140ms  → 19900ms 제거 (앞의 것 유지)
```

---

## 3. 점수 산출 및 소스 선택

### 3.1 Trial 점수 (`detectSingleSource` 반환)

```
score = densityScore×0.40 + snapRatio×0.30 + acPeak×0.20 + onsetMax×0.10

densityScore = min(1, 실제 비트 수 / 예상 비트 수)
               → BPM 기준 몇 %의 비트를 찾았는가
snapRatio    = chained 비트 수 / rawPeak 수
               → 피크 중 그리드에 정렬된 비율
acPeak       = autocorr 최고 상관값
               → 신호의 주기성 강도
onsetMax     = ODF 최대값
               → 신호 선명도
```

### 3.2 세그먼트 최우선 소스 결정

```
동일 세그먼트에서 6개 소스 Trial 중 best 선택 기준:

1. reason == "ok" 인 것만 대상
2. beats 수 > best.beats AND score ≥ best.score × 0.70  → 교체
3. beats 수 == best.beats AND score > best.score       → 교체
```

### 3.3 최종 소스 결정 (sourceVotes)

```
각 세그먼트에서 이긴 소스에 투표
→ 전체 세그먼트 중 가장 많이 이긴 소스 = finalSource
```

---

## 4. 개선 방향

### 4.1 BPM 후보 다중 추적 (Multi-Hypothesis Tracking)

**현재 문제:**  
각 세그먼트에서 단일 best Trial만 선택한다. 템포 변화(tempo rubato)나 박자 전환이 있는 곡에서 세그먼트 간 BPM 불연속이 발생할 수 있다.

**개선 방향:**

```
현재:  seg0:120BPM, seg1:120BPM, seg2:240BPM(오검출), seg3:120BPM
결과:  일부 구간 비트 틀림

개선:  BPM 후보 TOP-2 유지 → 이전 세그먼트 BPM과의 연속성 가중치 부여
       seg2: 240BPM(score=0.6) vs 120BPM(score=0.55)
             → 이전 세그먼트 120BPM과 연속성 보너스 → 120BPM 채택
```

---

### 4.2 다운비트 감지 (Downbeat Detection)

**현재 문제:**  
비트 위치는 찾지만 마디의 첫 박(downbeat, 1박)이 어디인지 알 수 없다. LED 이펙트는 1박에 STRONG 점등을 기대하지만, 현재는 첫 감지 비트가 어느 박자인지 모른다.

**개선 방향:**

```
1. 에너지 패턴 분석: 4비트 사이클에서 첫 박 에너지가 가장 큰 경향
   ODF 값 비교: [t=0: 0.8] [t=500: 0.3] [t=1000: 0.5] [t=1500: 0.2]
               → t=0이 downbeat 후보

2. 저주파(LOW) 에너지 피크 = 킥드럼 = 주로 1박, 3박
   → LOW 피크 패턴으로 1박 추정

3. 결과: DetectResult에 downbeatOffsetMs 추가
   → ON_PULSE STRONG 점등이 1박에 정렬됨
```

---

### 4.3 박자 분모 감지 (Time Signature Detection)

**현재 문제:**  
4/4, 3/4(왈츠), 6/8 등 박자 구조를 알 수 없어 BeatAccent 패턴이 항상 4박자(% 4) 고정이다.

**개선 방향:**

```
autocorr에서 3박자 패턴 감지:
  lag = beatMs × 3 의 상관값이 lag = beatMs × 4 보다 크면 → 3/4박자

현재 BeatAccent:  % 4 (0=STRONG, 2=MEDIUM, 1,3=WEAK)
3/4박자 감지 시:  % 3 (0=STRONG, 1=MEDIUM, 2=WEAK)
6/8박자 감지 시:  % 6 (0,3=STRONG, 2,5=MEDIUM, 1,4=WEAK)
```

---

### 4.4 비트 신뢰도 구간별 가중치 (Beat Confidence per Beat)

**현재 문제:**  
비트 리스트는 단순 타임스탬프 배열. 각 비트의 신뢰도를 모른다. 불확실한 비트에도 STRONG 점등을 한다.

**개선 방향:**

```kotlin
// 현재
data class DetectResult(
    val beatTimesMs: List<Long>,
    val beatMs: Long,
    ...
)

// 개선: 비트별 신뢰도 추가
data class TimedBeat(
    val timeMs: Long,
    val confidence: Float  // 0.0~1.0, ODF 값 기반
)

data class DetectResult(
    val beats: List<TimedBeat>,
    val beatMs: Long,
    ...
)

// 활용: confidence < 0.4 → MEDIUM 처리, < 0.2 → SKIP
```

---

### 4.5 조화 분석 강화 (Spectral Flux ODF)

**현재 문제:**  
ODF는 에너지 상승만 보는 단순 `positiveDiff`. 타악기가 약한 발라드·클래식에서 onset 검출이 불안정하다.

**개선 방향:**

```
현재 ODF:  positiveDiff(RMS)

개선 ODF:  Spectral Flux
  = Σ max(0, |X[k, t]| - |X[k, t-1]|)  (주파수 빈별 에너지 증가 합산)

  단순 RMS 상승과 달리 특정 주파수 대역의 타격감을 더 민감하게 포착
  → 발라드 피아노, 통기타 스트로크 비트 감지 개선

구현 필요: FFT 추가 (현재 MediaCodec 디코딩만 있어 별도 FFT 파이프라인 필요)
```

---

### 4.6 적응형 segmentMs (Adaptive Segment Length)

**현재 문제:**  
짧은 곡(< 60초)은 전체를 하나의 세그먼트로 처리하지만, 중간 길이(60~120초) 곡에서는 20초 세그먼트가 최적이 아닐 수 있다.

**개선 방향:**

```
현재:  durationMs < 60,000ms → effectiveSegmentMs = durationMs
       그 외                 → effectiveSegmentMs = 20,000ms

개선:
  60s   미만 → 전체 1 세그먼트
  60~120s   → 30s 세그먼트 (2~4개)
  120s  초과 → 20s 세그먼트 (기존)

이유: 60BPM(1000ms beat)은 20초에 비트 20개 → autocorr 충분
     하지만 40BPM(1500ms beat)은 20초에 13개만 → 30초가 더 안정적
```

---

## 요약

| 기능 | 현재 구현 | 핵심 목적 |
|---|---|---|
| 전곡 글로벌 BPM | LOW_MID 전곡 autocorr | 세그먼트 실패 보완 |
| localNormalize | 슬라이딩 윈도우 60f | 조용한 구간 onset 살리기 |
| 3단계 폴백 | autocorr → global → rawPeak | 감지 실패 최소화 |
| BASS_BONUS | LOW/LOW_MID +0.003 | 킥드럼 소스 우선 |
| hop 그리드 mode | binning → 최빈값 | 양자화 편향 제거 |
| 고조파 접기 | /2, /3, ×2/3, ×2 | 배속/절반 오검출 보정 |
| 그리드 스냅 | anchor 기반 다방향 탐색 | 피크 → BPM 격자 정렬 |
| 체인 유지 | 1×, 2×, 0.5× tol 허용 | 불규칙 잡음 제거 |
