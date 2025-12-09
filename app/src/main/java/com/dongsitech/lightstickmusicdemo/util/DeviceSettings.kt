package com.dongsitech.lightstickmusicdemo.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Device별 설정 통합 관리 (SharedPreferences)
 */
object DeviceSettings {
    private const val PREFS_NAME = "device_settings"

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Call Event
    // ═══════════════════════════════════════════════════════════

    fun setCallEventEnabled(mac: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean("call_$mac", enabled)?.apply()
    }

    fun getCallEventEnabled(mac: String): Boolean {
        return prefs?.getBoolean("call_$mac", true) ?: true
    }

    // ═══════════════════════════════════════════════════════════
    // SMS Event
    // ═══════════════════════════════════════════════════════════

    fun setSmsEventEnabled(mac: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean("sms_$mac", enabled)?.apply()
    }

    fun getSmsEventEnabled(mac: String): Boolean {
        return prefs?.getBoolean("sms_$mac", true) ?: true
    }

    // ═══════════════════════════════════════════════════════════
    // Broadcasting
    // ═══════════════════════════════════════════════════════════

    fun setBroadcasting(mac: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean("broadcast_$mac", enabled)?.apply()
    }

    fun getBroadcasting(mac: String): Boolean {
        return prefs?.getBoolean("broadcast_$mac", true) ?: true // ✅ default를 true로 변경
    }

    // ═══════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════

    /**
     * 특정 Device 설정 삭제
     */
    fun clearDevice(mac: String) {
        prefs?.edit()?.apply {
            remove("call_$mac")
            remove("sms_$mac")
            remove("broadcast_$mac")
            apply()
        }
    }

    /**
     * 모든 설정 초기화
     */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}