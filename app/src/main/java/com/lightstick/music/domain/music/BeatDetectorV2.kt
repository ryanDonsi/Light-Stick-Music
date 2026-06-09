package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.*

/**
 * BeatDetectorV2
 *
 * BeatDetectorV1 (autocorrelation + log-normal prior) 기반 +
 * 발라드 사전 분류 및 예외처리:
 *
 * 문제: 느린 발라드(TOMBOY GT=74BPM, 별보러가자 GT=67BPM)에서 8분음표 리듬이
 * ODF 에 강하게 반영되어 autocorr/DP 가 2배 빠른 BPM 을 선택함.
 *
 * 해결: BPM 추정 전에 ODF 활성도(active density) + 저역 비율(lowRatio)로
 * 발라드 여부를 사전 분류(preClassifyBallad)한 뒤:
 *   - priorCenterMs  : 500ms(120BPM) → 800ms(75BPM)  로 이동
 *   - maxBeatMs      : 1000ms        → 1500ms(40BPM) 로 확장
 *   - half-tempo 체크 비활성화 (느린 곡을 빠르게 오판하는 것 방지)
 */
object BeatDetectorV2 {

    private const val TAG = "AutoTimeline"

    private const val FILL_CONFIDENCE = 0.20f

    private const val LOCAL_NORM_WINDOW  = 60
    private const val GLOBAL_NORM_WINDOW = 80

    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f

    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB    = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    // log-normal prior 중심 (일반): 120 BPM (500ms), std: 1 octave
    private const val PRIOR_CENTER_MS       = 500L
    private const val PRIOR_CENTER_BALLAD_MS = 800L  // 75 BPM — 발라드 모드
    private const val PRIOR_STD_OCTAVE      = 1.0f

    // half-tempo 체크 임계값 (일반 모드만 사용)
    private const val HALF_TEMPO_RATIO = 0.60f

    // 발라드 감지: ODF 활성 프레임 비율 + 저역 에너지 비율
    private const val BALLAD_ACTIVE_DENSITY_MAX = 0.25f  // ODF > 0.3 프레임 비율
    private const val BALLAD_LOW_RATIO_MAX      = 0.35f  // lowEnv / fullEnv 평균 비율

    // 발라드 모드 maxBeatMs (40 BPM)
    private const val BALLAD_MAX_BEAT_MS = 1500L

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
        val minBeatMs: Long         = 375L,
        val maxBeatMs: Long         = 1000L,
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

    // =========================================================================
    // Public entry point
    // =========================================================================

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

        // ── 1. Multi-band flux ODF ────────────────────────────────────────────
        val globalOdf = computeMultiBandFluxOdf(low, mid, full, params)

        // ── 2. 발라드 사전 분류 ───────────────────────────────────────────────
        val isBalladHint = preClassifyBallad(globalOdf, low, full)
        Log.d(TAG, "V2 isBalladHint=$isBalladHint durationMs=$durationMs")

        // ── 3. Dense BPM 추정 (발라드 여부에 따라 파라미터 조정) ─────────────
        val effectiveMaxBeatMs  = if (isBalladHint) BALLAD_MAX_BEAT_MS else params.maxBeatMs
        val effectivePriorMs    = if (isBalladHint) PRIOR_CENTER_BALLAD_MS else PRIOR_CENTER_MS
        val beatMs = estimateBpmDense(
            globalOdf, params.hopMs, params.minBeatMs, effectiveMaxBeatMs,
            priorCenterMs = effectivePriorMs, enableHalfTempoCheck = !isBalladHint
        ) ?: 500L
        Log.d(TAG, "V2 beatMs=$beatMs (${60_000L / beatMs} BPM) ballad=$isBalladHint")

        // ── 4. 위상 추정 (comb-phase) ─────────────────────────────────────────
        val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
        Log.d(TAG, "V2 phaseMs=$phaseMs")

