# BeatDetectorV8 → BeatDetectorV9 변경 명세

> 대상 파일: `BeatDetectorV8.kt` → `BeatDetectorV9.kt`  
> 변경 범위: BPM 추정 신뢰도 개선 (5개 항목)  
> Harmonic folding / Peak snapping / Chain 로직은 양 버전 동일

---

## 목차

1. [전곡 글로벌 BPM 추정 추가](#1-전곡-글로벌-bpm-추정-추가)
2. [ODF 정규화 방식 변경](#2-odf-정규화-방식-변경)
3. [세그먼트 폴백 계층 추가](#3-세그먼트-폴백-계층-추가)
4. [소스 우선순위 — LOW / LOW_MID 보너스](#4-소스-우선순위--low--low_mid-보너스)
5. [finalBeatMs 결정 방식 개선](#5-finalbeatsms-결정-방식-개선)
6. [변경되지 않은 항목](#6-변경되지-않은-항목)
7. [전체 처리 흐름 비교](#7-전체-처리-흐름-비교)

---

## 1. 전곡 글로벌 BPM 추정 추가

### 문제 (As-Is)
V8은 20초 세그먼트 단위 autocorrelation만 사용한다. 조용한 세그먼트나 짧은 구간에서 autocorr이 실패하면 해당 세그먼트의 비트 정보가 유실된다.

### As-Is — V8 detect() 진입부

```kotlin
// V8: 세그먼트 분할 후 바로 루프 진입 — 전곡 BPM 없음
val effectiveSegmentMs = if (durationMs < 60_000L) durationMs else params.segmentMs
val segmentFrames = max(1, (effectiveSegmentMs / params.hopMs).toInt())
val segmentCount = (minSize + segmentFrames - 1) / segmentFrames

for (segIndex in 0 until segmentCount) { ... }
```

### To-Be — V9 detect() 진입부

```kotlin
// V9: 세그먼트 루프 전에 전곡 globalBeatMs를 먼저 추정
val globalBeatMs = estimateGlobalBpm(low, mid, params)
Log.d(TAG, "V9 globalBeatMs=$globalBeatMs durationMs=$durationMs")

val effectiveSegmentMs = if (durationMs < 60_000L) durationMs else params.segmentMs
...
for (segIndex in 0 until segmentCount) { ... }
```

### 신규 함수: `estimateGlobalBpm()`

```kotlin
private fun estimateGlobalBpm(low: List<Float>, mid: List<Float>, params: Params): Long? {
    val combined = mix(low, mid, 0.55f, 0.45f)           // LOW_MID 혼합
    val onset = computeOdf(combined, smoothWindow = 5,    // 전곡 ODF 계산
                           normWindow = GLOBAL_NORM_WINDOW)  // normWindow = 80프레임(4초)
    return autoCorrelateBeat(onset, params.hopMs,
                             params.minBeatMs, params.maxBeatMs)?.first
}
```

| 항목 | V8 | V9 |
|---|---|---|
| 전곡 BPM 추정 | 없음 | LOW_MID 전곡 ODF → autocorr |
| 정규화 윈도우 | — | GLOBAL_NORM_WINDOW = 80프레임(4초) |
| 결과 활용 | — | 세그먼트 autocorr 실패 시 폴백 + finalBeatMs 보정 |

---

## 2. ODF 정규화 방식 변경

### 문제 (As-Is)
V8의 `normalize01()`은 전체 구간 중 가장 큰 값을 기준으로 정규화한다. 곡 중 에너지가 강한 구간이 하나라도 있으면 조용한 구간의 onset이 0에 수렴해 autocorrelation threshold를 통과하지 못한다.

### As-Is — V8 ODF 계산

```kotlin
// V8: detectSingleSource() 내부
val smooth = movingAverage(env, params.onsetSmoothWindow)
val diff   = positiveDiff(smooth)
val onset  = normalize01(diff)    // ← 전구간 글로벌 정규화

// normalize01 구현
private fun normalize01(src: List<Float>): List<Float> {
    val mn = src.minOrNull() ?: 0f
    val mx = src.maxOrNull() ?: 0f        // 전구간 최댓값 1개로 정규화
    val range = (mx - mn)
    if (range <= 1e-6f) return List(src.size) { 0f }
    return src.map { ((it - mn) / range).coerceIn(0f, 1f) }
}
```

### To-Be — V9 ODF 계산

```kotlin
// V9: computeOdf() 함수로 통합
private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> {
    val smooth = movingAverage(env, smoothWindow)
    val diff   = positiveDiff(smooth)
    return localNormalize(diff, normWindow)    // ← 슬라이딩 윈도우 지역 정규화
}

// detectSingleSource() 내부
val onset = computeOdf(env, params.onsetSmoothWindow, LOCAL_NORM_WINDOW)

// localNormalize 구현
private fun localNormalize(src: List<Float>, windowFrames: Int): List<Float> {
    val out = ArrayList<Float>(src.size)
    for (i in src.indices) {
        val lo = max(0, i - windowFrames)
        val hi = min(src.lastIndex, i + windowFrames)
        var localMax = 0f
        for (j in lo..hi) if (src[j] > localMax) localMax = src[j]   // 주변 windowFrames 내 최댓값
        out.add(if (localMax > 1e-6f) (src[i] / localMax).coerceIn(0f, 1f) else 0f)
    }
    return out
}
```

### 비교

| 항목 | V8 `normalize01` | V9 `localNormalize` |
|---|---|---|
| 정규화 기준 | 전구간 단일 최댓값 | 슬라이딩 윈도우 내 지역 최댓값 |
| 윈도우 크기 | 전체 (무한대) | LOCAL_NORM_WINDOW = 60프레임(3초) |
| 조용한 구간 처리 | 0에 수렴 → autocorr threshold 미달 | 지역 최댓값 대비로 정규화 → onset 밀도 유지 |
| 클라이맥스 구간 영향 | 클라이맥스 1개가 전체 onset을 압도 | 각 구간 독립적으로 정규화 |

### 윈도우 크기별 용도

| 상수 | 값 | 용도 |
|---|---|---|
| `LOCAL_NORM_WINDOW` | 60프레임 (3초) | 세그먼트 단위 ODF 정규화 |
| `GLOBAL_NORM_WINDOW` | 80프레임 (4초) | 전곡 globalBeatMs 추정용 ODF |

---

## 3. 세그먼트 폴백 계층 추가

### 문제 (As-Is)
V8의 폴백은 autocorr 실패 시 rawPeak 간격 중앙값 단계뿐이다. 이 단계도 실패하면 세그먼트 전체가 버려진다.

### As-Is — V8 폴백 계층 (2단계)

```
autocorr 성공  →  beatMs = acResult.first
autocorr 실패  →  rawPeak 간격 중앙값 (fallbackBeatMs)
    ↓ 이것도 실패
    reason = "autocorr weak" (세그먼트 전체 탈락)
```

```kotlin
// V8: detectSingleSource() 내부
val beatRange = autoCorrelateBeat(onset, ...)

if (beatRange == null) {
    // rawPeak 간격 중앙값 폴백
    val fallbackBeatMs = if (rawPeaks.size >= 3) { ... 인라인 계산 ... } else null

    if (fallbackBeatMs != null) { ... }

    return TrialResult(..., reason = "autocorr weak")
}
val beatMs = beatRange.first
```

### To-Be — V9 폴백 계층 (3단계)

```
autocorr 성공  →  beatMs = acResult.first
autocorr 실패  →  globalBeatMs 폴백 (전곡 BPM)   ← NEW
    ↓ 이것도 null
    rawPeak 간격 중앙값 폴백 (rawPeakMedianInterval)
    ↓ 이것도 실패
    reason = "autocorr weak" (세그먼트 전체 탈락)
```

```kotlin
// V9: detectSingleSource()에 globalBeatMs: Long? 파라미터 추가
private fun detectSingleSource(
    ...
    globalBeatMs: Long?,        // ← 신규 파라미터
    params: Params
): TrialResult {

    val acResult = autoCorrelateBeat(onset, ...)

    val beatMs: Long
    val acPeak: Float
    when {
        acResult != null -> {
            beatMs = acResult.first
            acPeak = acResult.second
        }
        globalBeatMs != null -> {                      // ← NEW: 2단계 폴백
            beatMs = globalBeatMs
            acPeak = 0.5f                              // 임의 신뢰도 0.5 부여
            Log.d(TAG, "SEG[$segmentIndex] autocorr weak → globalBeatMs=$globalBeatMs fallback")
        }
        else -> {
            val fallbackMs = rawPeakMedianInterval(rawPeaks, params)  // 3단계 폴백 (함수 분리)
            ...
        }
    }
}
```

### 폴백 단계별 acPeak 처리

| 단계 | 폴백 사유 | acPeak | 점수 계산 |
|---|---|---|---|
| 정상 | autocorr 성공 | 실제 correlation값 | `densityScore×0.40 + snapRatio×0.30 + acPeak×0.20 + onsetMax×0.10` |
| 2단계 | autocorr 실패, globalBeatMs 사용 | 고정 0.5f | 동일 공식 적용 |
| 3단계 | 위 모두 실패, rawPeak 중앙값 사용 | 고정 0.0f | `density×0.35 + snapRatio×0.30 + 0.10` |

### V9 신규 함수: `rawPeakMedianInterval()`

V8에서 `detectSingleSource()` 내 인라인이었던 rawPeak 폴백 로직을 독립 함수로 분리:

```kotlin
private fun rawPeakMedianInterval(rawPeaks: List<Int>, params: Params): Long? {
    if (rawPeaks.size < 3) return null
    val intervals = (1 until rawPeaks.size)
        .map { (rawPeaks[it] - rawPeaks[it - 1]).toLong() * params.hopMs }
        .filter { it in params.minBeatMs..params.maxBeatMs }
    if (intervals.size < 2) return null
    val sorted = intervals.sorted()
    return sorted[sorted.size / 2]
}
```

---

## 4. 소스 우선순위 — LOW / LOW_MID 보너스

### 문제 (As-Is)
V8에서 6개 소스(LOW, MID, FULL, LOW_MID, MID_FULL, LOW_FULL)는 순수 분산(variance)으로만 순위가 결정된다. 베이스 드럼(20~300Hz) 중심의 저음 소스가 우선 시도되지 않아 MID/FULL이 먼저 선택될 수 있다.

### As-Is — V8 `buildSourceOrder()`

```kotlin
private fun buildSourceOrder(low: List<Float>, mid: List<Float>, full: List<Float>): List<BeatSource> {
    val lowVar = varOf(low); val midVar = varOf(mid); val fullVar = varOf(full)

    val scored = listOf(
        BeatSource.LOW      to lowVar,
        BeatSource.MID      to midVar,
        BeatSource.FULL     to fullVar,
        BeatSource.LOW_MID  to ((lowVar + midVar) * 0.5f + min(lowVar, midVar) * 0.2f),
        BeatSource.MID_FULL to ((midVar + fullVar) * 0.5f + min(midVar, fullVar) * 0.2f),
        BeatSource.LOW_FULL to ((lowVar + fullVar) * 0.5f + min(lowVar, fullVar) * 0.2f)
    ).sortedByDescending { it.second }     // ← 분산만으로 정렬, 보너스 없음

    return scored.map { it.first }
}
```

### To-Be — V9 `buildSourceOrder()`

```kotlin
private fun buildSourceOrder(low: List<Float>, mid: List<Float>, full: List<Float>): List<BeatSource> {
    val lowVar = varOf(low); val midVar = varOf(mid); val fullVar = varOf(full)
    val BASS_BONUS = 0.003f       // ← NEW: 저음 소스 우선 보너스

    val scored = listOf(
        BeatSource.LOW      to (lowVar + BASS_BONUS),                    // ← 보너스 추가
        BeatSource.LOW_MID  to ((lowVar + midVar) * 0.5f + min(lowVar, midVar) * 0.2f + BASS_BONUS),  // ← 보너스 추가
        BeatSource.MID      to midVar,
        BeatSource.FULL     to fullVar,
        BeatSource.MID_FULL to ((midVar + fullVar) * 0.5f + min(midVar, fullVar) * 0.2f),
        BeatSource.LOW_FULL to ((lowVar + fullVar) * 0.5f + min(lowVar, fullVar) * 0.2f)
    ).sortedByDescending { it.second }

    return scored.map { it.first }
}
```

### 소스 우선순위 변화

| 순위 | V8 (분산 기준) | V9 (분산 + 보너스) |
|---|---|---|
| 1순위 | 분산 최대 소스 (가변) | LOW 또는 LOW_MID (분산이 비슷할 때 저음 우선) |
| 특징 | 분산이 크면 MID/FULL도 먼저 선택 가능 | 저음 소스가 분산 0.003 이상 열세일 때만 밀림 |
| 근거 | — | 베이스 드럼은 20~300Hz → LOW 채널에 집중 |

---

## 5. finalBeatMs 결정 방식 개선

V8과 V9 모두 전체 비트 타임스탬프에서 `estimateMedianInterval()`로 최종 BPM을 결정한다. V9에서 두 가지 개선이 추가됐다.

### 5-A. 양자화 편향 보정 (estimateMedianInterval)

#### As-Is — V8

```kotlin
private fun estimateMedianInterval(beats: List<Long>, minBeatMs: Long, maxBeatMs: Long): Long {
    ...
    val diffs = ArrayList<Long>()
    for (i in 1 until beats.size) {
        val d = beats[i] - beats[i - 1]
        if (d in minBeatMs..maxBeatMs) diffs += d
    }
    if (diffs.isEmpty()) return 500L
    val sorted = diffs.sorted()
    return sorted[sorted.size / 2]    // ← 단순 중앙값
}
```

#### To-Be — V9

```kotlin
private fun estimateMedianInterval(beats: List<Long>, minBeatMs: Long, maxBeatMs: Long,
                                   hopMs: Long = 50L): Long {     // ← hopMs 파라미터 추가
    ...
    // hop 그리드로 binning → mode(최빈값) 계산
    // 이유: hopMs=50ms 양자화로 인해 400ms 비트가 450ms로 편향되는 현상 방지
    val binned = diffs.map { (it / hopMs) * hopMs }
    val mode = binned.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return mode ?: diffs.sorted()[diffs.size / 2]    // mode 없으면 중앙값 fallback
}
```

| 항목 | V8 | V9 |
|---|---|---|
| 최종값 결정 | 단순 중앙값 (sorted median) | hopMs 그리드 binning 후 최빈값(mode) |
| 문제 상황 | 400ms 비트 → 450ms 오검출 가능 | hop 단위로 묶어 mode 취함 → 편향 억제 |

### 5-B. globalBeatMs 우선 보정

```kotlin
// V9: detect() 내부 — 전곡 BPM이 세그먼트 간격 중앙값보다 신뢰도 높을 때 우선 적용
val rawBeatMs = estimateMedianInterval(deduped, params.minBeatMs, params.maxBeatMs, params.hopMs)

val finalBeatMs = if (globalBeatMs != null &&
    globalBeatMs < rawBeatMs &&
    rawBeatMs - globalBeatMs < rawBeatMs / 6) {    // ← 17% 이내 차이일 때만
    Log.d(TAG, "finalBeatMs: globalBeatMs=$globalBeatMs preferred over rawBeatMs=$rawBeatMs")
    globalBeatMs
} else {
    rawBeatMs
}
```

적용 조건 (모두 충족 시 globalBeatMs 채택):

| 조건 | 의미 |
|---|---|
| `globalBeatMs != null` | 전곡 autocorr 성공 |
| `globalBeatMs < rawBeatMs` | 전곡 BPM이 더 빠른 방향 (세그먼트 중앙값이 느린 쪽으로 편향됐을 가능성) |
| `rawBeatMs - globalBeatMs < rawBeatMs / 6` | 차이가 17% 이내 (동일 BPM의 양자화 오차 범위) |

---

## 6. 변경되지 않은 항목

| 항목 | 내용 |
|---|---|
| Params 기본값 | `minBeatMs=290`, `maxBeatMs=1200`, `onsetSmoothWindow=3` 등 모두 동일 |
| Harmonic folding | `/2`, `/3`, `×2/3`, `×2` 교정 로직 및 상수값 동일 |
| Harmonic 상수 | `FOLD_HALF_RATIO=0.40`, `FOLD_THIRD_RATIO=0.35`, `DOUBLE_RATIO=0.80`, `TWO_THIRDS_RATIO=0.75` |
| Peak snapping | `snapPeaksToGrid()` 알고리즘 동일 |
| Chain 필터링 | `keepConsistentChain()` — 1×tol, 2×(tol×0.6), 0.5×(tol×0.8) 기준 동일 |
| 소스 조합 비율 | `LOW_MID=0.55:0.45`, `MID_FULL=0.60:0.40`, `LOW_FULL=0.60:0.40` |
| 점수 가중치 | `density×0.40 + snapRatio×0.30 + acPeak×0.20 + onsetMax×0.10` |
| best 선택 기준 | 비트 수 우선(score ≥ best×0.70) + 동수 시 score 우선 (density-first) |
| dedupeCloseBeats | `minPeakDistanceMs` 기반 근접 비트 중복 제거 동일 |
| 짧은 곡 처리 | `durationMs < 60s` → 단일 세그먼트 처리 동일 |
| 짧은 세그먼트 minChain | `segDurationMs < 15s` → `effectiveMinChain = 2` 동일 |

---

## 7. 전체 처리 흐름 비교

### As-Is — V8 처리 흐름

```
detect() 진입
│
├─ 세그먼트 루프 (20초 단위)
│   └─ detectSingleSource()
│       ├─ movingAverage → positiveDiff → normalize01()   ← 글로벌 정규화
│       ├─ findPeaks
│       ├─ autoCorrelateBeat()
│       │   ├─ 성공: beatMs = acResult
│       │   └─ 실패: rawPeak 중앙값 폴백 (인라인)
│       ├─ snapPeaksToGrid → keepConsistentChain
│       └─ score 계산 (density/snapRatio/acPeak/onsetMax 가중합)
│
├─ dedupeCloseBeats (세그먼트 합산 비트)
├─ estimateMedianInterval → 단순 중앙값
└─ finalBeatMs = rawBeatMs
```

### To-Be — V9 처리 흐름

```
detect() 진입
│
├─ estimateGlobalBpm()             ← NEW: 전곡 LOW_MID ODF → autocorr
│   └─ globalBeatMs (nullable)
│
├─ 세그먼트 루프 (20초 단위)
│   └─ detectSingleSource(globalBeatMs)   ← globalBeatMs 전달
│       ├─ computeOdf()                   ← NEW: smooth → diff → localNormalize()
│       ├─ findPeaks
│       ├─ autoCorrelateBeat()
│       │   ├─ 성공: beatMs = acResult
│       │   ├─ 실패 + globalBeatMs 있음: beatMs = globalBeatMs (acPeak=0.5f)  ← NEW
│       │   └─ 실패 + globalBeatMs 없음: rawPeakMedianInterval() 폴백          ← 함수 분리
│       ├─ snapPeaksToGrid → keepConsistentChain
│       └─ score 계산 (동일)
│
├─ dedupeCloseBeats (세그먼트 합산 비트)
├─ estimateMedianInterval → hop 그리드 binning → mode            ← 개선
└─ finalBeatMs = globalBeatMs (17% 이내) OR rawBeatMs            ← NEW
```

---

## 요약 대조표

| 항목 | As-Is (V8) | To-Be (V9) |
|---|---|---|
| **전곡 BPM 추정** | 없음 | 전곡 LOW_MID ODF autocorr → `globalBeatMs` |
| **ODF 정규화** | `normalize01()` — 전구간 글로벌 | `localNormalize()` — 슬라이딩 3초 윈도우 |
| **세그먼트 폴백** | autocorr 실패 → rawPeak 중앙값 (2단계) | autocorr → globalBeatMs → rawPeak (3단계) |
| **소스 우선순위** | 분산만으로 정렬 | LOW / LOW_MID에 BASS_BONUS 추가 |
| **finalBeatMs 결정** | 단순 중앙값 | hop 그리드 mode + globalBeatMs 우선 적용 |
| **함수 구조** | ODF/폴백 로직 인라인 | `computeOdf()`, `rawPeakMedianInterval()` 분리 |
