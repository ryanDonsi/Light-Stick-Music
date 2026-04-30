package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.constants.PrefsKeys

/**
 * Device별 설정 통합 관리 (SharedPreferences)
 *
 * [수정] 하드코딩 문자열 → PrefsKeys 참조
 *   PREFS_NAME         : "device_settings"        → PrefsKeys.PREFS_DEVICE
 *   "call_$mac"        → PrefsKeys.deviceCallKey(mac)
 *   "sms_$mac"         → PrefsKeys.deviceSmsKey(mac)
 *   "broadcast_$mac"   → PrefsKeys.deviceBroadcastKey(mac)
 *   "auto_reconnect_enabled" → PrefsKeys.KEY_DEVICE_AUTO_RECONNECT
 */
object DevicePreferences {

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PrefsKeys.PREFS_DEVICE, Context.MODE_PRIVATE)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Call Event
    // ═══════════════════════════════════════════════════════════

    fun setCallEventEnabled(mac: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean(PrefsKeys.deviceCallKey(mac), enabled)?.apply()
    }

    fun getCallEventEnabled(mac: String): Boolean =
        prefs?.getBoolean(PrefsKeys.deviceCallKey(mac), true) ?: true

    // ═══════════════════════════════════════════════════════════
    // SMS Event
    // ═══════════════════════════════════════════════════════════

    fun setSmsEventEnabled(mac: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean(PrefsKeys.deviceSmsKey(mac), enabled)?.apply()
    }

    fun getSmsEventEnabled(mac: String): Boolean =
        prefs?.getBoolean(PrefsKeys.deviceSmsKey(mac), true) ?: true

    // ═══════════════════════════════════════════════════════════
    // Broadcasting
    // ═══════════════════════════════════════════════════════════

    fun setBroadcasting(mac: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean(PrefsKeys.deviceBroadcastKey(mac), enabled)?.apply()
    }

    fun getBroadcasting(mac: String): Boolean =
        prefs?.getBoolean(PrefsKeys.deviceBroadcastKey(mac), true) ?: true

    // ═══════════════════════════════════════════════════════════
    // Auto Reconnect
    // ═══════════════════════════════════════════════════════════

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(PrefsKeys.KEY_DEVICE_AUTO_RECONNECT, enabled)?.apply()
    }

    fun getAutoReconnectEnabled(): Boolean =
        prefs?.getBoolean(PrefsKeys.KEY_DEVICE_AUTO_RECONNECT, true) ?: true

    // ═══════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════

    /** 특정 디바이스의 모든 설정 삭제 */
    fun clearDevice(mac: String) {
        prefs?.edit()?.apply {
            remove(PrefsKeys.deviceCallKey(mac))
            remove(PrefsKeys.deviceSmsKey(mac))
            remove(PrefsKeys.deviceBroadcastKey(mac))
            apply()
        }
    }

    /** 모든 설정 초기화 */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}