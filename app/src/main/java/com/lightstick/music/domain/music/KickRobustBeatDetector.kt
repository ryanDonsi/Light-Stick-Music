package com.lightstick.music.domain.music

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * KickRobustBeatDetector (LOW + MID flux) — DEBUG/DIAGNOSTIC BUILD
 *
 * 특징:
 * - 안전망(lenient/forced) 없음
 * - 왜 cand/snapped가 0이 되는지 원인을 로그로 "수치"로 보여줌
 *
 * 출력:
 * - beats: snapped beats가 있으면 snapped, 아니면 candidates
 * - dbg: 각 단계 통계
 */
object KickRobustBeatDetector {

    data class Params(
        val hopMs: Long = 50L,

        // 후보 추출
        val minGapMs: Long = 200L,
        val maWindowMs: Long = 1200L, // adaptive threshold window
        val kStd: Float = 1.0f,
        val minPeak: Float = 0.02f,

        // tempo 탐색
        val minBeatMs: Long = 250L,
        val maxBeatMs: Long = 750L,

        // snapping
        val snapWindowMs: Long = 90L,

        // low/mid 합성 가중치
        val midWeight: Float = 0.7f
    )

    data class Debug(
        val onsetSize: Int,
        val lowFluxMax: Float,
        val midFluxMax: Float,
        val onsetMax: Float,
        val onsetMean: Float,
        val onsetStd: Float,

        val candidates: Int,
        val candMeanTh: Float,   // 대표 threshold(중앙 구간 기준)
        val candPassRate: Float, // threshold 통과율(대략)

        val beatMs: Long,
        val offsetMs: Long,
        val snapped: Int,

        val snapWindowMs: Long,
        val snapBestValMedian: Float,  // 각 grid window에서 최대값 median
        val snapBestValMin: Float,
        val snapBestValMax: Float,

        val reason: String
    )

    fun detectBeats(
        lowEnv: FloatArray,
        midEnv: FloatArray,
        durationMs: Long,
        params: Params,
        tag: String
    ): Pair<LongArray, Debug> {

        if (lowEnv.isEmpty() || midEnv.isEmpty()) {
            return longArrayOf() to Debug(
                onsetSize = 0,
                lowFluxMax = 0f, midFluxMax = 0f,
                onsetMax = 0f, onsetMean = 0f, onsetStd = 0f,
                candidates = 0,
                candMeanTh = 0f,
                candPassRate = 0f,
                beatMs = 0L, offsetMs = 0L,
                snapped = 0,
                snapWindowMs = params.snapWindowMs,
                snapBestValMedian = 0f, snapBestValMin = 0f, snapBestValMax = 0f,
                reason = "env empty"
            )
        }

        val lowFlux = positiveFlux(lowEnv)
        val midFlux = positiveFlux(midEnv)

        val n = minOf(lowFlux.size, midFlux.size)
        val onset = FloatArray(n)
        for (i in 0 until n) onset[i] = lowFlux[i] + params.midWeight * midFlux[i]

        normalize01InPlace(lowFlux)
        normalize01InPlace(midFlux)
        normalize01InPlace(onset)

        val (onsetMean, onsetStd, onsetMax) = meanStdMax(onset)
        val lowMax = maxOfArray(lowFlux)
        val midMax = maxOfArray(midFlux)

        // 1) candidates
        val candResult = pickPeaksAdaptiveWithStats(
            signal = onset,
            hopMs = params.hopMs,
            durationMs = durationMs,
            minGapMs = params.minGapMs,
            maWindowMs = params.maWindowMs,
            kStd = params.kStd,
            minPeak = params.minPeak
        )

        // 2) tempo + phase
        val beatMs = estimateBeatMsByAutocorr(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)
        val offsetMs = estimatePhaseOffsetMs(onset, params.hopMs, beatMs)

        // 3) snapping + snap window stats
        val snapStats = snapWindowStats(
            novelty = onset,
            hopMs = params.hopMs,
            durationMs = durationMs,
            beatMs = beatMs,
            offsetMs = offsetMs,
            snapWindowMs = params.snapWindowMs
        )

        val snapped = pickBeatsByGridSnappingStrict(
            novelty = onset,
            hopMs = params.hopMs,
            durationMs = durationMs,
            beatMs = beatMs,
            offsetMs = offsetMs,
            snapWindowMs = params.snapWindowMs,
            minPeak = params.minPeak,     // 스냅에도 동일 minPeak 적용(진짜 피크만)
            minGapMs = params.minGapMs
        )

        val out = if (snapped.isNotEmpty()) snapped else candResult.beats.toLongArray()

        val reason = when {
            onsetMax < 0.02f -> "onset too flat (max<$0.02)"
            candResult.beats.isEmpty() && snapped.isEmpty() -> "cand=0 & snapped=0 (threshold/minPeak too strict OR onset flat)"
            snapped.isEmpty() -> "snapped=0 (grid windows have no peaks above minPeak)"
            else -> "ok"
        }

        // ✅ 핵심 디버그 로그(이거 보고 원인 바로 좁혀짐)
        android.util.Log.d(
            tag,
            "BeatDbg: onset(n=$n) mean=${fmt(onsetMean)} std=${fmt(onsetStd)} max=${fmt(onsetMax)} " +
                    "lowMax=${fmt(lowMax)} midMax=${fmt(midMax)} " +
                    "cand=${candResult.beats.size} th~=${fmt(candResult.representativeTh)} passRate~=${fmt(candResult.passRate)} " +
                    "beatMs=$beatMs off=$offsetMs snapped=${snapped.size} " +
                    "snapBest(med/min/max)=${fmt(snapStats.median)}/${fmt(snapStats.min)}/${fmt(snapStats.max)} " +
                    "reason=$reason"
        )

        return out to Debug(
            onsetSize = n,
            lowFluxMax = lowMax,
            midFluxMax = midMax,
            onsetMax = onsetMax,
            onsetMean = onsetMean,
            onsetStd = onsetStd,

            candidates = candResult.beats.size,
            candMeanTh = candResult.representativeTh,
            candPassRate = candResult.passRate,

            beatMs = beatMs,
            offsetMs = offsetMs,
            snapped = snapped.size,

            snapWindowMs = params.snapWindowMs,
            snapBestValMedian = snapStats.median,
            snapBestValMin = snapStats.min,
            snapBestValMax = snapStats.max,

            reason = reason
        )
    }

