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
    @Volatile private var bpmChanged = false
    private var playerThread: Thread? = null

    fun start(mode: GameMode) {
        stop()
        bpmMultiplier = 1.0f
        bpmChanged = false
        isPlaying = true
        playerThread = Thread { bgmLoop(mode) }.also { it.isDaemon = true; it.start() }
    }

    fun setBpmMultiplier(multiplier: Float) {
        bpmMultiplier = multiplier
        bpmChanged = true
    }

    fun playFanfare() {
        Thread {
            try {
                val buf = renderFanfare()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, buf.size * 2), AudioTrack.MODE_STATIC
                )
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(buf.size.toLong() * 1000 / sampleRate + 300)
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        isPlaying = false
        playerThread?.interrupt()
        try { playerThread?.join(500) } catch (_: InterruptedException) {}
        playerThread = null
    }

    fun release() = stop()

    // ── BGM Loop ─────────────────────────────────────────────────────────────

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
                val needsFadeIn = bpmChanged.also { bpmChanged = false }
                val buf = renderLoop(mode, baseBpm * bpmMultiplier, fadeIn = needsFadeIn)
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

    // ── Patterns (8th-note steps) ────────────────────────────────────────────

    private data class Sound(val type: Int, val hz: Float = 0f, val amp: Float = 0.7f)

    private fun pattern(mode: GameMode): List<List<Sound>> = when (mode) {
        GameMode.SPEED_REACTION -> speedReactionPattern()
        GameMode.TEMPO          -> tempoPattern()
        GameMode.TEAM_BATTLE    -> teamBattlePattern()
    }

    // 6 bars × 8 steps @ 140 BPM ≈ 10.3s — ascending lead phrase over 6 bars
    private fun speedReactionPattern(): List<List<Sound>> {
        fun k(a: Float = 0.9f) = Sound(KICK, amp = a)
        fun s() = Sound(SNARE, amp = 0.8f)
        fun h(a: Float = 0.5f) = Sound(HIHAT, amp = a)
        fun l(hz: Float, a: Float = 0.48f) = Sound(LEAD, hz, a)

        fun bar(n0: Float, n2: Float, n4: Float, n6: Float): List<List<Sound>> = listOf(
            listOf(k(),        h(),      l(n0)),
            listOf(           h(0.35f)         ),
            listOf(s(),        h(),      l(n2, 0.4f)),
            listOf(           h(0.3f)          ),
            listOf(k(),        h(),      l(n4)),
            listOf(           h(0.35f)         ),
            listOf(s(),        h(),      l(n6, 0.4f)),
            listOf(           h(0.3f)          )
        )

        return bar(392f, 587f, 392f, 659f) +   // Bar 1  G4 D5 G4 E5
               bar(440f, 659f, 440f, 784f) +   // Bar 2  A4 E5 A4 G5
               bar(392f, 494f, 587f, 494f) +   // Bar 3  G4 B4 D5 B4
               bar(659f, 784f, 659f, 587f) +   // Bar 4  E5 G5 E5 D5
               bar(392f, 587f, 440f, 659f) +   // Bar 5  G4 D5 A4 E5
               bar(392f, 659f, 784f, 440f)     // Bar 6  G4 E5 G5 A4
    }

    // 5 bars × 8 steps @ 120 BPM = 10.0s — groovy syncopated bass line
    private fun tempoPattern(): List<List<Sound>> {
        fun k(a: Float = 0.9f) = Sound(KICK, amp = a)
        fun s() = Sound(SNARE, amp = 0.8f)
        fun h(a: Float = 0.5f) = Sound(HIHAT, amp = a)
        fun b(hz: Float, a: Float = 0.55f) = Sound(BASS, hz, a)

        // Kick on 0,3,4,7 (off-beat on 3&7 = syncopation). Snare on 2,6.
        fun bar(b0: Float, b2: Float, b3: Float, b4: Float, b6: Float, b7: Float): List<List<Sound>> = listOf(
            listOf(k(),       h(),      b(b0, 0.6f)),
            listOf(          h(0.3f)              ),
            listOf(s(),       h(),      b(b2, 0.45f)),
            listOf(k(0.5f),  h(0.3f),  b(b3, 0.5f)),  // synco kick
            listOf(k(),       h(),      b(b4, 0.6f)),
            listOf(          h(0.3f)              ),
            listOf(s(),       h(),      b(b6, 0.45f)),
            listOf(k(0.5f),  h(0.3f),  b(b7, 0.5f))   // synco kick
        )

        return bar(131f, 165f, 196f, 196f, 165f, 131f) + // Bar 1  C3 E3 G3 G3 E3 C3
               bar(175f, 220f, 262f, 262f, 220f, 175f) + // Bar 2  F3 A3 C4 C4 A3 F3
               bar(131f, 165f, 196f, 196f, 165f, 131f) + // Bar 3  repeat bar 1
               bar(196f, 247f, 294f, 294f, 247f, 196f) + // Bar 4  G3 B3 D4 D4 B3 G3
               bar(131f, 165f, 262f, 196f, 165f, 131f)   // Bar 5  C3 E3 C4 G3 E3 C3
    }

    // 5 bars × 8 steps @ 130 BPM ≈ 9.2s — call (bars 1-2) → response (3-4) → climax (5)
    private fun teamBattlePattern(): List<List<Sound>> {
        fun k(a: Float = 0.9f) = Sound(KICK, amp = a)
        fun s() = Sound(SNARE, amp = 0.8f)
        fun h(a: Float = 0.5f) = Sound(HIHAT, amp = a)
        fun l(hz: Float, a: Float = 0.48f) = Sound(LEAD, hz, a)

        // Lead at steps 0,1,3,4,5,6 for a continuous melodic feel
        fun bar(l0: Float, l1: Float, l3: Float, l4: Float, l5: Float, l6: Float): List<List<Sound>> = listOf(
            listOf(k(),        h(),      l(l0)),
            listOf(           h(0.35f),  l(l1, 0.4f)),
            listOf(s(),        h()               ),
            listOf(           h(0.3f),   l(l3, 0.43f)),
            listOf(k(0.8f),   h(),      l(l4)),
            listOf(           h(0.35f),  l(l5, 0.4f)),
            listOf(s(),        h(),      l(l6, 0.43f)),
            listOf(           h(0.3f)            )
        )

        return bar(523f, 659f, 784f, 523f, 659f, 784f) + // Bar 1  C5 E5 G5 C5 E5 G5  (call up)
               bar(784f, 659f, 523f, 659f, 523f, 392f) + // Bar 2  G5 E5 C5 E5 C5 G4  (call down)
               bar(392f, 440f, 523f, 392f, 440f, 523f) + // Bar 3  G4 A4 C5 G4 A4 C5  (response up)
               bar(523f, 440f, 392f, 440f, 523f, 659f) + // Bar 4  C5 A4 G4 A4 C5 E5  (response rise)
               bar(523f, 659f, 784f, 659f, 784f, 1047f)  // Bar 5  C5 E5 G5 E5 G5 C6  (climax)
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun renderLoop(mode: GameMode, bpm: Float, fadeIn: Boolean): ShortArray {
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

        // Short fade-in only when BPM changes, to prevent click at tempo switch
        if (fadeIn) {
            val fadeLen = minOf((sampleRate * 0.03f).toInt(), total)
            for (i in 0 until fadeLen) buf[i] *= i.toFloat() / fadeLen
        }

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

    // Pitch-swept sine 160→40 Hz; decays and ends well within its step for clean looping
    private fun kick(stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.35f).toInt().coerceAtMost(stepSamples)
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

    // 200 Hz body + inharmonic buzz
    private fun snare(stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.45f).toInt().coerceAtMost(stepSamples)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val body  = sin(2.0 * PI * 200.0 * t) * 0.3
                val buzz  = (sin(i * 2.3) + sin(i * 4.7) + sin(i * 7.9)) / 3.0 * 0.7
                val fade  = if (i > dur - 100) (dur - i) / 100.0 else 1.0
                ((body + buzz) * amp * exp(-12.0 * t) * fade).toFloat()
            }
        }
    }

    // Inharmonic high-freq partials → metallic click
    private fun hihat(stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.18f).toInt().coerceAtMost(stepSamples)
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

    // Warm fundamental + 2nd harmonic; fast decay + tail fade for seamless loop boundary
    private fun bass(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.80f).toInt().coerceAtMost(stepSamples)
        val fadeStart = (dur - minOf(300, dur / 4)).coerceAtLeast(0)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.65 + sin(2.0 * PI * hz * 2 * t) * 0.35
                val tail = if (i >= fadeStart) (dur - i).toDouble() / (dur - fadeStart) else 1.0
                (s * amp * exp(-8.0 * t) * tail).toFloat()
            }
        }
    }

    // 3-harmonic lead; fast decay + tail fade for seamless loop boundary
    private fun lead(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.72f).toInt().coerceAtMost(stepSamples)
        val fadeStart = (dur - minOf(200, dur / 4)).coerceAtLeast(0)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.5 +
                        sin(2.0 * PI * hz * 2 * t) * 0.3 +
                        sin(2.0 * PI * hz * 3 * t) * 0.2
                val tail = if (i >= fadeStart) (dur - i).toDouble() / (dur - fadeStart) else 1.0
                (s * amp * exp(-7.0 * t) * tail).toFloat()
            }
        }
    }

    // ── Fanfare ──────────────────────────────────────────────────────────────

    // G4→C5→E5→G5 quick ascent + C5+E5+G5+C6 sustained chord ≈ 1.2s
    private fun renderFanfare(): ShortArray {
        data class FNote(val hz: Float, val ms: Int)

        val ascent = listOf(FNote(392f, 90), FNote(523f, 90), FNote(659f, 90), FNote(784f, 120))
        val chordHz = listOf(523f, 659f, 784f, 1047f)   // C5 E5 G5 C6
        val chordMs = 800
        val chordFadeMs = 300

        val totalSamples = (ascent.sumOf { it.ms } + chordMs) * sampleRate / 1000
        val buf = FloatArray(totalSamples)

        // Ascending run
        var offset = 0
        for (note in ascent) {
            val dur = note.ms * sampleRate / 1000
            for (i in 0 until dur) {
                if (offset + i >= totalSamples) break
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * note.hz * t) * 0.5 +
                        sin(2.0 * PI * note.hz * 2 * t) * 0.3 +
                        sin(2.0 * PI * note.hz * 3 * t) * 0.2
                val fadeIn  = if (i < 80) i / 80.0 else 1.0
                val fadeOut = if (i > dur - 80) (dur - i) / 80.0 else 1.0
                buf[offset + i] += (s * 0.7 * exp(-2.0 * t) * fadeIn * fadeOut).toFloat()
            }
            offset += dur
        }

        // Sustained chord with slow decay and fade-out at end
        val chordDur  = chordMs * sampleRate / 1000
        val fadeDur   = chordFadeMs * sampleRate / 1000
        val fadeStart = (chordDur - fadeDur).coerceAtLeast(0)
        for (hz in chordHz) {
            for (i in 0 until chordDur) {
                if (offset + i >= totalSamples) break
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.45 +
                        sin(2.0 * PI * hz * 2 * t) * 0.35 +
                        sin(2.0 * PI * hz * 3 * t) * 0.20
                val fadeIn  = if (i < 150) i / 150.0 else 1.0
                val fadeOut = if (i >= fadeStart) (chordDur - i).toDouble() / fadeDur else 1.0
                buf[offset + i] += (s * 0.5 * exp(-1.5 * t) * fadeIn * fadeOut).toFloat()
            }
        }

        var maxAbs = 0.01f
        for (v in buf) { val a = abs(v); if (a > maxAbs) maxAbs = a }
        val scale = if (maxAbs > 0.9f) 0.9f / maxAbs else 1.0f
        return ShortArray(totalSamples) { i -> (buf[i] * scale * Short.MAX_VALUE).toInt().toShort() }
    }

    companion object {
        private const val KICK  = 0
        private const val SNARE = 1
        private const val HIHAT = 2
        private const val BASS  = 3
        private const val LEAD  = 4
    }
}
