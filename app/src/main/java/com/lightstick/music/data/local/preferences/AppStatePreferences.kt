package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.constants.PrefsKeys

/**
 * 앱 초기화 결과 캐싱
 * SplashViewModel의 초기화 완료 시점 통계를 저장한다.
 */
object AppStatePreferences {

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_APP_STATE, Context.MODE_PRIVATE)

    fun saveInitializationResult(
        context: Context,
        musicCount: Int,
        effectCount: Int,
        matchedCount: Int
    ) {
        getPreferences(context).edit()
            .putBoolean(PrefsKeys.KEY_IS_INITIALIZED, true)
            .putInt(PrefsKeys.KEY_MUSIC_COUNT, musicCount)
            .putInt(PrefsKeys.KEY_EFFECT_COUNT, effectCount)
            .putInt(PrefsKeys.KEY_MATCHED_COUNT, matchedCount)
            .putLong(PrefsKeys.KEY_LAST_INIT_TIME, System.currentTimeMillis())
            .apply()
    }
}