    // ─────────────────────────────────────────────
    // Candidate picking with stats
    // ─────────────────────────────────────────────

    private data class CandStats(
        val beats: List<Long>,
        val representativeTh: Float,
        val passRate: Float
    )

    private fun pickPeaksAdaptiveWithStats(
        signal: FloatArray,
        hopMs: Long,
        durationMs: Long,
        minGapMs: Long,
        maWindowMs: Long,
        kStd: Float,
        minPeak: Float
    ): CandStats {
        val win = max(3, (maWindowMs / hopMs).toInt())
        val half = win / 2

        var lastBeat = Long.MIN_VALUE
        val beats = ArrayList<Long>()

        // stats sampling: threshold 통과율, 대표 threshold
        var pass = 0
        var total = 0
        val thSamples = ArrayList<Float>()

        for (i in 2 until signal.size - 2) {
            val tMs = i.toLong() * hopMs
            if (tMs > durationMs) break
            total++

            val a = (i - half).coerceAtLeast(0)
            val b = (i + half).coerceAtMost(signal.lastIndex)

            var sum = 0.0
            var sum2 = 0.0
            var c = 0
            for (j in a..b) {
                val v = signal[j].toDouble()
                sum += v
                sum2 += v * v
                c++
            }
            val mean = (sum / c).toFloat()
            val varr = (sum2 / c - mean.toDouble() * mean.toDouble()).coerceAtLeast(0.0)
            val std = sqrt(varr).toFloat()
            val th = mean + kStd * std

            if (i % 97 == 0) thSamples.add(th)

            val v = signal[i]
            if (v >= th && v >= minPeak) pass++

            if (tMs - lastBeat < minGapMs) continue

            val isPeak =
                v >= signal[i - 1] && v >= signal[i + 1] &&
                        v >= signal[i - 2] && v >= signal[i + 2]

            if (isPeak && v >= th && v >= minPeak) {
                beats.add(tMs)
                lastBeat = tMs
            }
        }

        val repTh = if (thSamples.isNotEmpty()) medianOf(thSamples) else 0f
        val passRate = if (total > 0) pass.toFloat() / total.toFloat() else 0f

        return CandStats(beats, repTh, passRate)
    }

    // ─────────────────────────────────────────────
    // Flux / normalize
    // ─────────────────────────────────────────────

    private fun positiveFlux(env: FloatArray): FloatArray {
        val out = FloatArray(env.size)
        var prev = env[0]
        for (i in 1 until env.size) {
            val d = env[i] - prev
            prev = env[i]
            out[i] = if (d > 0f) d else 0f
        }
        smoothInPlace(out, 1)
        return out
    }

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    // ─────────────────────────────────────────────
    // Tempo / phase
    // ─────────────────────────────────────────────

    private fun estimateBeatMsByAutocorr(
        novelty: FloatArray,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Long {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt().coerceAtMost(novelty.size - 1))

        var bestLag = ((500L / hopMs).toInt()).coerceIn(minLag, maxLag)
        var bestScore = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var s = 0.0
            var i = lag
            while (i < novelty.size) {
                s += (novelty[i] * novelty[i - lag]).toDouble()
                i++
            }
            if (s > bestScore) {
                bestScore = s
                bestLag = lag
            }
        }

