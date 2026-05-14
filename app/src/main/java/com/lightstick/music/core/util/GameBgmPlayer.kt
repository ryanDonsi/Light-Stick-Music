package com.lightstick.music.core.util

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.lightstick.music.data.model.GameMode
import java.util.concurrent.atomic.AtomicReference
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

    fun release() {
        stop()
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
        GameMode.SPEED_REACTION -> 150f   // fast cyber-march
        GameMode.TEMPO          -> 120f
        GameMode.TEAM_BATTLE    -> 130f
    }

    // ── Patterns (8th-note steps) ────────────────────────────────────────────

    private data class Sound(val type: Int, val hz: Float = 0f, val amp: Float = 0.7f, val len: Int = 1)

    private fun pattern(mode: GameMode): List<List<Sound>> = when (mode) {
        GameMode.SPEED_REACTION -> speedReactionPattern()
        GameMode.TEMPO          -> tempoPattern()
        GameMode.TEAM_BATTLE    -> teamBattlePattern()
    }

    // 5 bars × 16 steps @ 150 BPM = 8.0s — E Phrygian cyber quick-march
    // Square-wave lead + sawtooth bass for hard electronic timbre
    // E Phrygian (E F G A B C D): ♭2 = F gives the dark, industrial colour
    private fun speedReactionPattern(): List<List<Sound>> {
        fun k(a: Float = 0.95f) = Sound(KICK, amp = a)
        fun s(a: Float = 0.85f) = Sound(SNARE, amp = a)
        fun cb(hz: Float, a: Float = 0.62f, n: Int = 4) = Sound(CBASS, hz, a, n)
        fun c(hz: Float, n: Int = 3, a: Float = 0.58f) = Sound(CYBER, hz, a, n)

        val E3 = 165f; val B3 = 247f   // E3 oom (root), B3 pah (5th)

        fun bar(vararg melody: Pair<Int, Sound>): List<List<Sound>> {
            val grid: Array<MutableList<Sound>> = Array(16) { i ->
                when (i) {
                    0  -> mutableListOf(k(),      cb(E3, 0.65f))
                    4  -> mutableListOf(s(),      cb(B3, 0.45f, 3))
                    8  -> mutableListOf(k(0.80f), cb(E3, 0.55f))
                    12 -> mutableListOf(s(0.80f), cb(B3, 0.40f, 3))
                    else -> mutableListOf()
                }
            }
            for ((step, note) in melody) grid[step].add(note)
            return grid.map { it.toList() }
        }

        // Bar 1: E5·E5 G5·B5 E6·D6 B5 A5  (ascending Phrygian fanfare)
        val bar1 = bar(
            0  to c(659f,  3),   // E5 dotted-8th
            3  to c(659f,  1),   // E5 16th
            4  to c(784f,  3),   // G5 dotted-8th
            7  to c(988f,  1),   // B5 16th
            8  to c(1319f, 3),   // E6 dotted-8th  (peak)
            11 to c(1175f, 1),   // D6 16th
            12 to c(988f,  2),   // B5 8th
            14 to c(880f,  2)    // A5 8th
        )
        // Bar 2: G5 F5 E5 D5 G5 F5 E5 D5  (Phrygian descent — F5=♭2 is the cyber signature)
        val bar2 = bar(
            0  to c(784f,  2),   // G5 8th
            2  to c(698f,  2),   // F5 8th  (Phrygian ♭2)
            4  to c(659f,  2),   // E5 8th
            6  to c(587f,  2),   // D5 8th
            8  to c(784f,  2),   // G5 8th
            10 to c(698f,  2),   // F5 8th  (Phrygian ♭2)
            12 to c(659f,  2),   // E5 8th
            14 to c(587f,  2)    // D5 8th
        )
        // Bar 3: B4 C5 D5 E5 | G5·F5 E5 D5 C5 B4  (chromatic run → angular descent)
        val bar3 = bar(
            0  to c(494f,  1),   // B4 16th \
            1  to c(523f,  1),   // C5 16th  | chromatic run
            2  to c(587f,  1),   // D5 16th  |
            3  to c(659f,  1),   // E5 16th /
            4  to c(784f,  3),   // G5 dotted-8th
            7  to c(698f,  1),   // F5 16th  (Phrygian ♭2)
            8  to c(659f,  2),   // E5 8th
            10 to c(587f,  2),   // D5 8th
            12 to c(523f,  2),   // C5 8th
            14 to c(494f,  2)    // B4 8th
        )
        // Bar 4: E5·E5 B5·B5 | E6(quarter) B5 G5  (driving power-fifth → held peak)
        val bar4 = bar(
            0  to c(659f,  3),   // E5 dotted-8th
            3  to c(659f,  1),   // E5 16th
            4  to c(988f,  3),   // B5 dotted-8th
            7  to c(988f,  1),   // B5 16th
            8  to c(1319f, 4),   // E6 quarter  (held peak)
            12 to c(988f,  2),   // B5 8th
            14 to c(784f,  2)    // G5 8th
        )
        // Bar 5 (climax): E5·E5 E6·E6 | E6(quarter) D6 B5  (octave leap + peak hold)
        val bar5 = bar(
            0  to c(659f,  3),   // E5 dotted-8th
            3  to c(659f,  1),   // E5 16th
            4  to c(1319f, 3),   // E6 dotted-8th  (climax leap)
            7  to c(1319f, 1),   // E6 16th
            8  to c(1319f, 4),   // E6 quarter  (PEAK HOLD)
            12 to c(1175f, 2),   // D6 8th
            14 to c(988f,  2)    // B5 8th  (resolve into loop seam)
        )

        return bar1 + bar2 + bar3 + bar4 + bar5
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
        // SPEED_REACTION uses 16th-note grid to express dotted-8th + 16th march lilt
        val stepDiv = if (mode == GameMode.SPEED_REACTION) 4 else 2
        val stepSamples = (sampleRate * 60f / (bpm * stepDiv)).toInt()
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
        KICK   -> kick(stepSamples, s.amp)
        SNARE  -> snare(stepSamples, s.amp)
        HIHAT  -> hihat(stepSamples, s.amp)
        BASS   -> bass(s.hz, stepSamples * s.len, s.amp)
        LEAD   -> lead(s.hz, stepSamples * s.len, s.amp)
        CYBER  -> cyberLead(s.hz, stepSamples * s.len, s.amp)
        CBASS  -> cyberBass(s.hz, stepSamples * s.len, s.amp)
        else   -> FloatArray(0)
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

    // Warm fundamental + 2nd harmonic; tail fade for seamless loop boundary
    private fun bass(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.80f).toInt().coerceAtMost(stepSamples)
        val fadeStart = (dur - minOf(400, dur / 4)).coerceAtLeast(0)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.65 + sin(2.0 * PI * hz * 2 * t) * 0.35
                val tail = if (i >= fadeStart) (dur - i).toDouble() / (dur - fadeStart) else 1.0
                (s * amp * exp(-5.0 * t) * tail).toFloat()
            }
        }
    }

    // 3-harmonic lead; tail fade for seamless loop boundary
    private fun lead(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.72f).toInt().coerceAtMost(stepSamples)
        val fadeStart = (dur - minOf(300, dur / 4)).coerceAtLeast(0)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t) * 0.5 +
                        sin(2.0 * PI * hz * 2 * t) * 0.3 +
                        sin(2.0 * PI * hz * 3 * t) * 0.2
                val tail = if (i >= fadeStart) (dur - i).toDouble() / (dur - fadeStart) else 1.0
                (s * amp * exp(-4.5 * t) * tail).toFloat()
            }
        }
    }

    // Odd harmonics (1,3,5,7) = square-wave approximation → hard, electronic lead
    private fun cyberLead(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.88f).toInt().coerceAtMost(stepSamples)
        val fadeStart = (dur - minOf(300, dur / 5)).coerceAtLeast(0)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t)     * 0.40 +
                        sin(2.0 * PI * hz * 3 * t) * 0.28 +
                        sin(2.0 * PI * hz * 5 * t) * 0.18 +
                        sin(2.0 * PI * hz * 7 * t) * 0.14
                // 3ms sharp attack then slow sustain decay
                val env = if (t < 0.003) t / 0.003 else exp(-1.8 * (t - 0.003))
                val tail = if (i >= fadeStart) (dur - i).toDouble() / (dur - fadeStart) else 1.0
                (s * amp * env * tail).toFloat()
            }
        }
    }

    // All harmonics (sawtooth) → buzzy, driving electronic bass
    private fun cyberBass(hz: Float, stepSamples: Int, amp: Float): FloatArray {
        val dur = (stepSamples * 0.85f).toInt().coerceAtMost(stepSamples)
        val fadeStart = (dur - minOf(400, dur / 5)).coerceAtLeast(0)
        return FloatArray(stepSamples) { i ->
            if (i >= dur) 0f
            else {
                val t = i.toDouble() / sampleRate
                val s = sin(2.0 * PI * hz * t)     * 0.35 +
                        sin(2.0 * PI * hz * 2 * t) * 0.25 +
                        sin(2.0 * PI * hz * 3 * t) * 0.18 +
                        sin(2.0 * PI * hz * 4 * t) * 0.12 +
                        sin(2.0 * PI * hz * 5 * t) * 0.10
                val env = exp(-4.0 * t)
                val tail = if (i >= fadeStart) (dur - i).toDouble() / (dur - fadeStart) else 1.0
                (s * amp * env * tail).toFloat()
            }
        }
    }

    // ── Fanfare ──────────────────────────────────────────────────────────────

    // FM(주파수 변조) 브라스 합성
    // output = A(t) · sin(2π·f·t + β(t)·sin(2π·f·t))
    // β 높음(어택) → 배음 풍부·밝음 / β 낮음(서스테인) → 따뜻함  ← 트럼펫 특유의 "bite"
    private fun renderFanfare(): ShortArray {
        fun fmBrass(hz: Double, t: Double, betaHi: Double = 4.5, betaLo: Double = 1.8): Double {
            val attackT = 0.012   // 12ms 어택
            val decayT  = 0.028   // 28ms β 하강 (bright→warm)
            val beta = when {
                t < attackT           -> betaLo + (betaHi - betaLo) * (t / attackT)
                t < attackT + decayT  -> betaHi + (betaLo - betaHi) * (t - attackT) / decayT
                else                  -> betaLo
            }
            val amp = when {
                t < attackT           -> t / attackT
                t < attackT + decayT  -> 1.0 - 0.25 * (t - attackT) / decayT
                else                  -> 0.75
            }
            val theta = 2.0 * PI * hz * t
            return amp * sin(theta + beta * sin(theta))
        }

        data class FNote(val hz: Double, val ms: Int)
        // G4→C5→E5→G5 상승 런 + G4+C5+E5+G5+C6 그랜드 코드
        val run   = listOf(FNote(392.0, 90), FNote(523.0, 90), FNote(659.0, 90), FNote(784.0, 130))
        val chord = listOf(392.0, 523.0, 659.0, 784.0, 1047.0)
        val chordMs = 700
        val fadeMs  = 220

        val runSamples   = run.sumOf { it.ms } * sampleRate / 1000
        val chordSamples = chordMs * sampleRate / 1000
        val total = runSamples + chordSamples
        val buf   = FloatArray(total)

        // 상승 런: FM 브라스, 노트 끝 25샘플 빠른 게이트
        var offset = 0
        for (note in run) {
            val dur = note.ms * sampleRate / 1000
            for (i in 0 until dur) {
                val t    = i.toDouble() / sampleRate
                val gate = if (i > dur - 25) (dur - i) / 25.0 else 1.0
                buf[offset + i] += (fmBrass(note.hz, t) * 0.85 * gate).toFloat()
            }
            offset += dur
        }

        // 그랜드 코드: β 낮게(따뜻한 톤), 10ms 어택, 페이드아웃
        val fadeSamples = fadeMs * sampleRate / 1000
        val fadeStart   = (chordSamples - fadeSamples).coerceAtLeast(0)
        val chordAttack = (sampleRate * 0.010).toInt()
        for (hz in chord) {
            for (i in 0 until chordSamples) {
                if (offset + i >= total) break
                val t       = i.toDouble() / sampleRate
                val attack  = if (i < chordAttack) i.toDouble() / chordAttack else 1.0
                val fadeOut = if (i >= fadeStart) (chordSamples - i).toDouble() / fadeSamples else 1.0
                buf[offset + i] += (fmBrass(hz, t, betaHi = 3.0, betaLo = 1.2) * 0.55 * attack * fadeOut).toFloat()
            }
        }

        var maxAbs = 0.01f
        for (v in buf) { val a = abs(v); if (a > maxAbs) maxAbs = a }
        val scale = if (maxAbs > 0.9f) 0.9f / maxAbs else 1.0f
        return ShortArray(total) { i -> (buf[i] * scale * Short.MAX_VALUE).toInt().toShort() }
    }

    companion object {
        private const val KICK  = 0
        private const val SNARE = 1
        private const val HIHAT = 2
        private const val BASS  = 3
        private const val LEAD  = 4
        private const val CYBER = 5   // square-wave lead (odd harmonics)
        private const val CBASS = 6   // sawtooth bass (all harmonics)
    }
}
