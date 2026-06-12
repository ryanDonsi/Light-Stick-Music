# BeatDetectorV11 사용 가이드

> 대상 파일: `BeatDetectorV11.kt`  
> 위치: `app/src/main/java/com/lightstick/music/domain/music/`  
> hopMs = 50ms (기본), segmentMs = 20,000ms (20초 단위 처리)

---

## 목차

1. [개요 및 V9 대비 개선점](#1-개요-및-v9-대비-개선점)
2. [입력 — 무엇을 전달하는가](#2-입력--무엇을-전달하는가)
3. [Params — 조정 가능한 파라미터](#3-params--조정-가능한-파라미터)
4. [내부 처리 흐름](#4-내부-처리-흐름)
5. [반환값 — DetectResult 전체 필드](#5-반환값--detectresult-전체-필드)
6. [사용 방법](#6-사용-방법)
7. [주의사항](#7-주의사항)

---

## 1. 개요 및 V9 대비 개선점

BeatDetectorV11은 V9 기반에 **정박자(다운비트) 우선 감지** 로직을 추가한 버전이다.

| 기능 | V9 | V11 |
|---|---|---|
| 전곡 글로벌 BPM 추정 | ✓ | ✓ |
| 세그먼트별 비트 감지 | ✓ | ✓ |
| Multi-Hypothesis 소스 선택 | ✓ | ✓ |
| BPM 연속성 가중치 | ✓ | ✓ (12% 기준) |
| **① 다운비트 감지** | ✗ | ✓ LOW 에너지 + 콤 필터 + 일관성 |
| **② 그리드 재정렬** | ✗ | ✓ downbeatMs 앵커 기준 ±80ms 스냅 |
| **③ 박자표 감지** | ✗ | ✓ 4/4, 3/4, 6/8 자동 판별 |
| **④ 비트 신뢰도** | ✗ | ✓ TimedBeat(timeMs, confidence) |

---

## 2. 입력 — 무엇을 전달하는가

```kotlin
BeatDetectorV11.detect(
    lowEnv:  List<Float>,        // 저역(킥드럼) 에너지 envelope
    midEnv:  List<Float>,        // 중역(스네어/멜로디) 에너지 envelope
    fullEnv: List<Float>,        // 전체 대역 에너지 envelope
    params:  Params = Params()   // 선택 파라미터 (생략 시 기본값 사용)
)
```

### 2.1 lowEnv / midEnv / fullEnv 란?

PCM 원시 파형을 직접 넘기는 것이 아니다.  
`AutoTimelineGeneratorBeat_v7`의 `decodeEnvelopeInternal()` 을 통해 변환한 값을 넘겨야 한다.

```
MP3 / AAC 파일
    ↓  MediaCodec 디코딩
PCM 샘플 (raw amplitude)
    ↓  IIR 밴드패스 필터 (주파수 대역별 분리)
    ↓  hopMs(50ms)마다 에너지(RMS) 계산
List<Float>
 └─ 인덱스 i = 곡 내 i × hopMs 시점의 에너지값
```

| 파라미터 | 주파수 대역 | 주로 감지하는 것 |
|---|---|---|
| `lowEnv` | 저역 (~300 Hz) | 킥드럼, 베이스 |
| `midEnv` | 중역 (300 Hz~3 kHz) | 스네어, 보컬, 멜로디 |
| `fullEnv` | 전 대역 | 전체 음압 변화 |

**리스트 크기:**  
`곡 길이(ms) / hopMs(50)` 개  
→ 3분 곡: `180,000 / 50 = 3,600` 개의 Float 값

### 2.2 실제 호출 예시 (v7 내부 방식)

```kotlin
val lowEnv  = decodeEnvelopeInternal(musicPath, hopMs = 50, mode = EnvMode.LOW)
val midEnv  = decodeEnvelopeInternal(musicPath, hopMs = 50, mode = EnvMode.MID)
val fullEnv = decodeEnvelopeInternal(musicPath, hopMs = 50, mode = EnvMode.FULL)

val result = BeatDetectorV11.detect(lowEnv, midEnv, fullEnv)
```

---

## 3. Params — 조정 가능한 파라미터

```kotlin
data class Params(
    val hopMs: Long             = 50L,
    val minBeatMs: Long         = 290L,
    val maxBeatMs: Long         = 1200L,
    val minPeakDistanceMs: Long = 140L,
    val onsetSmoothWindow: Int  = 3,
    val segmentMs: Long         = 20_000L,
    val peakThresholdK: Float   = 0.22f,
    val minPeakAbs: Float       = 0.04f,
    val snapToleranceMs: Long   = 150L,
    val chainToleranceMs: Long  = 170L,
    val minChainCount: Int      = 3,
    val continuityBonus: Float  = 0.08f
)
```

| 파라미터 | 기본값 | 의미 |
|---|---|---|
| `hopMs` | 50ms | envelope 프레임 간격. **envelope 추출 시 hopMs와 반드시 일치해야 함** |
| `minBeatMs` | 290ms | 최소 비트 간격 → BPM 상한 약 206 BPM |
| `maxBeatMs` | 1200ms | 최대 비트 간격 → BPM 하한 약 50 BPM |
| `minPeakDistanceMs` | 140ms | ODF 피크 최소 간격 (너무 촘촘한 피크 제거) |
| `onsetSmoothWindow` | 3 | ODF 스무딩 윈도우 크기 (프레임 수) |
| `segmentMs` | 20,000ms | 세그먼트 크기. **60초 미만 곡은 전체를 1구간으로 처리** |
| `peakThresholdK` | 0.22 | 피크 감지 임계값 계수 (`mean + std × K`) |
| `minPeakAbs` | 0.04 | 피크 절대 최솟값 |
| `snapToleranceMs` | 150ms | beatMs 그리드 스냅 허용 범위 |
| `chainToleranceMs` | 170ms | 연속 비트 체인 허용 오차 |
| `minChainCount` | 3 | 최소 연속 비트 수 (미달 시 해당 소스 제외) |
| `continuityBonus` | 0.08 | 이전 세그먼트 BPM과 ±12% 이내면 점수 보너스 |

---

## 4. 내부 처리 흐름

```
┌──────────────────────────────────────────────────────────┐
│  lowEnv / midEnv / fullEnv (전체 곡)                      │
└────────────────────────┬─────────────────────────────────┘
                         │
              ① 전곡 글로벌 BPM 추정
              LOW_MID 혼합 ODF → 자기상관 → globalBeatMs
                         │
              ② 세그먼트 분할 (20초씩)
                         │
         ┌───────────────┴──────────────────┐
         │  각 세그먼트마다                   │
         │  6가지 소스 시도                   │
         │  LOW / MID / FULL /               │
         │  LOW_MID / MID_FULL / LOW_FULL    │
         │    ↓                              │
         │  ODF 피크 탐지                    │
         │  beatMs 자기상관                   │
         │  그리드 스냅 → 체인 필터           │
         │    ↓                              │
         │  최고점 소스 선택                  │
         │  (연속성 가중치 적용)              │
         │    ↓                              │
         │  절대 시각 변환                    │
         │  frame × hopMs + segStartMs       │
         └───────────────┬──────────────────┘
                         │
              ③ 전체 비트 병합 + 중복 제거
                         │
              ④ 박자표 감지 (전곡 ODF 자기상관)
                 lag×3 / lag×4 / lag×6 비교
                 → 4/4, 3/4, 6/8 판별
                         │
              ⑤ 다운비트 감지 (detectDownbeatEnhanced)
                 감지된 비트 중 "마디의 1박" 결정
                 LOW 에너지 50% + 콤 필터 30% + 위상 일관성 20%
                         │
              ⑥ 그리드 재정렬 (realignBeatsToGrid)
                 downbeatMs + n×beatMs 그리드로
                 ±80ms 내 비트 스냅
                         │
                    DetectResult 반환
```

### 4.1 `detect` 내 절대 시각 변환 (핵심)

```kotlin
// 세그먼트 내 상대 시각 → 곡 내 절대 시각
val absBeats = best.timedBeats.map { it.copy(timeMs = it.timeMs + segStartMs) }
```

반환되는 `beats.timeMs` 는 곡 시작 기준 절대 ms이다.  
예: `[230, 730, 1230, 1730, ...]` — 곡의 230ms, 730ms, 1230ms 지점에 비트가 있음.

### 4.2 비트 타임스탬프 정밀도

| 오차 원인 | 크기 |
|---|---|
| hopMs=50ms 양자화 | 최대 ±25ms |
| envelope 스무딩으로 피크가 실제 어택보다 뒤로 밀릴 수 있음 | 수십ms 가능 |
| `realignBeatsToGrid` ±80ms 스냅 | 스냅 범위 내 비트는 보정됨 |

---

## 5. 반환값 — DetectResult 전체 필드

```kotlin
data class DetectResult(
    val beats: List<TimedBeat>,          // ★ 핵심: 전체 곡의 비트 목록
    val beatMs: Long,                    // ★ 핵심: 비트 간격 (ms)
    val source: BeatSource?,             // 가장 많이 채택된 소스
    val reason: String,                  // "ok" 또는 실패 사유
    val downbeatOffsetMs: Long,          // 첫 비트 ~ 다운비트 간격 (ms)
    val timeSignature: TimeSignature,    // 박자표 (4/4, 3/4, 6/8)
    val debugSegments: List<SegmentResult>  // 세그먼트별 디버그 정보
) {
    val beatTimesMs: List<Long> get() = beats.map { it.timeMs }  // 편의 접근자
}
```

### 5.1 `beats: List<TimedBeat>` ★

```kotlin
data class TimedBeat(
    val timeMs: Long,      // 곡 내 절대 시각 (ms) ← LED ON을 이 시점에
    val confidence: Float  // 신뢰도 0.0~1.0 (ODF 피크 강도 기반)
)
```

`beatTimesMs` 는 저장 필드가 아니라 `beats.map { it.timeMs }` 의 계산 프로퍼티다.

### 5.2 `beatMs: Long` ★

전곡 대표 비트 간격 (세그먼트 median interval 기반).

```
500ms → 120 BPM
600ms → 100 BPM
BPM = 60_000 / beatMs
```

### 5.3 `source: BeatSource?`

가장 많이 채택된 주파수 소스.

| 값 | 설명 | 주로 해당하는 장르 |
|---|---|---|
| `LOW` | 킥드럼 에너지 위주 | EDM, 힙합 |
| `LOW_MID` | 킥+스네어 균형 | 팝, 록 |
| `MID` | 스네어 위주 | |
| `FULL` | 전체 에너지 변화 위주 | |
| `MID_FULL`, `LOW_FULL` | 혼합 | |

### 5.4 `reason: String`

| 값 | 의미 |
|---|---|
| `"ok"` | 정상 감지 성공 |
| `"empty env"` | 입력 envelope가 비어 있음 |
| `"all failed"` | 모든 소스에서 비트 감지 실패 |

`"ok"` 가 아니면 `beats` 가 비어 있을 수 있다.

### 5.5 `downbeatOffsetMs: Long`

```kotlin
downbeatOffsetMs = downbeatMs - beats.first().timeMs
```

첫 감지 비트로부터 마디의 1박(다운비트)까지의 거리.  
예: `150ms` → 첫 비트 이후 150ms 시점이 마디 시작.

마디 시작 시각 계산:
```kotlin
val firstDownbeatMs = result.beats.first().timeMs + result.downbeatOffsetMs
// 이후 마디: firstDownbeatMs + beatMs × beatsPerBar × n
```

### 5.6 `timeSignature: TimeSignature`

```kotlin
data class TimeSignature(
    val type: TimeSignatureType,  // FOUR_FOUR / THREE_FOUR / SIX_EIGHT
    val numerator: Int,           // 4 / 3 / 6
    val denominator: Int          // 4 / 4 / 8
) {
    val beatsPerBar: Int get() = numerator  // 마디당 비트 수
}
```

### 5.7 `debugSegments: List<SegmentResult>`

각 20초 세그먼트의 분석 상세 결과.

```kotlin
data class SegmentResult(
    val index: Int,                    // 세그먼트 번호 (0부터)
    val startMs: Long,                 // 시작 시각
    val endMs: Long,                   // 종료 시각
    val selectedSource: BeatSource?,   // 채택된 소스
    val timedBeats: List<TimedBeat>,   // 이 구간의 비트들
    val beatMs: Long,                  // 이 구간의 비트 간격
    val score: Float,                  // 신뢰도 0.0~1.0
    val reason: String,                // "ok" / "chain too short" / "all failed" 등
    val trials: List<TrialResult>      // 6개 소스 각각의 시도 결과
) {
    val beatTimesMs: List<Long> get() = timedBeats.map { it.timeMs }
}
```

`TrialResult` 는 각 소스(LOW/MID/FULL 등)의 단일 시도 결과:

```kotlin
data class TrialResult(
    val source: BeatSource,
    val timedBeats: List<TimedBeat>,
    val beatMs: Long,
    val score: Float,          // 종합 점수
    val rawPeakCount: Int,     // ODF에서 탐지된 원시 피크 수
    val snappedCount: Int,     // 그리드 스냅 후 살아남은 비트 수
    val onsetMean: Float,      // ODF 평균값
    val onsetStd: Float,       // ODF 표준편차
    val onsetMax: Float,       // ODF 최대값
    val acPeak: Float,         // 자기상관 피크값 (BPM 신뢰도)
    val snapRatio: Float,      // snappedCount / rawPeakCount
    val reason: String
)
```

---

## 6. 사용 방법

### 6.1 기본 사용

```kotlin
// Step 1. envelope 추출 (v7 generator 내부에서 처리)
val lowEnv  = decodeEnvelopeInternal(musicPath, hopMs = 50, mode = EnvMode.LOW)
val midEnv  = decodeEnvelopeInternal(musicPath, hopMs = 50, mode = EnvMode.MID)
val fullEnv = decodeEnvelopeInternal(musicPath, hopMs = 50, mode = EnvMode.FULL)

// Step 2. 비트 감지
val result = BeatDetectorV11.detect(lowEnv, midEnv, fullEnv)

// Step 3. 실패 체크 (필수)
if (result.reason != "ok" || result.beats.isEmpty()) {
    // 폴백 처리
    return
}

// Step 4. 비트 시각 → LED 타임라인 생성
result.beats.forEach { beat ->
    // beat.timeMs 시점에 LED ON
    // beat.confidence 로 약한 비트 선별 가능
}

// 또는 시각만 필요할 때
val beatTimes: List<Long> = result.beatTimesMs
```

### 6.2 BPM 및 박자표 활용

```kotlin
val bpm         = 60_000L / result.beatMs           // 예: 120 BPM
val beatsPerBar = result.timeSignature.beatsPerBar  // 4/4이면 4
val barMs       = result.beatMs * beatsPerBar       // 마디 길이 (ms)

Log.d("Beat", "BPM=$bpm timeSignature=${result.timeSignature.type} barMs=$barMs")
```

### 6.3 다운비트(마디 시작) 활용

```kotlin
val firstBeatMs     = result.beats.first().timeMs
val firstDownbeatMs = firstBeatMs + result.downbeatOffsetMs

// 전체 마디 시작 시각 계산
val barStarts = mutableListOf<Long>()
var t = firstDownbeatMs
val barMs = result.beatMs * result.timeSignature.beatsPerBar
while (t < durationMs) {
    barStarts += t
    t += barMs
}
```

### 6.4 신뢰도 기반 필터링

```kotlin
// confidence 0.3 미만 비트 제외 (불확실한 비트 제거)
val strongBeats = result.beats.filter { it.confidence >= 0.3f }
```

### 6.5 디버그 확인

```kotlin
// 세그먼트별 분석 결과 출력
result.debugSegments.forEach { seg ->
    Log.d("Beat",
        "seg[${seg.index}] ${seg.startMs}~${seg.endMs}ms " +
        "source=${seg.selectedSource} beatMs=${seg.beatMs} " +
        "beats=${seg.timedBeats.size} score=%.2f reason=${seg.reason}".format(seg.score)
    )
}

// 특정 세그먼트의 소스별 시도 결과
result.debugSegments[0].trials.forEach { trial ->
    Log.d("Beat",
        "  ${trial.source} beatMs=${trial.beatMs} score=%.2f " +
        "rawPeaks=${trial.rawPeakCount} snapped=${trial.snappedCount} " +
        "snapRatio=%.2f reason=${trial.reason}".format(trial.score, trial.snapRatio)
    )
}
```

---

## 7. 주의사항

| 사항 | 내용 |
|---|---|
| **hopMs 일치 필수** | envelope 추출 시 hopMs와 `Params.hopMs` 가 반드시 같아야 함. 현재 양쪽 모두 50ms |
| **reason 체크 필수** | `reason != "ok"` 일 때 `beats` 가 비어 있을 수 있음 |
| **beatMs 범위 보정** | v7 generator에서 `beatMs > 900ms` 이면 `/2` 로 반속 보정 적용 |
| **절대 시각** | `beats.timeMs` 는 곡 시작 기준 절대 ms. 세그먼트 상대값이 아님 |
| **beatTimesMs 는 계산 프로퍼티** | 저장 필드가 아니라 `beats.map { it.timeMs }` 로 매번 계산됨 |
| **리스트 크기 불일치** | `lowEnv/midEnv/fullEnv` 크기가 다르면 내부에서 `min(size)` 로 자름. 가능하면 동일 크기로 전달 |
