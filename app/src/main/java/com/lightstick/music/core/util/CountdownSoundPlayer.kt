package com.lightstick.music.core.util

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class CountdownSoundPlayer {

    private val sampleRate = 44100

    // 짧은 비프 ("삐!") — 방송 표준 1kHz 순음
    fun playShortBeep() = playTone(1000f, 100)

    // 롱 비프 ("삐~~~") — 동일 피치, 길이로 구분
    fun playLongBeep() = playTone(1000f, 600)

    private fun playTone(hz: Float, durationMs: Int) {
        Thread {
            try {
                val numSamples = sampleRate * durationMs / 1000
                val fadeInSamples = (sampleRate * 0.005).toInt()   // 5ms fade-in
                val fadeOutSamples = (sampleRate * 0.02).toInt()   // 20ms fade-out

                val samples = ShortArray(numSamples) { i ->
                    val envelope = when {
                        i < fadeInSamples -> i.toDouble() / fadeInSamples
                        i > numSamples - fadeOutSamples ->
                            (numSamples - i).toDouble() / fadeOutSamples
                        else -> 1.0
                    }
                    (sin(2.0 * PI * hz * i / sampleRate) * Short.MAX_VALUE * 0.75 * envelope)
                        .toInt().toShort()
                }

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, numSamples * 2),
                    AudioTrack.MODE_STATIC
                )
                track.write(samples, 0, numSamples)
                track.play()
                Thread.sleep(durationMs.toLong() + 50)
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    fun release() {}
}
