package com.lightstick.music.core.ble

/**
 * BLE 전송 우선순위 정의 (숫자가 클수록 우선순위 높음)
 *
 * SYSTEM(120) > CONNECTION_EFFECT(100) > MANUAL_EFFECT(80)
 *   > EFX_EFFECT(60) = TIMELINE_EFFECT(60) > FFT_EFFECT(40) > BROADCAST(20)
 */
object TransmissionPriority {

    /** 시스템 제어 (디버그, 테스트 등) */
    const val SYSTEM = 120

    /** 연결 확인 효과 */
    const val CONNECTION_EFFECT = 100

    /** 수동 효과 (Effect 탭) */
    const val MANUAL_EFFECT = 80

    /** EFX 파일 기반 효과 (Music 재생 1순위) */
    const val EFX_EFFECT = 60

    /** 자동 타임라인 효과 (Music 재생 2순위) */
    const val TIMELINE_EFFECT = 60

    /** FFT 효과 (주파수 분석, Music 재생 3순위) */
    const val FFT_EFFECT = 40

    /** 브로드캐스트 */
    const val BROADCAST = 20

    fun hasHigherPriority(priority1: Int, priority2: Int): Boolean = priority1 > priority2
}
