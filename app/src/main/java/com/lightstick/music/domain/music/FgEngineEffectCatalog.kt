package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

/**
 * FgEngine 하나가 실제로 어떻게 보이는지(색 선택, SDK 파라미터, 전송 payload, 반복 전송
 * 스킵 여부)를 전부 담은 단위. 새 FgEngine을 추가하려면 EffectMatchingEngine.FgEngine에
 * 값 하나 추가 + 이 인터페이스 구현체 하나 작성 + FgEngineEffectCatalog.of()에 등록하면
 * 된다 — EffectMatchingEngineV1은 어떤 섹션이 어떤 엔진을 쓸지 고르고, 그 엔진의 이
 * 구현체를 호출해 결과를 조합하기만 한다.
 */
interface FgEngineEffect {

    /** FG/BG 색상 선택. barIdx/beatIndex 중 이 엔진이 실제로 쓰는 것만 참조하면 된다. */
    fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ): Pair<LSColor, LSColor?>

    /** period(10ms 단위, SDK 파라미터). 안 쓰는 엔진은 기본값 0. */
    fun period(beatMs: Long): Int = 0

    /** 랜덤 딜레이(SDK 파라미터). 안 쓰는 엔진은 기본값 0. */
    fun randomDelay(beatMs: Long, sectionType: SectionDetector.SectionType, isBalladMode: Boolean, period: Int): Int = 0

    /** randomColor 플래그(SDK 파라미터). 기본값 0(끔). */
    fun randomColorFlag(sectionType: SectionDetector.SectionType, isBalladMode: Boolean): Int = 0

    /** ON류 전환(transit) 길이. 기본값 0. */
    fun rotateTransit(isBalladMode: Boolean): Int = 0

    /** 같은 (fg,bg,period,randomDelay)가 반복되면 재전송을 건너뛰어도 되는 엔진인지(BLE 절약). */
    val isRepeatable: Boolean

    /** 실제 전송 payload 바이트. */
    fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray
}

private fun pick(ec: EffectMatchingEngine.EffectColors, idx: Int): Pair<LSColor, LSColor?> =
    ec.fgCycle[Math.floorMod(idx, ec.fgCycle.size)] to ec.bg

// period는 10ms 단위, FG+BG를 합친 한 사이클 전체 길이.
private fun msToBlinkPeriod(beatMs: Long)       = (beatMs / 100L).toInt().coerceIn(1, 255)
private fun msToBreathPeriod(beatMs: Long)      = (beatMs / 20L).toInt().coerceIn(1, 255)
private fun msToBreathRandomDelay(beatMs: Long) = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

// ON_TRANSIT_ROTATE의 발라드 전용 회전 transit 값.
private const val BALLAD_ROTATE_TRANSIT = 2

/** ON_PULSE: VERSE(비발라드)에서 bar마다 색을 순환시키며 그대로 켠다. */
object OnPulseEffect : FgEngineEffect {
    override fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ) = pick(palette.onPulse, barIdx)

    override val isRepeatable = false

    override fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray =
        LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
}

/** BLINK: VERSE(비발라드) 고에너지 bar에서 깜빡인다. */
object BlinkEffect : FgEngineEffect {
    override fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ) = pick(palette.blink, barIdx)

    override fun period(beatMs: Long) = msToBlinkPeriod(beatMs)

    override val isRepeatable = false

    override fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray =
        LSEffectPayload.Effects.blink(period = period, color = fg, backgroundColor = bg ?: LSColor(0, 0, 0),
            randomDelay = randomDelay).toByteArray()
}

/** BREATH: 발라드 BRIDGE/VERSE/그 외로 색과 랜덤딜레이가 구분된다. */
object BreathEffect : FgEngineEffect {
    override fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ) = when {
        sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> pick(palette.breathBridgeBallad, 0)
        sectionType == SectionDetector.SectionType.VERSE -> pick(palette.breathVerse, barIdx)
        else -> pick(palette.breathDefault, 0)
    }

    override fun period(beatMs: Long) = msToBreathPeriod(beatMs)

    // 발라드 BRIDGE: SDK 랜덤딜레이로 기기별 파도 효과. 비발라드 BRIDGE: 결정적 period(파도 없음).
    override fun randomDelay(beatMs: Long, sectionType: SectionDetector.SectionType, isBalladMode: Boolean, period: Int) = when {
        sectionType == SectionDetector.SectionType.VERSE -> 0
        sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> msToBreathRandomDelay(beatMs)
        sectionType == SectionDetector.SectionType.BRIDGE -> period
        else -> msToBreathRandomDelay(beatMs)
    }

    // 발라드 BRIDGE만 SDK의 randomColor 플래그를 켠다.
    override fun randomColorFlag(sectionType: SectionDetector.SectionType, isBalladMode: Boolean) =
        if (sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode) 1 else 0

    override val isRepeatable = true

    override fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray =
        LSEffectPayload.Effects.breath(period = period, color = fg, backgroundColor = bg ?: LSColor(0, 0, 0),
            randomColor = randomColor, randomDelay = randomDelay).toByteArray()
}

/** ON_TRANSIT_ROTATE: CHORUS에서 전환마다 또렷이 달라지도록 순환시킨다. */
object OnTransitRotateEffect : FgEngineEffect {
    override fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ) = pick(palette.chorus, beatIndex)

    override fun rotateTransit(isBalladMode: Boolean) = if (isBalladMode) BALLAD_ROTATE_TRANSIT else 0

    override val isRepeatable = true

    override fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray =
        LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
}

/** FREEZE(BREAK): 깜빡임/회전 없이 한 가지 색을 고정해서 보여준다. */
object FreezeEffect : FgEngineEffect {
    override fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ) = pick(palette.freeze, 0)

    override val isRepeatable = true

    // 애니메이션 없이 한 번 켜서 그대로 유지한다(transit=0, 이후 재전송 없음).
    override fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray =
        LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
}

/** OFF_TRANSIT: 섹션이 buildFramesFromSections에서 이미 continue로 걸러져 실제로 호출되지 않음. */
object OffTransitEffect : FgEngineEffect {
    override fun colors(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean
    ) = palette.black to palette.black

    override val isRepeatable = false

    override fun buildPayload(fg: LSColor, bg: LSColor?, period: Int, randomDelay: Int, rotateTransit: Int, randomColor: Int): ByteArray =
        LSEffectPayload.Effects.off(transit = 2).toByteArray()
}

/** FgEngine → FgEngineEffect 구현체 조회. 새 엔진을 추가하면 이 한 줄만 더하면 된다. */
object FgEngineEffectCatalog {
    fun of(engine: EffectMatchingEngine.FgEngine): FgEngineEffect = when (engine) {
        EffectMatchingEngine.FgEngine.ON_PULSE          -> OnPulseEffect
        EffectMatchingEngine.FgEngine.BLINK             -> BlinkEffect
        EffectMatchingEngine.FgEngine.BREATH            -> BreathEffect
        EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE -> OnTransitRotateEffect
        EffectMatchingEngine.FgEngine.FREEZE            -> FreezeEffect
        EffectMatchingEngine.FgEngine.OFF_TRANSIT       -> OffTransitEffect
    }
}
