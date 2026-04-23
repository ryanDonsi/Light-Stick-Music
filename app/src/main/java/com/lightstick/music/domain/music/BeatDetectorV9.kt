package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BeatDetectorV9
 *
 * V8 대비 개선 사항:
 * 1. 전곡 글로벌 BPM 추정 (LOW_MID 전체 ODF로 autocorr) — 20초 세그먼트 autocorr 실패 보완
 * 2. localNormalize ODF — 조용한 구간에서도 onset 밀도를 유지해 autocorr 신뢰도 향상
 *    (normalize01은 큰 피크 하나가 전체를 0에 수렴시켜 autocorr threshold 미달 초래)
 * 3. globalBeatMs 폴백 — 세그먼트 autocorr 실패 시 전곡 BPM으로 그리드 스냅 재시도
 * 4. LOW/LOW_MID 소스 우선 — 베이스 드럼(20~300 Hz) 중심으로 비트 신뢰도 향상
 * 5. selectBestTempoByGrid 제거 — 긴 주기 편향 문제의 근본 원인 제거
 */
object BeatDetectorV9 {

    private const val TAG = "AutoTimeline"

    private const val HARMONIC_FOLD_HALF_RATIO = 0.40f
    private const val HARMONIC_FOLD_THIRD_RATIO = 0.35f
    private const val HARMONIC_DOUBLE_RATIO = 0.80f
    private const val HARMONIC_TWO_THIRDS_RATIO = 0.75f

    // localNormalize 슬라이딩 윈도우: 60프레임 = 3초 (hopMs=50ms 기준)
    private const val LOCAL_NORM_WINDOW = 60
    // 글로벌 BPM 추정용 더 넓은 윈도우: 80프레임 = 4초
    private const val GLOBAL_NORM_WINDOW = 80

    enum class BeatSource {
        LOW, MID, FULL, LOW_MID, MID_FULL, LOW_FULL
    }

    data class Params(
        val hopMs: Long = 50L,
        val minBeatMs: Long = 290L,
        val maxBeatMs: Long = 1200L,
        val minPeakDistanceMs: Long = 140L,
        val onsetSmoothWindow: Int = 3,
        val segmentMs: Long = 20_000L,
        val peakThresholdK: Float = 0.22f,
        val minPeakAbs: Float = 0.04f,
        val snapToleranceMs: Long = 150L,
        val chainToleranceMs: Long = 170L,
        val minChainCount: Int = 3
    )

    data class DetectResult(
        val beatTimesMs: List<Long>,
        val beatMs: Long,
        val source: BeatSource?,
        val reason: String,
        val debugSegments: List<SegmentResult>
    )

    data class SegmentResult(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val selectedSource: BeatSource?,
        val beatTimesMs: List<Long>,
        val beatMs: Long,
        val score: Float,
        val reason: String,
        val trials: List<TrialResult>
    )

    data class TrialResult(
        val source: BeatSource,
        val beatTimesMs: List<Long>,
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
            return DetectResult(emptyList(), 0L, null, "empty env", emptyList())
        }

        val minSize = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        val low = lowEnv.take(minSize)
        val mid = midEnv.take(minSize)
        val full = fullEnv.take(minSize)
        val durationMs = minSize * params.hopMs

        // ① 전곡 글로벌 BPM 추정
        val globalBeatMs = estimateGlobalBpm(low, mid, params)
        Log.d(TAG, "V9 globalBeatMs=$globalBeatMs durationMs=$durationMs")

        val effectiveSegmentMs = if (durationMs < 60_000L) durationMs else params.segmentMs
        val segmentFrames = max(1, (effectiveSegmentMs / params.hopMs).toInt())
        val segmentCount = (minSize + segmentFrames - 1) / segmentFrames

        Log.d(TAG, "envSize low=${low.size} mid=${mid.size} full=${full.size} durationMs=$durationMs hopMs=${params.hopMs}")
        Log.d(TAG, "segments=$segmentCount segmentMs=${params.segmentMs} minBeatMs=${params.minBeatMs} maxBeatMs=${params.maxBeatMs}")

