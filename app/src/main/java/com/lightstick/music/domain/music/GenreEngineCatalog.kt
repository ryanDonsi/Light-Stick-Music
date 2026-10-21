package com.lightstick.music.domain.music

/**
 * 장르별 이펙트 엔진 매칭표 — 장르마다 "섹션 타입 → 엔진" 배정을 통째로 다른 EngineSet으로
 * 바꿔 끼울 수 있다. pool(): 그 장르에서 섹션이 bar 단위로 순환시킬 엔진 후보 목록(결정론적
 * 해시/에너지로 그중 하나를 고름). assign(): 섹션 전체를 대표하는 기본 엔진 — 항상 pool의
 * 첫 번째 값이다. BRIDGE는 비트 단위로 bridgePhaseEngine()이 매 비트마다 직접 override한다.
 */
object GenreEngineCatalog {

    /** 섹션 타입 → 엔진 후보 목록 매칭표 하나. 새 세트를 만들려면 이 타입으로 하나 선언하면 된다. */
    data class EngineSet(
        val label: String,
        val pools: Map<SectionDetector.SectionType, List<EffectMatchingEngine.FgEngine>>
    )

    // 대부분 장르가 공유하는 기본 세트 — VERSE는 리듬 위주(ON_PULSE/BLINK 순환).
    private val DEFAULT_SET = EngineSet(
        label = "DEFAULT",
        pools = mapOf(
            SectionDetector.SectionType.INTRO  to listOf(EffectMatchingEngine.FgEngine.BREATH),
            SectionDetector.SectionType.OUTRO  to listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT),
            SectionDetector.SectionType.BREAK  to listOf(EffectMatchingEngine.FgEngine.FREEZE),
            SectionDetector.SectionType.END    to listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT),
            SectionDetector.SectionType.BRIDGE to listOf(EffectMatchingEngine.FgEngine.BREATH),
            SectionDetector.SectionType.CLIMAX to listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE),
            SectionDetector.SectionType.CHORUS to listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE),
            SectionDetector.SectionType.VERSE  to listOf(EffectMatchingEngine.FgEngine.ON_PULSE, EffectMatchingEngine.FgEngine.BLINK)
        )
    )

    // 발라드 계열(BALLAD/HIPHOP_RNB) — VERSE만 숨쉬는 BREATH로 교체, 나머지는 DEFAULT와 동일.
    private val BALLAD_SET = DEFAULT_SET.copy(
        label = "BALLAD",
        pools = DEFAULT_SET.pools + (SectionDetector.SectionType.VERSE to listOf(EffectMatchingEngine.FgEngine.BREATH))
    )

    // 장르 → 세트 매핑. 새 세트를 하나 더 선언해 여기 값만 바꾸면 그 장르의 매칭을 통째로 교체할 수 있다.
    private val GENRE_ENGINE_SETS: Map<MusicStyleClassifier.MusicStyle, EngineSet> = mapOf(
        MusicStyleClassifier.MusicStyle.BALLAD     to BALLAD_SET,
        MusicStyleClassifier.MusicStyle.HIPHOP_RNB to BALLAD_SET,
        MusicStyleClassifier.MusicStyle.EDM        to DEFAULT_SET,
        MusicStyleClassifier.MusicStyle.DANCE_POP  to DEFAULT_SET,
        MusicStyleClassifier.MusicStyle.ROCK       to DEFAULT_SET,
        MusicStyleClassifier.MusicStyle.POP        to DEFAULT_SET
    )

    /** 장르의 EngineSet에서, 섹션 타입이 bar 단위로 순환시킬 엔진 후보 목록. */
    fun pool(type: SectionDetector.SectionType, musicStyle: MusicStyleClassifier.MusicStyle): List<EffectMatchingEngine.FgEngine> =
        (GENRE_ENGINE_SETS[musicStyle] ?: DEFAULT_SET).pools.getValue(type)

    /** 섹션의 기본 엔진 (pool의 첫 번째 값). */
    fun assign(type: SectionDetector.SectionType, musicStyle: MusicStyleClassifier.MusicStyle): EffectMatchingEngine.FgEngine =
        pool(type, musicStyle).first()

    /** BRIDGE는 항상 BREATH. */
    fun bridgePhaseEngine(): EffectMatchingEngine.FgEngine = EffectMatchingEngine.FgEngine.BREATH
}
