package com.lightstick.music.domain.music

/**
 * 장르별 이펙트 엔진 매칭표 — 장르마다 "섹션 타입 → 엔진" 배정을 통째로 다른 EngineSet으로
 * 바꿔 끼울 수 있다. pool(): 그 장르에서 섹션이 쓸 엔진 후보 목록. 후보가 여럿이면
 * strategies에 선언된 SelectionStrategy로 그중 하나를 고른다(pickFromPool() 참고).
 * assign(): 섹션 전체를 대표하는 기본 엔진 — 항상 pool의 첫 번째 값이다. BRIDGE는 비트
 * 단위로 bridgePhaseEngine()이 매 비트마다 직접 override한다.
 */
object GenreEngineCatalog {

    /** 풀에서 하나를 고르는 방법. 섹션 타입마다, 장르 세트마다 다르게 선언할 수 있다. */
    sealed class SelectionStrategy {
        /** 풀을 bar 블록 단위(2~5bar)로 그냥 순환 — 후보별 특별한 조건이 없을 때 기본값. */
        object BarBlockRotation : SelectionStrategy()
        /** bar 에너지가 상위 percentile 이상이면 풀의 두 번째(하이에너지용) 엔진, 아니면 첫 번째. */
        data class EnergyPercentile(val percentile: Float = 0.80f) : SelectionStrategy()
        /** 풀 순서 그대로 bar 인덱스로 고정 순환(해시/에너지 없이 1→2→3→4 식 연출). */
        object Sequential : SelectionStrategy()
    }

    /** 섹션 타입 → 엔진 후보 목록 + (선택적) 후보 선택 전략. 새 세트를 만들려면 이 타입으로 하나 선언하면 된다. */
    data class EngineSet(
        val label: String,
        val pools: Map<SectionDetector.SectionType, List<EffectMatchingEngine.FgEngine>>,
        val strategies: Map<SectionDetector.SectionType, SelectionStrategy> = emptyMap()
    )

    // 대부분 장르가 공유하는 기본 세트 — VERSE는 리듬 위주(ON_PULSE/BLINK, bar 에너지로 선택).
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
        ),
        strategies = mapOf(
            SectionDetector.SectionType.VERSE to SelectionStrategy.EnergyPercentile(0.80f)
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

    private fun setFor(musicStyle: MusicStyleClassifier.MusicStyle): EngineSet =
        GENRE_ENGINE_SETS[musicStyle] ?: DEFAULT_SET

    /** 장르의 EngineSet에서, 섹션 타입이 쓸 엔진 후보 목록. */
    fun pool(type: SectionDetector.SectionType, musicStyle: MusicStyleClassifier.MusicStyle): List<EffectMatchingEngine.FgEngine> =
        setFor(musicStyle).pools.getValue(type)

    /** 이 섹션 타입이 풀에서 후보를 고를 때 쓸 전략. 선언 안 돼 있으면 BarBlockRotation. */
    fun strategyFor(type: SectionDetector.SectionType, musicStyle: MusicStyleClassifier.MusicStyle): SelectionStrategy =
        setFor(musicStyle).strategies[type] ?: SelectionStrategy.BarBlockRotation

    /** 섹션의 기본 엔진 (pool의 첫 번째 값). */
    fun assign(type: SectionDetector.SectionType, musicStyle: MusicStyleClassifier.MusicStyle): EffectMatchingEngine.FgEngine =
        pool(type, musicStyle).first()

    /**
     * 풀에서 전략에 맞게 하나를 고른다. blockIdx/barIdx/barEnergy/energyCutoff는 전략에 따라
     * 선택적으로 쓰인다(에너지 계산 자체는 fullEnv가 필요해 호출부가 미리 구해서 넘겨준다).
     */
    fun pickFromPool(
        type: SectionDetector.SectionType, musicStyle: MusicStyleClassifier.MusicStyle,
        barIdx: Int, blockIdx: Int, barEnergy: Float, energyCutoff: Float
    ): EffectMatchingEngine.FgEngine {
        val candidates = pool(type, musicStyle)
        if (candidates.size <= 1) return candidates.first()
        return when (strategyFor(type, musicStyle)) {
            is SelectionStrategy.EnergyPercentile ->
                if (barEnergy >= energyCutoff) candidates[1] else candidates[0]
            SelectionStrategy.Sequential ->
                candidates[Math.floorMod(barIdx, candidates.size)]
            SelectionStrategy.BarBlockRotation ->
                candidates[Math.floorMod(blockIdx, candidates.size)]
        }
    }

    /** BRIDGE는 항상 BREATH. */
    fun bridgePhaseEngine(): EffectMatchingEngine.FgEngine = EffectMatchingEngine.FgEngine.BREATH
}
