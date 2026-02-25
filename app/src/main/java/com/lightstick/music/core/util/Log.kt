package com.lightstick.music.core.util

import com.lightstick.music.core.constants.AppConstants
import android.util.Log as AndroidLog

/**
 * 앱 전역 로그 유틸리티
 *
 * android.util.Log 와 완전히 동일한 시그니처를 제공합니다.
 * import 한 줄만 교체하면 기존 코드 수정 없이 사용 가능합니다.
 *
 *   import android.util.Log
 *   → import com.lightstick.music.core.util.Log
 *
 * 출력 조건:
 *   V  →  LOG_ENABLED && LOG_VERBOSE_ENABLED && TAG in LOG_ENABLED_FEATURES
 *   D  →  LOG_ENABLED && TAG in LOG_ENABLED_FEATURES
 *   I  →  LOG_ENABLED && TAG in LOG_ENABLED_FEATURES
 *   W  →  LOG_ENABLED && TAG in LOG_ENABLED_FEATURES
 *   E  →  항상 출력 (릴리즈에서도 크래시 추적 필요)
 *
 * TAG 는 반드시 AppConstants.Feature 상수를 사용하세요:
 *   private const val TAG = AppConstants.Feature.BLE_COORDINATOR
 */
object Log {

    // ═══════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════

    /**
     * 해당 TAG 의 로그 출력 여부 확인
     * LOG_ENABLED = true 이고 LOG_ENABLED_FEATURES 에 포함된 경우만 허용
     */
    private fun isLoggable(tag: String): Boolean {
        return AppConstants.LOG_ENABLED && tag in AppConstants.LOG_ENABLED_FEATURES
    }

    // ═══════════════════════════════════════════════════════════
    // VERBOSE
    // ═══════════════════════════════════════════════════════════

    fun v(tag: String, msg: String): Int {
        if (isLoggable(tag) && AppConstants.LOG_VERBOSE_ENABLED) {
            return AndroidLog.v(tag, msg)
        }
        return 0
    }

    fun v(tag: String, msg: String, tr: Throwable): Int {
        if (isLoggable(tag) && AppConstants.LOG_VERBOSE_ENABLED) {
            return AndroidLog.v(tag, msg, tr)
        }
        return 0
    }

    // ═══════════════════════════════════════════════════════════
    // DEBUG
    // ═══════════════════════════════════════════════════════════

    fun d(tag: String, msg: String): Int {
        if (isLoggable(tag)) {
            return AndroidLog.d(tag, msg)
        }
        return 0
    }

    fun d(tag: String, msg: String, tr: Throwable): Int {
        if (isLoggable(tag)) {
            return AndroidLog.d(tag, msg, tr)
        }
        return 0
    }

    // ═══════════════════════════════════════════════════════════
    // INFO
    // ═══════════════════════════════════════════════════════════

    fun i(tag: String, msg: String): Int {
        if (isLoggable(tag)) {
            return AndroidLog.i(tag, msg)
        }
        return 0
    }

    fun i(tag: String, msg: String, tr: Throwable): Int {
        if (isLoggable(tag)) {
            return AndroidLog.i(tag, msg, tr)
        }
        return 0
    }

    // ═══════════════════════════════════════════════════════════
    // WARNING
    // ═══════════════════════════════════════════════════════════

    fun w(tag: String, msg: String): Int {
        if (isLoggable(tag)) {
            return AndroidLog.w(tag, msg)
        }
        return 0
    }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        if (isLoggable(tag)) {
            return AndroidLog.w(tag, msg, tr)
        }
        return 0
    }

    fun w(tag: String, tr: Throwable): Int {
        if (isLoggable(tag)) {
            return AndroidLog.w(tag, tr)
        }
        return 0
    }

    // ═══════════════════════════════════════════════════════════
    // ERROR — 항상 출력
    // ═══════════════════════════════════════════════════════════

    fun e(tag: String, msg: String): Int {
        return AndroidLog.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        return AndroidLog.e(tag, msg, tr)
    }
}