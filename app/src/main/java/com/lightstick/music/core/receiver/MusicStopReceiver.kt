package com.lightstick.music.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.core.service.ServiceController

class MusicStopReceiver : BroadcastReceiver() {
    @UnstableApi
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MusicStopReceiver", "Stop action received. Stopping MusicEffectService.")
        ServiceController.stopMusicEffectService(context)
    }
}