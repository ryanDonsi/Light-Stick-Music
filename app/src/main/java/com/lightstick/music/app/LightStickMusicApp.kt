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

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "Application.onCreate() - Initializing...")

        try {
            initializeSDK()
            initializePreferences()

            Log.d(TAG, "✅ All initialization completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Initialization failed: ${e.message}", e)
        } finally {
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    /**
     * SDK 초기화
     * - 디바이스 필터 설정: "LS"로 끝나는 디바이스만 허용
     * - name이 null인 디바이스는 SDK 레벨에서 자동 거부
     */
    private fun initializeSDK() {
        Log.d(TAG, "Initializing Light Stick SDK...")

        // Filter setting
        val filter = DeviceFilter.byName(
            pattern = "LS",
            mode = DeviceFilter.MatchMode.ENDS_WITH,
            ignoreCase = true
        )

        Log.d(TAG, "Filter: ENDS_WITH('LS', ignoreCase=true)")

        // Initialize SDK
        LSBluetooth.initialize(
            context = applicationContext,
            deviceFilter = filter
        )

        Log.d(TAG, "✅ SDK initialized")
    }

    /**
     * SharedPreferences 초기화
     */
    private fun initializePreferences() {
        Log.d(TAG, "Initializing SharedPreferences...")

        DevicePreferences.initialize(applicationContext)

        Log.d(TAG, "✅ Preferences initialized")
    }
}