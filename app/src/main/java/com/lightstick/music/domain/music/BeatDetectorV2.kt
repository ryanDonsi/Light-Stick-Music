package com.lightstick.music.domain.music

import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

/**
 * BeatDetectorV2 — madmom SuperFlux ODF + ACF BPM + Ellis DP
 *
 * madmom (Böck et al.) 논문의 SuperFlux onset detection function 을 순수 Kotlin 으로 구현.
 *
 * ODF 파이프라인:
 *   PCM → STFT (Hanning window, FFT_SIZE=2048) → log magnitude: log(1+λ|X|), λ=1000
 *   → 이전 프레임에 max filter(±1 bin) 적용 → 양의 스펙트럼 차분 합산 = SuperFlux ODF
 *
 * BPM 추정:
 *   autocorr(ODF, lag) * log-normal prior (중심=120BPM, σ=1octave) → argmax
 *   half-tempo check: ratio ≥ 0.60 일 때만 빠른 템포 선택 (별보러가자 2x 오류 방지)
 *
 * Beat tracking:
 *   Ellis DP (tightness=100) + comb-phase 위상 앵커
 */
object BeatDetectorV2 {

    private const val TAG = "AutoTimeline"

    // madmom SuperFlux 파라미터
    private const val FFT_SIZE         = 2048
    private const val HOP_MS_TARGET    = 10L      // ~10ms/frame (441 samples @ 44100Hz)
    private const val LOG_LAMBDA       = 1000f    // log(1 + λ|X|)
    private const val MAX_FILTER_WIDTH = 1        // ±1 bin max filter

    // BPM prior (V1 과 동일)
    private const val PRIOR_CENTER_MS  = 500L
    private const val PRIOR_STD_OCTAVE = 1.0f
    private const val HALF_TEMPO_RATIO = 0.60f

    private const val FILL_CONFIDENCE  = 0.20f
    private const val DP_MIN_BEAT_RATIO = 0.25f

    private const val LOCAL_NORM_WINDOW = 80   // ODF 후처리 평활화 창
    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f
    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB    = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    data class TimedBeat(val timeMs: Long, val confidence: Float)

    enum class TimeSignatureType { FOUR_FOUR, THREE_FOUR, SIX_EIGHT }

    data class TimeSignature(
        val type: TimeSignatureType,
        val numerator: Int,
        val denominator: Int
    ) {
        companion object {
            val FOUR_FOUR  = TimeSignature(TimeSignatureType.FOUR_FOUR,  4, 4)
            val THREE_FOUR = TimeSignature(TimeSignatureType.THREE_FOUR, 3, 4)
            val SIX_EIGHT  = TimeSignature(TimeSignatureType.SIX_EIGHT,  6, 8)
        }
        val beatsPerBar: Int get() = numerator
    }

    enum class BeatSource { LOW, MID, FULL, LOW_MID, MID_FULL, LOW_FULL }

    data class Params(
        val minBeatMs: Long = 375L,
        val maxBeatMs: Long = 1000L,
        val minPeakDistanceMs: Long = 120L
    )

