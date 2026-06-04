# BeatDetector 사용자 앱 개선 계획

> 목표: 처리 시간 30초 이하 유지, K-pop 기준 정확도 78~83% 달성  
> 대상: `BeatDetectorV2.kt` (현재 BeatDetectorRouter 기본값 V11 = BeatDetectorV2)  
> 전제: 외부 ML 모델 없음, 순수 DSP 자체 구현

---

## 목차

1. [현황 분석](#1-현황-분석)
2. [개선 항목 요약](#2-개선-항목-요약)
3. [Fix 1 — MIN_BEAT_MS 조정](#3-fix-1--min_beat_ms-조정)
4. [Fix 2 — Comb Filter 2배수 해소](#4-fix-2--comb-filter-2배수-해소)
5. [Fix 3 — Multi-band Positive Flux ODF](#5-fix-3--multi-band-positive-flux-odf)
6. [Fix 4 — Adaptive Peak Threshold](#6-fix-4--adaptive-peak-threshold)
7. [Fix 5 — Ellis DP Beat Tracker](#7-fix-5--ellis-dp-beat-tracker)
8. [처리 시간 영향](#8-처리-시간-영향)
9. [전문가 앱 분리 항목](#9-전문가-앱-분리-항목)
10. [단계별 구현 순서](#10-단계별-구현-순서)

---

## 1. 현황 분석

### 현재 파이프라인

```
PCM → IIR 필터 → RMS 에너지 → 오토코릴레이션 → 피크 선택 → 갭필
```

### 주요 문제

| 문제 | 원인 | 예시 |
|------|------|------|
| BPM 2배 오탐 | MIN_BEAT_MS가 너무 낮아 8분음표 통과 | iKON Love Scenario: 85 BPM → 170 BPM 감지 |
| 에너지/멜로디 간섭 | IIR RMS가 변화량이 아닌 에너지 레벨을 측정 | 보컬 에너지가 킥드럼보다 강한 구간에서 오탐 |
| 조용한 구간 가짜 피크 | 전곡 고정 임계값 사용 | 발라드 조용한 구간에서 임의 비트 생성 |
| 갭 구간 비트 불안정 | 갭 채움 로직이 단순 그리드 보간 | 브릿지·인트로 구간 박자 흔들림 |

### 현재 처리 시간 (3분 곡 기준, 미드레인지 Android)

```
MediaCodec 디코딩 + IIR 필터링    10~15초  ← 병목
BeatDetectorV2 분석               1~2초
SectionDetectorV1                 1초 이하
타임라인 생성                      1초 이하
────────────────────────────────────────
현재 총합                          13~19초
30초 한도까지 여유                  11~17초
```

### 현재 추정 정확도

| 장르 | 추정 정확도 |
|------|------------|
| K-pop 댄스/아이돌 | 55~65% |
| K-pop 발라드 | 45~55% |
| 전장르 평균 | 55~60% |

> 측정 기준: 실제 비트 ±70ms 이내 매칭 F-measure

---

## 2. 개선 항목 요약

| Fix | 내용 | 추가 처리 시간 | 예상 정확도 향상 |
|-----|------|--------------|----------------|
| Fix 1 | MIN_BEAT_MS 조정 | 0초 | +8~10% |
| Fix 2 | Comb Filter 2배수 해소 | < 0.5초 | +7~9% |
| Fix 3 | Multi-band Positive Flux ODF | 0초 | +5~6% |
| Fix 4 | Adaptive Peak Threshold | < 0.3초 | +2~3% |
| Fix 5 | Ellis DP Beat Tracker | < 1초 | +2~3% |
| **합계** | | **< 2초 추가** | **+24~31%** |

**목표 달성 예측**

```
현재           55~60%
Fix 1 적용 후  65~70%
Fix 1+2        72~79%
Fix 1+2+3      77~85%
Fix 1+2+3+4    79~87%
Fix 1+2+3+4+5  80~88%  ← DSP 자체 구현 실용 상한
```

---

## 3. Fix 1 — MIN_BEAT_MS 조정

### 문제

`MIN_BEAT_MS=320ms`(V0), `MIN_BEAT_MS=290ms`(V3)가 너무 낮아 8분음표 레벨 비트가 통과된다.

```
85 BPM 곡의 8분음표 = 353ms → 320ms 최소값 통과 → 170 BPM으로 오탐
```

### 수정

```kotlin
// BeatDetectorV2.Params 기본값 수정
data class Params(
    val minBeatMs: Long = 430L,   // 기존 290~320ms → 430ms
    val maxBeatMs: Long = 1000L,  // 기존 1200ms → 1000ms (60 BPM 이하 제외)
    // ... 나머지 파라미터 유지
)
```

### 근거

| MIN_BEAT_MS | 차단되는 케이스 | 통과되는 케이스 |
|-------------|----------------|----------------|
| 320ms | 187 BPM 이상 8분음표 | 85 BPM 8분음표(353ms) 통과 ❌ |
| 430ms | 140 BPM 이상 8분음표 | 85 BPM 8분음표 차단 ✅ |

K-pop에서 140 BPM 이상이면 8분음표가 아닌 실제 빠른 박자이므로 통과가 맞다.

### AutoTimelineGeneratorBeat_v3.kt 수정

```kotlin
val beatResult = BeatDetectorV2.detect(lowEnv, midEnv, fullEnv,
    BeatDetectorV2.Params(
        hopMs             = HOP_MS,
        minBeatMs         = 430L,   // 기존 290L → 430L
        maxBeatMs         = 1000L,  // 기존 1200L → 1000L
        // ... 나머지 유지
    ))
```

---

## 4. Fix 2 — Comb Filter 2배수 해소

### 문제

오토코릴레이션이 85 BPM과 170 BPM 피크를 동시에 검출할 때 더 강한 쪽을 선택하지 못한다.

### 알고리즘

Comb Filter Score: 특정 BPM의 배수 위치(1x~4x)에서 ODF 에너지 합산.
실제 BPM일수록 배수 위치마다 강한 onset이 있어 점수가 높다.

```kotlin
private fun combScore(odf: FloatArray, periodMs: Long, hopMs: Long): Float {
    val period = (periodMs / hopMs).toInt().coerceAtLeast(1)
    var score = 0f
    for (tap in 1..4) {
        val lag = period * tap
        if (lag >= odf.size) break
        for (i in 0 until odf.size - lag) {
            score += odf[i] * odf[i + lag]
        }
    }
    return score / odf.size
}

private fun resolveOctave(
    odf: FloatArray,
    detectedBeatMs: Long,
    hopMs: Long
): Long {
    val half   = detectedBeatMs * 2L
    val double = detectedBeatMs / 2L

    val scoreCurrent = combScore(odf, detectedBeatMs, hopMs)
    val scoreHalf    = if (half   <= MAX_BEAT_MS) combScore(odf, half,   hopMs) else 0f
    val scoreDouble  = if (double >= MIN_BEAT_MS) combScore(odf, double, hopMs) else 0f

    return when {
        scoreHalf   > scoreCurrent * 0.82f -> half    // 절반 BPM이 더 강함
        scoreDouble > scoreCurrent * 1.25f -> double  // 2배 BPM이 압도적
        else                               -> detectedBeatMs
    }
}
```

### 적용 위치

BeatDetectorV2 글로벌 BPM 추정 직후 `resolveOctave()` 호출:

```kotlin
val rawBeatMs = estimateGlobalBpm(odf, hopMs)
val beatMs    = resolveOctave(odf, rawBeatMs, hopMs)  // 2배수 보정
```

---

## 5. Fix 3 — Multi-band Positive Flux ODF

### 문제

현재 IIR RMS는 에너지 **레벨**을 측정한다.  
킥드럼이 강하면 그 이후 수십 프레임 동안도 높은 값이 유지되어 onset 경계가 뭉개진다.

```
에너지(현재):  ___/‾‾‾‾‾‾\___    뭉개진 언덕
Flux(목표):    ___/\_________    날카로운 스파이크 (onset 순간만)
```

### 수정

기존 IIR 루프에서 RMS 누산 대신 프레임 간 positive 변화량을 저장한다.  
루프 구조 변경 없이 저장 방식만 변경하므로 처리 시간 영향이 없다.

```kotlin
// 밴드별 이전 에너지 상태 (루프 외부 선언)
var prevLowEnergy  = 0f
var prevMidEnergy  = 0f
var prevFullEnergy = 0f
var prevHighEnergy = 0f

// hopSamples마다 flush 시 (기존 RMS 저장 부분 교체)
if (winPos >= hopSamples) {
    val lowE  = sqrt(lowSumSq  / hopSamples)
    val midE  = sqrt(midSumSq  / hopSamples)
    val fullE = sqrt(fullSumSq / hopSamples)
    val highE = sqrt(highSumSq / hopSamples)

    // RMS 대신 positive flux 저장
    outLow  += max(0f, lowE  - prevLowEnergy)  * 1.4f  // 킥 가중치
    outMid  += max(0f, midE  - prevMidEnergy)  * 1.2f  // 스네어 가중치
    outFull += max(0f, fullE - prevFullEnergy) * 1.0f
    outHigh += max(0f, highE - prevHighEnergy) * 0.7f  // 하이햇 가중치

    prevLowEnergy  = lowE
    prevMidEnergy  = midE
    prevFullEnergy = fullE
    prevHighEnergy = highE

    lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; highSumSq = 0f; winPos = 0
}
```

### 가중치 근거

| 밴드 | 가중치 | 이유 |
|------|--------|------|
| LOW (킥드럼) | 1.4 | 비트 감지의 핵심 신호 |
| MID (스네어) | 1.2 | 2·4박 강조, 보조 신호 |
| FULL | 1.0 | 전체 에너지 기준 |
| HIGH (하이햇) | 0.7 | 세분화 기여, 과도하면 잡음 |

---

## 6. Fix 4 — Adaptive Peak Threshold

### 문제

전곡 고정 임계값을 사용하면 조용한 구간(발라드 인트로, 브릿지)에서 낮은 노이즈 피크가 비트로 인식된다.

### 수정

로컬 윈도우(±2초) 기준 적응형 임계값 적용:

```kotlin
private fun adaptiveThreshold(
    odf: FloatArray,
    windowFrames: Int = 40    // 50ms × 40 = 약 2초 윈도우
): FloatArray {
    val threshold = FloatArray(odf.size)
    for (i in odf.indices) {
        val lo = max(0, i - windowFrames)
        val hi = min(odf.lastIndex, i + windowFrames)
        val localMean = odf.slice(lo..hi).average().toFloat()
        val localMax  = odf.slice(lo..hi).maxOrNull() ?: 0f
        // 로컬 평균의 1.5배 또는 로컬 최대의 30% 중 큰 값
        threshold[i] = max(localMean * 1.5f, localMax * 0.30f)
    }
    return threshold
}

// 피크 선택 시 적용
val threshold = adaptiveThreshold(odf)
val peaks = odf.indices.filter { i ->
    odf[i] > threshold[i] &&                          // 로컬 임계값 초과
    (i == 0 || odf[i] >= odf[i - 1]) &&              // 상승 중
    (i == odf.lastIndex || odf[i] > odf[i + 1])      // 피크 정점
}
```

---

## 7. Fix 5 — Ellis DP Beat Tracker

### 문제

현재 갭 채움 로직은 단순 그리드 보간이다.  
조용한 구간에서 비트를 찍을 때 앞뒤 맥락 없이 균등 배분하므로 실제 그루브와 어긋난다.

### 알고리즘 (Ellis 2007 방법)

비트 경로를 동적 프로그래밍으로 최적화한다.  
강한 onset을 밟되 목표 BPM 간격에서 벗어나면 벌점을 부과한다.

```kotlin
private fun dpBeatTracker(
    odf: FloatArray,
    targetPeriodMs: Long,
    hopMs: Long
): LongArray {
    val targetFrames = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
    val n = odf.size
    val alpha = 0.9f   // 규칙성(BPM 일관성) vs onset 강도 트레이드오프

    val score = FloatArray(n) { Float.NEGATIVE_INFINITY }
    val prev  = IntArray(n) { -1 }

    // 초기화: 첫 targetPeriod 구간
    for (i in 0 until minOf(targetFrames, n)) {
        score[i] = odf[i]
    }

    // Forward pass
    for (t in targetFrames until n) {
        val lo = (targetFrames * 0.5f).toInt()
        val hi = (targetFrames * 2.0f).toInt()
        for (lag in lo..hi) {
            val p = t - lag
            if (p < 0 || score[p] == Float.NEGATIVE_INFINITY) continue
            val logRatio = ln(lag.toFloat() / targetFrames)
            val penalty  = alpha * logRatio * logRatio
            val candidate = score[p] - penalty
            if (candidate > score[t]) {
                score[t] = candidate
                prev[t]  = p
            }
        }
        score[t] += odf[t]
    }

    // Backtrack: 최고 점수 위치에서 역추적
    var t = score.indices.maxByOrNull { score[it] } ?: return LongArray(0)
    val beats = mutableListOf<Long>()
    while (t >= 0 && prev[t] != t) {
        beats.add(t.toLong() * hopMs)
        val p = prev[t]
        if (p < 0) break
        t = p
    }
    return beats.reversed().toLongArray()
}
```

### alpha 파라미터 튜닝 가이드

| alpha 값 | 동작 |
|----------|------|
| 0.5 이하 | onset 강도 우선, BPM 흔들림 허용 |
| 0.9 (기본) | 균형 (K-pop 권장) |
| 1.5 이상 | BPM 일관성 강제, 엇박 허용 안 함 |

---

## 8. 처리 시간 영향

3분 곡 기준, 미드레인지 Android 기기:

| 항목 | 현재 | 개선 후 |
|------|------|---------|
| MediaCodec 디코딩 + IIR | 10~15초 | 10~15초 (변화 없음) |
| BeatDetector (Fix 1~4 포함) | 1~2초 | 1.5~2.5초 |
| Ellis DP (Fix 5) | — | +0.5~1초 |
| SectionDetector | 1초 이하 | 1초 이하 |
| 타임라인 생성 | 1초 이하 | 1초 이하 |
| **총합** | **13~19초** | **14~20초** |

30초 한도 내 충분히 처리 가능.

---

## 9. 전문가 앱 분리 항목

30초를 초과하거나 구현 난이도가 높아 사용자 앱에 포함하지 않는 항목:

| 항목 | 추가 처리 시간 | 분리 이유 |
|------|--------------|----------|
| FFT 기반 SuperFlux ODF | +3~6초 | STFT 전체 패스 추가 |
| HPSS (드럼 분리) | +5~10초 | 스펙트로그램 메디안 필터 연산 |
| ONNX 딥러닝 모델 | +10~30초 | 모델 추론 시간 + 15~25MB 추가 |

전문가 앱 목표 정확도: 85~90% (K-pop 기준)

---

## 10. 단계별 구현 순서

```
1단계 — Quick Win (3~5일)
  Fix 1: MIN_BEAT_MS 430ms 조정
  Fix 2: Comb Filter resolveOctave() 추가
  → V0 검증: 10~20곡 테스트, Love Scenario BPM 오탐 해소 확인
  → 목표: 70~79%

2단계 — ODF 개선 (3~5일)
  Fix 3: Multi-band Positive Flux ODF 적용
  Fix 4: Adaptive Peak Threshold 적용
  → V0 검증: 발라드 포함 다양한 장르 테스트
  → 목표: 79~85%

3단계 — Beat Tracker 교체 (3~5일)
  Fix 5: Ellis DP Beat Tracker 적용
  → V0 검증: 갭/조용한 구간 개선 확인
  → 목표: 80~88%

4단계 — 전문가 앱 분기 (별도 일정)
  HPSS + FFT SuperFlux 추가
  선택적: ONNX 딥러닝 모델
```

### 각 단계 검증 방법

V0 제너레이터(Beat 감지 검증 모드)로 생성한 타임라인을 재생하며 확인:
- **White(강박)** 위치가 실제 1박에 맞는지
- **BPM** 이 감지 로그와 체감이 일치하는지
- **색상 시퀀스**(W-P-Y-C)가 끊김 없이 균등하게 반복되는지
- **조용한 구간**에서 임의 비트가 생성되지 않는지
