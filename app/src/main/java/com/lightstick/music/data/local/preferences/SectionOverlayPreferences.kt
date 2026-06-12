package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.constants.PrefsKeys

/**
 * 섹션 정보 오버레이 표시 여부 설정
 * MusicControlScreen 앨범 이미지 위 섹션 오버레이 ON/OFF 를 저장한다.
 */
object SectionOverlayPreferences {

    private const val DEFAULT_ENABLED = false

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_AUTO_MODE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        getPreferences(context).getBoolean(PrefsKeys.KEY_SECTION_OVERLAY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(PrefsKeys.KEY_SECTION_OVERLAY_ENABLED, enabled)
            .apply()
    }

    fun toggle(context: Context): Boolean {
        val newState = !isEnabled(context)
        setEnabled(context, newState)
        return newState
    }
}
