package com.lightstick.music.core.ble

/**
 * BLE 전송 제어 모드
 *
 * 각 전송 소스가 어떻게 제어권을 관리할지 정의합니다.
 *
 * 모드 설명:
 * - EXCLUSIVE: 독점 모드, 다른 모든 소스 차단
 * - COOPERATIVE: 협력 모드, 특정 조합 허용
 * - BACKGROUND: 백그라운드 모드, 우선순위 높은 소스에 자동 양보
 */
enum class ControlMode {
    /**
     * 독점 모드
     *
     * 제어권을 획득하면 다른 모든 소스를 차단합니다.
     *
     * 사용 예시:
     * - Manual Effect (사용자가 직접 제어)
     * - Connection Effect (디바이스 연결 확인)
     *
     * 특징:
     * - 제어권 획득 시 다른 소스의 전송을 모두 차단
     * - 우선순위가 높은 소스만 강제로 제어권을 빼앗을 수 있음
     * - 제어권 해제 전까지 독점적으로 BLE 전송 수행
     */
    EXCLUSIVE,

    /**
     * 협력 모드
     *
     * 특정 소스와 협력하여 동시에 전송할 수 있습니다.
     *
     * 사용 예시:
     * - Timeline Effect (FFT와 협력 가능)
     *
     * 특징:
     * - 제어권을 유지하면서 호환되는 소스의 전송 허용
     * - 호환 가능한 조합은 Coordinator에서 정의
     * - 예: Timeline + FFT (Timeline 없을 때만 FFT 작동)
     */
    COOPERATIVE,

    /**
     * 백그라운드 모드
     *
     * 우선순위가 높은 소스가 있으면 자동으로 양보합니다.
     *
     * 사용 예시:
     * - FFT Effect (배경 효과)
     *
     * 특징:
     * - 다른 소스가 제어권을 요청하면 즉시 양보
     * - 우선순위가 낮더라도 제어권을 강제로 빼앗김
     * - 제어권이 없을 때만 조용히 전송
     */
    BACKGROUND;

    /**
     * 모드 설명 가져오기
     */
    fun getDescription(): String {
        return when (this) {
            EXCLUSIVE -> "독점 모드 - 다른 모든 소스 차단"
            COOPERATIVE -> "협력 모드 - 특정 조합 허용"
            BACKGROUND -> "백그라운드 모드 - 우선순위 높은 소스에 양보"
        }
    }

    /**
     * 강제 제어권 획득 가능 여부
     *
     * @return true if can forcefully acquire control
     */
    fun canForceAcquire(): Boolean {
        return when (this) {
            EXCLUSIVE -> true
            COOPERATIVE -> true
            BACKGROUND -> false
        }
    }

    /**
     * 자동 양보 여부
     *
     * @return true if automatically yields control
     */
    fun autoYields(): Boolean {
        return when (this) {
            EXCLUSIVE -> false
            COOPERATIVE -> false
            BACKGROUND -> true
        }
    }
}