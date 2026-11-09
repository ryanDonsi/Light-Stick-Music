package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lightstick.music.core.constants.PrefsKeys

/**
 * EffectViewModel의 이펙트 설정 저장소
 * - 커스텀 이펙트 목록(JSON)
 * - FG/BG 프리셋 색상(RGB, 인덱스별)
 * - 이펙트별 설정(EffectKeys 키 기준 JSON, 키는 호출측에서 결정)
 */
object EffectSettingsPreferences {

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_EFFECT_SETTINGS, Context.MODE_PRIVATE)

    fun getCustomEffectsJson(context: Context): String? =
        getPreferences(context).getString(PrefsKeys.KEY_CUSTOM_EFFECTS, null)

    fun saveCustomEffectsJson(context: Context, json: String) {
        getPreferences(context).edit { putString(PrefsKeys.KEY_CUSTOM_EFFECTS, json) }
    }

    fun getFgPresetRgb(context: Context, index: Int): Int =
        getPreferences(context).getInt(PrefsKeys.fgPresetKey(index), -1)

    fun getBgPresetRgb(context: Context, index: Int): Int =
        getPreferences(context).getInt(PrefsKeys.bgPresetKey(index), -1)

    fun saveFgPresetColors(context: Context, rgbByIndex: Map<Int, Int>) {
        getPreferences(context).edit {
            rgbByIndex.forEach { (index, rgb) -> putInt(PrefsKeys.fgPresetKey(index), rgb) }
        }
    }

    fun saveBgPresetColors(context: Context, rgbByIndex: Map<Int, Int>) {
        getPreferences(context).edit {
            rgbByIndex.forEach { (index, rgb) -> putInt(PrefsKeys.bgPresetKey(index), rgb) }
        }
    }

    /**
     * 커스텀 이펙트/프리셋 색상 키를 제외한, 이펙트별 설정 키 → JSON 목록.
     * 과거 버전이 남긴 Int 등 String이 아닌 값이 있어도 getString이 ClassCastException을
     * 던지지 않도록, 저장된 값의 타입을 확인해 String 값만 가져온다.
     */
    fun getAllEffectSettingsEntries(context: Context): Map<String, String> {
        val prefs = getPreferences(context)
        return prefs.all.entries
            .filter { (key, _) ->
                key != PrefsKeys.KEY_CUSTOM_EFFECTS &&
                        !key.startsWith(PrefsKeys.KEY_FG_PRESET_PREFIX) &&
                        !key.startsWith(PrefsKeys.KEY_BG_PRESET_PREFIX)
            }
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()
    }

    fun saveEffectSettingsJson(context: Context, key: String, json: String) {
        getPreferences(context).edit { putString(key, json) }
    }
}
