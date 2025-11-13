package com.dongsitech.lightstickmusicdemo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.util.ServiceController

class MusicStopReceiver : BroadcastReceiver() {
    @UnstableApi
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MusicStopReceiver", "Stop action received. Stopping MusicEffectService.")
        ServiceController.stopMusicEffectService(context)
    }
}