        // ── 5. Global DP ──────────────────────────────────────────────────────
        val dpTimes = dpBeatTracker(globalOdf, beatMs, params.hopMs, durationMs, anchorMs = phaseMs)
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
            beats  = fallbackSegmentBeats(low, mid, full, params, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V2 detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        val timeSignature = detectTimeSignature(globalOdf, beatMs, params.hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, beatMs, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V2 OK beats=${beats.size} beatMs=$beatMs " +
            "timeSig=${timeSignature.type} reason=$reason ballad=$isBalladHint")

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
    // 발라드 사전 분류 (BPM 독립적)
    //
    // 느린 발라드 특징:
    //   ① ODF 활성 프레임이 적다 (긴 음표, 여백 많음)
    //   ② 저역(킥) 에너지 비율이 낮다 (베이스보다 멜로디 중심)
    // =========================================================================

    private fun preClassifyBallad(
        odf: List<Float>,
        lowEnv: List<Float>,
        fullEnv: List<Float>
    ): Boolean {
        val activeDensity = odf.count { it > 0.3f }.toFloat() / odf.size.coerceAtLeast(1)
        val lowAvg  = lowEnv.average().toFloat()
        val fullAvg = fullEnv.average().toFloat()
        val lowRatio = if (fullAvg > 1e-6f) lowAvg / fullAvg else 0f
        Log.d(TAG, "V2 preClassify: activeDensity=$activeDensity lowRatio=$lowRatio")
        return activeDensity < BALLAD_ACTIVE_DENSITY_MAX && lowRatio < BALLAD_LOW_RATIO_MAX
    }

    // =========================================================================
    // Dense BPM 추정 — librosa beat_track 방식
    // =========================================================================

    private fun estimateBpmDense(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long,
        priorCenterMs: Long = PRIOR_CENTER_MS,
        enableHalfTempoCheck: Boolean = true
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
            val logRatio = ln(lagMs.toFloat() / priorCenterMs) / ln(2f)
            val prior    = exp(-0.5f * (logRatio / PRIOR_STD_OCTAVE) * (logRatio / PRIOR_STD_OCTAVE))

            val score = acVal * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }

        if (bestLag <= 0) return null

        // half-tempo 체크: 발라드 모드에서는 비활성화
        if (enableHalfTempoCheck) {
            val halfLag = bestLag / 2
            if (halfLag >= minLag) {
                val halfAc = acVals[halfLag]
                val bestAc = acVals[bestLag]
                if (bestAc > 0f && halfAc / bestAc >= HALF_TEMPO_RATIO) {
                    val halfMs = halfLag * hopMs
                    Log.d(TAG, "V2 halfTempoCheck: ${bestLag*hopMs}ms(${60_000L/(bestLag*hopMs)}BPM)" +
                        " → ${halfMs}ms(${60_000L/halfMs}BPM)  ratio=${halfAc/bestAc}")
                    return halfMs
                }
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
    // Ellis DP Beat Tracker
    // =========================================================================

    private fun dpBeatTracker(
        odf: List<Float>,
        targetPeriodMs: Long,
        hopMs: Long,
        durationMs: Long,
        anchorMs: Long = 0L
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

        val result = beats.reversed().toLongArray()
        if (result.size < 2) return result
        val rms    = sqrt(localscore.map { it * it }.average().toFloat())
        val trimTh = 0.5f * rms
        var s = 0
        while (s < result.size && localscore[(result[s] / hopMs).toInt().coerceIn(0, n-1)] < trimTh) s++
        var e = result.size - 1
        while (e > s && localscore[(result[e] / hopMs).toInt().coerceIn(0, n-1)] < trimTh) e--
        return if (s > e) result else result.sliceArray(s..e)
    }

    // =========================================================================
    // Fallback — 세그먼트 단위 DP
    // =========================================================================

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

    // =========================================================================
    // Multi-band Positive Flux ODF
    // =========================================================================

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

    // =========================================================================
    // 박자표 감지
    // =========================================================================

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

    // =========================================================================
    // 다운비트 감지
    // =========================================================================

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

    // =========================================================================
    // ODF utilities
    // =========================================================================

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
