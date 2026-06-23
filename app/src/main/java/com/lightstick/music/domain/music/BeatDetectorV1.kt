package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.*

/**
 * BeatDetectorV1 (= 버전 14)
 *
 * librosa beat_track 충실 재현. V4 의 두 가지 근본 결함을 해소:
 *
 * 문제 A — BPM 오추정 (V4 8-seed comb filter)
 *   V4: 8개 이산 씨드(80/90/100/110/120/125/140/160 BPM)만 채점
 *   → 비표준 BPM(74/117.5/126/147.7 등)을 잡지 못함 → 30-60% BPM 오차
 *   수정: 모든 lag 에 대해 autocorrelation 연속 스윕 + log-normal prior 직접 곱셈
 *         harmonic folding 규칙 제거 → prior 가 octave 해소
 *         (librosa: ac_df * logprior, prior = exp(-0.5*(log2(bpm/120)/1octave)^2))
 *
 * 문제 B — 세그먼트 비트가 DP 를 덮어씀 (V4 segment merge)
 *   V4: segment 감지 비트(confidence≈0.5~1.0)가 DP FILL 비트(0.20) override
 *   → 잘못된 위상·BPM 의 세그먼트 비트가 전곡 DP 를 무력화
 *   수정: 전곡 DP 결과를 주 출력으로 사용, 세그먼트 루프는 DP 실패 시 fallback 만 사용
 *
 * 유지:
 *   - Multi-band flux ODF + localNormalizeMean (V4 Fix E)
 *   - Ellis DP, Gaussian local scoring, tightness=100 (V4 Rev3)
 *   - 고정 위상 보정 제거 (DP 가 자연스럽게 최적 위상 탐색)
 */
object BeatDetectorV1 {

    private const val TAG = "AutoTimeline_BeatDetectorV1"

    private const val FILL_CONFIDENCE = 0.20f

    private const val LOCAL_NORM_WINDOW  = 60
    private const val GLOBAL_NORM_WINDOW = 80

    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f

    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB    = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    // log-normal prior 중심: 120 BPM (500ms), std: 1 octave (librosa default)
    private const val PRIOR_CENTER_MS  = 500L
    private const val PRIOR_STD_OCTAVE = 1.0f

    // half-tempo 체크: autocorr[halfLag] / autocorr[bestLag] >= 이 값이면 빠른 템포 선택
    // prior 가 느린 BPM 쪽으로 과도하게 치우쳐 TOMBOY/Stars 등 140+ BPM 곡에서 반박자 오류 방지
    private const val HALF_TEMPO_RATIO = 0.60f

    // DP 실패 판단 기준: 예상 비트 수의 25% 미만이면 fallback
    private const val DP_MIN_BEAT_RATIO = 0.25f

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
        val hopMs: Long             = 50L,
        val minBeatMs: Long         = 375L,   // 160 BPM
        val maxBeatMs: Long         = 1000L,  // 60 BPM
        val minPeakDistanceMs: Long = 120L,
        val onsetSmoothWindow: Int  = 3,
        val segmentMs: Long         = 20_000L,
        val peakThresholdK: Float   = 0.22f,
        val minPeakAbs: Float       = 0.04f,
        val snapToleranceMs: Long   = 130L,
        val chainToleranceMs: Long  = 150L,
        val minChainCount: Int      = 3,
        val continuityBonus: Float  = 0.08f
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

    // IIR 계수 (AutoTimelineGeneratorBeat_v0 와 동일)
    private const val LOW_ALPHA     = 0.12f
    private const val MID_LP1_ALPHA = 0.35f
    private const val MID_LP2_ALPHA = 0.08f

    // PCM 입력 entry point — 내부에서 IIR 필터로 low/mid/full 엔벨로프 계산 후 detect()

