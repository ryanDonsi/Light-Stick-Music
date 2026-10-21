# 이펙트 매칭 구조 (EffectMatchingEngineV1)

섹션(`SectionGroup`)과 비트 타임스탬프를 받아 LED 프레임(타임스탬프→페이로드)을 만드는 과정을
**세 가지 독립 관심사**로 분리했다. 각자 자기 파일에서만 바뀐다.

```
PaletteCatalog          — "무슨 색을 쓸지"      (장르별 색상 데이터, 순수 조회)
SectionEngineCatalog    — "어느 섹션에 어떤 엔진을 쓸지" (장르별 섹션→엔진 매칭)
FgEngineEffectCatalog   — "그 엔진이 실제로 뭘 하는지"  (엔진별 색/파라미터/payload)
EffectMatchingEngineV1  — 위 세 가지를 비트 루프에서 조합만 함 (오케스트레이션)
```

Section 쪽(`SectionDetector*`, `SectionMeta*`)은 팔레트/이펙트를 전혀 참조하지 않는다 —
순수하게 섹션 구간(시작/끝/타입)만 감지한다.

---

## 1. PaletteCatalog — 색상 데이터

`Palette`는 한 곡에서 쓸 색상 묶음. `EffectMatchingEngine.Palette`에 정의되어 있고,
`musicStyle`(장르) 필드를 갖고 있어 엔진 매칭 쪽에서도 재사용한다.

```kotlin
data class Palette(
    val black: LSColor, val white: LSColor,
    val colorFamily: String,                        // "PURPLE"/"RED"/... (표시용)
    val musicStyle: MusicStyleClassifier.MusicStyle, // 이 팔레트를 고른 장르
    val onPulse, val blink, val chorus,
    val breathVerse, val breathBridgeBallad, val breathDefault, val freeze: EffectColors
)
```

- `EffectColors(fgCycle: List<Color>, bg: Color)` — fg는 bar/beat 인덱스로 순환, bg는 고정.
- `PaletteCatalog`에 장르×색계열 조합으로 미리 만든 `Palette` 상수 19개가 있고
  (`BALLAD_SET_1~3`, `EDM_SET_1~3`, `DANCE_POP_SET_1~3`, `POP_SET_1~5`, `HIPHOP_RNB_SET_1~3`,
  `ROCK_SET_1~2`), `GENRE_PALETTE_SETS: Map<MusicStyle, List<Palette>>`로 장르별 후보군을 묶는다.
- 진입점은 단 하나: `PaletteCatalog.select(seed, musicStyle)` — seed(musicId)로 후보 중 하나를
  결정론적으로 고른다. 런타임 색 계산은 없음, 전부 미리 박아둔 값 중에서 고르기만 한다.

**ON_PULSE의 bg는 전부 BLACK** — accent-hold 뒤 꺼지는 것처럼 보이되 실제로는 `ON(bg)`로
전환한다(아래 §3 참고). 다른 이펙트(`CHORUS`/`BREATH_VERSE`/`BREATH_DEFAULT`)의 bg는 해당 색의
어두운 셰이드(`<COLOR>_SHADE`)를 쓴다.

---

## 2. SectionEngineCatalog — 섹션 → 엔진 매칭

`EngineSet(label, pools: Map<SectionType, List<FgEngine>>)` 하나가 "섹션 타입별로 어떤 엔진
풀을 쓸지"를 정의한다. 현재 두 세트:

| SectionType | DEFAULT_SET | BALLAD_SET (발라드/힙합R&B) |
|---|---|---|
| INTRO  | BREATH | BREATH |
| VERSE  | ON_PULSE, BLINK (에너지로 선택) | BREATH |
| CHORUS | ON_TRANSIT_ROTATE | ON_TRANSIT_ROTATE |
| BRIDGE | BREATH | BREATH |
| CLIMAX | ON_TRANSIT_ROTATE | ON_TRANSIT_ROTATE |
| BREAK  | FREEZE | FREEZE |
| OUTRO / END | OFF_TRANSIT | OFF_TRANSIT |

`GENRE_ENGINE_SETS: Map<MusicStyle, EngineSet>`이 장르별로 세트를 고른다
(BALLAD/HIPHOP_RNB → `BALLAD_SET`, 나머지 → `DEFAULT_SET`).

공개 API 3개뿐:
- `pool(type, musicStyle)` — 그 섹션 타입이 쓸 엔진 후보 리스트.
- `assign(type, musicStyle)` = `pool(...).first()` — 대표 엔진 1개 (단일 소스, 드리프트 없음).
- `bridgePhaseEngine()` — BRIDGE 전용 고정 엔진(`BREATH`).

새 장르/새 엔진 조합을 추가하려면 `EngineSet` 하나 더 선언하고 `GENRE_ENGINE_SETS`에 매핑만
추가하면 된다.

---

## 3. FgEngineEffectCatalog — 엔진별 실제 동작

`FgEngine` enum 값 하나당 `FgEngineEffect` 구현체(싱글턴 `object`) 하나. "어떤 엔진을 쓸지"
고르는 오케스트레이션과 "그 엔진이 뭘 하는지"가 완전히 분리되어 있다.

