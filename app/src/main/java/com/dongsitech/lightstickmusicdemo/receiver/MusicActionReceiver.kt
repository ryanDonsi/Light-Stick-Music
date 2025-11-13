package com.dongsitech.lightstickmusicdemo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dongsitech.lightstickmusicdemo.service.MusicEffectService
import com.dongsitech.lightstickmusicdemo.util.MusicPlayerCommandBus

class MusicActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = when (intent.action) {
            MusicEffectService.ACTION_PLAY_PAUSE -> MusicPlayerCommandBus.Command.TogglePlay
            MusicEffectService.ACTION_NEXT -> MusicPlayerCommandBus.Command.Next
            MusicEffectService.ACTION_PREV -> MusicPlayerCommandBus.Command.Previous
            MusicEffectService.ACTION_SEEK -> {
                val pos = intent.getLongExtra("seekPosition", 0L)
                MusicPlayerCommandBus.Command.SeekTo(pos)
            }
            else -> null
        }
        command?.let { MusicPlayerCommandBus.trySend(it) }
    }
}
