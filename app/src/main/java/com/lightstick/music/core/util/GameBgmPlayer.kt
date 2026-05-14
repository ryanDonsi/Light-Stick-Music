package com.lightstick.music.core.util

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.lightstick.music.data.model.GameMode
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import kotlin.random.Random

class GameBgmPlayer {

    private val sampleRate = 44100

    @Volatile private var isPlaying = false
    @Volatile private var bpmMultiplier = 1.0f
    @Volatile private var bpmChanged = false
    private var playerThread: Thread? = null

    @Volatile private var celebrationActive = false
    private var celebrationThread: Thread? = null

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

    // 팡파레 이후 delayMs 뒤에 축포 팝 사운드를 결과 화면 머무는 동안 반복 재생
    fun playCelebration(delayMs: Long = 1300L) {
        stopCelebration()
        celebrationActive = true
        celebrationThread = Thread {
            try {
                Thread.sleep(delayMs)
                while (celebrationActive && !Thread.currentThread().isInterrupted) {
                    val burstCount = 2 + Random.nextInt(2)   // 2 or 3 pops per burst
                    repeat(burstCount) { idx ->
                        if (!celebrationActive || Thread.currentThread().isInterrupted) return@repeat
                        firePop()
                        // Short gap between pops in a burst — pop is already ~500ms long
                        if (idx < burstCount - 1) Thread.sleep(60L + Random.nextLong(100L))
                    }
                    Thread.sleep(700L + Random.nextLong(900L))
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    fun stopCelebration() {
        celebrationActive = false
        celebrationThread?.interrupt()
        try { celebrationThread?.join(200) } catch (_: InterruptedException) {}
        celebrationThread = null
    }

    fun stop() {
        isPlaying = false
        playerThread?.interrupt()
        try { playerThread?.join(500) } catch (_: InterruptedException) {}
        playerThread = null
    }

    fun release() {
        stop()
        stopCelebration()
    }

    // ── BGM Loop (double-buffered) ────────────────────────────────────────────
    //
    // While the audio thread writes buffer N to AudioTrack, a background render
    // thread pre-computes buffer N+1.  When buffer N finishes, buffer N+1 is
    // already ready → no gap between loops.

    private fun bgmLoop(mode: GameMode) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 1-second internal AudioTrack buffer absorbs the few ms it takes to swap buffers
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sampleRate * 2), AudioTrack.MODE_STREAM
        )
        track.play()

        val baseBpm = baseBpm(mode)
        val preNext  = AtomicReference<ShortArray?>(null)   // pre-rendered buffer slot
        var prerenderThread: Thread? = null

        // Launch a background render of the next loop buffer
        fun prerender(mult: Float, fadeIn: Boolean) {
            prerenderThread?.interrupt()
            preNext.set(null)
            prerenderThread = Thread {
                val buf = renderLoop(mode, baseBpm * mult, fadeIn = fadeIn)
                if (!Thread.currentThread().isInterrupted) preNext.set(buf)
            }.also { it.isDaemon = true; it.start() }
        }

        try {
            var mult = bpmMultiplier
            bpmChanged = false
            // Render first buffer synchronously, then immediately kick off the next
            var buf = renderLoop(mode, baseBpm * mult, fadeIn = false)
            prerender(mult, false)

            while (isPlaying && !Thread.currentThread().isInterrupted) {
                // Feed current buffer to AudioTrack
                var pos = 0
                while (pos < buf.size && isPlaying) {
                    val chunk = minOf(2048, buf.size - pos)
                    if (track.write(buf, pos, chunk) < 0) break
                    pos += chunk
                }
                if (!isPlaying) break

                val changed = bpmChanged.also { bpmChanged = false }
                val newMult = bpmMultiplier

                buf = if (!changed && newMult == mult) {
                    // Normal loop: pre-render should already be done (had ~10s to compute)
                    prerenderThread?.join(1000)
                    val pre = preNext.getAndSet(null)
                        ?: renderLoop(mode, baseBpm * mult, fadeIn = false) // rare fallback
                    prerender(mult, false)
                    pre
                } else {
                    // BPM changed: abort stale pre-render, render new tempo immediately
                    mult = newMult
                    prerender(mult, false)          // pre-render next at new tempo
                    renderLoop(mode, baseBpm * mult, fadeIn = true)
                }
            }
        } catch (_: Exception) {
        } finally {
            prerenderThread?.interrupt()
            try { prerenderThread?.join(200) } catch (_: InterruptedException) {}
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

    // ── Celebration pops ─────────────────────────────────────────────────────

    private fun firePop() {
        val buf = renderPop()
        try {
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
            Thread.sleep(buf.size.toLong() * 1000 / sampleRate + 50)
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }

    // 불꽃놀이: 팡(bang) → 촤라라락(crackle shimmer)
    private fun renderPop(): ShortArray {
        val pitchMult = 0.85 + Random.nextDouble() * 0.30  // narrower range → consistent bass

        // Phase 1 — 초기 폭발 snap (15ms): immediate hard transient
        val snapN = 15 * sampleRate / 1000

        // Phase 2 — 저음 boom (120–160ms): deep body of the explosion
        val boomN = (120 + Random.nextInt(40)) * sampleRate / 1000

        // Phase 3 — 촤라라락 crackle (280–460ms): high-freq shimmer trail
        val crackleN = (280 + Random.nextInt(180)) * sampleRate / 1000

        val total = snapN + boomN + crackleN
        val raw   = FloatArray(total)

        // LCG white-noise generator
        var nState = System.nanoTime() xor Random.nextLong()
        fun noise(): Double {
            nState = nState * 6364136223846793005L + 1442695040888963407L
            return (nState ushr 33).toInt().toDouble() / 2147483648.0
        }

        // Phase 1 — Snap: pure white noise, 3-sample attack, full amplitude for 15ms
        for (i in 0 until snapN) {
            val env = if (i < 3) i / 3.0 else 1.0
            raw[i]  = (noise() * 0.95 * env).toFloat()
        }

        // Phase 2 — Boom: layered bass frequencies + noise for heavy explosive body
        // Three octaves of bass so it translates on both phone speakers and headphones
        val f1 = 38.0 * pitchMult    // sub-bass
        val f2 = 76.0 * pitchMult    // bass
        val f3 = 130.0 * pitchMult   // upper bass (phone speakers hear this best)
        for (i in 0 until boomN) {
            val t    = i.toDouble() / sampleRate
            val n    = noise()
            val boom = sin(2.0 * PI * f1 * t) * 0.45 +
                       sin(2.0 * PI * f2 * t) * 0.35 +
                       sin(2.0 * PI * f3 * t) * 0.20
            val env  = exp(-9.0 * t)   // slow enough to give a real "thud" feeling
            raw[snapN + i] = ((n * 0.50 + boom * 0.50) * 0.95 * env).toFloat()
        }

        // Phase 3 — Crackle shimmer: IIR high-pass over white noise → crisp sparkle
        var prev = 0.0
        for (i in 0 until crackleN) {
            val t    = i.toDouble() / sampleRate
            val n    = noise()
            val hp   = n - 0.95 * prev   // strong high-pass → airy sparkle
            prev     = n
            val env  = exp(-7.0 * t)
            raw[snapN + boomN + i] = (hp * 0.82 * env).toFloat()
        }

        var maxAbs = 0.01f
        for (v in raw) { val a = abs(v); if (a > maxAbs) maxAbs = a }
        val scale = if (maxAbs > 0.9f) 0.9f / maxAbs else 1.0f
        return ShortArray(total) { i -> (raw[i] * scale * Short.MAX_VALUE).toInt().toShort() }
    }

    // ── Fanfare ──────────────────────────────────────────────────────────────

    // Brass waveform: sawtooth-like harmonic series (1/n amplitudes) + vibrato on chord
    // Run: G4→C5→E5→G5 punchy ascent; chord: G4+C5+E5+G5+C6 grand finish ≈ 1.3s
    private fun renderFanfare(): ShortArray {
        data class FNote(val hz: Float, val ms: Int)

        // Short punchy run then longer final note before chord
        val run = listOf(FNote(392f, 100), FNote(523f, 100), FNote(659f, 100), FNote(784f, 150))
        val chordNotes = listOf(392f, 523f, 659f, 784f, 1047f)  // G4 C5 E5 G5 C6
        val chordMs    = 750
        val chordFadeMs = 280

        // Sawtooth-like harmonic weights (brass character)
        val hWeights = doubleArrayOf(0.40, 0.26, 0.17, 0.10, 0.04, 0.03)

        fun brass(hz: Double, t: Double, vibDepth: Double = 0.0, vibPhase: Double = 0.0): Double {
            var s = 0.0
            for (n in 1..6) {
                val fv = hz * n * (1.0 + vibDepth * sin(vibPhase))
                s += sin(2.0 * PI * fv * t) * hWeights[n - 1]
            }
            return s
        }

        val totalSamples = (run.sumOf { it.ms } + chordMs) * sampleRate / 1000
        val buf = FloatArray(totalSamples)

        // Run: punchy 5ms attack, gentle sustain decay, hard cut at end (clipped by gate)
        val attackSamples = (sampleRate * 0.005).toInt()
        var offset = 0
        for (note in run) {
            val dur = note.ms * sampleRate / 1000
            for (i in 0 until dur) {
                if (offset + i >= totalSamples) break
                val t       = i.toDouble() / sampleRate
                val attack  = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val gate    = if (i > dur - 40) (dur - i) / 40.0 else 1.0
                buf[offset + i] += (brass(note.hz.toDouble(), t) * 0.75 * exp(-0.7 * t) * attack * gate).toFloat()
            }
            offset += dur
        }

        // Grand chord: 8ms attack, very gentle decay, vibrato after 80ms, fade out at end
        val chordDur  = chordMs * sampleRate / 1000
        val fadeDur   = chordFadeMs * sampleRate / 1000
        val fadeStart = (chordDur - fadeDur).coerceAtLeast(0)
        val chordAttack = (sampleRate * 0.008).toInt()
        val vibRate = 2.0 * PI * 5.5   // 5.5 Hz vibrato
        for (hz in chordNotes) {
            for (i in 0 until chordDur) {
                if (offset + i >= totalSamples) break
                val t       = i.toDouble() / sampleRate
                val vibDepth = if (t > 0.08) 0.007 else 0.0
                val attack  = if (i < chordAttack) i.toDouble() / chordAttack else 1.0
                val fadeOut = if (i >= fadeStart) (chordDur - i).toDouble() / fadeDur else 1.0
                buf[offset + i] += (brass(hz.toDouble(), t, vibDepth, vibRate * t) * 0.55 * exp(-0.6 * t) * attack * fadeOut).toFloat()
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