        val segResults = ArrayList<SegmentResult>()
        val mergedBeats = ArrayList<Long>()
        val sourceVotes = LinkedHashMap<BeatSource, Int>()

        for (segIndex in 0 until segmentCount) {
            val s = segIndex * segmentFrames
            val e = min(minSize, s + segmentFrames)
            if (e - s < 8) continue

            val lowSeg = low.subList(s, e)
            val midSeg = mid.subList(s, e)
            val fullSeg = full.subList(s, e)
            val segStartMs = s.toLong() * params.hopMs
            val segEndMs = e.toLong() * params.hopMs

            val srcOrder = buildSourceOrder(lowSeg, midSeg, fullSeg)
            Log.d(TAG,
                "SEG[$segIndex] srcOrder=$srcOrder " +
                "lowVar=${fmt(varOf(lowSeg))} midVar=${fmt(varOf(midSeg))} fullVar=${fmt(varOf(fullSeg))} " +
                "lowPeak=${fmt(maxOfList(lowSeg))} midPeak=${fmt(maxOfList(midSeg))} fullPeak=${fmt(maxOfList(fullSeg))}")

            val trials = ArrayList<TrialResult>()
            var best: TrialResult? = null

            for (src in srcOrder) {
                val combined = combineSource(src, lowSeg, midSeg, fullSeg)
                val trial = detectSingleSource(
                    segmentIndex = segIndex,
                    segmentStartMs = segStartMs,
                    source = src,
                    env = combined,
                    globalBeatMs = globalBeatMs,
                    params = params
                )
                trials += trial

                Log.d(TAG,
                    "SEG[$segIndex] try=${trial.source} beats=${trial.beatTimesMs.size} beatMs=${trial.beatMs} " +
                    "score=${fmt(trial.score)} rawPeak=${trial.rawPeakCount} snapped=${trial.snappedCount} " +
                    "onset(mean/std/max)=${fmt(trial.onsetMean)}/${fmt(trial.onsetStd)}/${fmt(trial.onsetMax)} " +
                    "acPeak=${fmt(trial.acPeak)} snapRatio=${fmt(trial.snapRatio)} reason=${trial.reason}")

                if (trial.reason == "ok") {
                    val bestNonNull = best
                    val isBetter = when {
                        bestNonNull == null -> true
                        trial.beatTimesMs.size > bestNonNull.beatTimesMs.size &&
                                trial.score >= bestNonNull.score * 0.70f -> true
                        trial.beatTimesMs.size == bestNonNull.beatTimesMs.size &&
                                trial.score > bestNonNull.score -> true
                        else -> false
                    }
                    if (isBetter) {
                        Log.d(TAG,
                            "SEG[$segIndex] best update: ${best?.source}(${best?.beatTimesMs?.size}beats) " +
                            "→ ${trial.source}(${trial.beatTimesMs.size}beats) score=${fmt(trial.score)}")
                        best = trial
                    }
                }
            }

            if (best == null) {
                Log.d(TAG, "SEG[$segIndex] $segStartMs-$segEndMs best=null beats=0 beatMs=0 score=0.0 reason=all failed")
                Log.w(TAG, "SEG[$segIndex] FAIL -> skip segment")
                segResults += SegmentResult(segIndex, segStartMs, segEndMs, null, emptyList(), 0L, 0f, "all failed", trials)
                continue
            }

            val absoluteBeats = best.beatTimesMs.map { it + segStartMs }
            mergedBeats += absoluteBeats
            sourceVotes[best.source] = (sourceVotes[best.source] ?: 0) + 1

            Log.d(TAG,
                "SEG[$segIndex] $segStartMs-$segEndMs best=${best.source} beats=${best.beatTimesMs.size} " +
                "beatMs=${best.beatMs} score=${fmt(best.score)} reason=${best.reason}")

            segResults += SegmentResult(segIndex, segStartMs, segEndMs, best.source,
                absoluteBeats, best.beatMs, best.score, best.reason, trials)
        }