        val beatMs = bestLag.toLong() * hopMs
        return beatMs.coerceIn(minBeatMs, maxBeatMs)
    }

    private fun estimatePhaseOffsetMs(novelty: FloatArray, hopMs: Long, beatMs: Long): Long {
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag <= 1) return 0L

        var bestOffsetIdx = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (offsetIdx in 0 until lag) {
            var s = 0.0
            var i = offsetIdx
            while (i < novelty.size) {
                s += novelty[i].toDouble()
                i += lag
            }
            if (s > bestScore) {
                bestScore = s
                bestOffsetIdx = offsetIdx
            }
        }
        return bestOffsetIdx.toLong() * hopMs
    }

    // ─────────────────────────────────────────────
    // Snapping + snap stats
    // ─────────────────────────────────────────────

    private data class SnapStats(val median: Float, val min: Float, val max: Float)

    private fun snapWindowStats(
        novelty: FloatArray,
        hopMs: Long,
        durationMs: Long,
        beatMs: Long,
        offsetMs: Long,
        snapWindowMs: Long
    ): SnapStats {
        val lag = max(1, (beatMs / hopMs).toInt())
        val win = max(1, (snapWindowMs / hopMs).toInt())
        val startIdx = ((offsetMs / hopMs).toInt()).coerceIn(0, novelty.lastIndex)

        val bestVals = ArrayList<Float>()
        var k = 0
        while (true) {
            val centerIdx = startIdx + k * lag
            if (centerIdx >= novelty.size) break
            val tMs = centerIdx.toLong() * hopMs
            if (tMs > durationMs) break

            val a = (centerIdx - win).coerceAtLeast(0)
            val b = (centerIdx + win).coerceAtMost(novelty.lastIndex)

            var best = 0f
            for (i in a..b) best = max(best, novelty[i])
            bestVals.add(best)

            k++
        }

        if (bestVals.isEmpty()) return SnapStats(0f, 0f, 0f)
        val med = medianOf(bestVals)
        var mn = Float.MAX_VALUE
        var mx = 0f
        for (v in bestVals) {
            mn = kotlin.math.min(mn, v)
            mx = kotlin.math.max(mx, v)
        }
        return SnapStats(med, mn, mx)
    }

    private fun pickBeatsByGridSnappingStrict(
        novelty: FloatArray,
        hopMs: Long,
        durationMs: Long,
        beatMs: Long,
        offsetMs: Long,
        snapWindowMs: Long,
        minPeak: Float,
        minGapMs: Long
    ): LongArray {
        val lag = max(1, (beatMs / hopMs).toInt())
        val win = max(1, (snapWindowMs / hopMs).toInt())
        val startIdx = ((offsetMs / hopMs).toInt()).coerceIn(0, novelty.lastIndex)

        val beats = ArrayList<Long>()
        var lastBeat = Long.MIN_VALUE
        var k = 0

        while (true) {
            val centerIdx = startIdx + k * lag
            if (centerIdx >= novelty.size) break

            val tMs = centerIdx.toLong() * hopMs
            if (tMs > durationMs) break

            val a = (centerIdx - win).coerceAtLeast(2)
            val b = (centerIdx + win).coerceAtMost(novelty.size - 3)

            var bestIdx = -1
            var bestVal = 0f
            for (i in a..b) {
                val v = novelty[i]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = i
                }
            }

            if (bestIdx >= 0 && bestVal >= minPeak) {
                val isPeak = bestVal >= novelty[bestIdx - 1] && bestVal >= novelty[bestIdx + 1]
                if (isPeak) {
                    val bt = bestIdx.toLong() * hopMs
                    if (bt - lastBeat >= minGapMs) {
                        beats.add(bt)
                        lastBeat = bt
                    }
                }
            }

            k++
        }

        return beats.toLongArray()
    }

    // ─────────────────────────────────────────────
    // Utils
    // ─────────────────────────────────────────────

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.size < win + 2) return
        val copy = x.copyOf()
        for (i in x.indices) {
            var s = 0f
            var c = 0
            val a = (i - win).coerceAtLeast(0)
            val b = (i + win).coerceAtMost(x.lastIndex)
            for (j in a..b) { s += copy[j]; c++ }
            x[i] = s / max(1, c)
        }
    }

    private fun meanStdMax(x: FloatArray): Triple<Float, Float, Float> {
        var sum = 0.0
        var sum2 = 0.0
        var mx = 0f
        for (v in x) {
            sum += v.toDouble()
            sum2 += (v * v).toDouble()
            mx = max(mx, v)
        }
        val n = max(1, x.size)
        val mean = (sum / n).toFloat()
        val varr = (sum2 / n - mean.toDouble() * mean.toDouble()).coerceAtLeast(0.0)
        val std = sqrt(varr).toFloat()
        return Triple(mean, std, mx)
    }

    private fun maxOfArray(x: FloatArray): Float {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        return mx
    }

    private fun medianOf(list: List<Float>): Float {
        val a = list.toFloatArray()
        a.sort()
        val mid = a.size / 2
        return if (a.size % 2 == 1) a[mid] else (a[mid - 1] + a[mid]) / 2f
    }

    private fun fmt(v: Float): String = String.format("%.3f", v)
}