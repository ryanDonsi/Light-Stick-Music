package com.lightstick.music.app

import android.app.Application
import android.content.Context
import com.lightstick.music.BuildConfig
import com.lightstick.music.core.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.config.DeviceFilter
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.data.local.preferences.BleTuningPreferences
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

        /** 이름이 "LS"/"GL"/"RL"로 끝나는 디바이스만 허용 ("LS"는 구 제품군 호환용) */
        fun deviceFilter(): DeviceFilter = DeviceFilter.Builder()
            .addName("LS", DeviceFilter.MatchMode.ENDS_WITH)
            .addName("GL", DeviceFilter.MatchMode.ENDS_WITH)
            .addName("RL", DeviceFilter.MatchMode.ENDS_WITH)
            .build()

        /**
         * 다른 앱이 이미 연결해 둔 응원봉을 이어받는다.
         * initialize()는 2회차부터 초기화를 건너뛰고 시스템 연결 복원만 재수행한다.
         * 연결은 비동기이므로 결과는 연결 이벤트로 전달된다. (메인 스레드에서 호출)
         */
        fun restoreSystemConnections(context: Context) {
            try {
                LSBluetooth.initialize(
                    context = context.applicationContext,
                    deviceFilter = deviceFilter()
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "restore without permission: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            com.lightstick.music.domain.music.MusicIdCache.init(this)
            initializeSDK()
            initializePreferences()
            com.lightstick.music.core.sync.DeviceDisconnectSync.register(this)
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
        // SDK 로그는 debug 빌드에서만 사용 (release, releaseTest 는 BuildConfig.DEBUG=false)
        LSBluetooth.setDebugLoggingEnabled(BuildConfig.DEBUG)

        // 디버그 빌드: 기기 상세 > BLE 전송 설정에서 저장한 전송 간격/큐 크기를 복원한다.
        // (SDK 는 값을 저장하지 않으며, release/releaseTest 는 SDK 기본값 50ms / 8 을 그대로 쓴다)
        if (BuildConfig.DEBUG) {
            try {
                LSBluetooth.setCommandIntervalMs(BleTuningPreferences.getCommandIntervalMs(applicationContext))
                LSBluetooth.setCommandQueueSize(BleTuningPreferences.getCommandQueueSize(applicationContext))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "저장된 BLE 전송 설정이 범위를 벗어나 기본값을 사용합니다: ${e.message}")
            }
        }

        restoreSystemConnections(applicationContext)
    }

    /**
     * SharedPreferences 초기화
     */
    private fun initializePreferences() {
        DevicePreferences.initialize(applicationContext)
    }
}