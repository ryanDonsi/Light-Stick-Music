package com.lightstick.music.core.ble

/**
 * BLE 전송 제어 모드
 *
 * - EXCLUSIVE:   독점 모드, 다른 모든 소스 차단
 * - COOPERATIVE: 협력 모드, 호환되는 소스와 동시 전송 허용
 * - BACKGROUND:  백그라운드 모드, 우선순위 높은 소스에 자동 양보
 */
enum class ControlMode {
    EXCLUSIVE,
    COOPERATIVE,
    BACKGROUND
}
