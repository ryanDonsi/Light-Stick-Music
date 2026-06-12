package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * BeatDetectorV3
 *
 * BeatDetectorV2 기반 + 5가지 Fix:
 * Fix 1 — MIN_BEAT_MS / MAX_BEAT_MS 조정 (minBeatMs=430, maxBeatMs=1000)
 * Fix 2 — Comb Filter 옥타브 해소 (combFilterScore + resolveOctave)
 * Fix 3 — Multi-band Positive Flux ODF (computeMultiBandFluxOdf, 가중합 정규화)
 * Fix 4 — Adaptive Peak Threshold (computeAdaptiveThreshold + findPeaksAdaptive)
 * Fix 5 — Ellis DP Beat Tracker (dpBeatTracker, DP 기반 갭 채우기)
 */
object BeatDetectorV3 {

    private const val TAG = "AutoTimeline"

    private const val HARMONIC_FOLD_HALF_RATIO  = 0.45f
    private const val HARMONIC_FOLD_THIRD_RATIO = 0.35f
    private const val HARMONIC_DOUBLE_RATIO     = 0.80f
    private const val HARMONIC_TWO_THIRDS_RATIO = 0.75f
    private const val HARMONIC_FOLD_HALF_MIN_MS = 340L

    private const val LOCAL_NORM_WINDOW  = 60
    private const val GLOBAL_NORM_WINDOW = 80

    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f

    private const val CONTINUITY_MAX_RATIO = 0.12f

    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB   = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    private const val REALIGN_SNAP_MS = 80L

    private const val THIN_RATIO      = 0.55f
    private const val FILL_CONFIDENCE = 0.20f

    private const val WEAK_SIGNAL_FAIL_RATE = 0.60f

    private const val LOW_COVERAGE_TH = 0.15f

    // ④ confidence
    data class TimedBeat(val timeMs: Long, val confidence: Float)

    // ③ 박자표
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

