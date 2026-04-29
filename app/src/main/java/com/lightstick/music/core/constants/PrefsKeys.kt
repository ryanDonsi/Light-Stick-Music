package com.lightstick.music.core.constants

/**
 * 중앙 집중식 SharedPreferences 키 관리
 *
 * 모든 SharedPreferences 파일명·키를 한 곳에서 관리하여 중복·오타를 방지합니다.
 *
 * [수정 내역]
 * - PREFS_DEVICE     : "device_preferences" → "device_settings"  (DevicePreferences 실제값 일치)
 * - PREFS_AUTO_MODE  : "auto_mode_preferences" → "auto_mode_settings" (AutoModePreferences 실제값 일치)
 * - 삭제 : KEY_FIRST_LAUNCH, KEY_APP_VERSION (미구현/미사용)
 * - 삭제 : KEY_LAST_CONNECTED_DEVICE, KEY_DEVICE_NAME_PREFIX, KEY_DEVICE_RSSI_PREFIX (미사용)
 * - 삭제 : KEY_EFFECT_SETTINGS_PREFIX (미사용)
 * - 변경 : KEY_FG_PRESET_COLORS / KEY_BG_PRESET_COLORS → PREFIX 상수로 교체
 *          (EffectViewModel에서 "fg_preset_$index" 패턴 사용)
 * - 추가 : Device 이벤트 key prefix (call_, sms_, broadcast_)
 * - helper 정리 : 미사용 3개 삭제, 실제 사용 패턴에 맞는 helper 추가
 */
object PrefsKeys {

    // ═══════════════════════════════════════════════════════════
    // Preferences 파일명
    // ═══════════════════════════════════════════════════════════

    /** MusicViewModel.loadCachedMusicOrScan() */
    const val PREFS_APP_STATE = "app_state"

    /** EffectViewModel */
    const val PREFS_EFFECT_SETTINGS = "effect_settings"

    /** EffectPathPreferences */
    const val PREFS_EFFECT_DIRECTORY = "effect_directory"

    /** DevicePreferences — 수정: "device_preferences" → "device_settings" */
    const val PREFS_DEVICE = "device_settings"

    /** AutoModePreferences — 수정: "auto_mode_preferences" → "auto_mode_settings" */
    const val PREFS_AUTO_MODE = "auto_mode_settings"

    // ═══════════════════════════════════════════════════════════
    // App State Keys  (MusicViewModel / SplashViewModel)
    // ═══════════════════════════════════════════════════════════

    const val KEY_IS_INITIALIZED = "is_initialized"
    const val KEY_MUSIC_COUNT    = "music_count"
    const val KEY_EFFECT_COUNT   = "effect_count"
    const val KEY_MATCHED_COUNT  = "matched_count"
    const val KEY_LAST_INIT_TIME = "last_init_time"

    // ═══════════════════════════════════════════════════════════
    // Effect Directory Keys  (EffectPathPreferences)
    // ═══════════════════════════════════════════════════════════

    const val KEY_DIRECTORY_PATH   = "directory_path"
    const val KEY_DIRECTORY_URI    = "directory_uri"
    const val KEY_AUTO_CONFIGURED  = "auto_configured"

    // ═══════════════════════════════════════════════════════════
    // Effect Settings Keys  (EffectViewModel)
    // ═══════════════════════════════════════════════════════════

    const val KEY_CUSTOM_EFFECTS  = "custom_effects"

    /** Foreground 프리셋 색상 key prefix — "fg_preset_$index" 형태로 사용 */
    const val KEY_FG_PRESET_PREFIX = "fg_preset_"

    /** Background 프리셋 색상 key prefix — "bg_preset_$index" 형태로 사용 */
    const val KEY_BG_PRESET_PREFIX = "bg_preset_"

    // ═══════════════════════════════════════════════════════════
    // Device Event Keys  (DevicePreferences)
    // ═══════════════════════════════════════════════════════════

    /** 전화 수신 이벤트 활성화 key prefix — "call_$mac" 형태 */
    const val KEY_DEVICE_CALL_PREFIX      = "call_"

    /** SMS 수신 이벤트 활성화 key prefix — "sms_$mac" 형태 */
    const val KEY_DEVICE_SMS_PREFIX       = "sms_"

    /** 브로드캐스팅 활성화 key prefix — "broadcast_$mac" 형태 */
    const val KEY_DEVICE_BROADCAST_PREFIX = "broadcast_"

    /** 디바이스 이름 key prefix — "device_name_$mac" 형태 */
    const val KEY_DEVICE_NAME_PREFIX      = "device_name_"

    /** 자동 재연결 활성화 key */
    const val KEY_DEVICE_AUTO_RECONNECT   = "auto_reconnect_enabled"

    // ═══════════════════════════════════════════════════════════
    // Auto Mode Keys  (AutoModePreferences)
    // ═══════════════════════════════════════════════════════════

    const val KEY_AUTO_MODE_ENABLED = "auto_mode_enabled"

    // ═══════════════════════════════════════════════════════════
    // Helper Functions
    // ═══════════════════════════════════════════════════════════

    /** Foreground 프리셋 색상 key 생성 */
    fun fgPresetKey(index: Int): String = KEY_FG_PRESET_PREFIX + index

    /** Background 프리셋 색상 key 생성 */
    fun bgPresetKey(index: Int): String = KEY_BG_PRESET_PREFIX + index

    /** 디바이스 전화 수신 이벤트 key 생성 */
    fun deviceCallKey(mac: String): String = KEY_DEVICE_CALL_PREFIX + mac

    /** 디바이스 SMS 수신 이벤트 key 생성 */
    fun deviceSmsKey(mac: String): String = KEY_DEVICE_SMS_PREFIX + mac

    /** 디바이스 브로드캐스팅 key 생성 */
    fun deviceBroadcastKey(mac: String): String = KEY_DEVICE_BROADCAST_PREFIX + mac

    /** 디바이스 이름 key 생성 */
    fun deviceNameKey(mac: String): String = KEY_DEVICE_NAME_PREFIX + mac
}