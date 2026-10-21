package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

/** 이 FrameSpec이 실제로 쓸 SDK 이펙트 프리미티브. */
enum class Primitive { ON, BLINK, BREATH, OFF }

/**
 * 한 프레임에 보낼 내용 전부 — 프리미티브 종류 + 색 + SDK 파라미터. fg/bg/period/randomDelay는
 * 오케스트레이터가 중복 전송 스킵 키로도 그대로 재사용한다. 실제 바이트 변환은 [toPayload].
 */
data class FrameSpec(
    val primitive: Primitive,
    val fg: LSColor,
    val bg: LSColor? = null,
    val period: Int = 0,
    val randomDelay: Int = 0,
    val rotateTransit: Int = 0,
    val randomColor: Int = 0
)

/** FrameSpec → 실제 전송 바이트. 프리미티브별 SDK 호출은 여기 한 곳에만 있다. */
fun FrameSpec.toPayload(): ByteArray = when (primitive) {
    Primitive.ON     -> LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
    Primitive.BLINK  -> LSEffectPayload.Effects.blink(period = period, color = fg, backgroundColor = bg ?: LSColor(0, 0, 0),
        randomDelay = randomDelay).toByteArray()
    Primitive.BREATH -> LSEffectPayload.Effects.breath(period = period, color = fg, backgroundColor = bg ?: LSColor(0, 0, 0),
        randomColor = randomColor, randomDelay = randomDelay).toByteArray()
    Primitive.OFF    -> LSEffectPayload.Effects.off(transit = rotateTransit).toByteArray()
}

/**
 * FgEngine 하나가 비트마다 뭘 보낼지(FrameSpec)를 결정하는 단위. 새 FgEngine을 추가하려면
 * EffectMatchingEngine.FgEngine에 값 하나 추가 + 이 인터페이스 구현체 하나 작성 +
 * FgEngineEffectCatalog.of()에 등록하면 된다 — EffectMatchingEngineV1은 어떤 섹션이 어떤
 * 엔진을 쓸지 고르고, 그 엔진의 이 구현체를 호출해 결과를 조합하기만 한다.
 */
interface FgEngineEffect {

    /** 이번 비트의 FrameSpec(프리미티브+색+SDK 파라미터) 전체. */
    fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec

    /** 같은 FrameSpec이 반복되면 재전송을 건너뛰어도 되는 엔진인지(BLE 절약). */
    val isRepeatable: Boolean

    /** 이 비트에 실제로 메인 프레임을 보낼지. 기본은 매 비트 전송(true). */
    fun shouldSend(beatIndex: Int): Boolean = true

    /**
     * 메인 프레임을 보낸 뒤 별도 시점에 추가로 보낼 프레임들(예: accent-hold 뒤 bg색으로 전환).
     * 개수 제한 없음 — 없으면 빈 리스트. t는 이 비트의 타임스탬프, sectionEndMs는 추가 프레임이
     * 섹션을 넘지 않도록 자를 때 쓰는 섹션 종료 시각, fg/bg는 이 비트의 전경/배경색(팔레트 기준).
     */
    fun followUpFrames(t: Long, beatMs: Long, sectionEndMs: Long, fg: LSColor, bg: LSColor, beatIndex: Int): List<Pair<Long, FrameSpec>> = emptyList()
}

/**
 * 여러 비트(구간) 전체의 프레임을 비트별 질의 없이 한 번에 통째로 만드는 단위 — "4비트 중
 * 70%는 ON, 20%는 BLINK, 10%는 ON"처럼 비트 경계에 안 맞는 비율 연출을 위한 확장 지점이다.
 * 아직 실제로 쓰는 패턴이나 섹션 배정은 없음 — 틀만 먼저 마련해둔 상태.
 */
interface FgPatternEffect {
    /** windowStartMs부터 windowMs 구간 전체에 보낼 (시각, FrameSpec) 목록을 한 번에 반환. */
    fun timeline(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        isBalladMode: Boolean, windowStartMs: Long, windowMs: Long
    ): List<Pair<Long, FrameSpec>>
}

private fun pick(ec: EffectMatchingEngine.EffectColors, idx: Int): Pair<LSColor, LSColor?> {
    val fg = ec.fgCycle[Math.floorMod(idx, ec.fgCycle.size)]
    val bg = ec.bgCycle?.let { it[Math.floorMod(idx, it.size)] } ?: ec.bg
    return fg to bg
}

// period는 10ms 단위, FG+BG를 합친 한 사이클 전체 길이.
private fun msToBlinkPeriod(beatMs: Long)       = (beatMs / 100L).toInt().coerceIn(1, 255)
private fun msToBreathPeriod(beatMs: Long)      = (beatMs / 20L).toInt().coerceIn(1, 255)
private fun msToBreathRandomDelay(beatMs: Long) = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

