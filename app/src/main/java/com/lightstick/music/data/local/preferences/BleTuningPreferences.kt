package com.lightstick.music.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lightstick.LSBluetooth
import com.lightstick.music.core.constants.PrefsKeys

/**
 * BLE 전송 설정 저장소 (디버그 빌드 전용 메뉴에서 사용).
 *
 * SDK 는 전송 간격/큐 크기를 저장하지 않으므로(앱을 다시 시작하면 기본값 50ms / 8), 여기에 저장해 두었다가
 * 앱 시작 때 [com.lightstick.music.app.LightStickMusicApp] 이 SDK 에 다시 적용한다.
 * 저장된 값이 없으면 SDK 기본값을 돌려준다.
 */
object BleTuningPreferences {

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsKeys.PREFS_BLE_TUNING, Context.MODE_PRIVATE)

    /** GATT 명령 최소 전송 간격(ms) */
    fun getCommandIntervalMs(context: Context): Long =
        prefs(context).getLong(PrefsKeys.KEY_BLE_CMD_INTERVAL_MS, LSBluetooth.DEFAULT_COMMAND_INTERVAL_MS)

    fun setCommandIntervalMs(context: Context, intervalMs: Long) {
        prefs(context).edit { putLong(PrefsKeys.KEY_BLE_CMD_INTERVAL_MS, intervalMs) }
    }

    /** 기기별 GATT 명령 대기 큐 최대 개수 */
    fun getCommandQueueSize(context: Context): Int =
        prefs(context).getInt(PrefsKeys.KEY_BLE_CMD_QUEUE_SIZE, LSBluetooth.DEFAULT_COMMAND_QUEUE_SIZE)

    fun setCommandQueueSize(context: Context, size: Int) {
        prefs(context).edit { putInt(PrefsKeys.KEY_BLE_CMD_QUEUE_SIZE, size) }
    }
}