    // Fix 1: minBeatMs=430L, maxBeatMs=1000L
    data class Params(
        val hopMs: Long             = 50L,
        val minBeatMs: Long         = 430L,
        val maxBeatMs: Long         = 1000L,
        val minPeakDistanceMs: Long = 140L,
        val onsetSmoothWindow: Int  = 3,
        val segmentMs: Long         = 20_000L,
        val peakThresholdK: Float   = 0.22f,
        val minPeakAbs: Float       = 0.04f,
        val snapToleranceMs: Long   = 150L,
        val chainToleranceMs: Long  = 170L,
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
        val debugSegments: List<SegmentResult>
    ) {
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }
    }

    data class SegmentResult(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val selectedSource: BeatSource?,
        val timedBeats: List<TimedBeat>,
        val beatMs: Long,
        val score: Float,
        val reason: String,
        val trials: List<TrialResult>
    ) {
        val beatTimesMs: List<Long> get() = timedBeats.map { it.timeMs }
    }

    data class TrialResult(
        val source: BeatSource,
        val timedBeats: List<TimedBeat>,
        val beatMs: Long,
        val score: Float,
        val rawPeakCount: Int,
        val snappedCount: Int,
        val onsetMean: Float,
        val onsetStd: Float,
        val onsetMax: Float,
        val acPeak: Float,
        val snapRatio: Float,
        val reason: String
    ) {
        val beatTimesMs: List<Long> get() = timedBeats.map { it.timeMs }
    }

    private data class SegLoopResult(
        val segResults: List<SegmentResult>,
        val mergedBeats: List<TimedBeat>,
        val sourceVotes: Map<BeatSource, Int>
    )

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
            return DetectResult(emptyList(), 0L, null, "empty env", 0L, TimeSignature.FOUR_FOUR, emptyList())
        }

        val minSize    = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        val low        = lowEnv.take(minSize)
        val mid        = midEnv.take(minSize)
        val full       = fullEnv.take(minSize)
        val durationMs = minSize * params.hopMs

        val globalBeatMs = estimateGlobalBpm(low, mid, full, params)
        Log.d(TAG, "V3 globalBeatMs=$globalBeatMs durationMs=$durationMs")

        val effectiveSegmentMs = if (durationMs < 60_000L) durationMs else params.segmentMs
        val segmentFrames      = max(1, (effectiveSegmentMs / params.hopMs).toInt())

        Log.d(TAG, "V3 segmentMs=$effectiveSegmentMs minBeatMs=${params.minBeatMs} maxBeatMs=${params.maxBeatMs}")

        var loop = runSegmentLoop(low, mid, full, globalBeatMs, params, segmentFrames, minSize)

        val failCount = loop.segResults.count { it.selectedSource == null }
        val totalSegs = loop.segResults.size
        if (totalSegs > 1 && failCount.toFloat() / totalSegs > WEAK_SIGNAL_FAIL_RATE) {
            Log.d(TAG, "V3 weak signal ($failCount/$totalSegs segs FAIL) → retry relaxed params")
            val relaxed = params.copy(
                peakThresholdK   = params.peakThresholdK   * 0.65f,
                minPeakAbs       = params.minPeakAbs        * 0.60f,
                chainToleranceMs = (params.chainToleranceMs * 1.30f).toLong(),
                minChainCount    = maxOf(2, params.minChainCount - 1)
            )
            loop = runSegmentLoop(low, mid, full, globalBeatMs, relaxed, segmentFrames, minSize)
        }

        val dedupedBeats = dedupeCloseTimedBeats(loop.mergedBeats.sortedBy { it.timeMs }, params.minPeakDistanceMs)
        if (dedupedBeats.isEmpty()) {
            Log.w(TAG, "V3 beat detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all segments failed", 0L, TimeSignature.FOUR_FOUR, loop.segResults)
        }

        val rawBeatMs   = estimateMedianInterval(dedupedBeats.map { it.timeMs }, params.minBeatMs, params.maxBeatMs, params.hopMs)
        val finalBeatMs: Long = when {
            globalBeatMs == null -> rawBeatMs
            kotlin.math.abs(globalBeatMs - rawBeatMs * 2L) < 100L -> {
                Log.d(TAG, "V3 finalBeatMs: global($globalBeatMs) ≈ 2×raw($rawBeatMs) → raw 채택 (global half-time)")
                rawBeatMs
            }
            kotlin.math.abs(rawBeatMs - globalBeatMs * 2L) < 100L -> {
                Log.d(TAG, "V3 finalBeatMs: raw($rawBeatMs) ≈ 2×global($globalBeatMs) → global 채택 (raw half-time)")
                globalBeatMs
            }
            else -> globalBeatMs
        }
        val finalSource = loop.sourceVotes.maxByOrNull { it.value }?.key

        // Fix 3+5: 글로벌 멀티밴드 ODF (Ellis DP + 박자표 감지 공용)
        val globalOdf  = computeMultiBandFluxOdf(low, mid, full, params)

        // Fix 5: Ellis DP로 최적 비트 경로 계산
        val dpTimes    = dpBeatTracker(globalOdf, finalBeatMs, params.hopMs, durationMs)

        // DP 결과와 실제 감지 비트를 병합: 실제 비트 우선, DP는 갭 채움
        // 버킷 단위(finalBeatMs/4)로 반올림해 같은 버킷이면 높은 confidence만 유지 → 50ms 아티팩트 제거
        val bucketMs    = (finalBeatMs / 4L).coerceAtLeast(1L)
        val bucketMap   = LinkedHashMap<Long, TimedBeat>()
        for (t in dpTimes) {
            val bucket = t / bucketMs
            val existing = bucketMap[bucket]
            if (existing == null || FILL_CONFIDENCE > existing.confidence) {
                bucketMap[bucket] = TimedBeat(t, FILL_CONFIDENCE)
            }
        }
        for (b in dedupedBeats) {
            val bucket = b.timeMs / bucketMs
            val existing = bucketMap[bucket]
            if (existing == null || b.confidence > existing.confidence) {
                bucketMap[bucket] = b   // 실제 감지 우선
            }
        }
        val mergedSorted = bucketMap.values.sortedBy { it.timeMs }

        // 최소 간격(finalBeatMs * 0.45)보다 가까운 중복 제거
        val minMergeGapMs = (finalBeatMs * 0.45f).toLong()
        val filledBeats = ArrayList<TimedBeat>(mergedSorted.size)
        for (b in mergedSorted) {
            val last = filledBeats.lastOrNull()
            when {
                last == null -> filledBeats += b
                b.timeMs - last.timeMs >= minMergeGapMs -> filledBeats += b
                b.confidence > last.confidence -> filledBeats[filledBeats.lastIndex] = b
            }
        }

        // 곡 앞/뒤 빈 구간 채우기
        val firstTime    = filledBeats.firstOrNull()?.timeMs ?: 0L
        val initialFills = ArrayList<TimedBeat>()
        var tStart = firstTime - finalBeatMs
        while (tStart >= 0) { initialFills.add(TimedBeat(tStart, FILL_CONFIDENCE)); tStart -= finalBeatMs }
        initialFills.reverse()

        val withHead  = initialFills + filledBeats
        val lastTime  = withHead.lastOrNull()?.timeMs ?: 0L
        val tailFills = ArrayList<TimedBeat>()
        var tEnd = lastTime + finalBeatMs
        while (tEnd <= durationMs) { tailFills.add(TimedBeat(tEnd, FILL_CONFIDENCE)); tEnd += finalBeatMs }

        // 중간 대형 갭(> finalBeatMs * 1.5) 그리드 채우기
        val withTail     = withHead + tailFills
        val gapThreshMs  = (finalBeatMs * 1.5f).toLong()
        val completeList = ArrayList<TimedBeat>(withTail.size + 32)
        completeList += withTail.first()
        for (i in 1 until withTail.size) {
            val prev = withTail[i - 1]
            val cur  = withTail[i]
            var gapT = prev.timeMs + finalBeatMs
            while (cur.timeMs - gapT > gapThreshMs / 2) {
                completeList += TimedBeat(gapT, FILL_CONFIDENCE)
                gapT += finalBeatMs
            }
            completeList += cur
        }
        val completeBeats = completeList

        val timeSignature = detectTimeSignature(globalOdf, finalBeatMs, params.hopMs)
        Log.d(TAG, "V3 timeSignature=${timeSignature.type}")

        val downbeatMs = detectDownbeatEnhanced(
            completeBeats.map { it.timeMs }, low, finalBeatMs, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (completeBeats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V3 detect OK source=$finalSource beats=${completeBeats.size} " +
            "beatMs=$finalBeatMs timeSignature=${timeSignature.type} downbeatOffset=${downbeatOffsetMs}ms " +
            "first=${completeBeats.firstOrNull()?.timeMs} last=${completeBeats.lastOrNull()?.timeMs}")

        return DetectResult(
            beats            = completeBeats,
            beatMs           = finalBeatMs,
            source           = finalSource,
            reason           = "ok",
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature,
            debugSegments    = loop.segResults
        )
    }

    // =========================================================================
    // 세그먼트 루프 — detect() 에서 분리된 내부 헬퍼
    // =========================================================================

    private fun runSegmentLoop(
        low: List<Float>,
        mid: List<Float>,
        full: List<Float>,
        globalBeatMs: Long?,
        params: Params,
        segmentFrames: Int,
        minSize: Int
    ): SegLoopResult {
        val segmentCount = (minSize + segmentFrames - 1) / segmentFrames
        val segResults   = ArrayList<SegmentResult>()
        val mergedBeats  = ArrayList<TimedBeat>()
        val sourceVotes  = LinkedHashMap<BeatSource, Int>()
        var prevBeatMs: Long? = null

        for (segIndex in 0 until segmentCount) {
            val s = segIndex * segmentFrames
            val e = min(minSize, s + segmentFrames)
            if (e - s < 8) continue

            val lowSeg     = low.subList(s, e)
            val midSeg     = mid.subList(s, e)
            val fullSeg    = full.subList(s, e)
            val segStartMs = s.toLong() * params.hopMs

            val srcOrder = buildSourceOrder(lowSeg, midSeg, fullSeg)
            Log.d(TAG, "SEG[$segIndex] srcOrder=${srcOrder.joinToString(",")}")
            val trials   = ArrayList<TrialResult>()
            val okTrials = ArrayList<TrialResult>()

            for (src in srcOrder) {
                val trial = detectSingleSource(
                    segIndex, src,
                    combineSource(src, lowSeg, midSeg, fullSeg),
                    globalBeatMs, params
                )
                trials += trial
                Log.d(TAG,
                    "SEG[$segIndex] try=${trial.source} beats=${trial.timedBeats.size} " +
                    "beatMs=${trial.beatMs} score=${fmt(trial.score)} " +
                    "acPeak=${fmt(trial.acPeak)} snapRatio=${fmt(trial.snapRatio)} " +
                    "reason=${trial.reason}")
                if (trial.reason == "ok") okTrials += trial
            }

            val best = selectBestWithContinuity(okTrials, prevBeatMs, params.continuityBonus)

            if (best == null) {
                Log.w(TAG, "SEG[$segIndex] FAIL → skip")
                if (globalBeatMs != null && globalBeatMs > 0L && prevBeatMs != null) {
                    val segEndMs = e.toLong() * params.hopMs
                    val lastMs   = mergedBeats.lastOrNull()?.timeMs ?: segStartMs
                    var t = lastMs + globalBeatMs
                    while (t < segEndMs) {
                        mergedBeats.add(TimedBeat(t, FILL_CONFIDENCE))
                        t += globalBeatMs
                    }
                    Log.d(TAG, "SEG[$segIndex] FAIL gap-fill: globalBeatMs=${globalBeatMs}ms phantom 삽입")
                }
                segResults += SegmentResult(segIndex, segStartMs,
                    e.toLong() * params.hopMs, null, emptyList(), 0L, 0f, "all failed", trials)
                continue
            }

            Log.d(TAG, "SEG[$segIndex] WINNER=${best.source} beatMs=${best.beatMs} " +
                "beats=${best.timedBeats.size} score=${fmt(best.score)}")

            val absBeats    = best.timedBeats.map { it.copy(timeMs = it.timeMs + segStartMs) }
            val halfBeatMs  = best.beatMs / 2

            var onBeatEnergy = 0f; var offBeatEnergy = 0f
            var onCount = 0; var offCount = 0
            for (beat in absBeats) {
                val fOn  = (beat.timeMs / params.hopMs).toInt().coerceIn(0, low.lastIndex)
                val fOff = ((beat.timeMs + halfBeatMs) / params.hopMs).toInt().coerceIn(0, low.lastIndex)
                onBeatEnergy  += low[fOn];  onCount++
                offBeatEnergy += low[fOff]; offCount++
            }
            val avgOn  = if (onCount  > 0) onBeatEnergy  / onCount  else 0f
            val avgOff = if (offCount > 0) offBeatEnergy / offCount else 0f

            val correctedBeats = if (avgOff > avgOn * 1.15f) {
                Log.d(TAG, "SEG[$segIndex] Inversion Detected! avgOff=${fmt(avgOff)} > avgOn=${fmt(avgOn)} → shift +${halfBeatMs}ms")
                absBeats.map { it.copy(timeMs = it.timeMs + halfBeatMs) }
            } else {
                absBeats
            }

            mergedBeats += correctedBeats
            sourceVotes[best.source] = (sourceVotes[best.source] ?: 0) + 1
            prevBeatMs = best.beatMs

            segResults += SegmentResult(segIndex, segStartMs, e.toLong() * params.hopMs,
                best.source, correctedBeats, best.beatMs, best.score, best.reason, trials)
        }

        return SegLoopResult(segResults, mergedBeats, sourceVotes)
    }

    // =========================================================================
    // Fix 2: Comb Filter 옥타브 해소
    // =========================================================================

    private fun combFilterScore(odf: List<Float>, periodMs: Long, hopMs: Long): Float {
        val period = (periodMs / hopMs).toInt().coerceAtLeast(1)
        var score = 0f; var count = 0
        for (tap in 1..4) {
            val lag = period * tap
            if (lag >= odf.size) break
            for (i in 0 until odf.size - lag) { score += odf[i] * odf[i + lag]; count++ }
        }
        return if (count > 0) score / count else 0f
    }

    private fun resolveOctave(odf: List<Float>, beatMs: Long, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): Long {
        val half   = beatMs * 2L
        val double = beatMs / 2L
        val sCurrent = combFilterScore(odf, beatMs, hopMs)
        val sHalf    = if (half   <= maxBeatMs) combFilterScore(odf, half,   hopMs) else 0f
        val sDouble  = if (double >= minBeatMs) combFilterScore(odf, double, hopMs) else 0f
        return when {
            sHalf   > sCurrent * 0.82f -> { Log.d(TAG, "V3 resolveOctave: ${beatMs}ms → ${half}ms (half)");   half   }
            sDouble > sCurrent * 1.25f -> { Log.d(TAG, "V3 resolveOctave: ${beatMs}ms → ${double}ms (double)"); double }
            else                       -> beatMs
        }
    }

    // =========================================================================
    // Fix 3: Multi-band Positive Flux ODF
    // =========================================================================

    private fun computeMultiBandFluxOdf(
        low: List<Float>, mid: List<Float>, full: List<Float>, params: Params
    ): List<Float> {
        val n = minOf(low.size, mid.size, full.size)
        val lowFlux  = computeOdf(low.take(n),  params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val midFlux  = computeOdf(mid.take(n),  params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val fullFlux = computeOdf(full.take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        // 킥(LOW) 1.4, 스네어(MID) 1.2, 전체(FULL) 0.8 가중합 → 정규화
        val combined = ArrayList<Float>(n)
        for (i in 0 until n) {
            combined += lowFlux[i] * 1.4f + midFlux[i] * 1.2f + fullFlux[i] * 0.8f
        }
        return localNormalize(combined, GLOBAL_NORM_WINDOW)
    }

    // =========================================================================
    // Fix 2: estimateGlobalBpm — autocorrelation 결과에 resolveOctave 적용
    // =========================================================================

    private fun estimateGlobalBpm(low: List<Float>, mid: List<Float>, full: List<Float>, params: Params): Long? {
        val odf = computeMultiBandFluxOdf(low, mid, full, params)
        val raw = autoCorrelateBeat(odf, params.hopMs, params.minBeatMs, params.maxBeatMs)?.first ?: return null
        return resolveOctave(odf, raw, params.hopMs, params.minBeatMs, params.maxBeatMs)
    }

    /**
     * 전곡 ODF에서 비트 위상(phase)을 추정한다.
     */
    private fun estimatePhaseFromOdf(
        onset: List<Float>,
        beatMs: Long,
        hopMs: Long
    ): Long {
        val beatFrames = max(1, (beatMs / hopMs).toInt())
        if (onset.size < beatFrames * 2) return 0L

        var bestPhase = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (ph in 0 until beatFrames) {
            var score = 0.0
            var f = ph
            while (f < onset.size) {
                score += onset[f]
                f += beatFrames
            }
            if (score > bestScore) {
                bestScore = score
                bestPhase = ph
            }
        }

        return bestPhase.toLong() * hopMs
    }

    // =========================================================================
    // ③ 박자표 감지 (Time Signature)
    // =========================================================================

    private fun detectTimeSignature(onset: List<Float>, beatMs: Long, hopMs: Long): TimeSignature {
        if (onset.size < 8 || beatMs <= 0L) return TimeSignature.FOUR_FOUR
        val bf    = (beatMs / hopMs).toInt().coerceAtLeast(1)
        val corr3 = lagCorr(onset, bf * 3)
        val corr4 = lagCorr(onset, bf * 4)
        val corr6 = lagCorr(onset, bf * 6)
        Log.d(TAG, "V3 timeSig corr3=${fmt(corr3)} corr4=${fmt(corr4)} corr6=${fmt(corr6)}")
        return when {
            corr3 > corr4 * TIME_SIG_THREE_RATIO                            -> TimeSignature.THREE_FOUR
            corr6 > corr4 * TIME_SIG_SIX_RATIO && corr3 > corr4 * 0.85f   -> TimeSignature.SIX_EIGHT
            else                                                             -> TimeSignature.FOUR_FOUR
        }
    }

    private fun lagCorr(onset: List<Float>, lag: Int): Float {
        if (lag <= 0 || lag >= onset.size) return 0f
        var sum = 0f; var i = 0
        while (i + lag < onset.size) { sum += onset[i] * onset[i + lag]; i++ }
        return sum / i.toFloat().coerceAtLeast(1f)
    }

    // =========================================================================
    // ① 다운비트 감지 — 3가지 Factor 결합
    // =========================================================================

    private fun detectDownbeatEnhanced(
        beatTimesMs: List<Long>,
        lowEnv: List<Float>,
        beatMs: Long,
        beatsPerBar: Int,
        hopMs: Long
    ): Long {
        if (beatTimesMs.isEmpty() || beatMs <= 0L) return 0L
        if (beatTimesMs.size < beatsPerBar) return beatTimesMs.first()

        val barFrames = ((beatMs * beatsPerBar) / hopMs).toInt().coerceAtLeast(1)

        val phaseSum   = FloatArray(beatsPerBar)
        val phaseCnt   = IntArray(beatsPerBar)
        for (i in beatTimesMs.indices) {
            val ph = i % beatsPerBar
            val fr = (beatTimesMs[i] / hopMs).toInt().coerceIn(0, lowEnv.lastIndex)
            phaseSum[ph] += lowEnv[fr]
            phaseCnt[ph]++
        }
        val avgEnergy = FloatArray(beatsPerBar) { p ->
            if (phaseCnt[p] > 0) phaseSum[p] / phaseCnt[p] else 0f
        }

        val combScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val anchor = (beatTimesMs.getOrElse(ph) { ph.toLong() * beatMs } / hopMs).toInt()
            var k = anchor; var sum = 0f; var cnt = 0
            while (k < lowEnv.size) {
                sum += lowEnv[k.coerceIn(0, lowEnv.lastIndex)]; cnt++; k += barFrames
            }
            k = anchor - barFrames
            while (k >= 0) {
                sum += lowEnv[k.coerceIn(0, lowEnv.lastIndex)]; cnt++; k -= barFrames
            }
            combScore[ph] = if (cnt > 0) sum / cnt else 0f
        }

        val consistScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val energies = beatTimesMs
                .filterIndexed { i, _ -> i % beatsPerBar == ph }
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
        val nEnergy  = avgEnergy.normMax()
        val nComb    = combScore.normMax()
        val nConsist = consistScore.normMax()

        val bestPhase = (0 until beatsPerBar).maxByOrNull { p ->
            nEnergy[p]  * DOWNBEAT_W_LOW_ENERGY  +
            nComb[p]    * DOWNBEAT_W_BAR_COMB    +
            nConsist[p] * DOWNBEAT_W_CONSISTENCY
        } ?: 0

        Log.d(TAG,
            "V3 downbeat avgE=[${avgEnergy.joinToString { fmt(it) }}] " +
            "comb=[${combScore.joinToString { fmt(it) }}] " +
            "consist=[${consistScore.joinToString { fmt(it) }}] " +
            "bestPhase=$bestPhase")

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }

    // =========================================================================
    // ② 비트 그리드 재정렬 — 다운비트 앵커 정박자 스냅
    // =========================================================================

    private fun realignBeatsToGrid(
        beats: List<TimedBeat>,
        downbeatMs: Long,
        beatMs: Long
    ): List<TimedBeat> {
        if (beats.isEmpty() || beatMs <= 0L) return beats

        val result = beats.mapNotNull { beat ->
            val offset        = (beat.timeMs - downbeatMs).toDouble()
            val nearestStep   = (offset / beatMs.toDouble()).roundToLong()
            val nearestGridMs = downbeatMs + nearestStep * beatMs

            if (abs(beat.timeMs - nearestGridMs) <= REALIGN_SNAP_MS) {
                beat.copy(timeMs = nearestGridMs)
            } else {
                null
            }
        }.distinctBy { it.timeMs }.sortedBy { it.timeMs }

        val dropped = beats.size - result.size
        Log.d(TAG, "V3 realignBeatsToGrid: in=${beats.size} kept=${result.size} " +
            "dropped=$dropped snapMs=${REALIGN_SNAP_MS}ms downbeat=${downbeatMs}ms")
        return result
    }

    // =========================================================================
    // ⑥ 갭 채우기 + 밀도 축소 후처리
    // =========================================================================

    private fun normalizeBeats(
        beats: List<TimedBeat>,
        beatMs: Long,
        downbeatMs: Long,
        durationMs: Long
    ): List<TimedBeat> {
        if (beatMs <= 0L) return beats

        val thinThresholdMs = (beatMs * THIN_RATIO).toLong()

        val thinned = ArrayList<TimedBeat>(beats.size)
        for (beat in beats) {
            val last = thinned.lastOrNull()
            when {
                last == null -> thinned += beat
                beat.timeMs - last.timeMs >= thinThresholdMs -> thinned += beat
                beat.confidence > last.confidence -> thinned[thinned.lastIndex] = beat
            }
        }

        val phase     = ((downbeatMs % beatMs) + beatMs) % beatMs
        val realTimes = LongArray(thinned.size) { thinned[it].timeMs }.also { it.sort() }
        val snapMs    = REALIGN_SNAP_MS

        val filled = ArrayList<TimedBeat>(thinned)
        var t = phase
        while (t <= durationMs) {
            if (!hasNearbyBeat(realTimes, t, snapMs)) {
                filled += TimedBeat(t, FILL_CONFIDENCE)
            }
            t += beatMs
        }

        val minFinalGapMs = (beatMs * 0.75f).toLong()
        val cleaned = ArrayList<TimedBeat>(filled.size)
        for (beat in filled.sortedBy { it.timeMs }) {
            val last = cleaned.lastOrNull()
            when {
                last == null                                 -> cleaned += beat
                beat.timeMs - last.timeMs >= minFinalGapMs  -> cleaned += beat
                beat.confidence > last.confidence           -> cleaned[cleaned.lastIndex] = beat
            }
        }

        val thinCount  = beats.size - thinned.size
        val fillCount  = filled.size - thinned.size
        val cleanCount = filled.size - cleaned.size
        val realCount  = thinned.size
        val synthRatio = if (realCount + fillCount > 0) fillCount * 100 / (realCount + fillCount) else 0
        Log.d(TAG, "V3 normalizeBeats: original=${beats.size} " +
                "thinned=$thinCount filled=$fillCount cleaned=$cleanCount " +
                "final=${cleaned.size} real=$realCount synth=$fillCount(${synthRatio}%) " +
                "phase=${phase}ms beatMs=${beatMs}ms")

        return cleaned
    }

    private fun hasNearbyBeat(sortedTimes: LongArray, t: Long, snapMs: Long): Boolean {
        var lo = 0; var hi = sortedTimes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v   = sortedTimes[mid]
            when {
                v < t - snapMs -> lo = mid + 1
                v > t + snapMs -> hi = mid - 1
                else           -> return true
            }
        }
        return false
    }

    // =========================================================================
    // Fix 5: Ellis DP Beat Tracker
    // =========================================================================

    private fun dpBeatTracker(
        odf: List<Float>,
        targetPeriodMs: Long,
        hopMs: Long,
        durationMs: Long
    ): LongArray {
        if (odf.isEmpty() || targetPeriodMs <= 0L) return LongArray(0)
        val n = odf.size
        val targetFrames = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
        val alpha = 0.9f

        val score = FloatArray(n) { Float.NEGATIVE_INFINITY }
        val prev  = IntArray(n)  { -1 }

        val initEnd = (targetFrames * 2).coerceAtMost(n - 1)
        for (i in 0..initEnd) score[i] = odf[i]

        for (t in 1 until n) {
            val searchLo = max(0, t - (targetFrames * 2.0f).toInt())
            val searchHi = max(0, t - (targetFrames * 0.5f).toInt())
            for (p in searchLo..searchHi) {
                if (score[p] == Float.NEGATIVE_INFINITY) continue
                val lag = (t - p).toFloat()
                val logRatio = ln(lag / targetFrames)
                val penalty  = alpha * logRatio * logRatio
                val cand     = score[p] - penalty
                if (cand > score[t]) { score[t] = cand; prev[t] = p }
            }
            if (score[t] != Float.NEGATIVE_INFINITY) score[t] += odf[t]
        }

        // Backtrack
        var t = score.indices.maxByOrNull { score[it] } ?: return LongArray(0)
        val beats = mutableListOf<Long>()
        var iter  = 0
        while (t >= 0 && iter < n) {
            beats.add(t.toLong() * hopMs)
            val p = prev[t]; if (p < 0 || p == t) break; t = p; iter++
        }
        return beats.reversed().toLongArray()
    }

    // =========================================================================
    // ⑤ Multi-Hypothesis 연속성 선택
    // =========================================================================

    private fun selectBestWithContinuity(
        okTrials: List<TrialResult>,
        prevBeatMs: Long?,
        continuityBonus: Float
    ): TrialResult? {
        if (okTrials.isEmpty()) return null
        if (okTrials.size == 1 || prevBeatMs == null || prevBeatMs <= 0L)
            return okTrials.maxByOrNull { it.score }

        return okTrials.maxByOrNull { trial ->
            val bonus = if (trial.beatMs > 0L &&
                abs(trial.beatMs - prevBeatMs).toFloat() / prevBeatMs.toFloat() <= CONTINUITY_MAX_RATIO
            ) continuityBonus else 0f
            trial.score + bonus
        }
    }

    // =========================================================================
    // Single source detection — Fix 4: Adaptive Peak Threshold 적용
    // =========================================================================

    private fun detectSingleSource(
        segmentIndex: Int,
        source: BeatSource,
        env: List<Float>,
        globalBeatMs: Long?,
        params: Params
    ): TrialResult {
        if (env.size < 8)
            return TrialResult(source, emptyList(), 0L, 0f, 0, 0, 0f, 0f, 0f, 0f, 0f, "env too short")

        val onset    = computeOdf(env, params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val mean     = meanOf(onset)
        val std      = stdOf(onset, mean)
        val onsetMax = maxOfList(onset)

        // Fix 4: Adaptive Peak Threshold
        val minPeakFrames = max(1, (params.minPeakDistanceMs / params.hopMs).toInt())
        val adaptiveTh    = computeAdaptiveThreshold(onset, windowFrames = 40, minAbs = params.minPeakAbs)
        val rawPeaks      = findPeaksAdaptive(onset, adaptiveTh, minPeakFrames)

        Log.d(TAG, "SEG[$segmentIndex][$source] mean=${fmt(mean)} std=${fmt(std)} " +
            "max=${fmt(onsetMax)} rawPeaks=${rawPeaks.size}")

        val acResult = autoCorrelateBeat(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)

        val beatMs: Long
        val acPeak: Float
        when {
            acResult != null -> { beatMs = acResult.first; acPeak = acResult.second }
            globalBeatMs != null -> {
                beatMs = globalBeatMs; acPeak = 0.5f
                Log.d(TAG, "SEG[$segmentIndex] autocorr weak → globalBeatMs=$globalBeatMs fallback")
            }
            else -> {
                val fallbackMs = rawPeakMedianInterval(rawPeaks, params)
                if (fallbackMs != null) {
                    val snappedFb = snapPeaksToGrid(rawPeaks, onset, fallbackMs, params.hopMs, params.snapToleranceMs)
                    val chainedFb = keepConsistentChain(snappedFb, fallbackMs, params.hopMs, params.chainToleranceMs)
                    if (chainedFb.size >= 2) {
                        val snapRatioFb = chainedFb.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
                        val segDur      = onset.size.toLong() * params.hopMs
                        val expectedFb  = max(1, (segDur / fallbackMs).toInt())
                        val densityFb   = min(1f, chainedFb.size.toFloat() / expectedFb.toFloat())
                        val scoreFb     = (densityFb * 0.35f + snapRatioFb * 0.30f + 0.10f).coerceIn(0f, 1f)
                        val timedFb = chainedFb.map { fr ->
                            TimedBeat(fr.toLong() * params.hopMs,
                                (onset.getOrElse(fr) { 0f } * 0.6f).coerceIn(0f, 1f))
                        }
                        return TrialResult(source, timedFb, fallbackMs, scoreFb,
                            rawPeaks.size, chainedFb.size, mean, std, onsetMax, 0f, snapRatioFb, "onset-fallback")
                    }
                }
                return TrialResult(source, emptyList(), 0L, 0.08f,
                    rawPeaks.size, 0, mean, std, onsetMax, 0f, 0f, "autocorr weak")
            }
        }

        val snapped = snapPeaksToGrid(rawPeaks, onset, beatMs, params.hopMs, params.snapToleranceMs)
        if (snapped.isEmpty()) {
            return TrialResult(source, emptyList(), beatMs, 0.1f + acPeak * 0.5f,
                rawPeaks.size, 0, mean, std, onsetMax, acPeak, 0f, "snap empty")
        }

        val chained = keepConsistentChain(snapped, beatMs, params.hopMs, params.chainToleranceMs)
        val segDurationMs     = onset.size.toLong() * params.hopMs
        val effectiveMinChain = if (segDurationMs < 15_000L) 2 else params.minChainCount

        if (chained.size < effectiveMinChain) {
            val snapRatio = chained.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
            return TrialResult(source, emptyList(), beatMs,
                0.2f + acPeak * 0.5f + snapRatio * 0.1f,
                rawPeaks.size, chained.size, mean, std, onsetMax, acPeak, snapRatio, "chain too short")
        }

        val timedBeats = chained.map { frame ->
            TimedBeat(frame.toLong() * params.hopMs,
                onset.getOrElse(frame) { 0f }.coerceIn(0f, 1f))
        }

        val snapRatio     = chained.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
        val expectedBeats = max(1, (segDurationMs / beatMs).toInt())
        val densityScore  = min(1f, timedBeats.size.toFloat() / expectedBeats.toFloat())
        val score = (densityScore * 0.40f + snapRatio * 0.30f +
                     acPeak * 0.20f + min(1f, onsetMax) * 0.10f).coerceIn(0f, 1f)

        return TrialResult(source, timedBeats, beatMs, score,
            rawPeaks.size, chained.size, mean, std, onsetMax, acPeak, snapRatio, "ok")
    }

    // =========================================================================
    // Fix 4: Adaptive Threshold + findPeaksAdaptive
    // =========================================================================

    private fun computeAdaptiveThreshold(odf: List<Float>, windowFrames: Int = 40, minAbs: Float = 0.04f): FloatArray {
        val th = FloatArray(odf.size)
        for (i in odf.indices) {
            val lo = max(0, i - windowFrames); val hi = min(odf.lastIndex, i + windowFrames)
            var localMean = 0f; var localMax = 0f; var cnt = 0
            for (j in lo..hi) { localMean += odf[j]; if (odf[j] > localMax) localMax = odf[j]; cnt++ }
            localMean = if (cnt > 0) localMean / cnt else 0f
            th[i] = max(minAbs, max(localMean * 1.5f, localMax * 0.30f))
        }
        return th
    }

    private fun findPeaksAdaptive(src: List<Float>, threshold: FloatArray, minDistance: Int): List<Int> {
        if (src.size < 3) return emptyList()
        val peaks = ArrayList<Int>(); var lastAccepted = -minDistance * 2
        for (i in 1 until src.lastIndex) {
            val c = src[i]; if (c < threshold[i]) continue
            if (c < src[i - 1] || c < src[i + 1]) continue
            if (i - lastAccepted < minDistance) {
                if (peaks.isNotEmpty() && c > src[peaks.last()]) { peaks[peaks.lastIndex] = i; lastAccepted = i }
            } else { peaks += i; lastAccepted = i }
        }
        return peaks
    }

    // =========================================================================
    // 소스 우선순위
    // =========================================================================

    private fun buildSourceOrder(low: List<Float>, mid: List<Float>, full: List<Float>): List<BeatSource> {
        val lowVar  = varOf(low)
        val midVar  = varOf(mid)
        val fullVar = varOf(full)
        val BASS_BONUS = 0.003f
        val scored = listOf(
            BeatSource.LOW      to (lowVar + BASS_BONUS),
            BeatSource.LOW_MID  to ((lowVar + midVar) * 0.5f + min(lowVar, midVar) * 0.2f + BASS_BONUS),
            BeatSource.MID      to midVar,
            BeatSource.FULL     to fullVar,
            BeatSource.MID_FULL to ((midVar + fullVar) * 0.5f + min(midVar, fullVar) * 0.2f),
            BeatSource.LOW_FULL to ((lowVar + fullVar) * 0.5f + min(lowVar, fullVar) * 0.2f)
        ).sortedByDescending { it.second }
        return scored.map { it.first }
    }

    private fun combineSource(src: BeatSource, low: List<Float>, mid: List<Float>, full: List<Float>): List<Float> =
        when (src) {
            BeatSource.LOW      -> low
            BeatSource.MID      -> mid
            BeatSource.FULL     -> full
            BeatSource.LOW_MID  -> mix(low, mid,  0.55f, 0.45f)
            BeatSource.MID_FULL -> mix(mid, full, 0.60f, 0.40f)
            BeatSource.LOW_FULL -> mix(low, full, 0.60f, 0.40f)
        }

    // =========================================================================
    // Autocorrelation + harmonic folding
    // =========================================================================

    private fun autoCorrelateBeat(
        onset: List<Float>, hopMs: Long, minBeatMs: Long, maxBeatMs: Long
    ): Pair<Long, Float>? {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt())
        if (onset.size <= maxLag + 2) return null

        val corrArray = FloatArray(maxLag + 1)
        var bestLag = -1; var bestValue = 0f; var secondValue = 0f

        for (lag in minLag..maxLag) {
            var sum = 0f; var count = 0; var i = 0
            while (i + lag < onset.size) { sum += onset[i] * onset[i + lag]; count++; i++ }
            if (count == 0) continue
            val value = sum / count.toFloat()
            corrArray[lag] = value
            if (value > bestValue) { secondValue = bestValue; bestValue = value; bestLag = lag }
            else if (value > secondValue) { secondValue = value }
        }

        if (bestLag <= 0 || bestValue < 0.015f) return null
        if ((bestValue - secondValue).coerceAtLeast(0f) + bestValue < 0.012f) return null

        val halfLag        = bestLag / 2
        val halfMs         = halfLag * hopMs
        val dynamicHalfRatio = if (bestLag * hopMs > 650L) 0.30f else HARMONIC_FOLD_HALF_RATIO
        if (halfLag >= minLag && halfMs >= HARMONIC_FOLD_HALF_MIN_MS &&
            corrArray[halfLag] >= bestValue * dynamicHalfRatio) {
            Log.d(TAG, "autoCorr half-fold: bestLag=${bestLag * hopMs}ms → halfMs=${halfMs}ms")
            val quarterLag = halfLag / 2
            if (quarterLag >= minLag && corrArray[quarterLag] >= corrArray[halfLag] * HARMONIC_FOLD_HALF_RATIO)
                return quarterLag * hopMs to corrArray[quarterLag].coerceIn(0f, 1f)
            return halfMs to corrArray[halfLag].coerceIn(0f, 1f)
        }

        val thirdLag = bestLag / 3
        if (thirdLag >= minLag && corrArray[thirdLag] >= bestValue * HARMONIC_FOLD_THIRD_RATIO)
            return thirdLag * hopMs to corrArray[thirdLag].coerceIn(0f, 1f)

        val ttLag = bestLag * 2 / 3
        val ttMs  = ttLag * hopMs
        if (ttLag >= minLag && ttMs in minBeatMs..(maxBeatMs * 0.65f).toLong()) {
            val ttVal = if (ttLag < corrArray.size) corrArray[ttLag] else {
                var sum = 0f; var cnt = 0; var i = 0
                while (i + ttLag < onset.size) { sum += onset[i] * onset[i + ttLag]; cnt++; i++ }
                if (cnt > 0) sum / cnt else 0f
            }
            if (ttVal >= bestValue * HARMONIC_TWO_THIRDS_RATIO)
                return ttMs to ttVal.coerceIn(0f, 1f)
        }

        val dLag = bestLag * 2; val dMs = dLag * hopMs
        val dVal: Float = when {
            dLag <= maxLag -> corrArray[dLag]
            dLag + 2 < onset.size -> {
                var sum = 0f; var cnt = 0; var i = 0
                while (i + dLag < onset.size) { sum += onset[i] * onset[i + dLag]; cnt++; i++ }
                if (cnt > 0) sum / cnt else 0f
            }
            else -> 0f
        }
        if (bestLag * hopMs > (maxBeatMs * 0.55f).toLong() &&
            dVal >= bestValue * HARMONIC_DOUBLE_RATIO && dMs <= maxBeatMs)
            return dMs to dVal.coerceIn(0f, 1f)

        return bestLag * hopMs to bestValue.coerceIn(0f, 1f)
    }

    // =========================================================================
    // Peak snapping & chain
    // =========================================================================

    private fun snapPeaksToGrid(
        rawPeakFrames: List<Int>, onset: List<Float>,
        beatMs: Long, hopMs: Long, snapToleranceMs: Long
    ): List<Int> {
        if (rawPeakFrames.isEmpty()) return emptyList()
        val beatFrames = max(1, (beatMs / hopMs).toInt())
        val tolFrames  = max(1, (snapToleranceMs / hopMs).toInt())
        var bestGrid: List<Int> = emptyList(); var bestScore = -1f
        for (anchor in rawPeakFrames) {
            val snapped = ArrayList<Int>()
            var g = anchor
            while (g >= 0) { nearestPeak(rawPeakFrames, g, tolFrames)?.let { snapped += it }; g -= beatFrames }
            g = anchor + beatFrames
            while (g < onset.size) { nearestPeak(rawPeakFrames, g, tolFrames)?.let { snapped += it }; g += beatFrames }
            val uniq = snapped.distinct().sorted()
            var score = 0f; for (idx in uniq) score += onset[idx]
            if (score > bestScore) { bestScore = score; bestGrid = uniq }
        }
        return bestGrid
    }

    private fun keepConsistentChain(
        snappedFrames: List<Int>, expectedBeatMs: Long, hopMs: Long, toleranceMs: Long
    ): List<Int> {
        if (snappedFrames.size <= 2) return snappedFrames
        val expected = expectedBeatMs.toFloat(); val tol = toleranceMs.toFloat()
        val kept = ArrayList<Int>(); kept += snappedFrames.first()
        for (i in 1 until snappedFrames.size) {
            val prev = kept.last(); val cur = snappedFrames[i]
            val diffMs = (cur - prev) * hopMs.toFloat()
            when {
                abs(diffMs - expected)        <= tol         -> kept += cur
                abs(diffMs - expected * 2f)   <= tol * 0.6f -> kept += cur
                abs(diffMs - expected * 0.5f) <= tol * 0.8f -> kept += cur
                abs(diffMs - expected * 3f)   <= tol * 1.2f -> kept += cur
                abs(diffMs - expected * 4f)   <= tol * 1.4f -> kept += cur
            }
        }
        return kept
    }

    private fun rawPeakMedianInterval(rawPeaks: List<Int>, params: Params): Long? {
        if (rawPeaks.size < 3) return null
        val intervals = (1 until rawPeaks.size)
            .map { (rawPeaks[it] - rawPeaks[it - 1]).toLong() * params.hopMs }
            .filter { it in params.minBeatMs..params.maxBeatMs }
        if (intervals.size < 2) return null
        return intervals.sorted()[intervals.size / 2]
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private fun estimateMedianInterval(
        beats: List<Long>, minBeatMs: Long, maxBeatMs: Long, hopMs: Long
    ): Long {
        if (beats.size < 2) return 500L
        val diffs = (1 until beats.size).mapNotNull {
            val d = beats[it] - beats[it - 1]
            if (d in minBeatMs..maxBeatMs) d else null
        }
        if (diffs.isEmpty()) return 500L
        val binned = diffs.map { (it / hopMs) * hopMs }
        return binned.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: diffs.sorted()[diffs.size / 2]
    }

    private fun dedupeCloseTimedBeats(beats: List<TimedBeat>, minDistanceMs: Long): List<TimedBeat> {
        if (beats.isEmpty()) return emptyList()
        val out = ArrayList<TimedBeat>(); var lastMs = Long.MIN_VALUE / 4
        for (b in beats) {
            if (b.timeMs - lastMs >= minDistanceMs) { out += b; lastMs = b.timeMs }
        }
        return out
    }

    private fun nearestPeak(peaks: List<Int>, center: Int, tol: Int): Int? {
        var best: Int? = null; var bestDist = Int.MAX_VALUE
        for (p in peaks) { val d = abs(p - center); if (d <= tol && d < bestDist) { bestDist = d; best = p } }
        return best
    }

    // 기존 findPeaks (고정 threshold 버전) — 다른 곳에서 사용 가능하므로 유지
    private fun findPeaks(src: List<Float>, threshold: Float, minDistance: Int): List<Int> {
        if (src.size < 3) return emptyList()
        val peaks = ArrayList<Int>(); var lastAccepted = -minDistance * 2
        for (i in 1 until src.lastIndex) {
            val c = src[i]; if (c < threshold) continue
            if (c < src[i - 1] || c < src[i + 1]) continue
            if (i - lastAccepted < minDistance) {
                if (peaks.isNotEmpty() && c > src[peaks.last()]) { peaks[peaks.lastIndex] = i; lastAccepted = i }
            } else { peaks += i; lastAccepted = i }
        }
        return peaks
    }

    private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> =
        localNormalize(positiveDiff(movingAverage(env, smoothWindow)), normWindow)

    private fun localNormalize(src: List<Float>, windowFrames: Int): List<Float> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<Float>(src.size)
        for (i in src.indices) {
            val lo = max(0, i - windowFrames); val hi = min(src.lastIndex, i + windowFrames)
            var localMax = 0f; for (j in lo..hi) if (src[j] > localMax) localMax = src[j]
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

    private fun mix(a: List<Float>, b: List<Float>, aw: Float, bw: Float): List<Float> {
        val n = min(a.size, b.size); val out = ArrayList<Float>(n)
        for (i in 0 until n) out += a[i] * aw + b[i] * bw
        return out
    }

    private fun meanOf(v: List<Float>): Float {
        if (v.isEmpty()) return 0f; var s = 0f; for (x in v) s += x; return s / v.size
    }
    private fun stdOf(v: List<Float>, mean: Float): Float {
        if (v.isEmpty()) return 0f; var s = 0f
        for (x in v) { val d = x - mean; s += d * d }
        return sqrt(s / v.size)
    }
    private fun varOf(v: List<Float>): Float {
        val m = meanOf(v); var s = 0f
        for (x in v) { val d = x - m; s += d * d }
        return if (v.isEmpty()) 0f else s / v.size
    }
    private fun maxOfList(v: List<Float>): Float = v.maxOrNull() ?: 0f
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
