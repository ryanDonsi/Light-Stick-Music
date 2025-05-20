package com.dongsitech.lightstickmusicdemo.util

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.util.MyAudioProcessor

@UnstableApi
class MyPlaybackListener
    (
    private val audioProcessor: MyAudioProcessor
) : Player.Listener {

    override fun onRenderedFirstFrame() {
        Log.d("MyPlaybackListener", "First audio frame rendered")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d("MyPlaybackListener", "Playback state changed: isPlaying=$isPlaying")
        // isPlaying 상태에 따라 audioProcessor 연동 가능
    }

    override fun onPositionDiscontinuity(reason: Int) {
        Log.d("MyPlaybackListener", "Position changed: reason=$reason")
    }

    // 필요 시 다른 Player.Listener 메서드도 구현 가능
}
