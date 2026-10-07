package com.lightstick.music.domain.music

/**
 * EffectMatchingEngine 라우터
 *
 * AutoTimelineConfig.EFFECT_RULE_VERSION에 따라 적절한 엔진을 선택해 반환한다.
 *
 * 지원 버전:
 * - 0: EffectMatchingEngineV0 (단순 ON 매칭, 비트 감지기 테스트용)
 * - 2: EffectMatchingEngineV2 (V8 기반 완전 구현)
 *
 * (미완성 스켈레톤이던 EffectMatchingEngineV1은 제거했다.)
 */
object EffectMatchingEngineRouter {

    /**
     * 버전에 맞는 EffectMatchingEngine 인스턴스 반환
     *
     * @param version [AutoTimelineConfig.EFFECT_RULE_VERSION] 기본값
     *                - 0: 기본 (단순 비트 ON/OFF)
     *                - 2: 고급 (V8 복잡 이펙트)
     * @return 해당 버전의 엔진 구현체
     */
    fun createEngine(version: Int = AutoTimelineConfig.EFFECT_RULE_VERSION): EffectMatchingEngine =
        when (version) {
            2 -> EffectMatchingEngineV2()
            else -> EffectMatchingEngineV0()  // 0 또는 미지원 버전은 기본값 사용
        }
}
