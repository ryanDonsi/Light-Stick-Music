# AutoTimelineGenerator v8 → v9 변경 명세

> 대상 파일: `AutoTimelineGeneratorBeat_v8.kt` → `AutoTimelineGeneratorBeat_v9.kt`  
> 변경 범위: 이펙트 다양성 개선 (5개 항목), 오디오 분석 로직 동일

---

## 목차

1. [BLINK 엔진 재활성화](#1-blink-엔진-재활성화)
2. [비트 강약 4박자 패턴](#2-비트-강약-4박자-패턴)
3. [섹션 반복 에스컬레이션](#3-섹션-반복-에스컬레이션)
4. [클라이맥스 3단계 빌드업](#4-클라이맥스-3단계-빌드업)
5. [BREATH 색상 섹션별 다변화](#5-breath-색상-섹션별-다변화)
6. [변경되지 않은 항목](#6-변경되지-않은-항목)

---

## 1. BLINK 엔진 재활성화

### 문제 (As-Is)
v8에서 BLINK는 `buildPayload()`에서 처리 가능하나 `buildContentSection()`의 엔진 배정 로직에서 한 번도 선택되지 않아 실질적으로 미사용 상태였다.

### As-Is — v8 엔진 배정 (`buildContentSection`)

```
VERSE:
  isBalladMode        → BREATH
  rel < 0.10 && beats < 8 → BREATH
  rel < 0.75          → ON_PULSE
  그 외               → ON_TRANSIT_ROTATE
  (BLINK 배정 없음)

CHORUS:
  isBalladMode + climax/rel≥0.65 → ON_TRANSIT_ROTATE
  isBalladMode        → BREATH
  beatMs ≤ 290ms      → STROBE
  isClimaxSection     → STROBE
  rel ≥ 0.40          → ON_TRANSIT_ROTATE
  그 외               → ON_PULSE
  (BLINK 배정 없음)
```

### To-Be — v9 엔진 배정 (`buildContentSection`)

```
VERSE:
  isBalladMode        → BREATH / ON_TRANSIT_ROTATE (rel 기준)
  rel < 0.10 && beats < 8 → BREATH
  rel ≥ 0.85          → BLINK          ← NEW: 고에너지 VERSE에 BLINK 배정
  rel < 0.75          → ON_PULSE
  그 외               → ON_TRANSIT_ROTATE

CHORUS:
  isBalladMode + climax/rel≥0.65 → ON_TRANSIT_ROTATE
  isBalladMode        → BREATH
  beatMs ≤ 290ms      → STROBE
  isClimaxSection     → STROBE
  repeatIndex ≥ 2 && rel ≥ 0.30 → STROBE   ← NEW: 3회+ 반복 에스컬레이션 (③ 연계)
  repeatIndex ≥ 1 && rel ≥ 0.40 → BLINK    ← NEW: 2회+ 반복 에스컬레이션 (③ 연계)
  beatMs 291~500 && rel ≥ 0.50  → BLINK    ← NEW: 중간 템포 BLINK
  rel ≥ 0.40          → ON_TRANSIT_ROTATE
  그 외               → ON_PULSE
```

### 영향 범위
- `buildContentSection()` 조건 분기 추가
- BLINK 커버 처리: `coverEngine` 산출 시 BLINK도 BREATH로 폴백 처리 추가

---

## 2. 비트 강약 4박자 패턴

### 문제 (As-Is)
v8은 `skipOnPulseOdd`로 ON_PULSE 홀수 비트를 완전 스킵했다. 모든 짝수 비트가 동일한 강도로 발광해 단조로운 리듬감이 생겼다.

### As-Is — v8 ON_PULSE 비트 처리

```
beatIndex % 2 == 1  →  skip (아무것도 발광하지 않음)
beatIndex % 2 == 0  →  ON 즉시 점등 (모두 동일 강도)
                        holdMs = beatMs × 44%
                        BG 복원 프레임 추가
```

```kotlin
// v8 코드
val skipOnPulseOdd = (beatEngine == FgEngine.ON_PULSE && beatIndex % 2 != 0)
```

### To-Be — v9 ON_PULSE 비트 처리 (BeatAccent 3단계)

```
beatIndex % 4 == 0  →  STRONG: 기존과 동일 (즉시 점등, holdMs = beatMs × 44%)
beatIndex % 4 == 2  →  MEDIUM: groupColor, holdMs = beatMs × 18%, transit = ON_TRANSIT (부드럽게)
beatIndex % 4 == 1,3 → WEAK:  스킵 (기존 홀수 스킵과 동일 빈도 유지)
```

```kotlin
// v9 코드
private enum class BeatAccent { STRONG, MEDIUM, WEAK }

private fun beatAccentFor(beatIndex: Int): BeatAccent = when (beatIndex % 4) {
    0    -> BeatAccent.STRONG
    2    -> BeatAccent.MEDIUM
    else -> BeatAccent.WEAK
}
```

| 구분 | v8 (As-Is) | v9 (To-Be) |
|---|---|---|
| 박자 0, 4, 8… | ON 즉시, holdMs×44% | STRONG: 동일 |
| 박자 2, 6, 10… | 스킵 | MEDIUM: groupColor, holdMs×18%, transit 2 |
| 박자 1, 3, 5, 7… | 스킵 | WEAK: 스킵 |

**MEDIUM 비트 발광 플로우:**
1. `t` 시점: `Effects.on(color=groupColor, transit=2)` 발광
2. `t + holdMs` 시점: `Effects.on(color=black, transit=2)` 복원

---

## 3. 섹션 반복 에스컬레이션

### 문제 (As-Is)
v8은 같은 곡에서 CHORUS가 반복 등장해도 항상 동일한 엔진을 배정했다. 곡이 후반부로 갈수록 盛해지는 느낌이 없었다.

### As-Is — v8 Section 데이터 클래스

```kotlin
data class Section(
    val startMs: Long,
    val endMs: Long,
    val type: SectionType,
    val engine: FgEngine,
    val beatMs: Long,
    val beats: Int,
    val source: String,
    val change: ChangeLevel,
    val energyScore: Float = 0f,
    val relScore: Float = 0f
    // repeatIndex 없음
)
```

`buildSections()`에서 반복 횟수를 추적하지 않음 → 모든 CHORUS가 동일 조건으로 엔진 배정.

### To-Be — v9 Section 데이터 클래스 + 추적 로직

```kotlin
data class Section(
    ...
    val repeatIndex: Int = 0   // ← NEW: 같은 섹션 타입의 0-based 등장 횟수
)
```

```kotlin
// buildSections() 내부
val sectionTypeCount = mutableMapOf<SectionType, Int>()

// 각 구간마다:
val repeatIdx = sectionTypeCount.getOrDefault(type, 0)
val section = buildContentSection(..., repeatIndex = repeatIdx)
sectionTypeCount[section.type] = sectionTypeCount.getOrDefault(section.type, 0) + 1
```

### 에스컬레이션 규칙

| CHORUS 등장 횟수 | v8 엔진 | v9 엔진 |
|---|---|---|
| 1회 (repeatIndex=0) | rel≥0.40 → ON_TRANSIT_ROTATE | 동일 |
| 2회 (repeatIndex=1) | rel≥0.40 → ON_TRANSIT_ROTATE | rel≥0.40 → **BLINK** |
| 3회+ (repeatIndex≥2) | rel≥0.40 → ON_TRANSIT_ROTATE | rel≥0.30 → **STROBE** |

> 발라드 모드(`isBalladMode=true`)에서는 에스컬레이션 비활성화.

---

## 4. 클라이맥스 3단계 빌드업

### 문제 (As-Is)
v8에서 STROBE 섹션은 클라이맥스 ±4s 이내(PEAK)면 STROBE, 그 외는 BREATH로 즉시 폴백했다. 클라이맥스 전 긴장감 고조 구간이 없었다.

### As-Is — v8 클라이맥스 처리 (2단계)

```
클라이맥스 ±4s 이내  →  STROBE (period=1)
그 외                →  BREATH (즉시 폴백)
```

```kotlin
// v8 코드
val effectiveBeatEngine = when {
    beatEngine == FgEngine.STROBE && !nearClimax -> FgEngine.BREATH
    else -> beatEngine
}
val beatPeriod = when (effectiveBeatEngine) {
    FgEngine.STROBE -> 1
    FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
    else            -> null
}
```

### To-Be — v9 클라이맥스 처리 (3단계)

```
ClimaxPhase.PEAK    (±4s)          →  STROBE (period=1)
ClimaxPhase.BUILDUP (12s~4s 이전)  →  BLINK  (period 점진 단축)
ClimaxPhase.NONE    (그 외)         →  BREATH
```

```kotlin
// v9 코드
private enum class ClimaxPhase { NONE, BUILDUP, PEAK }

private const val CLIMAX_WINDOW_HALF_MS = 4_000L   // PEAK 반경
private const val CLIMAX_BUILDUP_MS     = 12_000L  // BUILDUP 시작 거리

private fun resolveClimaxPhase(tMs: Long, climaxMoments: List<Long>): ClimaxPhase {
    val nearest = climaxMoments.minByOrNull { abs(it - tMs) } ?: return ClimaxPhase.NONE
    val delta = nearest - tMs
    return when {
        abs(delta) <= CLIMAX_WINDOW_HALF_MS    -> ClimaxPhase.PEAK
        delta in 0L..CLIMAX_BUILDUP_MS         -> ClimaxPhase.BUILDUP
        else                                    -> ClimaxPhase.NONE
    }
}

// BUILDUP 구간 진행도 0.0 → 1.0
private fun buildupProgress(tMs: Long, climaxMoments: List<Long>): Float { ... }
```

### BUILDUP 구간 period 점진 단축

| 엔진 | 계산식 |
|---|---|
| BLINK | `msToBlinkPeriod(beatMs) × (1 - progress × 0.35)` |
| BREATH | `msToBreathPeriod(beatMs) × (1 - progress × 0.40)` |

> `progress`는 BUILDUP 시작(0.0) → PEAK 직전(1.0)으로 선형 증가.  
> PEAK 진입 시 period는 항상 1(최대 속도).

### 타임라인 예시 (클라이맥스 T=60s 기준)

```
t=48s  ClimaxPhase.BUILDUP  progress=0.0  BLINK period=원본
t=52s  ClimaxPhase.BUILDUP  progress=0.5  BLINK period=원본×0.825
t=56s  ClimaxPhase.BUILDUP  progress=1.0  BLINK period=원본×0.65
t=60s  ClimaxPhase.PEAK                   STROBE period=1
t=64s  ClimaxPhase.NONE                   BREATH (일반 섹션으로 복귀)
```

---

## 5. BREATH 색상 섹션별 다변화

### 문제 (As-Is)
v8에서 BREATH 이펙트는 섹션 타입과 무관하게 항상 `breathSet(white / patternABg)` 단일 색상 조합을 사용했다. INTRO, BRIDGE, CHORUS의 분위기가 구분되지 않았다.

### As-Is — v8 BREATH 색상

```kotlin
// v8: 모든 섹션에서 동일
val (fg, bg) = colorsForEngine(palette, FgEngine.BREATH, sameTypeIdx)
// 결과: always white / patternABg
```

### To-Be — v9 BREATH 색상 (`breathColorsFor()` 신규 함수)

```kotlin
private fun breathColorsFor(
    palette: Palette,
    sectionType: SectionType,
    sameTypeIdx: Int
): Pair<LSColor, LSColor> = when (sectionType) {
    SectionType.INTRO  -> palette.colorGroup[0] to palette.black   // cMain / black
    SectionType.BRIDGE -> palette.colorGroup[2] to palette.chorusBg // cStep2 / cDeep
    SectionType.CHORUS -> palette.white         to palette.chorusBg // white / cDeep
    else               -> palette.breathSet.fg  to palette.breathSet.bg // white / patternABg
}
```

### 섹션별 BREATH 색상 비교

| 섹션 | v8 FG / BG | v9 FG / BG | 의도 |
|---|---|---|---|
| INTRO | white / patternABg | **cMain / black** | 팔레트 메인 컬러로 따뜻한 시작 |
| VERSE | white / patternABg | white / patternABg | 변경 없음 |
| CHORUS | white / patternABg | **white / chorusBg(cDeep)** | 코러스 전용 깊은 배경색 |
| BRIDGE | white / patternABg | **cStep2 / cDeep** | 차분하고 깊은 전환부 느낌 |

> 적용 위치: 섹션 START 프레임, 비트 루프의 BREATH 폴백, SECTION_COVER 중 BREATH 커버 모두 동일하게 적용.

---

## 6. 변경되지 않은 항목

아래 항목은 v8과 v9 간에 완전히 동일하다.

| 항목 | 내용 |
|---|---|
| 오디오 디코딩 | `decodeAllEnvelopes()` — 단일 패스 IIR 필터, MediaCodec 1회 |
| 비트 감지 | `BeatDetectorV9.detect()` — 파라미터 동일 |
| 섹션 경계 탐지 | Novelty 기반 변화점 감지 — 알고리즘 동일 |
| 섹션 에너지 스코어 | `sectionEnergyScore()` — 가중치 동일 |
| 클라이맥스 감지 | `detectClimaxPeakMoments()` — CV/peakRatio 임계값 동일 |
| 팔레트 생성 | `buildPalette()` — musicId 시드 결정론적 생성 동일 |
| 발라드 감지 | `isQuietFolkOrBallad()` — 임계값 동일 |
| Beat grid 구성 | `buildSectionBeatGrid()` / `fillBeatGaps()` 동일 |
| Bridge 페이즈 엔진 | `bridgePhaseEngine()` — 3단계 페이즈 전환 동일 |
| Period 계산 | `msToBlinkPeriod` / `msToStrobePeriod` / `msToBreathPeriod` 동일 |
| 캐시 포맷 | `AutoTimelineStorage` 바이너리 포맷 동일 (VERSION 필드만 9로 변경) |

---

## 요약 대조표

| 항목 | As-Is (v8) | To-Be (v9) |
|---|---|---|
| BLINK 활용 | 미사용 (배정 로직 없음) | VERSE 고에너지·CHORUS 중간 템포 배정 |
| ON_PULSE 패턴 | 홀수 비트 스킵 (2박자) | STRONG/MEDIUM/WEAK 3단계 (4박자) |
| 섹션 반복 인식 | 없음 | `Section.repeatIndex`로 추적, CHORUS 에스컬레이션 |
| 클라이맥스 전환 | 2단계 (BREATH ↔ STROBE) | 3단계 (BREATH → BLINK → STROBE) + period 점진 단축 |
| BREATH 색상 | 단일 조합 (white/patternABg) | 섹션별 4종 분리 |
| 버전 | 8 | 9 |
