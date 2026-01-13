package com.lightstick.music.service

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

    fun updateNotificationProgress(
        context: Context,
        musicItem: MusicItem,
        isPlaying: Boolean,
        position: Long,
        duration: Long
    ) {
        startMusicEffectService(
            context = context,
            musicItem = musicItem,
            isPlaying = isPlaying,
            position = position,
            duration = duration
        )
    }

    fun stopMusicEffectService(context: Context) {
        val intent = Intent(context, MusicEffectService::class.java)
        context.stopService(intent)
    }
}