```kotlin
interface FgEngineEffect {
    fun colors(palette, sectionType, barIdx, beatIndex, isBalladMode): Pair<Color, Color?>
    fun period(beatMs): Int = 0
    fun randomDelay(beatMs, sectionType, isBalladMode, period): Int = 0
    fun randomColorFlag(sectionType, isBalladMode): Int = 0
    fun rotateTransit(isBalladMode): Int = 0
    val isRepeatable: Boolean
    fun buildPayload(fg, bg, period, randomDelay, rotateTransit, randomColor): ByteArray
    fun shouldSend(beatIndex): Boolean = true                       // 이 비트에 보낼지
    fun followUpFrame(t, beatMs, sectionEndMs, bg): Pair<Long, ByteArray>? = null  // 추가 프레임
}
```

| 구현체 | SDK 호출 | 특이사항 |
|---|---|---|
| `OnPulseEffect` | `on` | VERSE(비발라드). 짝수 beatIndex만 전송, accent-hold 뒤 `on(bg)`로 전환(followUpFrame) |
| `BlinkEffect` | `blink` | VERSE 고에너지 bar |
| `BreathEffect` | `breath` | BRIDGE+발라드 / VERSE / 그 외로 색·randomDelay·randomColor 분기 |
| `OnTransitRotateEffect` | `on` (rotate transit) | CHORUS/CLIMAX |
| `FreezeEffect` | `on` (transit 0) | BREAK, 고정 1색 |
| `OffTransitEffect` | `off` | 실제로는 오케스트레이션에서 섹션 자체를 건너뛰어 호출 안 됨 |

`shouldSend`/`followUpFrame`은 "이 엔진만의 전송 스케줄 특성"을 캡슐화하기 위한 범용 훅.
지금은 `OnPulseEffect`만 쓰지만(짝수 비트만 전송 + 지연 후 bg 전환), 비슷한 스케줄링이
필요한 새 엔진이 생기면 그대로 재사용한다.

새 `FgEngine`을 추가하려면: ① `EffectMatchingEngine.FgEngine`에 값 추가 ② 이 인터페이스
구현체 작성 ③ `FgEngineEffectCatalog.of()`에 한 줄 등록 ④ `SectionEngineCatalog`의 어느
`EngineSet` 풀에 넣을지 결정.

---

## 4. EffectMatchingEngineV1 — 조합(오케스트레이션)

`buildFrames()`의 흐름:

1. **`convertToResolvedSections`**: `SectionGroup` → `ResolvedSection`. 섹션별 에너지를
   계산해 상대 에너지 점수(`relScore`)를 매기고, `SectionEngineCatalog.assign()`으로 대표
   엔진을 할당한다.
2. **`buildFramesFromSections`**: 섹션 → 비트 2중 루프.
   - `OFF_TRANSIT` 섹션은 통째로 스킵.
   - **VERSE(비발라드)**: bar 에너지를 구해 상위 20%(percentile 0.80) bar는 `BLINK`, 나머지는
     `ON_PULSE`로 비트마다 다시 분기(섹션 대표 엔진을 덮어씀).
   - **BRIDGE**: `SectionEngineCatalog.bridgePhaseEngine()` 고정.
   - **그 외(풀이 2개 이상인 섹션)**: bar를 2~5개 단위 블록으로 묶어(`sectionBarBlockLen`,
     곡마다 결정론적) 블록마다 풀 안의 엔진을 순환.
   - 비트마다 `FgEngineEffectCatalog.of(engine)`으로 구현체를 찾아 `colors`/`period`/
     `randomDelay`/`randomColorFlag`/`rotateTransit`/`shouldSend`를 호출해 payload 구성.
   - **반복 전송 스킵**: `isRepeatable`인 엔진은 (엔진,fg,bg,period,randomDelay)가 직전과
     같으면 재전송하지 않음(BLE 대역 절약). 섹션이 바뀌면 리셋.
   - `shouldSend`가 true면 `followUpFrame`으로 추가 프레임(현재는 ON_PULSE의 accent-hold off
     전환)도 끼워넣음.
3. **OUTRO 페이드**: OUTRO 섹션 구간을 여러 스텝(최대 8)으로 나눠 마지막 fg 색을 점점
   어둡게(`scaleColor`) 보내고 마지막 스텝에서 실제 off.

### 팔레트/엔진 선택과 실제 동작의 경계

- 섹션이 "어떤 엔진을 쓸지" 고르는 로직(위 2번 항목)은 전부 `EffectMatchingEngineV1`과
  `SectionEngineCatalog`에 있다.
- 그 엔진이 "실제로 어떤 색/파라미터/payload"를 내는지는 전부 `FgEngineEffectCatalog`에 있다.
- 색 데이터 자체(스와치, 세트, Palette 상수)는 전부 `PaletteCatalog`에 있다.

세 파일 중 하나만 보면 "무슨 색이 있는지", "어느 섹션에 어떤 엔진이 붙는지", "그 엔진이
구체적으로 뭘 하는지"를 각각 확인할 수 있다.
