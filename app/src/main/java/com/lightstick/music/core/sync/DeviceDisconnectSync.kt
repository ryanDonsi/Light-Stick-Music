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
 *
 * TODO(보류): 서명 기반 보호는 현재 적용하지 않음 (RECEIVER_EXPORTED, 권한 없음).
 *  - 하는 일이 "연결 해제"뿐이라 위험이 낮아 보류 결정.
 *  - 추후 적용 시: 두 앱(Music/EfxCreator)의 앱 서명 키(Play 앱 무결성 > 앱 서명 SHA-256)가
 *    같은지 먼저 확인 → 같으면 signature 수준 커스텀 권한으로 송수신 보호, 다르면 적용 불가.
 *  - 서명이 다른 상태에서 signature 권한을 쓰면 정상 연동도 막힌다.
 *  - 같은 키 공유는 키 유출/교체 시 두 앱이 함께 영향받는 단점이 있으므로 유의.
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
