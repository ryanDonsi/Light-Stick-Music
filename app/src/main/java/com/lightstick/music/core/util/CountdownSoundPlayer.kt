package com.lightstick.music.core.util

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin

/**
 * 카운트다운 비프음 플레이어.
 * 단일 영구 스레드와 warm AudioTrack을 유지해 스레드 시작/AudioTrack 초기화 지연 없이
 * 즉시 재생한다. 이전 구현에서 첫 비프음이 잘려 들리던 문제를 해결한다.
 */
class CountdownSoundPlayer {

    private val sampleRate = 44100

    // 미리 생성해 두어 호출 시 즉시 큐에 삽입
    private val shortBeepSamples = buildSamples(hz = 1000f, durationMs = 100)
    private val longBeepSamples  = buildSamples(hz = 1000f, durationMs = 600)

    private val queue  = LinkedBlockingQueue<ShortArray>(8)
    private val worker: Thread

    init {
        worker = Thread {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf, AudioTrack.MODE_STREAM
            )
            track.play()   // warm — AudioTrack is always in play state
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val samples = queue.take()   // blocks until a beep is requested
                    track.write(samples, 0, samples.size)
                }
            } catch (_: InterruptedException) {
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    // Non-blocking: just enqueues the pre-generated samples
    fun playShortBeep() { queue.offer(shortBeepSamples) }
    fun playLongBeep()  { queue.offer(longBeepSamples)  }

    fun release() {
        worker.interrupt()
        try { worker.join(300) } catch (_: InterruptedException) {}
    }

    private fun buildSamples(hz: Float, durationMs: Int): ShortArray {
        val n       = sampleRate * durationMs / 1000
        val fadeIn  = (sampleRate * 0.005).toInt()  // 5ms
        val fadeOut = (sampleRate * 0.020).toInt()  // 20ms
        return ShortArray(n) { i ->
            val env = when {
                i < fadeIn      -> i.toDouble() / fadeIn
                i > n - fadeOut -> (n - i).toDouble() / fadeOut
                else            -> 1.0
            }
            (sin(2.0 * PI * hz * i / sampleRate) * Short.MAX_VALUE * 0.75 * env)
                .toInt().toShort()
        }
    }
}
