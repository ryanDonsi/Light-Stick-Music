package com.lightstick.music.data.model

/**
 * Splash 화면의 단계별 상태
 */
sealed class SplashState {
    /**
     * 로고 표시 단계
     */
    object ShowLogo : SplashState()

    /**
     * 권한 안내 화면 표시 단계
     */
    object ShowPermissionGuide : SplashState()

    /**
     * 앱 초기화 진행 중 (기존 InitializationState 사용)
     */
    data class Initializing(val initState: InitializationState) : SplashState()
}