// ON_TRANSIT_ROTATE의 발라드 전용 회전 transit 값.
private const val BALLAD_ROTATE_TRANSIT = 2

/**
 * ON_PULSE: VERSE(비발라드)에서 bar마다 색을 순환시키며 그대로 켠다. 짝수 beatIndex에만
 * 실제로 보내고(홀수는 건너뜀), 보낸 뒤 accent-hold를 반으로 나눠 fg를 한 번 더 유지하는
 * 프레임과 그 뒤 bg색으로 전환하는 프레임을 끼워넣는다 — 전송 스케줄 특성이라 여기 캡슐화한다.
 */
object OnPulseEffect : FgEngineEffect {
    private const val ACCENT_HOLD_MS = 200L

    override fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec {
        val (fg, bg) = pick(palette.onPulse, barIdx)
        return FrameSpec(Primitive.ON, fg, bg)
    }

    override val isRepeatable = false

    override fun shouldSend(beatIndex: Int): Boolean = beatIndex % 2 == 0

    override fun followUpFrames(t: Long, beatMs: Long, sectionEndMs: Long, fg: LSColor, bg: LSColor, beatIndex: Int): List<Pair<Long, FrameSpec>> {
        val holdMs = minOf(ACCENT_HOLD_MS * 2L, beatMs * 44L / 100L).coerceAtLeast(60L)
        val maxT = sectionEndMs - 1L
        val t1 = minOf(maxT, t + holdMs / 2)
        val t2 = minOf(maxT, t + holdMs)
        val frames = mutableListOf<Pair<Long, FrameSpec>>()
        if (t1 > t)  frames += t1 to FrameSpec(Primitive.ON, fg, bg)
        if (t2 > t1) frames += t2 to FrameSpec(Primitive.ON, bg, bg, rotateTransit = 3)
        return frames
    }
}

/** BLINK: VERSE(비발라드) 고에너지 bar에서 깜빡인다. */
object BlinkEffect : FgEngineEffect {
    override fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec {
        val (fg, bg) = pick(palette.blink, barIdx)
        return FrameSpec(Primitive.BLINK, fg, bg, period = msToBlinkPeriod(beatMs))
    }

    override val isRepeatable = false
}

/** BREATH: 발라드 BRIDGE/VERSE/그 외로 색과 랜덤딜레이가 구분된다. */
object BreathEffect : FgEngineEffect {
    override fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec {
        val (fg, bg) = when {
            sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> pick(palette.breathBridgeBallad, 0)
            sectionType == SectionDetector.SectionType.VERSE -> pick(palette.breathVerse, barIdx)
            else -> pick(palette.breathDefault, 0)
        }
        val period = msToBreathPeriod(beatMs)
        // 발라드 BRIDGE: SDK 랜덤딜레이로 기기별 파도 효과. 비발라드 BRIDGE: 결정적 period(파도 없음).
        val randomDelay = when {
            sectionType == SectionDetector.SectionType.VERSE -> 0
            sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> msToBreathRandomDelay(beatMs)
            sectionType == SectionDetector.SectionType.BRIDGE -> period
            else -> msToBreathRandomDelay(beatMs)
        }
        // 발라드 BRIDGE만 SDK의 randomColor 플래그를 켠다.
        val randomColor = if (sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode) 1 else 0
        return FrameSpec(Primitive.BREATH, fg, bg, period = period, randomDelay = randomDelay, randomColor = randomColor)
    }

    override val isRepeatable = true
}

/** ON_TRANSIT_ROTATE: CHORUS에서 전환마다 또렷이 달라지도록 순환시킨다. */
object OnTransitRotateEffect : FgEngineEffect {
    override fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec {
        val (fg, bg) = pick(palette.chorus, beatIndex)
        val rotateTransit = if (isBalladMode) BALLAD_ROTATE_TRANSIT else 0
        return FrameSpec(Primitive.ON, fg, bg, rotateTransit = rotateTransit)
    }

    override val isRepeatable = true
}

/** FREEZE(BREAK): 깜빡임/회전 없이 한 가지 색을 고정해서 보여준다. */
object FreezeEffect : FgEngineEffect {
    override fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec {
        val (fg, bg) = pick(palette.freeze, 0)
        return FrameSpec(Primitive.ON, fg, bg)
    }

    override val isRepeatable = true
}

/** OFF_TRANSIT: 섹션이 buildFramesFromSections에서 이미 continue로 걸러져 실제로 호출되지 않음. */
object OffTransitEffect : FgEngineEffect {
    override fun frameSpec(
        palette: EffectMatchingEngine.Palette, sectionType: SectionDetector.SectionType,
        barIdx: Int, beatIndex: Int, isBalladMode: Boolean, beatMs: Long
    ): FrameSpec = FrameSpec(Primitive.OFF, palette.black, palette.black, rotateTransit = 2)

    override val isRepeatable = false
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
