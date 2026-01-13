package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * AUTO 모드 관리자
 * - 자동 연출 기능 ON/OFF 상태 관리
 * - SharedPreferences에 저장하여 앱 재시작 후에도 유지
 */
object AutoModePreferences {
    private const val PREF_NAME = "auto_mode_settings"
    private const val KEY_AUTO_MODE_ENABLED = "auto_mode_enabled"
    private const val DEFAULT_AUTO_MODE = true  // Default: ON

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * AUTO 모드 활성화 여부 조회
     */
    fun isAutoModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_MODE_ENABLED, DEFAULT_AUTO_MODE)
    }

    /**
     * AUTO 모드 상태 저장
     */
    fun setAutoModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_MODE_ENABLED, enabled)
            .apply()
    }

    /**
     * AUTO 모드 토글
     * @return 변경된 상태 (true: ON, false: OFF)
     */
    fun toggleAutoMode(context: Context): Boolean {
        val currentState = isAutoModeEnabled(context)
        val newState = !currentState
        setAutoModeEnabled(context, newState)
        return newState
    }
}