    data class DetectResult(
        val beats: List<TimedBeat>,
        val beatMs: Long,
        val source: BeatSource?,
        val reason: String,
        val downbeatOffsetMs: Long,
        val timeSignature: TimeSignature,
        val debugSegments: List<Any> = emptyList()
    ) {
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }
    }

    // =========================================================================
    // Public entry point — PCM 입력
    // =========================================================================

    fun detect(
        monoSamples: FloatArray,
        sampleRate: Int,
        params: Params = Params()
    ): DetectResult {
        if (monoSamples.isEmpty() || sampleRate <= 0) {
            return DetectResult(emptyList(), 0L, null, "empty input", 0L, TimeSignature.FOUR_FOUR)
        }

        val hopSamples = max(1, (sampleRate * HOP_MS_TARGET / 1000).toInt())
        val hopMs      = hopSamples * 1000L / sampleRate
        val durationMs = monoSamples.size.toLong() * 1000L / sampleRate

        // ── 1. SuperFlux ODF ──────────────────────────────────────────────────
        val odf = superFluxOdf(monoSamples, hopSamples)
        Log.d(TAG, "V2 SuperFlux odf frames=${odf.size} hopMs=$hopMs durationMs=$durationMs")

        // ── 2. Dense BPM 추정 ─────────────────────────────────────────────────
        val beatMs = estimateBpmDense(odf, hopMs, params.minBeatMs, params.maxBeatMs) ?: 500L
        Log.d(TAG, "V2 beatMs=$beatMs (${60_000L / beatMs} BPM)")

        // ── 3. 위상 추정 ──────────────────────────────────────────────────────
        val phaseMs = estimatePhaseFromOdf(odf, beatMs, hopMs)
        Log.d(TAG, "V2 phaseMs=$phaseMs")

        // ── 4. Global DP ──────────────────────────────────────────────────────
        val dpTimes = dpBeatTracker(odf, beatMs, hopMs, durationMs, anchorMs = phaseMs)
        Log.d(TAG, "V2 dpTimes=${dpTimes.size}")

        val expectedBeats = max(1, (durationMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            Log.w(TAG, "V2 DP insufficient (${dpTimes.size}/$expectedBeats) → segment fallback")
            beats  = fallbackSegmentBeats(monoSamples, hopSamples, hopMs, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V2 detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        val timeSignature = detectTimeSignature(odf, beatMs, hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            beats.map { it.timeMs }, odf, beatMs, timeSignature.beatsPerBar, hopMs)
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V2 OK beats=${beats.size} beatMs=$beatMs " +
            "timeSig=${timeSignature.type} reason=$reason")

        return DetectResult(
            beats            = beats,
            beatMs           = beatMs,
            source           = BeatSource.FULL,
            reason           = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature
        )
    }

    // =========================================================================
    // madmom SuperFlux ODF
    //
    // 각 프레임:
    //   1. Hanning window 적용 후 FFT
    //   2. log magnitude: log(1 + λ|X[k]|)
    //   3. 이전 프레임에 max filter(±1 bin): prevMax[k] = max(prev[k-1..k+1])
    //   4. positive flux: sum(max(0, cur[k] - prevMax[k]))
    //
    // 메모리: 슬라이딩 2-프레임만 유지 (전체 스펙트럼 배열 불필요)
    // =========================================================================

    private fun superFluxOdf(samples: FloatArray, hopSamples: Int): List<Float> {
        val numBins = FFT_SIZE / 2 + 1
        val numFrames = if (samples.size >= FFT_SIZE)
            (samples.size - FFT_SIZE) / hopSamples + 1 else 0
        if (numFrames == 0) return emptyList()

        val fft = FloatFFT_1D(FFT_SIZE.toLong())
        val hannWindow = FloatArray(FFT_SIZE) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
        }

        val fftBuf    = FloatArray(FFT_SIZE)
        val curLogMag = FloatArray(numBins)
        val prevLogMag = FloatArray(numBins)
        val odf = ArrayList<Float>(numFrames)

        for (t in 0 until numFrames) {
            val start = t * hopSamples
            for (i in 0 until FFT_SIZE) {
                fftBuf[i] = (if (start + i < samples.size) samples[start + i] else 0f) * hannWindow[i]
            }
            fft.realForward(fftBuf)

            // JTransforms realForward packing:
            //   fftBuf[0]       = Re(0)   DC
            //   fftBuf[1]       = Re(N/2) Nyquist
            //   fftBuf[2k], [2k+1] = Re(k), Im(k)  for k=1..N/2-1
            curLogMag[0] = ln(1f + LOG_LAMBDA * abs(fftBuf[0]))
            curLogMag[numBins - 1] = ln(1f + LOG_LAMBDA * abs(fftBuf[1]))
            for (k in 1 until numBins - 1) {
                val re = fftBuf[2 * k]; val im = fftBuf[2 * k + 1]
                curLogMag[k] = ln(1f + LOG_LAMBDA * sqrt(re * re + im * im))
            }

            if (t == 0) {
                odf.add(0f)
            } else {
                var flux = 0f
                for (k in 0 until numBins) {
                    var prevMax = prevLogMag[k]
                    if (k > 0 && prevLogMag[k - 1] > prevMax) prevMax = prevLogMag[k - 1]
                    if (k < numBins - 1 && prevLogMag[k + 1] > prevMax) prevMax = prevLogMag[k + 1]
                    val diff = curLogMag[k] - prevMax
                    if (diff > 0f) flux += diff
                }
                odf.add(flux)
            }
            curLogMag.copyInto(prevLogMag)
        }

        // 전역 정규화 → [0, 1]
        val maxVal = odf.maxOrNull() ?: 1f
        return if (maxVal > 1e-6f) odf.map { it / maxVal } else odf
    }

    // =========================================================================
    // Dense BPM 추정 (V1 과 동일 — log-normal prior + half-tempo check)
    // =========================================================================

    private fun estimateBpmDense(
        odf: List<Float>, hopMs: Long, minBeatMs: Long, maxBeatMs: Long
    ): Long? {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt())
        if (odf.size <= maxLag + 2) return null

        val acVals    = FloatArray(maxLag + 1)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestLag   = -1

        for (lag in minLag..maxLag) {
            var sum = 0f; var count = 0
            for (i in 0 until odf.size - lag) { sum += odf[i] * odf[i + lag]; count++ }
            if (count == 0) continue
            val acVal = sum / count
            acVals[lag] = acVal

            val lagMs    = lag * hopMs
            val logRatio = ln(lagMs.toFloat() / PRIOR_CENTER_MS) / ln(2f)
            val prior    = exp(-0.5f * (logRatio / PRIOR_STD_OCTAVE).pow(2))
            val score    = acVal * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }

        if (bestLag <= 0) return null

        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfAc = acVals[halfLag]; val bestAc = acVals[bestLag]
            if (bestAc > 0f && halfAc / bestAc >= HALF_TEMPO_RATIO) {
                val halfMs = halfLag * hopMs
                Log.d(TAG, "V2 halfTempoCheck: ${bestLag * hopMs}ms(${60_000L / (bestLag * hopMs)}BPM)" +
                    " → ${halfMs}ms(${60_000L / halfMs}BPM) ratio=${halfAc / bestAc}")
                return halfMs
            }
        }

        val resultMs = bestLag * hopMs
        Log.d(TAG, "V2 estimateBpmDense: ${resultMs}ms (${60_000L / resultMs} BPM)")
        return resultMs
    }

    // =========================================================================
    // 위상 추정 — comb-phase scoring
    // =========================================================================

    private fun estimatePhaseFromOdf(odf: List<Float>, beatMs: Long, hopMs: Long): Long {
        val fpb = max(1, (beatMs / hopMs).toInt())
        if (odf.size < fpb * 2) return 0L
        var bestPhase = 0; var bestScore = Float.NEGATIVE_INFINITY
        for (ph in 0 until fpb) {
            var score = 0f; var f = ph
            while (f < odf.size) { score += odf[f]; f += fpb }
            if (score > bestScore) { bestScore = score; bestPhase = ph }
        }
        return bestPhase.toLong() * hopMs
    }

    // =========================================================================
    // Ellis DP Beat Tracker (V1 과 동일)
    // =========================================================================

    private fun dpBeatTracker(
        odf: List<Float>, targetPeriodMs: Long, hopMs: Long,
        durationMs: Long, anchorMs: Long = 0L
    ): LongArray {
        if (odf.isEmpty() || targetPeriodMs <= 0L) return LongArray(0)
        val n           = odf.size
        val fpb         = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
        val tightness   = 100.0f
        val anchorFrame = if (anchorMs > 0L) (anchorMs / hopMs).toInt().coerceIn(0, n - 1) else -1

        val gaussHalf = fpb; val gaussSize = gaussHalf * 2 + 1
        val gaussWin  = FloatArray(gaussSize) { k ->
            val i = (k - gaussHalf).toFloat()
            exp(-0.5f * (i * 32.0f / fpb).pow(2))
        }
        val localscore = FloatArray(n)
        for (t in 0 until n) {
            var s = 0f
            for (k in 0 until gaussSize) {
                val idx = t - gaussHalf + k
                if (idx in 0 until n) s += gaussWin[k] * odf[idx]
            }
            localscore[t] = s
        }

        val cumscore = FloatArray(n) { Float.NEGATIVE_INFINITY }
        val prev     = IntArray(n)  { -1 }
        val searchRange = fpb * 2

        for (t in 0 until n) {
            val pLo = max(0, t - searchRange)
            val pHi = max(0, t - max(1, fpb / 2))
            var bestVal = Float.NEGATIVE_INFINITY
            for (p in pLo..pHi) {
                val isFreeStart = (p == 0 || p == anchorFrame)
                if (cumscore[p] == Float.NEGATIVE_INFINITY && !isFreeStart) continue
                val pScore   = if (isFreeStart) 0f else cumscore[p]
                val lag      = (t - p).toFloat().coerceAtLeast(1f)
                val logRatio = ln(lag / fpb)
                val penalty  = tightness * logRatio * logRatio
                val cand     = pScore - penalty
                if (cand > bestVal) { bestVal = cand; prev[t] = p }
            }
            cumscore[t] = if (bestVal == Float.NEGATIVE_INFINITY) localscore[t]
                          else bestVal + localscore[t]
        }

        var t     = cumscore.indices.maxByOrNull { cumscore[it] } ?: return LongArray(0)
        val beats = mutableListOf<Long>()
        var iter  = 0
        while (t > 0 && iter < n) {
            beats.add(t.toLong() * hopMs)
            val p = prev[t]; if (p < 0 || p == t) break
            t = p; iter++
        }

        val result = beats.reversed().toLongArray()
        if (result.size < 2) return result
        val rms = sqrt(localscore.map { it * it }.average().toFloat())
        val trimTh = 0.5f * rms
        var s = 0
        while (s < result.size && localscore[(result[s] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) s++
        var e = result.size - 1
        while (e > s && localscore[(result[e] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) e--
        return if (s > e) result else result.sliceArray(s..e)
    }

    // =========================================================================
    // Fallback — 세그먼트 단위 DP
    // =========================================================================

    private fun fallbackSegmentBeats(
        samples: FloatArray, hopSamples: Int, hopMs: Long, beatMs: Long, durationMs: Long
    ): List<TimedBeat> {
        val segmentMs = 20_000L
        val segFrames = (segmentMs / hopMs).toInt().coerceAtLeast(1)
        val totalFrames = if (samples.size >= FFT_SIZE)
            (samples.size - FFT_SIZE) / hopSamples + 1 else 0
        val result = ArrayList<TimedBeat>()

        var segIdx = 0
        while (segIdx * segFrames < totalFrames) {
            val sFrame = segIdx * segFrames
            val eFrame = min(totalFrames, sFrame + segFrames)
            if (eFrame - sFrame < 8) { segIdx++; continue }

            val sSample = sFrame * hopSamples
            val eSample = min(samples.size, eFrame * hopSamples + FFT_SIZE)
            val segSamples = samples.copyOfRange(sSample, eSample)
            val segOdf   = superFluxOdf(segSamples, hopSamples)
            val segPhase = estimatePhaseFromOdf(segOdf, beatMs, hopMs)
            val segDur   = (eFrame - sFrame).toLong() * hopMs
            val segTimes = dpBeatTracker(segOdf, beatMs, hopMs, segDur, anchorMs = segPhase)
            val offset   = sFrame.toLong() * hopMs
            segTimes.forEach { result += TimedBeat(offset + it, FILL_CONFIDENCE) }
            segIdx++
        }
        return result.sortedBy { it.timeMs }
    }

    // =========================================================================
    // 박자표 감지
    // =========================================================================

    private fun detectTimeSignature(odf: List<Float>, beatMs: Long, hopMs: Long): TimeSignature {
        if (odf.size < 8 || beatMs <= 0L) return TimeSignature.FOUR_FOUR
        val bf    = (beatMs / hopMs).toInt().coerceAtLeast(1)
        val corr3 = lagCorr(odf, bf * 3)
        val corr4 = lagCorr(odf, bf * 4)
        val corr6 = lagCorr(odf, bf * 6)
        return when {
            corr3 > corr4 * TIME_SIG_THREE_RATIO                          -> TimeSignature.THREE_FOUR
            corr6 > corr4 * TIME_SIG_SIX_RATIO && corr3 > corr4 * 0.85f -> TimeSignature.SIX_EIGHT
            else                                                           -> TimeSignature.FOUR_FOUR
        }
    }

    private fun lagCorr(odf: List<Float>, lag: Int): Float {
        if (lag <= 0 || lag >= odf.size) return 0f
        var sum = 0f; var i = 0
        while (i + lag < odf.size) { sum += odf[i] * odf[i + lag]; i++ }
        return sum / i.toFloat().coerceAtLeast(1f)
    }

    // =========================================================================
    // 다운비트 감지 — ODF 를 lowEnv 대역 프록시로 사용
    // =========================================================================

    private fun detectDownbeatEnhanced(
        beatTimesMs: List<Long>, odf: List<Float>,
        beatMs: Long, beatsPerBar: Int, hopMs: Long
    ): Long {
        if (beatTimesMs.isEmpty() || beatMs <= 0L) return 0L
        if (beatTimesMs.size < beatsPerBar) return beatTimesMs.first()

        val phaseSum = FloatArray(beatsPerBar); val phaseCnt = IntArray(beatsPerBar)
        for (i in beatTimesMs.indices) {
            val ph = i % beatsPerBar
            val fr = (beatTimesMs[i] / hopMs).toInt().coerceIn(0, odf.lastIndex)
            phaseSum[ph] += odf[fr]; phaseCnt[ph]++
        }
        val avgEnergy = FloatArray(beatsPerBar) { p ->
            if (phaseCnt[p] > 0) phaseSum[p] / phaseCnt[p] else 0f
        }

        val barFrames = ((beatMs * beatsPerBar) / hopMs).toInt().coerceAtLeast(1)
        val combScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val anchor = (beatTimesMs.getOrElse(ph) { ph.toLong() * beatMs } / hopMs).toInt()
            var k = anchor; var sum = 0f; var cnt = 0
            while (k < odf.size) { sum += odf[k.coerceIn(0, odf.lastIndex)]; cnt++; k += barFrames }
            k = anchor - barFrames
            while (k >= 0) { sum += odf[k.coerceIn(0, odf.lastIndex)]; cnt++; k -= barFrames }
            combScore[ph] = if (cnt > 0) sum / cnt else 0f
        }

        val consistScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val energies = beatTimesMs.filterIndexed { i, _ -> i % beatsPerBar == ph }
                .map { t -> odf[(t / hopMs).toInt().coerceIn(0, odf.lastIndex)] }
            if (energies.size >= 2) {
                val avg = energies.average().toFloat()
                val std = sqrt(energies.map { (it - avg) * (it - avg) }.average().toFloat())
                val cv  = if (avg > 0.001f) std / avg else 1f
                consistScore[ph] = ((1f - cv.coerceIn(0f, 1f)) * avg).coerceAtLeast(0f)
            }
        }

        fun FloatArray.normMax(): FloatArray {
            val mx = maxOrNull() ?: return this
            return if (mx > 0.001f) FloatArray(size) { this[it] / mx } else this
        }
        val bestPhase = (0 until beatsPerBar).maxByOrNull { p ->
            avgEnergy.normMax()[p] * DOWNBEAT_W_LOW_ENERGY +
            combScore.normMax()[p]  * DOWNBEAT_W_BAR_COMB  +
            consistScore.normMax()[p] * DOWNBEAT_W_CONSISTENCY
        } ?: 0

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }
}
