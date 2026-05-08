package com.lightstick.music.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.core.service.ServiceController

class MusicStopReceiver : BroadcastReceiver() {
    @UnstableApi
    override fun onReceive(context: Context, intent: Intent) {
        ServiceController.stopMusicEffectService(context)
    }
}