package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.constants.PrefsKeys

/**
 * AUTO 모드 관리자
 * - 자동 연출 기능 ON/OFF 상태 관리
 * - SharedPreferences에 저장하여 앱 재시작 후에도 유지
 *
 * [수정] 하드코딩 문자열 → PrefsKeys 참조
 *   PREF_NAME            : "auto_mode_settings"   → PrefsKeys.PREFS_AUTO_MODE
 *   KEY_AUTO_MODE_ENABLED: "auto_mode_enabled"    → PrefsKeys.KEY_AUTO_MODE_ENABLED
 */
object AutoModePreferences {

    private const val DEFAULT_AUTO_MODE = true

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_AUTO_MODE, Context.MODE_PRIVATE)

    /** AUTO 모드 활성화 여부 조회 */
    fun isAutoModeEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(PrefsKeys.KEY_AUTO_MODE_ENABLED, DEFAULT_AUTO_MODE)

    /** AUTO 모드 상태 저장 */
    fun setAutoModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(PrefsKeys.KEY_AUTO_MODE_ENABLED, enabled)
            .apply()
    }

    /**
     * AUTO 모드 토글
     * @return 변경된 상태 (true: ON, false: OFF)
     */
    fun toggleAutoMode(context: Context): Boolean {
        val newState = !isAutoModeEnabled(context)
        setAutoModeEnabled(context, newState)
        return newState
    }
}