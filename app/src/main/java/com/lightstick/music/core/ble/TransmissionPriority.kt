package com.lightstick.music.core.ble

/**
 * BLE 전송 우선순위 정의
 *
 * 각 전송 소스의 우선순위를 정의합니다.
 * 숫자가 클수록 우선순위가 높습니다.
 *
 * 우선순위 규칙:
 * - CONNECTION_EFFECT (100): 최고 우선순위, 디바이스 연결 확인용
 * - MANUAL_EFFECT (80): 높음, 사용자가 직접 제어
 * - TIMELINE_EFFECT (60): 중간, 음악 재생 중 타임라인 효과
 * - FFT_EFFECT (40): 낮음, 배경 효과
 * - SYSTEM (120): 시스템 제어 (디버그, 테스트 등)
 */
object TransmissionPriority {

    // ═══════════════════════════════════════════════════════════
    // Priority Levels
    // ═══════════════════════════════════════════════════════════

    /** 시스템 제어 (디버그, 테스트 등) - 최고 우선순위 */
    const val SYSTEM = 120

    /** 연결 확인 효과 - 매우 높음 */
    const val CONNECTION_EFFECT = 100

    /** 수동 효과 (Effect 탭) - 높음 */
    const val MANUAL_EFFECT = 80

    /** 타임라인 효과 (Music 재생) - 중간 */
    const val TIMELINE_EFFECT = 60

    /** FFT 효과 (주파수 분석) - 낮음 */
    const val FFT_EFFECT = 40

    /** 브로드캐스트 - 매우 낮음 */
    const val BROADCAST = 20

    // ═══════════════════════════════════════════════════════════
    // Helper Functions
    // ═══════════════════════════════════════════════════════════

    /**
     * 우선순위 비교
     *
     * @param priority1 첫 번째 우선순위
     * @param priority2 두 번째 우선순위
     * @return true if priority1 has higher priority than priority2
     */
    fun hasHigherPriority(priority1: Int, priority2: Int): Boolean {
        return priority1 > priority2
    }

    /**
     * 우선순위 이름 가져오기
     *
     * @param priority 우선순위 값
     * @return 우선순위 이름
     */
    fun getPriorityName(priority: Int): String {
        return when (priority) {
            SYSTEM -> "SYSTEM"
            CONNECTION_EFFECT -> "CONNECTION_EFFECT"
            MANUAL_EFFECT -> "MANUAL_EFFECT"
            TIMELINE_EFFECT -> "TIMELINE_EFFECT"
            FFT_EFFECT -> "FFT_EFFECT"
            BROADCAST -> "BROADCAST"
            else -> "CUSTOM($priority)"
        }
    }

    /**
     * 우선순위 레벨 가져오기
     *
     * @param priority 우선순위 값
     * @return 우선순위 레벨 (CRITICAL, HIGH, MEDIUM, LOW)
     */
    fun getPriorityLevel(priority: Int): PriorityLevel {
        return when {
            priority >= SYSTEM -> PriorityLevel.CRITICAL
            priority >= MANUAL_EFFECT -> PriorityLevel.HIGH
            priority >= TIMELINE_EFFECT -> PriorityLevel.MEDIUM
            else -> PriorityLevel.LOW
        }
    }

    /**
     * 우선순위 검증
     *
     * @param priority 우선순위 값
     * @return true if valid priority range
     */
    fun isValidPriority(priority: Int): Boolean {
        return priority in 0..150
    }
}

/**
 * 우선순위 레벨
 */
enum class PriorityLevel {
    /** 치명적 (시스템) */
    CRITICAL,

    /** 높음 (사용자 직접 제어) */
    HIGH,

    /** 중간 (음악 재생) */
    MEDIUM,

    /** 낮음 (배경 효과) */
    LOW
}