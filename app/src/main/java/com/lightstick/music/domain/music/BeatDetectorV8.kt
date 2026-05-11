package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BeatDetectorV8
 *
 * 수정 사항:
 * A. Params.minBeatMs 기본값 350ms → 290ms (≈207 BPM)
 *    - 빠른 K-pop(170~200 BPM)의 실제 beatMs가 탐색 범위 아래로 떨어지는 문제 해결
 *
 * B. autoCorrelateBeat — harmonic folding 검사 추가 (빠른 곡 교정)
 *    - 전체 구간의 correlation 배열을 먼저 계산
 *    - bestLag가 선택되면 bestLag/2, bestLag/3 위치의 correlation 값을 확인
 *    - 절반(또는 1/3) lag에서도 충분한 상관값이 있으면 그 값으로 교정
 *    - 2배 주기 편향(80 BPM 감지 문제)의 핵심 원인 해소
 *
 * C. keepConsistentChain — 2배 간격 허용 조건 강화
 *    - 기존: expected×2 허용 tolerance = tol × 1.2f
 *    - 변경: expected×2 허용 tolerance = tol × 0.6f (훨씬 타이트하게)
 *    - 정상 비트 누락(fill, pickup)은 여전히 허용하되, 반속 그리드 전체가 통과하는 것을 방지
 *
 * D. estimateMedianInterval — 유효 diff 범위를 Params와 정렬
 *    - 기존: 250L..1200L (너무 넓어 반속 beatMs도 중앙값으로 채택)
 *    - 변경: minBeatMs..maxBeatMs 파라미터 기반으로 동적 조정
 *
 * E. Params.maxBeatMs 기본값 900ms → 1200ms (≈50 BPM) + harmonic doubling 추가 (느린 곡 교정)
 *    - 느린 발라드(60~80 BPM, beatMs 750~1000ms) 커버 확대
 *    - autocorrelation이 실제 주기의 절반(빠른 주기)을 선택한 경우 2배로 교정
 *    - bestLag × 2 위치의 correlation이 충분하면 느린 주기(실제 비트)를 채택
 *    - corrArray 범위 밖(bestLag×2 > maxLag)은 직접 계산하여 처리
 */
object BeatDetectorV8 {

    private const val TAG = "AutoTimeline"

    private const val HARMONIC_FOLD_HALF_RATIO = 0.40f
    private const val HARMONIC_FOLD_THIRD_RATIO = 0.35f
    private const val HARMONIC_DOUBLE_RATIO = 0.80f

    private const val HARMONIC_TWO_THIRDS_RATIO = 0.75f

    enum class BeatSource {
        LOW,
        MID,
        FULL,
        LOW_MID,
        MID_FULL,
        LOW_FULL
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

    fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        params: Params = Params()
    ): DetectResult {
        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            return DetectResult(
                beatTimesMs = emptyList(),
                beatMs = 0L,
                source = null,
                reason = "empty env",
                debugSegments = emptyList()
            )
        }

        val minSize = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        val low = lowEnv.take(minSize)
        val mid = midEnv.take(minSize)
        val full = fullEnv.take(minSize)

        val durationMs = minSize * params.hopMs
        val effectiveSegmentMs = if (durationMs < 60_000L) durationMs else params.segmentMs
        val segmentFrames = max(1, (effectiveSegmentMs / params.hopMs).toInt())
        val segmentCount = (minSize + segmentFrames - 1) / segmentFrames

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

            val srcOrder = buildSourceOrder(lowSeg, midSeg, fullSeg)

            val trials = ArrayList<TrialResult>()
            var best: TrialResult? = null

