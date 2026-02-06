package com.lightstick.music.core.constants

/**
 * 중앙 집중식 SharedPreferences 키 관리
 *
 * 모든 SharedPreferences 키를 한 곳에서 관리하여:
 * - 중복 방지
 * - 오타 방지
 * - 유지보수 용이
 */
object PrefsKeys {

    // ═══════════════════════════════════════════════════════════
    // Preferences 파일명
    // ═══════════════════════════════════════════════════════════

    const val PREFS_APP_STATE = "app_state"
    const val PREFS_EFFECT_SETTINGS = "effect_settings"
    const val PREFS_EFFECT_DIRECTORY = "effect_directory"
    const val PREFS_DEVICE = "device_preferences"
    const val PREFS_AUTO_MODE = "auto_mode_preferences"

    // ═══════════════════════════════════════════════════════════
    // App State Keys
    // ═══════════════════════════════════════════════════════════

    const val KEY_IS_INITIALIZED = "is_initialized"
    const val KEY_FIRST_LAUNCH = "first_launch"
    const val KEY_APP_VERSION = "app_version"

    // ═══════════════════════════════════════════════════════════
    // Effect Directory Keys
    // ═══════════════════════════════════════════════════════════

    const val KEY_DIRECTORY_PATH = "directory_path"
    const val KEY_DIRECTORY_URI = "directory_uri"
    const val KEY_AUTO_CONFIGURED = "auto_configured"

    // ═══════════════════════════════════════════════════════════
    // Effect Settings Keys
    // ═══════════════════════════════════════════════════════════

    const val KEY_CUSTOM_EFFECTS = "custom_effects"
    const val KEY_EFFECT_SETTINGS_PREFIX = "effect_settings_"
    const val KEY_FG_PRESET_COLORS = "fg_preset_colors"
    const val KEY_BG_PRESET_COLORS = "bg_preset_colors"

    // ═══════════════════════════════════════════════════════════
    // Device Preferences Keys
    // ═══════════════════════════════════════════════════════════

    const val KEY_LAST_CONNECTED_DEVICE = "last_connected_device"
    const val KEY_DEVICE_NAME_PREFIX = "device_name_"
    const val KEY_DEVICE_RSSI_PREFIX = "device_rssi_"

    // ═══════════════════════════════════════════════════════════
    // Auto Mode Keys
    // ═══════════════════════════════════════════════════════════

    const val KEY_AUTO_MODE_ENABLED = "auto_mode_enabled"

    // ═══════════════════════════════════════════════════════════
    // Helper Functions
    // ═══════════════════════════════════════════════════════════

    /**
     * Effect 설정 키 생성
     * @param effectKey Effect의 고유 키
     */
    fun effectSettingsKey(effectKey: String): String {
        return KEY_EFFECT_SETTINGS_PREFIX + effectKey
    }

    /**
     * 디바이스 이름 키 생성
     * @param mac 디바이스 MAC 주소
     */
    fun deviceNameKey(mac: String): String {
        return KEY_DEVICE_NAME_PREFIX + mac
    }

    /**
     * 디바이스 RSSI 키 생성
     * @param mac 디바이스 MAC 주소
     */
    fun deviceRssiKey(mac: String): String {
        return KEY_DEVICE_RSSI_PREFIX + mac
    }
}