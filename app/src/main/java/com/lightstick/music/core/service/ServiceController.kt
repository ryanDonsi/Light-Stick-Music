package com.lightstick.music.core.service

import android.content.Context
import android.content.Intent
import com.lightstick.music.data.model.MusicItem

object ServiceController {

    fun startMusicEffectService(
        context: Context,
        musicItem: MusicItem,
        isPlaying: Boolean,
        position: Long = 0L,
        duration: Long = 0L
    ) {
        val intent = Intent(context, MusicEffectService::class.java).apply {
            putExtra("musicItem", musicItem)
            putExtra("isPlaying", isPlaying)
            putExtra("position", position)
            putExtra("duration", duration)
        }
        context.startForegroundService(intent)
    }

    /**
     * 재생 상태/위치를 알림에 반영한다.
     *
     * 서비스가 이미 떠 있으면 startService 로 보낸다. 포그라운드 서비스가 있는 앱은
     * 백그라운드 시작 제한 대상이 아니라서 통화 중 자동 일시정지처럼 앱이 화면에 없을 때 불려도
     * 안전하다. 예전에는 매번 startForegroundService 를 불러 Android 12+에서
     * ForegroundServiceStartNotAllowedException 위험이 있었다.
     */
    fun updateNotificationProgress(
        context: Context,
        musicItem: MusicItem,
        isPlaying: Boolean,
        position: Long,
        duration: Long
    ) {
        if (!MusicEffectService.isRunning) {
            startMusicEffectService(context, musicItem, isPlaying, position, duration)
            return
        }
        val intent = Intent(context, MusicEffectService::class.java).apply {
            putExtra("musicItem", musicItem)
            putExtra("isPlaying", isPlaying)
            putExtra("position", position)
            putExtra("duration", duration)
        }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            // 서비스가 막 종료된 경우 등 — 알림 갱신 실패는 재생에 영향이 없으므로 무시
        }
    }

    fun stopMusicEffectService(context: Context) {
        val intent = Intent(context, MusicEffectService::class.java)
        context.stopService(intent)
    }
}