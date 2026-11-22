package com.lightstick.music.core.sync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lightstick.LSBluetooth
import com.lightstick.music.core.util.Log

/**
 * 연결 해제 연동 (Light-Stick-EfxCreator 와 규칙 동일)
 *
 * - 사용자가 직접 해제했을 때만 상대 앱에 명시적 브로드캐스트로 알린다.
 * - 받은 앱은 해당 MAC 의 자기 연결만 SDK 로 직접 끊는다 (다시 알리지 않음 → 반복 방지).
 */
object DeviceDisconnectSync {
    private const val TAG = "DisconnectSync"
    const val ACTION = "com.lightstick.action.DISCONNECT_DEVICE"
    const val EXTRA_MAC = "mac"
    private const val PEER_PACKAGE = "com.efxcreator"

    fun notifyPeer(context: Context, mac: String) {
        try {
            context.sendBroadcast(
                Intent(ACTION).setPackage(PEER_PACKAGE).putExtra(EXTRA_MAC, mac)
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    /** 앱 실행 중에만 필요하므로 동적 등록 (앱을 깨우지 않음) */
    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val mac = intent.getStringExtra(EXTRA_MAC) ?: return
                    runCatching {
                        LSBluetooth.connectedDevices().firstOrNull { it.mac == mac }
                            ?.let { it.stopTimeline(); it.disconnect() }
                    }.onFailure { Log.w(TAG, "disconnect failed: ${it.message}") }
                }
            },
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