            for (src in srcOrder) {
                val combined = combineSource(src, lowSeg, midSeg, fullSeg)
                val trial = detectSingleSource(
                    segmentIndex = segIndex,
                    segmentStartMs = s.toLong() * params.hopMs,
                    source = src,
                    env = combined,
                    params = params
                )
                trials += trial

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
                        best = trial
                    }
                }
            }

            val segStartMs = s.toLong() * params.hopMs
            val segEndMs = e.toLong() * params.hopMs

            if (best == null) {
                Log.w(TAG, "SEG[$segIndex] FAIL -> skip segment")
                segResults += SegmentResult(
                    index = segIndex,
                    startMs = segStartMs,
                    endMs = segEndMs,
                    selectedSource = null,
                    beatTimesMs = emptyList(),
                    beatMs = 0L,
                    score = 0f,
                    reason = "all failed",
                    trials = trials
                )
                continue
            }

            val absoluteBeats = best.beatTimesMs.map { it + segStartMs }
            mergedBeats += absoluteBeats
            sourceVotes[best.source] = (sourceVotes[best.source] ?: 0) + 1

            segResults += SegmentResult(
                index = segIndex,
                startMs = segStartMs,
                endMs = segEndMs,
                selectedSource = best.source,
                beatTimesMs = absoluteBeats,
                beatMs = best.beatMs,
                score = best.score,
                reason = best.reason,
                trials = trials
            )
        }

        val deduped = dedupeCloseBeats(mergedBeats.sorted(), params.minPeakDistanceMs)
        if (deduped.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return DetectResult(
                beatTimesMs = emptyList(),
                beatMs = 0L,
                source = null,
                reason = "all segments failed",
                debugSegments = segResults
            )
        }

        val finalBeatMs = estimateMedianInterval(deduped, params.minBeatMs, params.maxBeatMs)
        val finalSource = sourceVotes.maxByOrNull { it.value }?.key

        return DetectResult(
            beatTimesMs = deduped,
            beatMs = finalBeatMs,
            source = finalSource,
            reason = "ok",
            debugSegments = segResults
        )
    }

    private fun detectSingleSource(
        @Suppress("UNUSED_PARAMETER") segmentIndex: Int,
        @Suppress("UNUSED_PARAMETER") segmentStartMs: Long,
        source: BeatSource,
        env: List<Float>,
        params: Params
    ): TrialResult {
        if (env.size < 8) {
            return TrialResult(
                source = source,
                beatTimesMs = emptyList(),
                beatMs = 0L,
                score = 0f,
                rawPeakCount = 0,
                snappedCount = 0,
                onsetMean = 0f,
                onsetStd = 0f,
                onsetMax = 0f,
                acPeak = 0f,
                snapRatio = 0f,
                reason = "env too short"
            )
        }

        val smooth = movingAverage(env, params.onsetSmoothWindow)
        val diff = positiveDiff(smooth)
        val onset = normalize01(diff)

        val mean = meanOf(onset)
        val std = stdOf(onset, mean)
        val onsetMax = maxOfList(onset)

        val threshold = max(params.minPeakAbs, mean + std * params.peakThresholdK)
        val minPeakFrames = max(1, (params.minPeakDistanceMs / params.hopMs).toInt())
        val rawPeaks = findPeaks(onset, threshold, minPeakFrames)

        val beatRange = autoCorrelateBeat(
            onset = onset,
            hopMs = params.hopMs,
            minBeatMs = params.minBeatMs,
            maxBeatMs = params.maxBeatMs
        )

        if (beatRange == null) {
            val fallbackBeatMs = if (rawPeaks.size >= 3) {
                val intervals = (1 until rawPeaks.size)
                    .map { (rawPeaks[it] - rawPeaks[it - 1]).toLong() * params.hopMs }
                    .filter { it in params.minBeatMs..params.maxBeatMs }
                if (intervals.size >= 2) {
                    val sorted = intervals.sorted()
                    sorted[sorted.size / 2]
                } else null
            } else null

            if (fallbackBeatMs != null) {
                val snappedFb = snapPeaksToGrid(rawPeaks, onset, fallbackBeatMs, params.hopMs, params.snapToleranceMs)
                val chainedFb = keepConsistentChain(
                    snappedFrames = snappedFb,
                    expectedBeatMs = fallbackBeatMs,
                    hopMs = params.hopMs,
                    toleranceMs = params.chainToleranceMs
                )
                if (chainedFb.size >= 2) {
                    val snapRatioFb = chainedFb.size.toFloat() / rawPeaks.size.toFloat()
                    val segDur = env.size.toLong() * params.hopMs
                    val expectedFb = max(1, (segDur / fallbackBeatMs).toInt())
                    val densityFb = min(1f, chainedFb.size.toFloat() / expectedFb.toFloat())
                    val scoreFb = (densityFb * 0.35f + snapRatioFb * 0.30f + 0.10f).coerceIn(0f, 1f)
                    return TrialResult(
                        source = source,
                        beatTimesMs = chainedFb.map { frame -> frame.toLong() * params.hopMs },
                        beatMs = fallbackBeatMs,
                        score = scoreFb,
                        rawPeakCount = rawPeaks.size,
                        snappedCount = chainedFb.size,
                        onsetMean = mean,
                        onsetStd = std,
                        onsetMax = onsetMax,
                        acPeak = 0f,
                        snapRatio = snapRatioFb,
                        reason = "onset-fallback"
                    )
                }
            }
            return TrialResult(
                source = source,
                beatTimesMs = emptyList(),
                beatMs = 0L,
                score = 0.08f,
                rawPeakCount = rawPeaks.size,
                snappedCount = 0,
                onsetMean = mean,
                onsetStd = std,
                onsetMax = onsetMax,
                acPeak = 0f,
                snapRatio = 0f,
                reason = "autocorr weak"
            )
        }

        val beatMs = beatRange.first
        val acPeak = beatRange.second

        val snapped = snapPeaksToGrid(
            rawPeakFrames = rawPeaks,
            onset = onset,
            beatMs = beatMs,
            hopMs = params.hopMs,
            snapToleranceMs = params.snapToleranceMs
        )

        if (snapped.isEmpty()) {
            return TrialResult(
                source = source,
                beatTimesMs = emptyList(),
                beatMs = beatMs,
                score = 0.1f + acPeak * 0.5f,
                rawPeakCount = rawPeaks.size,
                snappedCount = 0,
                onsetMean = mean,
                onsetStd = std,
                onsetMax = onsetMax,
                acPeak = acPeak,
                snapRatio = 0f,
                reason = "snap empty"
            )
        }

        val chained = keepConsistentChain(
            snappedFrames = snapped,
            expectedBeatMs = beatMs,
            hopMs = params.hopMs,
            toleranceMs = params.chainToleranceMs
        )

        val finalBeatsMs = chained.map { it.toLong() * params.hopMs }
        val snapRatio = if (rawPeaks.isEmpty()) 0f else chained.size.toFloat() / rawPeaks.size.toFloat()

        val segDurationMs = env.size.toLong() * params.hopMs
        val effectiveMinChain = if (segDurationMs < 15_000L) 2 else params.minChainCount

        if (finalBeatsMs.size < effectiveMinChain) {
            return TrialResult(
                source = source,
                beatTimesMs = emptyList(),
                beatMs = beatMs,
                score = 0.2f + acPeak * 0.5f + snapRatio * 0.1f,
                rawPeakCount = rawPeaks.size,
                snappedCount = chained.size,
                onsetMean = mean,
                onsetStd = std,
                onsetMax = onsetMax,
                acPeak = acPeak,
                snapRatio = snapRatio,
                reason = "chain too short"
            )
        }

        val expectedBeatsInSeg = max(1, (segDurationMs / beatMs).toInt())
        val densityScore = min(1f, finalBeatsMs.size.toFloat() / expectedBeatsInSeg.toFloat())

        val score = (
                densityScore * 0.40f +
                        snapRatio * 0.30f +
                        acPeak * 0.20f +
                        min(1f, onsetMax) * 0.10f
                ).coerceIn(0f, 1f)

        return TrialResult(
            source = source,
            beatTimesMs = finalBeatsMs,
            beatMs = beatMs,
            score = score,
            rawPeakCount = rawPeaks.size,
            snappedCount = chained.size,
            onsetMean = mean,
            onsetStd = std,
            onsetMax = onsetMax,
            acPeak = acPeak,
            snapRatio = snapRatio,
            reason = "ok"
        )
    }

    private fun buildSourceOrder(
        low: List<Float>,
        mid: List<Float>,
        full: List<Float>
    ): List<BeatSource> {
        val lowVar = varOf(low)
        val midVar = varOf(mid)
        val fullVar = varOf(full)

        val scored = listOf(
            BeatSource.LOW to lowVar,
            BeatSource.MID to midVar,
            BeatSource.FULL to fullVar,
            BeatSource.LOW_MID to ((lowVar + midVar) * 0.5f + min(lowVar, midVar) * 0.2f),
            BeatSource.MID_FULL to ((midVar + fullVar) * 0.5f + min(midVar, fullVar) * 0.2f),
            BeatSource.LOW_FULL to ((lowVar + fullVar) * 0.5f + min(lowVar, fullVar) * 0.2f)
        ).sortedByDescending { it.second }

        return scored.map { it.first }
    }

    private fun combineSource(
        source: BeatSource,
        low: List<Float>,
        mid: List<Float>,
        full: List<Float>
    ): List<Float> {
        return when (source) {
            BeatSource.LOW -> low
            BeatSource.MID -> mid
            BeatSource.FULL -> full
            BeatSource.LOW_MID -> mix(low, mid, 0.55f, 0.45f)
            BeatSource.MID_FULL -> mix(mid, full, 0.60f, 0.40f)
            BeatSource.LOW_FULL -> mix(low, full, 0.60f, 0.40f)
        }
    }

    /**
     * onset 신호에서 autocorrelation으로 비트 주기를 추정한다.
     *
     * [수정 내용 - harmonic folding]
     * 1) 전체 탐색 범위의 correlation 값을 FloatArray에 저장
     * 2) bestLag 선택 후 bestLag/2 위치의 correlation 확인
     *    → bestValue의 40% 이상이면 bestLag/2 채택 (2배 편향 교정)
     * 3) bestLag/3 위치도 확인 (3배 편향)
     *    → bestValue의 35% 이상이면 bestLag/3 채택
     *
     * 이유: autocorrelation은 진짜 주기 T뿐 아니라 2T, 3T에서도 피크가 발생.
     * 비트가 규칙적인 K-pop은 T와 2T에서 비슷한 상관값이 나오고,
     * 2T를 가진 lag가 maxValue로 선택될 수 있다.
     * 절반 lag에서도 유의미한 상관값이 있으면 더 짧은 주기(실제 비트)로 교정한다.
     */
    private fun autoCorrelateBeat(
        onset: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Pair<Long, Float>? {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt())
        if (onset.size <= maxLag + 2) return null

        val corrArray = FloatArray(maxLag + 1)

        var bestLag = -1
        var bestValue = 0f
        var secondValue = 0f

        for (lag in minLag..maxLag) {
            var sum = 0f
            var count = 0
            var i = 0
            while (i + lag < onset.size) {
                sum += onset[i] * onset[i + lag]
                count++
                i++
            }
            if (count == 0) continue
            val value = sum / count.toFloat()
            corrArray[lag] = value

            if (value > bestValue) {
                secondValue = bestValue
                bestValue = value
                bestLag = lag
            } else if (value > secondValue) {
                secondValue = value
            }
        }

        if (bestLag <= 0) return null
        if (bestValue < 0.015f) return null

        val confidence = (bestValue - secondValue).coerceAtLeast(0f) + bestValue
        if (confidence < 0.012f) return null

        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfValue = corrArray[halfLag]
            if (halfValue >= bestValue * HARMONIC_FOLD_HALF_RATIO) {
                val halfBeatMs = halfLag * hopMs
                val quarterLag = halfLag / 2
                if (quarterLag >= minLag) {
                    val quarterValue = corrArray[quarterLag]
                    if (quarterValue >= halfValue * HARMONIC_FOLD_HALF_RATIO) {
                        val quarterBeatMs = quarterLag * hopMs
                        return quarterBeatMs to quarterValue.coerceIn(0f, 1f)
                    }
                }
                return halfBeatMs to halfValue.coerceIn(0f, 1f)
            }
        }

        val thirdLag = bestLag / 3
        if (thirdLag >= minLag) {
            val thirdValue = corrArray[thirdLag]
            if (thirdValue >= bestValue * HARMONIC_FOLD_THIRD_RATIO) {
                val thirdBeatMs = thirdLag * hopMs
                return thirdBeatMs to thirdValue.coerceIn(0f, 1f)
            }
        }

        val twoThirdLag = bestLag * 2 / 3
        val twoThirdBeatMs = twoThirdLag * hopMs
        if (twoThirdLag >= minLag &&
            twoThirdBeatMs >= minBeatMs &&
            twoThirdBeatMs <= (maxBeatMs * 0.65f).toLong()
        ) {
            val ttValue: Float = if (twoThirdLag < corrArray.size) corrArray[twoThirdLag] else {
                var sum = 0f; var count = 0; var i = 0
                while (i + twoThirdLag < onset.size) { sum += onset[i] * onset[i + twoThirdLag]; count++; i++ }
                if (count > 0) sum / count.toFloat() else 0f
            }
            if (ttValue >= bestValue * HARMONIC_TWO_THIRDS_RATIO) {
                return twoThirdBeatMs to ttValue.coerceIn(0f, 1f)
            }
        }

        val doubleGuardMs = (maxBeatMs * 0.55f).toLong()
        val doubleLag = bestLag * 2
        val doubleValue: Float = when {
            doubleLag <= maxLag -> corrArray[doubleLag]
            doubleLag + 2 < onset.size -> {
                var sum = 0f
                var count = 0
                var i = 0
                while (i + doubleLag < onset.size) {
                    sum += onset[i] * onset[i + doubleLag]
                    count++
                    i++
                }
                if (count > 0) sum / count.toFloat() else 0f
            }
            else -> 0f
        }
        val doubleBeatMs = doubleLag * hopMs
        if (bestLag * hopMs > doubleGuardMs &&
            doubleValue >= bestValue * HARMONIC_DOUBLE_RATIO &&
            doubleBeatMs <= maxBeatMs
        ) {
            return doubleBeatMs to doubleValue.coerceIn(0f, 1f)
        }

        return bestLag * hopMs to bestValue.coerceIn(0f, 1f)
    }

    private fun snapPeaksToGrid(
        rawPeakFrames: List<Int>,
        onset: List<Float>,
        beatMs: Long,
        hopMs: Long,
        snapToleranceMs: Long
    ): List<Int> {
        if (rawPeakFrames.isEmpty()) return emptyList()

        val beatFrames = max(1, (beatMs / hopMs).toInt())
        val tolFrames = max(1, (snapToleranceMs / hopMs).toInt())

        var bestGrid: List<Int> = emptyList()
        var bestScore = -1f

        for (anchor in rawPeakFrames) {
            val snapped = ArrayList<Int>()
            var g = anchor

            while (g >= 0) {
                val p = nearestPeak(rawPeakFrames, g, tolFrames)
                if (p != null) snapped += p
                g -= beatFrames
            }

            g = anchor + beatFrames
            while (g < onset.size) {
                val p = nearestPeak(rawPeakFrames, g, tolFrames)
                if (p != null) snapped += p
                g += beatFrames
            }

            val uniq = snapped.distinct().sorted()
            var score = 0f
            for (idx in uniq) score += onset[idx]

            if (score > bestScore) {
                bestScore = score
                bestGrid = uniq
            }
        }

        return bestGrid
    }

    /**
     * C. 수정: 2배 간격 허용 tolerance를 tol × 1.2f → tol × 0.6f 로 강화
     *
     * 기존 코드는 expected × 2 간격도 tol × 1.2f의 넉넉한 오차로 통과시켰는데,
     * autocorrelation이 2× beatMs를 잘못 선택한 경우에 반속 그리드 전체가
     * 체인으로 살아남는 문제가 있었다.
     *
     * - 정상 fill/pickup에 의한 일시적 박자 누락(2× 간격 1~2개): 여전히 허용
     * - 반속 그리드 전체(연속 2× 간격): 타이트한 tol × 0.6f에서 대부분 탈락
     *
     * 0.5× 간격(서브비트) 허용은 그대로 유지 (비트 밀도 높은 구간 대응)
     */
    private fun keepConsistentChain(
        snappedFrames: List<Int>,
        expectedBeatMs: Long,
        hopMs: Long,
        toleranceMs: Long
    ): List<Int> {
        if (snappedFrames.size <= 2) return snappedFrames

        val expected = expectedBeatMs.toFloat()
        val tol = toleranceMs.toFloat()

        val kept = ArrayList<Int>()
        kept += snappedFrames.first()

        for (i in 1 until snappedFrames.size) {
            val prev = kept.last()
            val cur = snappedFrames[i]
            val diffMs = (cur - prev) * hopMs.toFloat()

            when {
                abs(diffMs - expected) <= tol -> kept += cur

                abs(diffMs - expected * 2f) <= tol * 0.6f -> kept += cur

                abs(diffMs - expected * 0.5f) <= tol * 0.8f -> kept += cur
            }
        }
        return kept
    }

    /**
     * D. 수정: 유효 diff 범위를 minBeatMs..maxBeatMs로 정렬
     *
     * 기존 고정값 250L..1200L은 너무 넓어 반속 beatMs(~700ms)도 중앙값으로
     * 채택될 수 있었다. minBeatMs, maxBeatMs를 직접 받아 범위를 제한한다.
     */
    private fun estimateMedianInterval(
        beats: List<Long>,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Long {
        if (beats.size < 2) return 500L
        val diffs = ArrayList<Long>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d in minBeatMs..maxBeatMs) diffs += d
        }
        if (diffs.isEmpty()) return 500L
        val sorted = diffs.sorted()
        return sorted[sorted.size / 2]
    }

    private fun dedupeCloseBeats(beats: List<Long>, minDistanceMs: Long): List<Long> {
        if (beats.isEmpty()) return emptyList()
        val out = ArrayList<Long>()
        var last = Long.MIN_VALUE / 4
        for (b in beats) {
            if (b - last >= minDistanceMs) {
                out += b
                last = b
            }
        }
        return out
    }

    private fun nearestPeak(peaks: List<Int>, center: Int, tol: Int): Int? {
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        for (p in peaks) {
            val d = abs(p - center)
            if (d <= tol && d < bestDist) {
                bestDist = d
                best = p
            }
        }
        return best
    }

    private fun findPeaks(
        src: List<Float>,
        threshold: Float,
        minDistance: Int
    ): List<Int> {
        if (src.size < 3) return emptyList()
        val peaks = ArrayList<Int>()
        var lastAccepted = -minDistance * 2

        for (i in 1 until src.lastIndex) {
            val c = src[i]
            if (c < threshold) continue

            val isPeak = c >= src[i - 1] && c >= src[i + 1]
            if (!isPeak) continue

            if (i - lastAccepted < minDistance) {
                if (peaks.isNotEmpty() && c > src[peaks.last()]) {
                    peaks[peaks.lastIndex] = i
                    lastAccepted = i
                }
            } else {
                peaks += i
                lastAccepted = i
            }
        }
        return peaks
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src.toList()
        val out = ArrayList<Float>(src.size)
        val half = window / 2
        for (i in src.indices) {
            var sum = 0f
            var count = 0
            val s = max(0, i - half)
            val e = min(src.lastIndex, i + half)
            for (j in s..e) {
                sum += src[j]
                count++
            }
            out += if (count == 0) 0f else sum / count.toFloat()
        }
        return out
    }

    private fun positiveDiff(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<Float>(src.size)
        out += 0f
        for (i in 1 until src.size) {
            out += max(0f, src[i] - src[i - 1])
        }
        return out
    }

    private fun normalize01(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val mn = src.minOrNull() ?: 0f
        val mx = src.maxOrNull() ?: 0f
        val range = (mx - mn)
        if (range <= 1e-6f) return List(src.size) { 0f }
        return src.map { ((it - mn) / range).coerceIn(0f, 1f) }
    }

    private fun mix(a: List<Float>, b: List<Float>, aw: Float, bw: Float): List<Float> {
        val n = min(a.size, b.size)
        val out = ArrayList<Float>(n)
        for (i in 0 until n) {
            out += a[i] * aw + b[i] * bw
        }
        return out
    }

    private fun meanOf(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        var s = 0f
        for (x in v) s += x
        return s / v.size.toFloat()
    }

    private fun stdOf(v: List<Float>, mean: Float): Float {
        if (v.isEmpty()) return 0f
        var s = 0f
        for (x in v) {
            val d = x - mean
            s += d * d
        }
        return sqrt(s / v.size.toFloat())
    }

    private fun varOf(v: List<Float>): Float {
        val m = meanOf(v)
        var s = 0f
        for (x in v) {
            val d = x - m
            s += d * d
        }
        return if (v.isEmpty()) 0f else s / v.size.toFloat()
    }

    private fun maxOfList(v: List<Float>): Float = v.maxOrNull() ?: 0f

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
