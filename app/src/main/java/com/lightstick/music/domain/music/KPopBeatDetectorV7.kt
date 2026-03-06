package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object KPopBeatDetectorV7 {

    private const val TAG = "AutoTimeline"

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
        val minBeatMs: Long = 350L,
        val maxBeatMs: Long = 900L,
        val minPeakDistanceMs: Long = 180L,
        val onsetSmoothWindow: Int = 3,
        val segmentMs: Long = 20_000L,
        val peakThresholdK: Float = 0.55f,
        val minPeakAbs: Float = 0.08f,
        val snapToleranceMs: Long = 120L,
        val chainToleranceMs: Long = 140L,
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
        val segmentFrames = max(1, (params.segmentMs / params.hopMs).toInt())
        val segmentCount = (minSize + segmentFrames - 1) / segmentFrames

        Log.d(TAG, "envSize low=${low.size} mid=${mid.size} full=${full.size} durationMs=$durationMs hopMs=${params.hopMs}")
        Log.d(TAG, "segments=$segmentCount segmentMs=${params.segmentMs}")

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
            Log.d(
                TAG,
                "SEG[$segIndex] srcOrder=$srcOrder " +
                        "lowVar=${fmt(varOf(lowSeg))} midVar=${fmt(varOf(midSeg))} fullVar=${fmt(varOf(fullSeg))} " +
                        "lowPeak=${fmt(maxOfList(lowSeg))} midPeak=${fmt(maxOfList(midSeg))} fullPeak=${fmt(maxOfList(fullSeg))}"
            )

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

                Log.d(
                    TAG,
                    "SEG[$segIndex] try=${trial.source} beats=${trial.beatTimesMs.size} beatMs=${trial.beatMs} " +
                            "score=${fmt(trial.score)} rawPeak=${trial.rawPeakCount} snapped=${trial.snappedCount} " +
                            "onset(mean/std/max)=${fmt(trial.onsetMean)}/${fmt(trial.onsetStd)}/${fmt(trial.onsetMax)} " +
                            "acPeak=${fmt(trial.acPeak)} snapRatio=${fmt(trial.snapRatio)} reason=${trial.reason}"
                )

                if (trial.reason == "ok") {
                    if (best == null || trial.score > best!!.score) {
                        best = trial
                    }
                }
            }

            val segStartMs = s.toLong() * params.hopMs
            val segEndMs = e.toLong() * params.hopMs

            if (best == null) {
                Log.d(
                    TAG,
                    "SEG[$segIndex] $segStartMs-$segEndMs best=null beats=0 beatMs=0 score=0.0 reason=all failed"
                )
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

            Log.d(
                TAG,
                "SEG[$segIndex] $segStartMs-$segEndMs best=${best.source} beats=${best.beatTimesMs.size} " +
                        "beatMs=${best.beatMs} score=${fmt(best.score)} reason=${best.reason}"
            )

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

        val finalBeatMs = estimateMedianInterval(deduped)
        val finalSource = sourceVotes.maxByOrNull { it.value }?.key

        Log.d(
            TAG,
            "beat detect OK source=$finalSource totalBeats=${deduped.size} beatMs=$finalBeatMs " +
                    "first=${deduped.firstOrNull()} last=${deduped.lastOrNull()}"
        )

        return DetectResult(
            beatTimesMs = deduped,
            beatMs = finalBeatMs,
            source = finalSource,
            reason = "ok",
            debugSegments = segResults
        )
    }

    private fun detectSingleSource(
        segmentIndex: Int,
        segmentStartMs: Long,
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

        if (finalBeatsMs.size < params.minChainCount) {
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

        val densityScore = min(1f, finalBeatsMs.size / 8f)
        val score = (
                acPeak * 0.40f +
                        snapRatio * 0.30f +
                        densityScore * 0.20f +
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

    private fun autoCorrelateBeat(
        onset: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Pair<Long, Float>? {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt())
        if (onset.size <= maxLag + 2) return null

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
            if (value > bestValue) {
                secondValue = bestValue
                bestValue = value
                bestLag = lag
            } else if (value > secondValue) {
                secondValue = value
            }
        }

        if (bestLag <= 0) return null
        if (bestValue < 0.02f) return null

        val confidence = if (bestValue <= 0f) 0f else (bestValue - secondValue).coerceAtLeast(0f) + bestValue
        if (confidence < 0.025f) return null

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

            if (abs(diffMs - expected) <= tol) {
                kept += cur
            } else if (abs(diffMs - expected * 2f) <= tol * 1.2f) {
                kept += cur
            } else if (abs(diffMs - expected * 0.5f) <= tol * 0.8f) {
                kept += cur
            }
        }
        return kept
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

    private fun estimateMedianInterval(beats: List<Long>): Long {
        if (beats.size < 2) return 500L
        val diffs = ArrayList<Long>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d in 250L..1200L) diffs += d
        }
        if (diffs.isEmpty()) return 500L
        val sorted = diffs.sorted()
        return sorted[sorted.size / 2]
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

    private fun fmt(v: Float): String = String.format("%.3f", v)
}