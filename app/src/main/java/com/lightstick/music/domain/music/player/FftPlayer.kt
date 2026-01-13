package com.lightstick.music.domain.music.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player

@UnstableApi
class CustomRenderersFactory(
    context: Context,
    private val audioProcessor: AudioProcessor
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false) // PCM 처리 강제
            .setAudioProcessors(arrayOf(audioProcessor))
            .build()
    }
}

@UnstableApi
fun createFftPlayer(
    context: Context,
    processor: FftAudioProcessor,
    onSeekHandled: ((position: Long) -> Unit)? = null
): ExoPlayer {
    val renderersFactory: RenderersFactory = CustomRenderersFactory(context, processor)
    val player = ExoPlayer.Builder(context)
        .setRenderersFactory(renderersFactory)
        .build()

    player.addListener(object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                onSeekHandled?.invoke(newPosition.positionMs)
            }
        }
    })

    return player
}
