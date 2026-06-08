package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.*

/**
 * BeatDetectorV5 (= 버전 14)
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
object BeatDetectorV5 {

    private const val TAG = "AutoTimeline"

    private const val FILL_CONFIDENCE = 0.20f

    private const val LOCAL_NORM_WINDOW  = 60
    private const val GLOBAL_NORM_WINDOW = 80

    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f

    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB    = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    // DBN 템포 추정 파라미터 (dbnEstimateTempo 에서 사용)
    private const val DBN_TRANSITION_LAMBDA  = 100f  // 템포 변경 패널티 (높을수록 안정)
    private const val DBN_OBSERVATION_LAMBDA = 8     // 박자 존 폭 = 1/8 (madmom 기본값 1/16보다 넓게)

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

        // ── 2. DBN 템포 추정 (madmom DBNBeatTracker 방식) ────────────────────
        //    autocorrelation + prior 대신 HMM Forward 알고리즘으로 (tempo, phase) 동시 추정
        //    → 2×/0.5× BPM 오류를 구조적으로 방지
        val beatMs = dbnEstimateTempo(globalOdf, params.hopMs, params.minBeatMs, params.maxBeatMs,
                                      DBN_TRANSITION_LAMBDA, DBN_OBSERVATION_LAMBDA)
        Log.d(TAG, "V5 beatMs=$beatMs (${60_000L / beatMs} BPM) durationMs=$durationMs")

        // ── 3. 위상 추정 (comb-phase) — DP 앵커로 사용 ───────────────────────
        //    DP가 잘못된 위상에 수렴하는 문제(Dynamite 등) 방지
        val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
        Log.d(TAG, "V5 phaseMs=$phaseMs")

        // ── 4. Global DP (전곡 단위, 위상 앵커 주입) ─────────────────────────
        val dpTimes = dpBeatTracker(globalOdf, beatMs, params.hopMs, durationMs, anchorMs = phaseMs)
        Log.d(TAG, "V5 dpTimes=${dpTimes.size}")

        // DP 품질 검증
        val expectedBeats = max(1, (durationMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            Log.w(TAG, "V5 DP insufficient (${dpTimes.size}/$expectedBeats) → segment fallback")
            beats  = fallbackSegmentBeats(low, mid, full, params, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V5 detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        val timeSignature = detectTimeSignature(globalOdf, beatMs, params.hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, beatMs, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V5 OK beats=${beats.size} beatMs=$beatMs " +
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
    // DBN 템포 추정 — madmom DBNBeatTrackingProcessor 핵심 알고리즘 이식
    //
    // autocorrelation + prior 방식의 근본 문제인 2×/0.5× BPM 오류를
    // HMM Forward 알고리즘으로 구조적으로 해소한다.
    //
    // 상태 공간:
    //   각 후보 템포 interval i 에 대해 i 개의 위상 상태 (0..i-1) 존재
    //   전체 상태 수 = sum(minInterval..maxInterval) ≈ 1,100 (20ms hop 기준)
    //
    // 전이 모델:
    //   위상 내부: state(p, i) → state(p+1, i)  확률 1.0 (결정론적)
    //   박자 경계: state(last, i) → state(0, j)  ∝ exp(-λ × |j/i - 1|)
    //              → 템포 변경에 지수 패널티 → 2×/0.5× 점프 구조적 억제
    //
    // 관측 모델:
    //   박자 위치(p=0): log(odf[t])
    //   비박자 위치:    log((1-odf[t]) / (obsLambda-1))
    //
    // 복잡도: O(n × S) = O(12,000 × 1,100) ≈ 13M ops → 20ms 이하
    // =========================================================================

    private fun dbnEstimateTempo(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long,
        transitionLambda: Float = 100f,
        observationLambda: Int  = 8       // madmom default 16 → 우리 ODF 는 피크 폭이 넓어 8 사용
    ): Long {
        val minInterval = max(1, (minBeatMs / hopMs).toInt())
        val maxInterval = max(minInterval + 1, (maxBeatMs / hopMs).toInt())
        val intervals   = IntArray(maxInterval - minInterval + 1) { minInterval + it }
        val numIntv     = intervals.size
        if (numIntv == 0 || odf.size < minInterval * 2) return minBeatMs

        // ── 상태 공간 구축 ──────────────────────────────────────────────────
        val totalStates     = intervals.sum()
        val stateIntv       = IntArray(totalStates)
        val statePos        = IntArray(totalStates)
        val stateIntvIdx    = IntArray(totalStates)
        val intvStartState  = IntArray(numIntv)

        var s = 0
        for (ii in 0 until numIntv) {
            val intv = intervals[ii]
            intvStartState[ii] = s
            for (p in 0 until intv) {
                stateIntv[s]    = intv
                statePos[s]     = p
                stateIntvIdx[s] = ii
                s++
            }
        }

        // ── 박자 경계 로그 전이 확률 사전 계산 ─────────────────────────────
        val bbLogTrans = Array(numIntv) { fromII ->
            val fi  = intervals[fromII].toFloat()
            val raw = FloatArray(numIntv) { toII ->
                -transitionLambda * abs(intervals[toII].toFloat() / fi - 1f)
            }
            val maxR = raw.max()
            var sumE = 0.0
            for (v in raw) sumE += exp((v - maxR).toDouble())
            val logZ = maxR + ln(sumE.toFloat())
            FloatArray(numIntv) { toII -> raw[toII] - logZ }
        }

        // ── HMM Forward (Viterbi-max 근사, log 도메인) ──────────────────────
        val LOG_ZERO    = -1e9f
        var logFwd      = FloatArray(totalStates) { LOG_ZERO }
        val logInitUnif = -ln(numIntv.toFloat())
        for (ii in 0 until numIntv) logFwd[intvStartState[ii]] = logInitUnif

        val n = odf.size
        for (t in 0 until n) {
            val act        = odf[t].coerceIn(1e-6f, 1f - 1e-6f)
            val logBeat    = ln(act)
            val logNonBeat = ln((1f - act) / (observationLambda - 1).toFloat())

            // 각 interval 의 마지막 상태 log 확률 캐싱 (박자 경계 계산용)
            val lastLogFwd = FloatArray(numIntv) { ii ->
                logFwd[intvStartState[ii] + intervals[ii] - 1]
            }

            val logFwdNew = FloatArray(totalStates) { LOG_ZERO }
            for (st in 0 until totalStates) {
                val p      = statePos[st]
                val logObs = if (p == 0) logBeat else logNonBeat

                val logPrev = if (p > 0) {
                    logFwd[st - 1]
                } else {
                    val toII   = stateIntvIdx[st]
                    var maxVal = LOG_ZERO
                    for (fromII in 0 until numIntv) {
                        val cand = lastLogFwd[fromII] + bbLogTrans[fromII][toII]
                        if (cand > maxVal) maxVal = cand
                    }
                    maxVal
                }

                if (logPrev > LOG_ZERO) logFwdNew[st] = logPrev + logObs
            }

            // 언더플로 방지 정규화
            val peak = logFwdNew.max()
            if (peak > LOG_ZERO) for (i in logFwdNew.indices) {
                if (logFwdNew[i] > LOG_ZERO) logFwdNew[i] -= peak
            }
            logFwd = logFwdNew
        }

        // ── 최종 상태에서 최적 tempo 추출 ──────────────────────────────────
        val intvScore = FloatArray(numIntv)
        for (st in 0 until totalStates) {
            val ii = stateIntvIdx[st]
            if (logFwd[st] > intvScore[ii]) intvScore[ii] = logFwd[st]
        }
        var bestII = 0
        for (ii in 1 until numIntv) if (intvScore[ii] > intvScore[bestII]) bestII = ii
        val resultMs = intervals[bestII].toLong() * hopMs
        Log.d(TAG, "V5 dbnTempo: ${resultMs}ms (${60_000L / resultMs} BPM)")
        return resultMs
    }

    // =========================================================================
    // 위상 추정 — comb-phase scoring
    //
    // 모든 가능한 위상(0..fpb-1)에 대해 해당 위상으로 grid를 놓았을 때
    // ODF 합계를 계산하고 최대 위상을 반환한다.
    //
    // 이 함수로 얻은 phaseMs 를 DP 앵커로 사용하면 Dynamite 류의
    // "BPM은 맞지만 위상 완전 오류" 문제를 방지한다.
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
    // Ellis DP Beat Tracker — Gaussian local scoring + tightness=100 (V4 Rev3 유지)
    //
    // localscore[t] = conv(odf, gaussWin)[t]
    //   gaussWin[k] = exp(-0.5*(k*32/fpb)^2),  k ∈ [-fpb, fpb]
    //
    // cumscore[t] = max_p { cumscore[p] - 100*(ln(t-p) - ln(fpb))^2 } + localscore[t]
    //
    // edge trimming: localscore < 0.5*RMS 인 앞뒤 비트 제거 (librosa trim=True)
    // =========================================================================

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

        // ── Gaussian local scoring ──────────────────────────────────────────
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

        // ── DP ──────────────────────────────────────────────────────────────
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

        // ── Backtrack ────────────────────────────────────────────────────────
        var t     = cumscore.indices.maxByOrNull { cumscore[it] } ?: return LongArray(0)
        val beats = mutableListOf<Long>()
        var iter  = 0
        while (t > 0 && iter < n) {
            beats.add(t.toLong() * hopMs)
            val p = prev[t]
            if (p < 0 || p == t) break
            t = p; iter++
        }

        // ── Edge trimming (localscore < 0.5 * RMS) ──────────────────────────
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
    // Fallback — 세그먼트 단위 DP (전곡 DP 실패 시에만 사용)
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
    // Multi-band Positive Flux ODF (V4 Fix E 유지)
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