        val deduped = dedupeCloseBeats(mergedBeats.sorted(), params.minPeakDistanceMs)
        if (deduped.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return DetectResult(emptyList(), 0L, null, "all segments failed", segResults)
        }

        val finalBeatMs = estimateMedianInterval(deduped, params.minBeatMs, params.maxBeatMs, params.hopMs)
        val finalSource = sourceVotes.maxByOrNull { it.value }?.key

        Log.d(TAG,
            "beat detect OK source=$finalSource totalBeats=${deduped.size} beatMs=$finalBeatMs " +
            "first=${deduped.firstOrNull()} last=${deduped.lastOrNull()}")

        return DetectResult(deduped, finalBeatMs, finalSource, "ok", segResults)
    }

    // =========================================================================
    // ① 글로벌 BPM 추정 (전곡 LOW_MID ODF 기반)
    // =========================================================================

    private fun estimateGlobalBpm(low: List<Float>, mid: List<Float>, params: Params): Long? {
        val combined = mix(low, mid, 0.55f, 0.45f)
        val onset = computeOdf(combined, smoothWindow = 5, normWindow = GLOBAL_NORM_WINDOW)
        return autoCorrelateBeat(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)?.first
    }

    // =========================================================================
    // Single source detection
    // =========================================================================

    private fun detectSingleSource(
        segmentIndex: Int,
        @Suppress("UNUSED_PARAMETER") segmentStartMs: Long,
        source: BeatSource,
        env: List<Float>,
        globalBeatMs: Long?,
        params: Params
    ): TrialResult {
        if (env.size < 8) {
            return TrialResult(source, emptyList(), 0L, 0f, 0, 0, 0f, 0f, 0f, 0f, 0f, "env too short")
        }

        // ② localNormalize ODF — 조용한 구간도 onset 밀도 유지
        val onset = computeOdf(env, params.onsetSmoothWindow, LOCAL_NORM_WINDOW)

        val mean = meanOf(onset)
        val std = stdOf(onset, mean)
        val onsetMax = maxOfList(onset)

        val threshold = max(params.minPeakAbs, mean + std * params.peakThresholdK)
        val minPeakFrames = max(1, (params.minPeakDistanceMs / params.hopMs).toInt())
        val rawPeaks = findPeaks(onset, threshold, minPeakFrames)

        // 세그먼트 autocorr 우선 시도
        val acResult = autoCorrelateBeat(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)

        // ③ globalBeatMs 폴백: 세그먼트 autocorr 실패 시 전곡 BPM 사용
        val beatMs: Long
        val acPeak: Float
        when {
            acResult != null -> {
                beatMs = acResult.first
                acPeak = acResult.second
            }
            globalBeatMs != null -> {
                beatMs = globalBeatMs
                acPeak = 0.5f
                Log.d(TAG, "SEG[$segmentIndex] autocorr weak → globalBeatMs=$globalBeatMs fallback")
            }
            else -> {
                // rawPeak 간격 중앙값 폴백
                val fallbackMs = rawPeakMedianInterval(rawPeaks, params)
                if (fallbackMs != null) {
                    val snappedFb = snapPeaksToGrid(rawPeaks, onset, fallbackMs, params.hopMs, params.snapToleranceMs)
                    val chainedFb = keepConsistentChain(snappedFb, fallbackMs, params.hopMs, params.chainToleranceMs)
                    if (chainedFb.size >= 2) {
                        val snapRatioFb = chainedFb.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
                        val segDur = env.size.toLong() * params.hopMs
                        val expectedFb = max(1, (segDur / fallbackMs).toInt())
                        val densityFb = min(1f, chainedFb.size.toFloat() / expectedFb.toFloat())
                        val scoreFb = (densityFb * 0.35f + snapRatioFb * 0.30f + 0.10f).coerceIn(0f, 1f)
                        Log.d(TAG, "onset fallback: beatMs=$fallbackMs beats=${chainedFb.size} score=${fmt(scoreFb)}")
                        return TrialResult(source, chainedFb.map { it.toLong() * params.hopMs },
                            fallbackMs, scoreFb, rawPeaks.size, chainedFb.size,
                            mean, std, onsetMax, 0f, snapRatioFb, "onset-fallback")
                    }
                }
                return TrialResult(source, emptyList(), 0L, 0.08f, rawPeaks.size, 0, mean, std, onsetMax, 0f, 0f, "autocorr weak")
            }
        }

        val snapped = snapPeaksToGrid(rawPeaks, onset, beatMs, params.hopMs, params.snapToleranceMs)
        if (snapped.isEmpty()) {
            return TrialResult(source, emptyList(), beatMs, 0.1f + acPeak * 0.5f,
                rawPeaks.size, 0, mean, std, onsetMax, acPeak, 0f, "snap empty")
        }

        val chained = keepConsistentChain(snapped, beatMs, params.hopMs, params.chainToleranceMs)
        val segDurationMs = env.size.toLong() * params.hopMs
        val effectiveMinChain = if (segDurationMs < 15_000L) 2 else params.minChainCount

        if (chained.size < effectiveMinChain) {
            val snapRatio = chained.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
            return TrialResult(source, emptyList(), beatMs, 0.2f + acPeak * 0.5f + snapRatio * 0.1f,
                rawPeaks.size, chained.size, mean, std, onsetMax, acPeak, snapRatio, "chain too short")
        }

        val finalBeatsMs = chained.map { it.toLong() * params.hopMs }
        val snapRatio = chained.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
        val expectedBeats = max(1, (segDurationMs / beatMs).toInt())
        val densityScore = min(1f, finalBeatsMs.size.toFloat() / expectedBeats.toFloat())
        val score = (densityScore * 0.40f + snapRatio * 0.30f + acPeak * 0.20f + min(1f, onsetMax) * 0.10f).coerceIn(0f, 1f)

        return TrialResult(source, finalBeatsMs, beatMs, score,
            rawPeaks.size, chained.size, mean, std, onsetMax, acPeak, snapRatio, "ok")
    }

    // =========================================================================
    // ODF — localNormalize 기반
    // =========================================================================

    private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> {
        val smooth = movingAverage(env, smoothWindow)
        val diff = positiveDiff(smooth)
        return localNormalize(diff, normWindow)
    }

    // =========================================================================
    // ④ Source ordering — LOW/LOW_MID 우선 보너스
    // =========================================================================

    private fun buildSourceOrder(low: List<Float>, mid: List<Float>, full: List<Float>): List<BeatSource> {
        val lowVar = varOf(low)
        val midVar = varOf(mid)
        val fullVar = varOf(full)
        val BASS_BONUS = 0.003f  // 저음 소스 우선
        val scored = listOf(
            BeatSource.LOW to (lowVar + BASS_BONUS),
            BeatSource.LOW_MID to ((lowVar + midVar) * 0.5f + min(lowVar, midVar) * 0.2f + BASS_BONUS),
            BeatSource.MID to midVar,
            BeatSource.FULL to fullVar,
            BeatSource.MID_FULL to ((midVar + fullVar) * 0.5f + min(midVar, fullVar) * 0.2f),
            BeatSource.LOW_FULL to ((lowVar + fullVar) * 0.5f + min(lowVar, fullVar) * 0.2f)
        ).sortedByDescending { it.second }
        return scored.map { it.first }
    }

    private fun combineSource(source: BeatSource, low: List<Float>, mid: List<Float>, full: List<Float>): List<Float> {
        return when (source) {
            BeatSource.LOW -> low
            BeatSource.MID -> mid
            BeatSource.FULL -> full
            BeatSource.LOW_MID -> mix(low, mid, 0.55f, 0.45f)
            BeatSource.MID_FULL -> mix(mid, full, 0.60f, 0.40f)
            BeatSource.LOW_FULL -> mix(low, full, 0.60f, 0.40f)
        }
    }

    // =========================================================================
    // Autocorrelation + harmonic folding (V8과 동일)
    // =========================================================================

    private fun autoCorrelateBeat(onset: List<Float>, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): Pair<Long, Float>? {
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

        if (bestLag <= 0) return null
        if (bestValue < 0.015f) return null
        val confidence = (bestValue - secondValue).coerceAtLeast(0f) + bestValue
        if (confidence < 0.012f) return null

        // harmonic fold /2
        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfValue = corrArray[halfLag]
            if (halfValue >= bestValue * HARMONIC_FOLD_HALF_RATIO) {
                val halfBeatMs = halfLag * hopMs
                Log.d(TAG, "harmonic fold (/2): bestLag=$bestLag (${bestLag * hopMs}ms) → halfLag=$halfLag (${halfBeatMs}ms) ratio=${fmt(halfValue / bestValue)}")
                val quarterLag = halfLag / 2
                if (quarterLag >= minLag) {
                    val quarterValue = corrArray[quarterLag]
                    if (quarterValue >= halfValue * HARMONIC_FOLD_HALF_RATIO) {
                        return quarterLag * hopMs to quarterValue.coerceIn(0f, 1f)
                    }
                }
                return halfBeatMs to halfValue.coerceIn(0f, 1f)
            }
        }

        // harmonic fold /3
        val thirdLag = bestLag / 3
        if (thirdLag >= minLag) {
            val thirdValue = corrArray[thirdLag]
            if (thirdValue >= bestValue * HARMONIC_FOLD_THIRD_RATIO) {
                Log.d(TAG, "harmonic fold (/3): bestLag=$bestLag (${bestLag * hopMs}ms) → thirdLag=$thirdLag ratio=${fmt(thirdValue / bestValue)}")
                return thirdLag * hopMs to thirdValue.coerceIn(0f, 1f)
            }
        }

        // harmonic two-thirds ×2/3
        val twoThirdLag = bestLag * 2 / 3
        val twoThirdBeatMs = twoThirdLag * hopMs
        if (twoThirdLag >= minLag && twoThirdBeatMs >= minBeatMs && twoThirdBeatMs <= (maxBeatMs * 0.65f).toLong()) {
            val ttValue = if (twoThirdLag < corrArray.size) corrArray[twoThirdLag] else {
                var sum = 0f; var count = 0; var i = 0
                while (i + twoThirdLag < onset.size) { sum += onset[i] * onset[i + twoThirdLag]; count++; i++ }
                if (count > 0) sum / count.toFloat() else 0f
            }
            if (ttValue >= bestValue * HARMONIC_TWO_THIRDS_RATIO) {
                Log.d(TAG, "harmonic two-thirds (×2/3): bestLag=$bestLag → ttLag=$twoThirdLag (${twoThirdBeatMs}ms) ratio=${fmt(ttValue / bestValue)}")
                return twoThirdBeatMs to ttValue.coerceIn(0f, 1f)
            }
        }

        // harmonic doubling ×2
        val doubleGuardMs = (maxBeatMs * 0.55f).toLong()
        val doubleLag = bestLag * 2
        val doubleValue: Float = when {
            doubleLag <= maxLag -> corrArray[doubleLag]
            doubleLag + 2 < onset.size -> {
                var sum = 0f; var count = 0; var i = 0
                while (i + doubleLag < onset.size) { sum += onset[i] * onset[i + doubleLag]; count++; i++ }
                if (count > 0) sum / count.toFloat() else 0f
            }
            else -> 0f
        }
        val doubleBeatMs = doubleLag * hopMs
        if (bestLag * hopMs > doubleGuardMs && doubleValue >= bestValue * HARMONIC_DOUBLE_RATIO && doubleBeatMs <= maxBeatMs) {
            Log.d(TAG, "harmonic double (×2): bestLag=$bestLag guard=${doubleGuardMs}ms → doubleLag=$doubleLag (${doubleBeatMs}ms) ratio=${fmt(doubleValue / bestValue)}")
            return doubleBeatMs to doubleValue.coerceIn(0f, 1f)
        }

        return bestLag * hopMs to bestValue.coerceIn(0f, 1f)
    }

    // =========================================================================
    // Peak snapping & chain
    // =========================================================================

    private fun snapPeaksToGrid(rawPeakFrames: List<Int>, onset: List<Float>, beatMs: Long, hopMs: Long, snapToleranceMs: Long): List<Int> {
        if (rawPeakFrames.isEmpty()) return emptyList()
        val beatFrames = max(1, (beatMs / hopMs).toInt())
        val tolFrames = max(1, (snapToleranceMs / hopMs).toInt())
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

    private fun keepConsistentChain(snappedFrames: List<Int>, expectedBeatMs: Long, hopMs: Long, toleranceMs: Long): List<Int> {
        if (snappedFrames.size <= 2) return snappedFrames
        val expected = expectedBeatMs.toFloat(); val tol = toleranceMs.toFloat()
        val kept = ArrayList<Int>(); kept += snappedFrames.first()
        for (i in 1 until snappedFrames.size) {
            val prev = kept.last(); val cur = snappedFrames[i]
            val diffMs = (cur - prev) * hopMs.toFloat()
            when {
                abs(diffMs - expected) <= tol -> kept += cur
                abs(diffMs - expected * 2f) <= tol * 0.6f -> kept += cur
                abs(diffMs - expected * 0.5f) <= tol * 0.8f -> kept += cur
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
        val sorted = intervals.sorted()
        return sorted[sorted.size / 2]
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private fun estimateMedianInterval(beats: List<Long>, minBeatMs: Long, maxBeatMs: Long, hopMs: Long = 50L): Long {
        if (beats.size < 2) return 500L
        val diffs = ArrayList<Long>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d in minBeatMs..maxBeatMs) diffs += d
        }
        if (diffs.isEmpty()) return 500L
        // hop 그리드(50ms)로 올림해 mode를 취함: 양자화 노이즈로 median이
        // 인접 hop 값(450ms)으로 튀는 현상 방지 (예: TOMBOY 400ms → 450ms 오검출)
        val binned = diffs.map { (it / hopMs) * hopMs }
        val mode = binned.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return mode ?: diffs.sorted()[diffs.size / 2]
    }

    private fun dedupeCloseBeats(beats: List<Long>, minDistanceMs: Long): List<Long> {
        if (beats.isEmpty()) return emptyList()
        val out = ArrayList<Long>(); var last = Long.MIN_VALUE / 4
        for (b in beats) { if (b - last >= minDistanceMs) { out += b; last = b } }
        return out
    }

    private fun nearestPeak(peaks: List<Int>, center: Int, tol: Int): Int? {
        var best: Int? = null; var bestDist = Int.MAX_VALUE
        for (p in peaks) { val d = abs(p - center); if (d <= tol && d < bestDist) { bestDist = d; best = p } }
        return best
    }

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

    // ② localNormalize: 슬라이딩 윈도우 지역 정규화
    // normalize01과 달리 조용한 구간에서도 onset 피크를 살려 autocorr 신뢰도 유지
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

    private fun meanOf(v: List<Float>): Float { if (v.isEmpty()) return 0f; var s = 0f; for (x in v) s += x; return s / v.size }
    private fun stdOf(v: List<Float>, mean: Float): Float {
        if (v.isEmpty()) return 0f; var s = 0f; for (x in v) { val d = x - mean; s += d * d }; return sqrt(s / v.size)
    }
    private fun varOf(v: List<Float>): Float {
        val m = meanOf(v); var s = 0f; for (x in v) { val d = x - m; s += d * d }; return if (v.isEmpty()) 0f else s / v.size
    }
    private fun maxOfList(v: List<Float>): Float = v.maxOrNull() ?: 0f
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
