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

    // 불꽃놀이: snap(팡) → pitch-swept boom → amplitude-crackle(촤라라락)
    private fun renderPop(): ShortArray {
        val pitchScale = 0.85 + Random.nextDouble() * 0.30

        // LCG 노이즈 두 스트림 (신호용 + 진폭 변조용)
        var nState = System.nanoTime() xor Random.nextLong()
        fun noise(): Double {
            nState = nState * 6364136223846793005L + 1442695040888963407L
            return (nState ushr 33).toInt().toDouble() / 2147483648.0
        }
        var aState = nState xor 0x9E3779B97F4A7C15L
        fun randD(): Double {
            aState = aState * 2862933555777941757L + 3037000493L
            return (aState ushr 33).toInt().toDouble() / 2147483648.0
        }
        fun randI(n: Int): Int {
            aState = aState * 2862933555777941757L + 3037000493L
            return ((aState ushr 33).toInt() and 0x7FFFFFFF) % n
        }

        // Phase 1 — snap: 5ms 최대 진폭 순간 폭발
        val snapN = 5 * sampleRate / 1000

        // Phase 2 — boom: 피치 하강 저주파 진동 (쿵~)
        val boomMs = 180 + randI(40)
        val boomN  = boomMs * sampleRate / 1000

        // Phase 3 — crackle: 진폭 랜덤 변조 하이패스 노이즈
        val crackleMs = 300 + randI(150)
        val crackleN  = crackleMs * sampleRate / 1000

        val total = snapN + boomN + crackleN
        val raw   = FloatArray(total)

        // Phase 1: 2샘플 어택 후 최대 진폭
        for (i in 0 until snapN) {
            raw[i] = (noise() * (if (i < 2) i / 2.0 else 1.0)).toFloat()
        }

        // Phase 2: f(t) = endHz + (startHz - endHz)*exp(-10t) 하강 스윕 + 노이즈 블렌드
        val startHz = 350.0 * pitchScale
        val endHz   = 28.0  * pitchScale
        var boomPhase = 0.0
        for (i in 0 until boomN) {
            val t    = i.toDouble() / sampleRate
            val freq = endHz + (startHz - endHz) * exp(-10.0 * t)
            boomPhase += 2.0 * PI * freq / sampleRate
            val tone  = sin(boomPhase)
            val nFade = exp(-18.0 * t)   // 초반엔 노이즈, 이후 톤이 지배
            raw[snapN + i] = ((noise() * nFade + tone * (1.0 - nFade * 0.6)) * 0.95 * exp(-6.5 * t)).toFloat()
        }

        // Phase 3: 랜덤 진폭 이벤트로 크래클 질감 생성
        var prev      = 0.0
        var crackAmp  = 1.0
        var countdown = randI(530) + 220   // 5–17ms 간격
        for (i in 0 until crackleN) {
            val t = i.toDouble() / sampleRate
            if (--countdown <= 0) {
                crackAmp  = 0.25 + randD() * 0.75
                countdown = randI(530) + 220
            }
            val n  = noise()
            val hp = n - 0.95 * prev
            prev   = n
            raw[snapN + boomN + i] = (hp * crackAmp * 0.88 * exp(-6.5 * t)).toFloat()
        }

        var maxAbs = 0.01f
        for (v in raw) { val a = abs(v); if (a > maxAbs) maxAbs = a }
        val scale = if (maxAbs > 0.9f) 0.9f / maxAbs else 1.0f
        return ShortArray(total) { i -> (raw[i] * scale * Short.MAX_VALUE).toInt().toShort() }
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
    }
}
