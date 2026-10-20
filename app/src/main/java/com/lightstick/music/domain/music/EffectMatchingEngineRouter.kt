package com.lightstick.music.domain.music

/**
 * EffectMatchingEngine 라우터 — 버전에 맞는 엔진 인스턴스를 선택해 반환한다.
 * 0: EffectMatchingEngineV0 (단순 ON 매칭), 1: EffectMatchingEngineV1 (완전 구현)
 */
object EffectMatchingEngineRouter {

    fun createEngine(version: Int = AutoTimelineConfig.EFFECT_RULE_VERSION): EffectMatchingEngine =
        when (version) {
            1 -> EffectMatchingEngineV1()
            else -> EffectMatchingEngineV0()
        }
}
