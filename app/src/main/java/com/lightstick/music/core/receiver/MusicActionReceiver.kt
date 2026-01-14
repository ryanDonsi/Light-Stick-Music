package com.lightstick.music.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lightstick.music.core.bus.MusicPlayerCommandBus
import com.lightstick.music.core.service.MusicEffectService

class MusicActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = when (intent.action) {
            MusicEffectService.Companion.ACTION_PLAY_PAUSE -> MusicPlayerCommandBus.Command.TogglePlay
            MusicEffectService.Companion.ACTION_NEXT -> MusicPlayerCommandBus.Command.Next
            MusicEffectService.Companion.ACTION_PREV -> MusicPlayerCommandBus.Command.Previous
            MusicEffectService.Companion.ACTION_SEEK -> {
                val pos = intent.getLongExtra("seekPosition", 0L)
                MusicPlayerCommandBus.Command.SeekTo(pos)
            }
            else -> null
        }
        command?.let { MusicPlayerCommandBus.trySend(it) }
    }
}