    fun detectPcm(
        monoSamples: FloatArray,
        sampleRate: Int,
        params: Params = Params()
    ): DetectResult {
        if (monoSamples.isEmpty() || sampleRate <= 0) {
            return DetectResult(emptyList(), 0L, null, "empty pcm", 0L, TimeSignature.FOUR_FOUR)
        }
        val hopSamples = max(1, (sampleRate * params.hopMs / 1000).toInt())
        val numFrames  = monoSamples.size / hopSamples
        val outLow  = ArrayList<Float>(numFrames)
        val outMid  = ArrayList<Float>(numFrames)
        val outFull = ArrayList<Float>(numFrames)

        var lowZ = 0f; var midLP1 = 0f; var midLP2 = 0f
        var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var winPos = 0

        for (sample in monoSamples) {
            lowZ   += LOW_ALPHA     * (sample - lowZ)
            midLP1 += MID_LP1_ALPHA * (sample - midLP1)
            midLP2 += MID_LP2_ALPHA * (sample - midLP2)
            val lowVal = kotlin.math.abs(lowZ)
            val midVal = kotlin.math.abs(midLP1 - midLP2)
            lowSumSq  += lowVal * lowVal
            midSumSq  += midVal * midVal
            fullSumSq += sample * sample
            winPos++
            if (winPos >= hopSamples) {
                outLow  += kotlin.math.sqrt(lowSumSq  / winPos)
                outMid  += kotlin.math.sqrt(midSumSq  / winPos)
                outFull += kotlin.math.sqrt(fullSumSq / winPos)
                lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; winPos = 0
            }
        }

        fun normalizeEnv(src: List<Float>): List<Float> {
            val mx = src.maxOrNull() ?: 0f
            return if (mx > 1e-6f) src.map { (it / mx).coerceIn(0f, 1f) } else src
        }
        return detect(normalizeEnv(outLow), normalizeEnv(outMid), normalizeEnv(outFull), params)
    }

    // Public entry point

    fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        params: Params = Params()
    ): DetectResult {
        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "empty env", 0L, TimeSignature.FOUR_FOUR)
        }

        val minSize    = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        val low        = lowEnv.take(minSize)
        val mid        = midEnv.take(minSize)
        val full       = fullEnv.take(minSize)
        val durationMs = minSize * params.hopMs

        val globalOdf = computeMultiBandFluxOdf(low, mid, full, params)

        val beatMs = estimateBpmDense(globalOdf, params.hopMs, params.minBeatMs, params.maxBeatMs)
                     ?: 500L
        Log.d(TAG, "V1 beatMs=$beatMs (${60_000L / beatMs} BPM) durationMs=$durationMs")

        //    DP가 잘못된 위상에 수렴하는 문제(Dynamite 등) 방지
        val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
        Log.d(TAG, "V1 phaseMs=$phaseMs")

        val dpTimes = dpBeatTracker(globalOdf, beatMs, params.hopMs, durationMs, anchorMs = phaseMs)
        Log.d(TAG, "V1 dpTimes=${dpTimes.size}")

        // DP 품질 검증
        val expectedBeats = max(1, (durationMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            Log.w(TAG, "V1 DP insufficient (${dpTimes.size}/$expectedBeats) → segment fallback")
            beats  = fallbackSegmentBeats(low, mid, full, params, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V1 detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        val timeSignature = detectTimeSignature(globalOdf, beatMs, params.hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, beatMs, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        // [DIAG] detect() 최종 출력에서 비정상 간격 탐지
        val beatTimes = beats.map { it.timeMs }
        for (i in 1 until beatTimes.size) {
            val gap = beatTimes[i] - beatTimes[i - 1]
            if (gap < beatMs * 3L / 4L) {
                Log.w(TAG, "V1 detect() short-gap FINAL: ${beatTimes[i-1]}ms→${beatTimes[i]}ms gap=${gap}ms (beatMs=$beatMs) idx=$i reason=$reason")
            }
        }

        Log.d(TAG, "V1 OK beats=${beats.size} beatMs=$beatMs " +
            "timeSig=${timeSignature.type} reason=$reason first=${beatTimes.firstOrNull()} last=${beatTimes.lastOrNull()}")

        return DetectResult(
            beats            = beats,
            beatMs           = beatMs,
            source           = BeatSource.FULL,
            reason           = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature
        )
    }

    private fun estimateBpmDense(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
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

            // log-normal prior: exp(-0.5*(log2(lagMs/500ms)/1octave)^2)
            val lagMs    = lag * hopMs
            val logRatio = ln(lagMs.toFloat() / PRIOR_CENTER_MS) / ln(2f)
            val prior    = exp(-0.5f * (logRatio / PRIOR_STD_OCTAVE) * (logRatio / PRIOR_STD_OCTAVE))

            val score = acVal * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }

        if (bestLag <= 0) return null

        // prior 가 낮은 BPM(긴 주기)을 선호하는 경향이 있어 140+ BPM 곡에서 반박자 오류 발생
        // ex) TOMBOY GT=147.7 BPM (406ms) → prior 편향으로 75 BPM (800ms) 선택됨
        // halfLag의 autocorr 가 bestLag의 55% 이상이면 빠른 템포(halfLag) 선택
        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfAc = acVals[halfLag]
            val bestAc = acVals[bestLag]
            if (bestAc > 0f && halfAc / bestAc >= HALF_TEMPO_RATIO) {
                val halfMs = halfLag * hopMs
                Log.d(TAG, "V1 halfTempoCheck: ${bestLag*hopMs}ms(${60_000L/(bestLag*hopMs)}BPM)" +
                    " → ${halfMs}ms(${60_000L/halfMs}BPM)  ratio=${halfAc/bestAc}")
                return halfMs
            }
        }

        val resultMs = bestLag * hopMs
        Log.d(TAG, "V1 estimateBpmDense: ${resultMs}ms (${60_000L / resultMs} BPM)")
        return resultMs
    }

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

    private fun dpBeatTracker(
        odf: List<Float>,
        targetPeriodMs: Long,
        hopMs: Long,
        durationMs: Long,
        anchorMs: Long = 0L   // 위상 앵커: estimatePhaseFromOdf 결과를 주입하여 위상 오류 방지
    ): LongArray {
        if (odf.isEmpty() || targetPeriodMs <= 0L) return LongArray(0)
        val n            = odf.size
        val fpb          = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
        val tightness    = 100.0f
        val anchorFrame  = if (anchorMs > 0L) (anchorMs / hopMs).toInt().coerceIn(0, n - 1) else -1

        val gaussHalf = fpb
        val gaussSize = gaussHalf * 2 + 1
        val gaussWin  = FloatArray(gaussSize) { k ->
            val i = (k - gaussHalf).toFloat()
            exp(-0.5f * (i * 32.0f / fpb) * (i * 32.0f / fpb))
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

        // p == 0 또는 p == anchorFrame 이면 "자유 시작" 으로 pScore = 0 취급
        // → anchorFrame(comb-phase 추정 위상)에서 chain 을 시작할 수 있어
        //   전역 DP 가 잘못된 위상에 수렴하는 문제를 방지한다
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
            val p = prev[t]
            if (p < 0 || p == t) break
            t = p; iter++
        }

        val preTrim = beats.reversed().toLongArray()
        // [DIAG] pre-trim 에서 비정상 간격 탐지
        for (i in 1 until preTrim.size) {
            val gap = preTrim[i] - preTrim[i - 1]
            if (gap < hopMs * 10L) {
                Log.w(TAG, "V1 DP close-pair [PRE-TRIM]: ${preTrim[i-1]}ms→${preTrim[i]}ms gap=${gap}ms idx=$i")
            }
        }

        if (preTrim.size < 2) return preTrim
        val rms    = sqrt(localscore.map { it * it }.average().toFloat())
        val trimTh = 0.5f * rms
        var s = 0
        while (s < preTrim.size && localscore[(preTrim[s] / hopMs).toInt().coerceIn(0, n-1)] < trimTh) s++
        var e = preTrim.size - 1
        while (e > s && localscore[(preTrim[e] / hopMs).toInt().coerceIn(0, n-1)] < trimTh) e--
        val result = if (s > e) preTrim else preTrim.sliceArray(s..e)
        // [DIAG] post-trim 에서 비정상 간격 탐지
        for (i in 1 until result.size) {
            val gap = result[i] - result[i - 1]
            if (gap < hopMs * 10L) {
                Log.w(TAG, "V1 DP close-pair [POST-TRIM]: ${result[i-1]}ms→${result[i]}ms gap=${gap}ms idx=$i")
            }
        }
        return result
    }

    private fun fallbackSegmentBeats(
        low: List<Float>, mid: List<Float>, full: List<Float>,
        params: Params, beatMs: Long, durationMs: Long
    ): List<TimedBeat> {
        val n          = min(low.size, min(mid.size, full.size))
        val segFrames  = max(1, (params.segmentMs / params.hopMs).toInt())
        val result     = ArrayList<TimedBeat>()

        for (segIdx in 0 until (n + segFrames - 1) / segFrames) {
            val s = segIdx * segFrames
            val e = min(n, s + segFrames)
            if (e - s < 8) continue

            val odf      = computeMultiBandFluxOdf(low.subList(s, e), mid.subList(s, e), full.subList(s, e), params)
            val segPhase = estimatePhaseFromOdf(odf, beatMs, params.hopMs)
            val segTimes = dpBeatTracker(odf, beatMs, params.hopMs, (e - s).toLong() * params.hopMs, anchorMs = segPhase)
            val offset   = s.toLong() * params.hopMs
            segTimes.forEach { result += TimedBeat(offset + it, FILL_CONFIDENCE) }
        }
        return result.sortedBy { it.timeMs }
    }

    private fun computeMultiBandFluxOdf(
        low: List<Float>, mid: List<Float>, full: List<Float>, params: Params
    ): List<Float> {
        val n = minOf(low.size, mid.size, full.size)
        val lowFlux  = computeOdf(low.take(n),  params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val midFlux  = computeOdf(mid.take(n),  params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val fullFlux = computeOdf(full.take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val combined = ArrayList<Float>(n)
        for (i in 0 until n) {
            combined += lowFlux[i] * 1.0f + midFlux[i] * 1.8f + fullFlux[i] * 0.8f
        }
        return localNormalizeMean(combined, GLOBAL_NORM_WINDOW)
    }

    // Fix E: 배경 평균 제거 후 0-1 정규화
    private fun localNormalizeMean(src: List<Float>, windowFrames: Int): List<Float> {
        if (src.isEmpty()) return emptyList()
        val bgRemoved = ArrayList<Float>(src.size)
        for (i in src.indices) {
            val lo = max(0, i - windowFrames); val hi = min(src.lastIndex, i + windowFrames)
            var localMean = 0f; var cnt = 0
            for (j in lo..hi) { localMean += src[j]; cnt++ }
            localMean = if (cnt > 0) localMean / cnt else 0f
            bgRemoved.add((src[i] - localMean).coerceAtLeast(0f))
        }
        val out = ArrayList<Float>(src.size)
        for (i in bgRemoved.indices) {
            val lo = max(0, i - windowFrames); val hi = min(bgRemoved.lastIndex, i + windowFrames)
            var localMax = 0f
            for (j in lo..hi) if (bgRemoved[j] > localMax) localMax = bgRemoved[j]
            out.add(if (localMax > 1e-6f) (bgRemoved[i] / localMax).coerceIn(0f, 1f) else 0f)
        }
        return out
    }

    private fun detectTimeSignature(onset: List<Float>, beatMs: Long, hopMs: Long): TimeSignature {
        if (onset.size < 8 || beatMs <= 0L) return TimeSignature.FOUR_FOUR
        val bf    = (beatMs / hopMs).toInt().coerceAtLeast(1)
        val corr3 = lagCorr(onset, bf * 3)
        val corr4 = lagCorr(onset, bf * 4)
        val corr6 = lagCorr(onset, bf * 6)
        return when {
            corr3 > corr4 * TIME_SIG_THREE_RATIO                           -> TimeSignature.THREE_FOUR
            corr6 > corr4 * TIME_SIG_SIX_RATIO && corr3 > corr4 * 0.85f  -> TimeSignature.SIX_EIGHT
            else                                                            -> TimeSignature.FOUR_FOUR
        }
    }

    private fun lagCorr(onset: List<Float>, lag: Int): Float {
        if (lag <= 0 || lag >= onset.size) return 0f
        var sum = 0f; var i = 0
        while (i + lag < onset.size) { sum += onset[i] * onset[i + lag]; i++ }
        return sum / i.toFloat().coerceAtLeast(1f)
    }

    private fun detectDownbeatEnhanced(
        beatTimesMs: List<Long>, lowEnv: List<Float>,
        beatMs: Long, beatsPerBar: Int, hopMs: Long
    ): Long {
        if (beatTimesMs.isEmpty() || beatMs <= 0L) return 0L
        if (beatTimesMs.size < beatsPerBar) return beatTimesMs.first()

        val phaseSum = FloatArray(beatsPerBar); val phaseCnt = IntArray(beatsPerBar)
        for (i in beatTimesMs.indices) {
            val ph = i % beatsPerBar
            val fr = (beatTimesMs[i] / hopMs).toInt().coerceIn(0, lowEnv.lastIndex)
            phaseSum[ph] += lowEnv[fr]; phaseCnt[ph]++
        }
        val avgEnergy = FloatArray(beatsPerBar) { p ->
            if (phaseCnt[p] > 0) phaseSum[p] / phaseCnt[p] else 0f
        }

        val barFrames = ((beatMs * beatsPerBar) / hopMs).toInt().coerceAtLeast(1)
        val combScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val anchor = (beatTimesMs.getOrElse(ph) { ph.toLong() * beatMs } / hopMs).toInt()
            var k = anchor; var sum = 0f; var cnt = 0
            while (k < lowEnv.size) { sum += lowEnv[k.coerceIn(0, lowEnv.lastIndex)]; cnt++; k += barFrames }
            k = anchor - barFrames
            while (k >= 0) { sum += lowEnv[k.coerceIn(0, lowEnv.lastIndex)]; cnt++; k -= barFrames }
            combScore[ph] = if (cnt > 0) sum / cnt else 0f
        }

        val consistScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val energies = beatTimesMs.filterIndexed { i, _ -> i % beatsPerBar == ph }
                .map { t -> lowEnv[(t / hopMs).toInt().coerceIn(0, lowEnv.lastIndex)] }
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
            avgEnergy.normMax()[p] * DOWNBEAT_W_LOW_ENERGY  +
            combScore.normMax()[p]  * DOWNBEAT_W_BAR_COMB   +
            consistScore.normMax()[p] * DOWNBEAT_W_CONSISTENCY
        } ?: 0

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }

    private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> =
        localNormalizeMax(positiveDiff(movingAverage(env, smoothWindow)), normWindow)

    private fun localNormalizeMax(src: List<Float>, windowFrames: Int): List<Float> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<Float>(src.size)
        for (i in src.indices) {
            val lo = max(0, i - windowFrames); val hi = min(src.lastIndex, i + windowFrames)
            var localMax = 0f
            for (j in lo..hi) if (src[j] > localMax) localMax = src[j]
            out.add(if (localMax > 1e-6f) (src[i] / localMax).coerceIn(0f, 1f) else 0f)
        }
        return out
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src.toList()
        val out = ArrayList<Float>(src.size); val half = window / 2
        for (i in src.indices) {
            var sum = 0f; var count = 0
            val s = max(0, i - half); val e = min(src.lastIndex, i + half)
            for (j in s..e) { sum += src[j]; count++ }
            out += if (count == 0) 0f else sum / count.toFloat()
        }
        return out
    }

    private fun positiveDiff(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<Float>(src.size); out += 0f
        for (i in 1 until src.size) out += max(0f, src[i] - src[i - 1])
        return out
    }
}
