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
                    val burstCount = 1 + Random.nextInt(2)   // 1–2 pops (pop 자체가 ~1s)
                    repeat(burstCount) { idx ->
                        if (!celebrationActive || Thread.currentThread().isInterrupted) return@repeat
                        firePop()
                        if (idx < burstCount - 1) Thread.sleep(80L + Random.nextLong(120L))
                    }
                    Thread.sleep(800L + Random.nextLong(1200L))
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

    // ASMR 폭죽: 쉬익(whistle) → 팡(bang) → 촤라라락(discrete tick crackle)
    // 참조: ASMR 폭죽소리 효과음 (youtube AXzgckCsUv8)
    // 연속 노이즈가 아닌 짧은 tick 이벤트가 간격을 두고 반복 → ASMR 특유의 끊기는 크래클
    private fun renderPop(): ShortArray {
        val pitchScale = 0.88 + Random.nextDouble() * 0.24

        // 두 LCG 스트림: 신호용(nState) + 제어용(aState)
        var nState = System.nanoTime() xor Random.nextLong()
        fun noise(): Double {
            nState = nState * 6364136223846793005L + 1442695040888963407L
            return (nState ushr 33).toInt().toDouble() / 2147483648.0
        }
        var aState = nState xor -7046029254386353131L
        fun randD(): Double {
            aState = aState * 2862933555777941757L + 3037000493L
            return (aState ushr 33).toInt().toDouble() / 2147483648.0
        }
        fun randI(n: Int): Int {
            aState = aState * 2862933555777941757L + 3037000493L
            return ((aState ushr 33).toInt() and 0x7FFFFFFF) % n
        }

        // 각 단계 길이
        val whistleMs = 180 + randI(60)            // 쉬익: 180–240ms
        val bangN     = 18  * sampleRate / 1000    // 팡:   18ms
        val bloomN    = 45  * sampleRate / 1000    // 팡 잔향: 45ms
        val crackleMs = 580 + randI(280)           // 촤라라락: 580–860ms

        val whistleN  = whistleMs * sampleRate / 1000
        val crackleN  = crackleMs * sampleRate / 1000
        val total     = whistleN + bangN + bloomN + crackleN
        val raw       = FloatArray(total)

        // ── 쉬익 (whistle): 상승하는 주파수 스윕 ──────────────────────────────
        // 위상 누적으로 800→6500Hz 부드럽게 상승, 진폭도 0→0.6 상승
        var whistlePhase = 0.0
        for (i in 0 until whistleN) {
            val progress = i.toDouble() / whistleN          // 0→1
            val freq     = 800.0 + 5700.0 * progress * progress  // 가속 상승
            whistlePhase += 2.0 * PI * freq / sampleRate
            val amp = progress * 0.60
            // 사인 + 약간의 노이즈 → 순수한 휘파람 + 실제감
            raw[i] = ((sin(whistlePhase) * 0.82 + noise() * 0.18) * amp).toFloat()
        }

        // ── 팡 (bang): 순간 최대 진폭 백색 노이즈 ────────────────────────────
        for (i in 0 until bangN) {
            val gate = if (i < 3) i / 3.0 else 1.0
            raw[whistleN + i] = (noise() * gate).toFloat()
        }

        // ── 팡 잔향 (bloom): 짧은 감쇠 ──────────────────────────────────────
        for (i in 0 until bloomN) {
            val t = i.toDouble() / sampleRate
            raw[whistleN + bangN + i] = (noise() * exp(-28.0 * t)).toFloat()
        }

        // ── 촤라라락 (crackle): ASMR 스타일 개별 tick 이벤트 ─────────────────
        // tick(2–5ms 노이즈 버스트) + gap(8–30ms 무음) 반복
        // gap 길이는 시간이 지날수록 점점 길어짐 → 자연스럽게 소멸
        var crackPrev   = 0.0
        var inBurst     = false
        var burstLeft   = 0
        var gapLeft     = randI(220) + 132    // 초기 갭: 3–8ms
        var tickAmp     = 1.0

        val base0 = whistleN + bangN + bloomN
        for (i in 0 until crackleN) {
            val t        = i.toDouble() / sampleRate
            val progress = t / (crackleMs / 1000.0)

            if (inBurst) {
                if (--burstLeft <= 0) {
                    inBurst = false
                    // 진행될수록 갭이 길어짐 (5ms → 30ms)
                    val baseGap = (220 + (progress * 1100).toInt())
                    gapLeft = randI(baseGap) + 220
                }
            } else {
                if (--gapLeft <= 0) {
                    inBurst  = true
                    burstLeft = randI(132) + 88     // 2–5ms 버스트
                    tickAmp   = 0.30 + randD() * 0.70
                }
            }

            val n  = noise()
            val hp = n - 0.96 * crackPrev   // 강한 하이패스 → 선명한 고음 크래클
            crackPrev = n

            if (inBurst) {
                // 전체 감쇠 + 각 tick별 랜덤 진폭
                raw[base0 + i] = (hp * tickAmp * exp(-4.8 * t)).toFloat()
            }
            // 갭 구간: raw 은 이미 0f 이므로 별도 처리 불필요
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
