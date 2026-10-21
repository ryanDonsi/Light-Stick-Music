package com.lightstick.music.domain.music

/**
 * 섹션 타입별 이펙트 엔진 매칭표 — 어떤 SectionType이 어떤 FgEngine을 쓰는지 선언해 둔 카탈로그.
 * pool(): 그 섹션이 bar 단위로 순환시킬 엔진 후보 목록(결정론적 해시/에너지로 그중 하나를
 * 고름). assign(): 섹션 전체를 대표하는 기본 엔진 — 항상 pool의 첫 번째 값이다.
 * BRIDGE는 비트 단위로 bridgePhaseEngine()이 매 비트마다 직접 override한다.
 */
object SectionEngineCatalog {

    private val NON_BALLAD_VERSE_POOL = listOf(EffectMatchingEngine.FgEngine.ON_PULSE, EffectMatchingEngine.FgEngine.BLINK)
    private val BALLAD_VERSE_POOL = listOf(EffectMatchingEngine.FgEngine.BREATH)

    // VERSE는 isBalladMode에 따라 후보군 자체가 달라지므로 이 표에는 넣지 않고 pool()에서 따로 분기한다.
    private val POOLS: Map<SectionDetector.SectionType, List<EffectMatchingEngine.FgEngine>> = mapOf(
        SectionDetector.SectionType.INTRO  to listOf(EffectMatchingEngine.FgEngine.BREATH),
        SectionDetector.SectionType.OUTRO  to listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT),
        SectionDetector.SectionType.BREAK  to listOf(EffectMatchingEngine.FgEngine.FREEZE),
        SectionDetector.SectionType.END    to listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT),
        SectionDetector.SectionType.BRIDGE to listOf(EffectMatchingEngine.FgEngine.BREATH),
        SectionDetector.SectionType.CLIMAX to listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE),
        SectionDetector.SectionType.CHORUS to listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE)
    )

    /** 섹션 타입이 bar 단위로 순환시킬 엔진 후보 목록. */
    fun pool(type: SectionDetector.SectionType, isBalladMode: Boolean): List<EffectMatchingEngine.FgEngine> =
        if (type == SectionDetector.SectionType.VERSE) {
            if (isBalladMode) BALLAD_VERSE_POOL else NON_BALLAD_VERSE_POOL
        } else {
            POOLS.getValue(type)
        }

    /** 섹션의 기본 엔진 (pool의 첫 번째 값). */
    fun assign(type: SectionDetector.SectionType, isBalladMode: Boolean): EffectMatchingEngine.FgEngine =
        pool(type, isBalladMode).first()

    /** BRIDGE는 항상 BREATH. */
    fun bridgePhaseEngine(): EffectMatchingEngine.FgEngine = EffectMatchingEngine.FgEngine.BREATH
}
