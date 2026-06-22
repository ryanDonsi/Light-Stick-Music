package com.lightstick.music.domain.music

/**
 * EffectMatchingEngine 라우터
 *
 * AutoTimelineConfig.EFFECT_RULE_VERSION에 따라 적절한 엔진을 선택해 반환한다.
 *
 * 지원 버전:
 * - 0: EffectMatchingEngineV0 (단순 ON 매칭)
 * - 1: EffectMatchingEngineV1 (V8 기반 이펙트, skeleton)
 * - 2: EffectMatchingEngineV2 (V8 기반 완전 구현)
 */
object EffectMatchingEngineRouter {

    /**
     * 버전에 맞는 EffectMatchingEngine 인스턴스 반환
     *
     * @param version [AutoTimelineConfig.EFFECT_RULE_VERSION] 기본값
     *                - 0: 기본 (단순 비트 ON/OFF)
     *                - 1: 섹션 기반 이펙트
     *                - 2: 고급 (V8 복잡 이펙트)
     * @return 해당 버전의 엔진 구현체
     */
    fun createEngine(version: Int = AutoTimelineConfig.EFFECT_RULE_VERSION): EffectMatchingEngine =
        when (version) {
            1 -> EffectMatchingEngineV1()
            2 -> EffectMatchingEngineV2()
            else -> EffectMatchingEngineV0()  // 0 또는 미지원 버전은 기본값 사용
        }
}
