package com.lightstick.music.app

import android.app.Application
import com.lightstick.music.core.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.config.DeviceFilter
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.data.local.preferences.DevicePreferences
import dagger.hilt.android.HiltAndroidApp

/**
 * Application 클래스
 * 앱 시작 시 SDK 및 SharedPreferences 초기화, permission권한이 필요한 초기화는 SplashActivity에서 수행
 */
@HiltAndroidApp
class LightStickMusicApp : Application() {

    companion object {
        private const val TAG = AppConstants.Feature.APP
    }

    override fun onCreate() {
        super.onCreate()

        try {
            initializeSDK()
            initializePreferences()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}", e)
        }
    }

    /**
     * SDK 초기화
     * - 디바이스 필터 설정: 이름이 "LS"/"GL"/"RL"로 끝나는 디바이스만 허용
     *   ("LS"는 구 제품군 호환용 — 추후 제거 예정)
     * - name이 null인 디바이스는 SDK 레벨에서 자동 거부
     */
    private fun initializeSDK() {
        LSBluetooth.setDebugLoggingEnabled(true)

        val filter = DeviceFilter.Builder()
            .addName("LS", DeviceFilter.MatchMode.ENDS_WITH)
            .addName("GL", DeviceFilter.MatchMode.ENDS_WITH)
            .addName("RL", DeviceFilter.MatchMode.ENDS_WITH)
            .build()

        LSBluetooth.initialize(
            context = applicationContext,
            deviceFilter = filter
        )
    }

    /**
     * SharedPreferences 초기화
     */
    private fun initializePreferences() {
        DevicePreferences.initialize(applicationContext)
    }
}