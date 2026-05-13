package com.lightstick.music.core.util

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.lightstick.music.data.model.GameMode
import kotlin.math.*

class GameBgmPlayer {

    private val sampleRate = 44100

    @Volatile private var isPlaying = false
    @Volatile private var bpmMultiplier = 1.0f
    private var playerThread: Thread? = null

    fun start(mode: GameMode) {
        stop()
        bpmMultiplier = 1.0f
        isPlaying = true
        playerThread = Thread { bgmLoop(mode) }.also { it.isDaemon = true; it.start() }
    }

    fun setBpmMultiplier(multiplier: Float) {
        bpmMultiplier = multiplier
    }

    fun stop() {
        isPlaying = false
        playerThread?.interrupt()
        try { playerThread?.join(500) } catch (_: InterruptedException) {}
        playerThread = null
    }

    fun release() = stop()

    // ── Audio loop ───────────────────────────────────────────────────────────

    private fun bgmLoop(mode: GameMode) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 4096), AudioTrack.MODE_STREAM
        )
        track.play()
        try {
            val baseBpm = baseBpm(mode)
            while (isPlaying && !Thread.currentThread().isInterrupted) {
                val buf = renderMeasure(mode, baseBpm * bpmMultiplier)
                var pos = 0
                while (pos < buf.size && isPlaying) {
                    val chunk = minOf(2048, buf.size - pos)
                    if (track.write(buf, pos, chunk) < 0) break
                    pos += chunk
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun baseBpm(mode: GameMode) = when (mode) {
        GameMode.SPEED_REACTION -> 140f
        GameMode.TEMPO          -> 120f
        GameMode.TEAM_BATTLE    -> 130f
    }

    // ── Pattern definitions ──────────────────────────────────────────────────

    private data class Sound(val type: Int, val hz: Float = 0f, val amp: Float = 0.7f)

    private fun pattern(mode: GameMode): List<List<Sound>> = when (mode) {
        GameMode.SPEED_REACTION -> speedReactionPattern()
        GameMode.TEMPO          -> tempoPattern()
        GameMode.TEAM_BATTLE    -> teamBattlePattern()
    }

    // 8 steps = 1 bar (8th notes). Driving, fast, ascending lead melody.
    private fun speedReactionPattern(): List<List<Sound>> = listOf(
        listOf(Sound(KICK, amp = 0.9f),  Sound(HIHAT, amp = 0.5f), Sound(LEAD, 392f, 0.5f)),
        listOf(Sound(HIHAT, amp = 0.4f), Sound(LEAD, 494f, 0.35f)),
        listOf(Sound(SNARE, amp = 0.8f), Sound(HIHAT, amp = 0.5f), Sound(LEAD, 587f, 0.4f)),
        listOf(Sound(HIHAT, amp = 0.3f)),
        listOf(Sound(KICK, amp = 0.85f), Sound(HIHAT, amp = 0.5f), Sound(LEAD, 784f, 0.5f)),
        listOf(Sound(HIHAT, amp = 0.4f), Sound(LEAD, 587f, 0.35f)),
        listOf(Sound(SNARE, amp = 0.8f), Sound(HIHAT, amp = 0.5f), Sound(LEAD, 494f, 0.4f)),
        listOf(Sound(HIHAT, amp = 0.3f), Sound(LEAD, 392f, 0.3f))
    )

    // Rhythmical/groovy: syncopated kick on steps 3 & 7, strong bass line.
    private fun tempoPattern(): List<List<Sound>> = listOf(
        listOf(Sound(KICK, amp = 0.9f),  Sound(BASS, 131f, 0.6f), Sound(HIHAT, amp = 0.5f)),
        listOf(Sound(HIHAT, amp = 0.3f)),
        listOf(Sound(SNARE, amp = 0.8f), Sound(HIHAT, amp = 0.5f), Sound(BASS, 165f, 0.45f)),
        listOf(Sound(KICK, amp = 0.5f),  Sound(BASS, 196f, 0.5f), Sound(HIHAT, amp = 0.35f)),
        listOf(Sound(KICK, amp = 0.9f),  Sound(BASS, 220f, 0.6f), Sound(HIHAT, amp = 0.5f)),
        listOf(Sound(HIHAT, amp = 0.3f)),
        listOf(Sound(SNARE, amp = 0.8f), Sound(HIHAT, amp = 0.5f), Sound(BASS, 196f, 0.45f)),
        listOf(Sound(KICK, amp = 0.45f), Sound(HIHAT, amp = 0.4f), Sound(BASS, 131f, 0.5f))
    )

    // Call-response: first half ascends (red), second half responds (blue).
    private fun teamBattlePattern(): List<List<Sound>> = listOf(
        listOf(Sound(KICK, amp = 0.9f),  Sound(LEAD, 523f, 0.55f), Sound(HIHAT, amp = 0.5f)),
        listOf(Sound(HIHAT, amp = 0.4f), Sound(LEAD, 659f, 0.4f)),
        listOf(Sound(SNARE, amp = 0.8f), Sound(HIHAT, amp = 0.5f)),
        listOf(Sound(LEAD, 784f, 0.5f),  Sound(HIHAT, amp = 0.3f)),
        listOf(Sound(KICK, amp = 0.85f), Sound(LEAD, 392f, 0.5f), Sound(HIHAT, amp = 0.5f)),
        listOf(Sound(HIHAT, amp = 0.4f), Sound(LEAD, 440f, 0.4f)),
        listOf(Sound(SNARE, amp = 0.8f), Sound(LEAD, 523f, 0.45f), Sound(HIHAT, amp = 0.5f)),
        listOf(Sound(HIHAT, amp = 0.3f))
    )

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun renderMeasure(mode: GameMode, bpm: Float): ShortArray {
        val stepSamples = (sampleRate * 60f / (bpm * 2)).toInt()
        val steps = pattern(mode)
        val total = steps.size * stepSamples
        val buf = FloatArray(total)

        for ((i, sounds) in steps.withIndex()) {
            val offset = i * stepSamples
            for (s in sounds) {
                val rendered = renderSound(s, stepSamples)
                for (j in rendered.indices) {
                    val pos = offset + j
                    if (pos < total) buf[pos] += rendered[j]
                }
            }
        }

        // 10ms crossfade at boundaries for smooth measure looping
        val fadeLen = minOf((sampleRate * 0.01f).toInt(), total / 4)
        for (i in 0 until fadeLen) {
            val t = i.toFloat() / fadeLen
            buf[i] *= t
            buf[total - 1 - i] *= t
        }

        // Normalize peak to 0.9 and convert to shorts
        var maxAbs = 0.01f
        for (v in buf) { val a = abs(v); if (a > maxAbs) maxAbs = a }
        val scale = if (maxAbs > 0.9f) 0.9f / maxAbs else 1.0f

        return ShortArray(total) { i -> (buf[i] * scale * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun renderSound(s: Sound, stepSamples: Int): FloatArray = when (s.type) {
        KICK  -> kick(stepSamples, s.amp)
        SNARE -> snare(stepSamples, s.amp)
        HIHAT -> hihat(stepSamples, s.amp)
        BASS  -> bass(s.hz, stepSamples, s.amp)
        LEAD  -> lead(s.hz, stepSamples, s.amp)
        else  -> FloatArray(0)
    }

    // ── Synthesis ────────────────────────────────────────────────────────────

    // Pitch-swept sine: 160 → 40 Hz with fast decay
    private fun kick(stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.4f).toInt().coerceAtMost(stepSamples)
        var phase = 0.0
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val freq = 120.0 * exp(-30.0 * t) + 40.0
                phase += 2.0 * PI * freq / sampleRate
                (sin(phase) * amp * exp(-18.0 * t)).toFloat()
            }
        }
    }

    // Body tone at 200 Hz + inharmonic buzz for snappy texture
    private fun snare(stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.5f).toInt().coerceAtMost(stepSamples)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val body  = sin(2.0 * PI * 200.0 * t) * 0.3
                val buzz  = (sin(i * 2.3) + sin(i * 4.7) + sin(i * 7.9)) / 3.0 * 0.7
                ((body + buzz) * amp * exp(-10.0 * t)).toFloat()
            }
        }
    }

    // Inharmonic high-frequency series for metallic click
    private fun hihat(stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.2f).toInt().coerceAtMost(stepSamples)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(i * 14.7) * 0.35 + sin(i * 17.3) * 0.25 +
                        sin(i * 21.1) * 0.20 + sin(i * 26.7) * 0.20
                (s * amp * exp(-60.0 * t)).toFloat()
            }
        }
    }

    // Warm bass: fundamental + 2nd harmonic
    private fun bass(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.85f).toInt().coerceAtMost(stepSamples)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.65 +
                        sin(2.0 * PI * hz * 2 * t) * 0.35
                (s * amp * exp(-4.0 * t)).toFloat()
            }
        }
    }

    // Bright lead: 3 harmonics for richness
    private fun lead(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.75f).toInt().coerceAtMost(stepSamples)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.5 +
                        sin(2.0 * PI * hz * 2 * t) * 0.3 +
                        sin(2.0 * PI * hz * 3 * t) * 0.2
                (s * amp * exp(-5.0 * t)).toFloat()
            }
        }
    }

    companion object {
        private const val KICK  = 0
        private const val SNARE = 1
        private const val HIHAT = 2
        private const val BASS  = 3
        private const val LEAD  = 4
    }
}
