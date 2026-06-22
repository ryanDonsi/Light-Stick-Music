package com.lightstick.music.domain.music

/**
 * EffectMatchingEngine 라우터
 *
 * AutoTimelineConfig.EFFECT_RULE_VERSION에 따라 적절한 엔진을 선택해 반환한다.
 *
 * 지원 버전:
 * - 0: EffectMatchingEngineV0 (단순 ON 매칭)
 * - 1: EffectMatchingEngineV1 (V8 기반 이펙트)
 */
object EffectMatchingEngineRouter {

    /**
     * 버전에 맞는 EffectMatchingEngine 인스턴스 반환
     *
     * @param version [AutoTimelineConfig.EFFECT_RULE_VERSION] 기본값
     * @return 해당 버전의 엔진 구현체
     * @throws IllegalArgumentException 지원하지 않는 버전
     */
    fun createEngine(version: Int = AutoTimelineConfig.EFFECT_RULE_VERSION): EffectMatchingEngine =
        when (version) {
            0 -> EffectMatchingEngineV0()
            1 -> EffectMatchingEngineV1()
            else -> throw IllegalArgumentException("Unsupported effect matching engine version: $version (supported: 0, 1)")
        }
}
