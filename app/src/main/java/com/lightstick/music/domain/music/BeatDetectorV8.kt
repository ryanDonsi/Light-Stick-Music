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

    // harmonic folding: bestLag/2 correlation이 bestValue의 이 비율 이상이면 절반 채택 (빠른 곡)
    private const val HARMONIC_FOLD_HALF_RATIO = 0.40f
    // harmonic folding: bestLag/3 correlation이 bestValue의 이 비율 이상이면 1/3 채택 (빠른 곡)
    private const val HARMONIC_FOLD_THIRD_RATIO = 0.35f
    // harmonic doubling: bestLag×2 correlation이 bestValue의 이 비율 이상이면 2배 채택 (느린 곡)
    // 0.55f: 정상 속도 곡의 2배 주기 correlation이 일반적으로 50% 미만임을 감안한 보수적 기준
    // harmonic doubling: 0.55f → 0.80f로 상향 (ratio=1.3 수준 오작동 방지)
    // 로그 SEG[3]에서 ratio=1.295로 통과 → 1400ms 오선택 발생
    private const val HARMONIC_DOUBLE_RATIO = 0.80f

    // harmonic 2/3: bestLag * 2/3 교정 (1.5× 편향 보정)
    // SEG[5,6]에서 450ms 감지 → 실제 DOUBLE CHORUS 287ms ≈ 450 × 2/3 = 300ms
    // 조건: 2/3 lag이 minBeatMs 이상이고 maxBeatMs * 0.65 이하일 때만 적용
    private const val HARMONIC_TWO_THIRDS_RATIO = 0.75f

    // 조용한 기타/어쿠스틱 곡 대응 — 빠른 결과 주기에서 더 보수적인 fold ratio
    // fold 결과가 이 값(ms) 미만이면 harmonic ringing 오검출 가능성이 높다고 판단
    // 350ms: K-팝 실제 비트 하한(≈171 BPM)에 맞게 낮춤
    //   → 750ms→375ms fold 는 표준 비율(0.40) 적용 (기존 380 → 보수적 0.65 방지)
    //   → 700ms→350ms 경계, 600ms→300ms 이하는 여전히 보수적 0.65 유지
    private const val HARMONIC_FOLD_GUARD_MS = 350L
    // 빠른 결과 주기용 higher ratio (0.40 → 0.65): ringing 으로 인한 fold 억제
    private const val HARMONIC_FOLD_HALF_RATIO_FAST  = 0.65f
    private const val HARMONIC_FOLD_THIRD_RATIO_FAST = 0.55f

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
        val minBeatMs: Long = 290L,     // A. 수정: 350 → 290ms (≈207 BPM)
        val maxBeatMs: Long = 1200L,    // E. 수정: 900 → 1200ms (≈50 BPM)
        val minPeakDistanceMs: Long = 140L,  // 완화: 180→140ms
        val onsetSmoothWindow: Int = 3,
        val segmentMs: Long = 20_000L,
        val peakThresholdK: Float = 0.22f,  // 완화: 0.55→0.30→0.22 (낮은 에너지/잔잔한 곡 대응)
        val minPeakAbs: Float = 0.04f,  // 완화: 0.08→0.05→0.04
        val snapToleranceMs: Long = 150L,  // 120 → 150ms: 스냅 허용 범위 확대
        val chainToleranceMs: Long = 170L, // 140 → 170ms: 체인 연속성 오차 허용 확대
        val minChainCount: Int = 3     // 완화: 4→3 (짧은 구간도 체인 허용)
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
        // E. 짧은 곡 단일 세그먼트 처리
        // durationMs < 60초이면 전체를 1개 세그먼트로 처리
        // 이유: 32초 곡을 20초/12초로 나누면 각 세그먼트가 너무 짧아 autocorr 신뢰도 저하
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
                    // [수정] density-first 선택:
                    // 비트가 더 많고, 점수가 현재 best의 70% 이상이면 더 밀도 높은 소스를 우선 채택
                    // 이유: acPeak/snapRatio 기반 score만으로 선택하면 비트가 적은 소스가
                    //       autocorrelation이 강해서 선택되는 문제 발생 (900ms/26비트 사례)
                    val bestNonNull = best  // null 체크 이후 smart cast 보장용
                    val isBetter = when {
                        bestNonNull == null -> true
                        trial.beatTimesMs.size > bestNonNull.beatTimesMs.size &&
                                trial.score >= bestNonNull.score * 0.70f -> true
                        trial.beatTimesMs.size == bestNonNull.beatTimesMs.size &&
                                trial.score > bestNonNull.score -> true
                        else -> false
                    }
                    if (isBetter) {
                        Log.d(
                            TAG,
                            "SEG[$segIndex] best update: ${best?.source}(${best?.beatTimesMs?.size}beats) " +
                                    "→ ${trial.source}(${trial.beatTimesMs.size}beats) score=${fmt(trial.score)}"
                        )
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

        // ── Pass 2: failed segment recovery with consensus beatMs ─────────────
        // Pass 1에서 실패한 세그먼트를 consensus beatMs로 grid projection 재시도.
        // 많은 세그먼트가 동시에 실패하는 빠른 K-pop(예: 148 BPM)에서 커버리지 확장.
        val firstPassConsensusBeatMs = computeSegmentVoteBeatMs(segResults)
        if (firstPassConsensusBeatMs > 0L) {
            Log.d(TAG, "Pass2 start: consensusBeatMs=$firstPassConsensusBeatMs, scanning failed segments")
            for (i in segResults.indices) {
                val seg = segResults[i]
                if (seg.beatMs > 0L) continue  // 이미 성공한 세그먼트 건너뜀

                val s = seg.index * segmentFrames
                val e = min(minSize, s + segmentFrames)
                if (e - s < 8) continue

                val lowSeg = low.subList(s, e)
                val midSeg = mid.subList(s, e)
                val fullSeg = full.subList(s, e)
                val srcOrder = buildSourceOrder(lowSeg, midSeg, fullSeg)

                var bestFixed: TrialResult? = null
                for (src in srcOrder) {
                    val combined = combineSource(src, lowSeg, midSeg, fullSeg)
                    val trial = detectSingleSourceWithFixedBeatMs(
                        segmentIndex = seg.index,
                        source = src,
                        env = combined,
                        fixedBeatMs = firstPassConsensusBeatMs,
                        params = params
                    )
                    if (trial.reason == "grid-projected-fixed") {
                        val bn = bestFixed
                        val isBetter = when {
                            bn == null -> true
                            trial.beatTimesMs.size > bn.beatTimesMs.size &&
                                    trial.score >= bn.score * 0.70f -> true
                            trial.beatTimesMs.size == bn.beatTimesMs.size &&
                                    trial.score > bn.score -> true
                            else -> false
                        }
                        if (isBetter) bestFixed = trial
                    }
                }

                if (bestFixed != null) {
                    val absoluteBeats = bestFixed.beatTimesMs.map { it + seg.startMs }
                    mergedBeats += absoluteBeats
                    sourceVotes[bestFixed.source] = (sourceVotes[bestFixed.source] ?: 0) + 1
                    segResults[i] = SegmentResult(
                        index = seg.index,
                        startMs = seg.startMs,
                        endMs = seg.endMs,
                        selectedSource = bestFixed.source,
                        beatTimesMs = absoluteBeats,
                        beatMs = firstPassConsensusBeatMs,
                        score = bestFixed.score,
                        reason = "pass2",
                        trials = seg.trials
                    )
                    Log.d(TAG, "SEG[${seg.index}] Pass2 recovered: src=${bestFixed.source} " +
                            "beats=${absoluteBeats.size} beatMs=$firstPassConsensusBeatMs")
                }
            }
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

        // D. 수정: estimateMedianInterval에 Params 전달
        val rawBeatMs = estimateMedianInterval(deduped, params.minBeatMs, params.maxBeatMs)

        // E. 세그먼트 가중 투표로 교차 검증: 세그먼트 합의가 강하고 median과 5% 이상 차이나면 교정
        val segVoteBeatMs = computeSegmentVoteBeatMs(segResults)
        val finalBeatMs = if (segVoteBeatMs > 0L) {
            val diffRatio = abs(segVoteBeatMs - rawBeatMs).toDouble() / rawBeatMs
            if (diffRatio > 0.05) {
                Log.d(TAG, "segVote OVERRIDE: ${segVoteBeatMs}ms (raw=${rawBeatMs}ms diff=${String.format("%.0f", diffRatio * 100)}%)")
                segVoteBeatMs
            } else {
                Log.d(TAG, "segVote KEEP raw: segVote=${segVoteBeatMs}ms raw=${rawBeatMs}ms diff<5%")
                rawBeatMs
            }
        } else rawBeatMs

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

    // =========================================================================
    // Single source detection
    // =========================================================================

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

        // B. 수정: harmonic folding이 적용된 autoCorrelateBeat 사용
        val beatRange = autoCorrelateBeat(
            onset = onset,
            hopMs = params.hopMs,
            minBeatMs = params.minBeatMs,
            maxBeatMs = params.maxBeatMs
        )

        if (beatRange == null) {
            // C. Fallback: autocorr 실패 시 rawPeak 간격 중앙값으로 beatMs 추정
            // 잔잔한 곡/짧은 세그먼트에서 자기상관이 약해도 peak 간격은 유효할 수 있음
            val fallbackBeatMs = if (rawPeaks.size >= 3) {
                val intervals = (1 until rawPeaks.size)
                    .map { (rawPeaks[it] - rawPeaks[it - 1]).toLong() * params.hopMs }
                    .filter { it in params.minBeatMs..params.maxBeatMs }
                if (intervals.size >= 2) {
                    val sorted = intervals.sorted()
                    sorted[sorted.size / 2]  // 중앙값
                } else null
            } else null

            if (fallbackBeatMs != null) {
                // fallback beatMs로 재시도
                val snappedFb = snapPeaksToGrid(rawPeaks, onset, fallbackBeatMs, params.hopMs, params.snapToleranceMs)
                val chainedFb = keepConsistentChain(
                    snappedFrames = snappedFb,
                    expectedBeatMs = fallbackBeatMs,
                    hopMs = params.hopMs,
                    toleranceMs = params.chainToleranceMs
                )
                if (chainedFb.size >= 2) {  // fallback: minChainCount=2로 완화
                    val snapRatioFb = chainedFb.size.toFloat() / rawPeaks.size.toFloat()
                    val segDur = env.size.toLong() * params.hopMs
                    val expectedFb = max(1, (segDur / fallbackBeatMs).toInt())
                    val densityFb = min(1f, chainedFb.size.toFloat() / expectedFb.toFloat())
                    val scoreFb = (densityFb * 0.35f + snapRatioFb * 0.30f + 0.10f).coerceIn(0f, 1f)
                    Log.d(TAG, "onset fallback: beatMs=$fallbackBeatMs beats=${chainedFb.size} score=${fmt(scoreFb)}")
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

        // C. 수정: keepConsistentChain에 params 전달 (2× tolerance 강화)
        val chained = keepConsistentChain(
            snappedFrames = snapped,
            expectedBeatMs = beatMs,
            hopMs = params.hopMs,
            toleranceMs = params.chainToleranceMs
        )

        val finalBeatsMs = chained.map { it.toLong() * params.hopMs }
        val snapRatio = if (rawPeaks.isEmpty()) 0f else chained.size.toFloat() / rawPeaks.size.toFloat()

        // D. 짧은 세그먼트 적응형 minChainCount
        // 세그먼트 길이 < 15초이면 minChainCount=2로 완화 (12.8s SEG에서도 체인 허용)
        val segDurationMs = env.size.toLong() * params.hopMs
        val effectiveMinChain = if (segDurationMs < 15_000L) 2 else params.minChainCount

        if (finalBeatsMs.size < effectiveMinChain) {
            // autocorr이 유효한 주기를 반환했으나 rawPeak-snap 체인이 부족한 경우:
            // grid projection으로 onset 에너지 기반 등간격 비트 생성 시도
            // (K-pop 고속 곡에서 fold 결과가 맞는데 rawPeak가 드물어 chain 실패하는 상황 대응)
            val projFrames = gridProjectBeats(
                rawPeakFrames = rawPeaks,
                onset = onset,
                beatMs = beatMs,
                hopMs = params.hopMs,
                snapToleranceMs = params.snapToleranceMs,
                minBeats = effectiveMinChain
            )
            if (projFrames != null) {
                val projBeatsMs = projFrames.map { it.toLong() * params.hopMs }
                val tolF = max(1, (params.snapToleranceMs / params.hopMs).toInt())
                val projSnapRatio = if (rawPeaks.isEmpty()) 0f else
                    projFrames.count { pf -> rawPeaks.any { abs(it - pf) <= tolF } }
                        .toFloat() / rawPeaks.size.toFloat()
                val expBeats = max(1, (segDurationMs / beatMs).toInt())
                val densProj = min(1f, projBeatsMs.size.toFloat() / expBeats.toFloat())
                val scoreProj = (densProj * 0.40f + projSnapRatio * 0.30f +
                        acPeak * 0.20f + min(1f, onsetMax) * 0.10f).coerceIn(0f, 1f)
                Log.d(TAG, "grid-projected: beatMs=$beatMs beats=${projBeatsMs.size} score=${fmt(scoreProj)}")
                return TrialResult(
                    source = source,
                    beatTimesMs = projBeatsMs,
                    beatMs = beatMs,
                    score = scoreProj,
                    rawPeakCount = rawPeaks.size,
                    snappedCount = projFrames.size,
                    onsetMean = mean,
                    onsetStd = std,
                    onsetMax = onsetMax,
                    acPeak = acPeak,
                    snapRatio = projSnapRatio,
                    reason = "grid-projected"
                )
            }
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

        // [수정] densityScore: 고정값(8) 기준 → 세그먼트 길이 기반 기대 비트 수 대비 실제 비율
        // 기존: min(1f, beats.size / 8f) → 22비트와 2비트를 분별하지 못함
        // 수정: 실제 감지 비트 / (세그먼트 지속시간 / beatMs) 로 상대 비율 계산
        // segDurationMs는 위에서 이미 선언됨 (effectiveMinChain 계산에 사용)
        val expectedBeatsInSeg = max(1, (segDurationMs / beatMs).toInt())
        val densityScore = min(1f, finalBeatsMs.size.toFloat() / expectedBeatsInSeg.toFloat())

        // [수정] 가중치 재조정: density 우선 (0.20→0.40), acPeak 완화 (0.40→0.20)
        // acPeak는 비트가 드물어도 강하면 높게 나와서 sparse 소스를 선호하는 문제 보정
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

    // =========================================================================
    // Segment vote: beatMs 교차 검증
    // =========================================================================

    /**
     * 성공한 세그먼트들의 beatMs를 비트 수로 가중하여 투표.
     * estimateMedianInterval 이 세그먼트 간 갭 오염으로 틀릴 때 교정.
     *
     * 반환값: 지배적 클러스터가 있으면 그 중심 beatMs, 없으면 0L.
     */
    private fun computeSegmentVoteBeatMs(segResults: List<SegmentResult>): Long {
        val validSegs = segResults.filter { it.beatMs > 0L && it.beatTimesMs.isNotEmpty() }
        if (validSegs.isEmpty()) return 0L
        val totalBeats = validSegs.sumOf { it.beatTimesMs.size }
        if (totalBeats < 5) return 0L

        // ±15% 오차로 같은 클러스터에 묶음
        // (10% → 15%: 50ms hop 양자화에서 발생하는 350ms/400ms 같은 인접 값 통합)
        data class Cluster(var centerMs: Long, var beatCount: Int)
        val clusters = mutableListOf<Cluster>()
        for (seg in validSegs) {
            val bms = seg.beatMs; val bc = seg.beatTimesMs.size
            val match = clusters.firstOrNull { c ->
                val ratio = if (c.centerMs > bms) c.centerMs.toDouble() / bms
                            else bms.toDouble() / c.centerMs
                ratio <= 1.15
            }
            if (match != null) {
                match.centerMs = (match.centerMs * match.beatCount + bms * bc) / (match.beatCount + bc)
                match.beatCount += bc
            } else {
                clusters.add(Cluster(bms, bc))
            }
        }

        val dominant = clusters.maxByOrNull { it.beatCount } ?: return 0L
        val dominanceRatio = dominant.beatCount.toDouble() / totalBeats
        Log.d(TAG, "segVote clusters=${clusters.map { "${it.centerMs}ms×${it.beatCount}" }} " +
                "dominant=${dominant.centerMs}ms ratio=${String.format("%.2f", dominanceRatio)}")
        return if (dominanceRatio >= 0.60) dominant.centerMs else 0L
    }

    // =========================================================================
    // Source ordering & combination
    // =========================================================================

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

    // =========================================================================
    // B. autocorrelation + harmonic folding
    // =========================================================================

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

        // ① 전체 탐색 구간의 correlation 배열을 먼저 구한다
        val corrArray = FloatArray(maxLag + 1)  // 0f로 자동 초기화

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
        if (bestValue < 0.015f) return null     // 완화: 0.02→0.015 (낮은 에너지 곡 대응)

        // bestValue < 0.015f 체크 이후이므로 항상 양수. 직접 계산
        val confidence = (bestValue - secondValue).coerceAtLeast(0f) + bestValue
        if (confidence < 0.012f) return null    // 완화: 0.025→0.012 (잔잔한 곡/짧은 세그먼트)

        // ② harmonic folding: bestLag/2 검사 (빠른 곡 — 2배 편향 교정)
        // 조용한 기타/어쿠스틱 곡 대응:
        // 기타 harmonic ringing은 실제 비트 주기의 1/2, 1/3 에서도 상관값이 발생해
        // 정상 비트(1000ms)를 절반(500ms)이나 그 이하로 잘못 교정할 수 있음.
        // fold 결과가 HARMONIC_FOLD_GUARD_MS(380ms) 미만이면 ringing 오검출로 간주,
        // 더 높은 ratio를 요구해 fold를 억제한다.
        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfValue = corrArray[halfLag]
            val halfBeatMs = halfLag * hopMs
            val effectiveHalfRatio = if (halfBeatMs < HARMONIC_FOLD_GUARD_MS)
                HARMONIC_FOLD_HALF_RATIO_FAST else HARMONIC_FOLD_HALF_RATIO
            if (halfValue >= bestValue * effectiveHalfRatio) {
                Log.d(
                    TAG,
                    "harmonic fold (/2): bestLag=$bestLag (${bestLag * hopMs}ms) " +
                            "→ halfLag=$halfLag (${halfBeatMs}ms) " +
                            "halfCorr=${fmt(halfValue)} bestCorr=${fmt(bestValue)} " +
                            "ratio=${fmt(halfValue / bestValue)} threshold=${fmt(effectiveHalfRatio)}"
                )
                // halfLag 자체도 추가 교정 대상인지 재귀적으로 확인 (최대 1회)
                val quarterLag = halfLag / 2
                if (quarterLag >= minLag) {
                    val quarterValue = corrArray[quarterLag]
                    val quarterBeatMs = quarterLag * hopMs
                    val effectiveQuarterRatio = if (quarterBeatMs < HARMONIC_FOLD_GUARD_MS)
                        HARMONIC_FOLD_HALF_RATIO_FAST else HARMONIC_FOLD_HALF_RATIO
                    if (quarterValue >= halfValue * effectiveQuarterRatio) {
                        Log.d(
                            TAG,
                            "harmonic fold (/4): halfLag=$halfLag (${halfBeatMs}ms) " +
                                    "→ quarterLag=$quarterLag (${quarterBeatMs}ms) " +
                                    "quarterCorr=${fmt(quarterValue)} halfCorr=${fmt(halfValue)} " +
                                    "threshold=${fmt(effectiveQuarterRatio)}"
                        )
                        return quarterBeatMs to quarterValue.coerceIn(0f, 1f)
                    }
                }
                return halfBeatMs to halfValue.coerceIn(0f, 1f)
            }
        }

        // ③ harmonic folding: bestLag/3 검사 (빠른 곡 — 3배 편향 교정)
        val thirdLag = bestLag / 3
        if (thirdLag >= minLag) {
            val thirdValue = corrArray[thirdLag]
            val thirdBeatMs = thirdLag * hopMs
            val effectiveThirdRatio = if (thirdBeatMs < HARMONIC_FOLD_GUARD_MS)
                HARMONIC_FOLD_THIRD_RATIO_FAST else HARMONIC_FOLD_THIRD_RATIO
            if (thirdValue >= bestValue * effectiveThirdRatio) {
                Log.d(
                    TAG,
                    "harmonic fold (/3): bestLag=$bestLag (${bestLag * hopMs}ms) " +
                            "→ thirdLag=$thirdLag (${thirdBeatMs}ms) " +
                            "thirdCorr=${fmt(thirdValue)} bestCorr=${fmt(bestValue)} " +
                            "ratio=${fmt(thirdValue / bestValue)} threshold=${fmt(effectiveThirdRatio)}"
                )
                return thirdBeatMs to thirdValue.coerceIn(0f, 1f)
            }
        }

        // ④-pre: harmonic two-thirds (×2/3): bestLag×2/3 검사 (1.5× 편향 교정)
        // 대상: 450ms 감지 시 300ms로 교정 → DOUBLE CHORUS(287ms) 근사
        // 조건: 2/3 lag이 minBeatMs 이상 && maxBeatMs × 0.65 이하
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
                Log.d(TAG, "harmonic two-thirds (×2/3): bestLag=$bestLag (${bestLag * hopMs}ms) " +
                        "→ ttLag=$twoThirdLag (${twoThirdBeatMs}ms) " +
                        "ttCorr=${fmt(ttValue)} bestCorr=${fmt(bestValue)} ratio=${fmt(ttValue / bestValue)}")
                return twoThirdBeatMs to ttValue.coerceIn(0f, 1f)
            }
        }

        // ④ harmonic doubling: bestLag×2 검사 (느린 곡 — 절반 속도 편향 교정)
        // 조건: bestLag가 maxBeatMs의 55% 초과일 때만 적용
        //   이유: 130 BPM(461ms)처럼 정상 속도 곡에서도 2배 주기(922ms)의 correlation이
        //         HARMONIC_DOUBLE_RATIO를 넘을 수 있어 오 교정이 발생함.
        //   '느린 곡'은 본래 탐색 범위 상한 부근에서 검출됐을 때만 의심해야 함.
        //   예) maxBeatMs=1200ms → 660ms(55%) 초과 시만 doubling 검사
        val doubleGuardMs = (maxBeatMs * 0.55f).toLong()
        val doubleLag = bestLag * 2
        val doubleValue: Float = when {
            // corrArray 범위 안에 있으면 그대로 사용
            doubleLag <= maxLag -> corrArray[doubleLag]
            // corrArray 범위 밖이지만 onset이 충분히 길면 직접 계산
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
            doubleBeatMs <= maxBeatMs          // 추가: doubleMs가 maxBeatMs를 넘으면 채택 금지
        ) {
            Log.d(
                TAG,
                "harmonic double (×2): bestLag=$bestLag (${bestLag * hopMs}ms) guard=${doubleGuardMs}ms " +
                        "→ doubleLag=$doubleLag (${doubleBeatMs}ms) " +
                        "doubleCorr=${fmt(doubleValue)} bestCorr=${fmt(bestValue)} " +
                        "ratio=${fmt(doubleValue / bestValue)}"
            )
            return doubleBeatMs to doubleValue.coerceIn(0f, 1f)
        }

        return bestLag * hopMs to bestValue.coerceIn(0f, 1f)
    }

    // =========================================================================
    // Peak snapping & grid fitting
    // =========================================================================

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

        // ─────────────────────────────────────────────────────────────
        // Phase refinement: 정박(downbeat) 정렬
        //
        // 문제: rawPeak 앵커에서 출발한 그리드가 실제 비트 위상과
        //       어긋나 있으면 모든 비트가 일정량 앞/뒤로 치우친다.
        //       (guitar pickup note, 어쿠스틱 어택 지연 등)
        //
        // 해결: 최적 그리드를 결정한 뒤 ±half-beat 범위에서
        //       onset 에너지가 가장 높은 위상 오프셋을 탐색,
        //       전체 그리드를 해당 오프셋만큼 이동한다.
        //       (rawPeak가 없는 위치도 onset 에너지 창으로 평가)
        if (bestGrid.size >= 2) {
            bestGrid = refineGridPhase(bestGrid, onset, beatFrames, tolFrames, hopMs)
        }
        return bestGrid
    }

    /**
     * 전체 그리드를 ±halfBeat 범위에서 위상 이동하며
     * onset 에너지 창(window) 합계가 최대인 오프셋을 찾아 그리드를 정렬한다.
     *
     * scoreGridWithWindow: rawPeak 유무 관계없이 각 그리드 위치 주변
     * tolFrames 내 최대 onset 값을 합산해 위상 품질을 평가한다.
     */
    private fun refineGridPhase(
        grid: List<Int>,
        onset: List<Float>,
        beatFrames: Int,
        tolFrames: Int,
        hopMs: Long
    ): List<Int> {
        if (grid.size < 2 || beatFrames <= 0) return grid
        val halfBeat = max(1, beatFrames / 2)
        var bestShift = 0
        var bestScore = scoreGridWithWindow(grid, onset, tolFrames)
        for (shift in -halfBeat until halfBeat) {
            if (shift == 0) continue
            val score = scoreGridWithWindow(
                grid.map { (it + shift).coerceIn(0, onset.size - 1) }, onset, tolFrames
            )
            if (score > bestScore) { bestScore = score; bestShift = shift }
        }
        if (bestShift == 0) return grid
        Log.d(TAG, "phase refine shift=${bestShift}f (${bestShift * hopMs}ms) score=${fmt(bestScore)}")
        return grid.map { (it + bestShift).coerceIn(0, onset.size - 1) }.distinct().sorted()
    }

    /** 그리드 각 위치 ±tolFrames 창 내 최대 onset 값 합산 */
    private fun scoreGridWithWindow(grid: List<Int>, onset: List<Float>, tolFrames: Int): Float {
        var score = 0f
        for (g in grid) {
            val lo = max(0, g - tolFrames)
            val hi = min(onset.lastIndex, g + tolFrames)
            var peak = 0f
            for (i in lo..hi) if (onset[i] > peak) peak = onset[i]
            score += peak
        }
        return score
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
                // 정상 비트 간격
                abs(diffMs - expected) <= tol -> kept += cur

                // C. 수정: 2배 간격 — tolerance를 1.2f → 0.6f로 타이트하게
                // fill, pickup 등 일시적 1비트 누락은 허용하되
                // 반속 그리드 전체가 통과하는 것을 방지
                abs(diffMs - expected * 2f) <= tol * 0.6f -> kept += cur

                // 0.5배 간격 (서브비트, 비트 밀도 높은 구간) — 기존과 동일
                abs(diffMs - expected * 0.5f) <= tol * 0.8f -> kept += cur
            }
        }
        return kept
    }

    // =========================================================================
    // Grid projection: rawPeak 없이 onset 에너지 기반 등간격 비트 생성
    // =========================================================================

    /**
     * 비트 주기가 알려져 있을 때 onset 에너지 창으로 그리드를 평가해 합성 비트 시퀀스를 반환.
     *
     * 사용 목적:
     * - autocorrelation fold가 유효한 beatMs를 반환했으나 rawPeak가 드물어
     *   snap→chain이 짧아지는 경우(chain too short)의 대안.
     * - Pass 2: 전체 합의 beatMs로 실패 세그먼트를 재시도할 때.
     *
     * rawPeak 앵커 + frame0 + onset 최대값 위치를 모두 시도,
     * scoreGridWithWindow(onset 에너지 창 합산)이 최고인 그리드를 반환한다.
     *
     * 품질 기준: per-beat 점수 ≥ max(meanOnset × 0.30, 0.008)
     */
    private fun gridProjectBeats(
        rawPeakFrames: List<Int>,
        onset: List<Float>,
        beatMs: Long,
        hopMs: Long,
        snapToleranceMs: Long,
        minBeats: Int
    ): List<Int>? {
        if (onset.isEmpty() || beatMs <= 0L) return null
        val beatFrames = max(1, (beatMs / hopMs).toInt())
        val tolFrames = max(1, (snapToleranceMs / hopMs).toInt())

        // 앵커 후보: rawPeaks + frame 0 + onset 최대값 위치
        val anchors = LinkedHashSet<Int>()
        anchors.add(0)
        rawPeakFrames.forEach { anchors.add(it) }
        var maxIdx = 0
        for (i in onset.indices) if (onset[i] > onset[maxIdx]) maxIdx = i
        anchors.add(maxIdx)

        var bestGrid: List<Int>? = null
        var bestPerBeatScore = 0f

        for (anchor in anchors) {
            val grid = ArrayList<Int>()
            var g = anchor
            while (g >= 0) { grid.add(g); g -= beatFrames }
            g = anchor + beatFrames
            while (g < onset.size) { grid.add(g); g += beatFrames }
            val sorted = grid.distinct().sorted()
            if (sorted.size < minBeats) continue

            val perBeatScore = scoreGridWithWindow(sorted, onset, tolFrames) / sorted.size.toFloat()
            if (perBeatScore > bestPerBeatScore) {
                bestPerBeatScore = perBeatScore
                bestGrid = sorted
            }
        }

        if (bestGrid == null || (bestGrid?.size ?: 0) < minBeats) return null

        val meanOnset = meanOf(onset)
        val qualThreshold = max(meanOnset * 0.30f, 0.008f)
        if (bestPerBeatScore < qualThreshold) {
            Log.d(TAG, "gridProject rejected: perBeat=${fmt(bestPerBeatScore)} < threshold=${fmt(qualThreshold)}")
            return null
        }
        return bestGrid
    }

    // =========================================================================
    // Pass 2: fixed-beatMs grid projection for failed segments
    // =========================================================================

    /**
     * autocorr을 건너뛰고 fixedBeatMs로 grid projection만 시도.
     * Pass 2에서 첫 번째 패스에 실패한 세그먼트를 consensus beatMs로 재시도할 때 사용.
     */
    private fun detectSingleSourceWithFixedBeatMs(
        segmentIndex: Int,
        source: BeatSource,
        env: List<Float>,
        fixedBeatMs: Long,
        params: Params
    ): TrialResult {
        if (env.size < 8) return TrialResult(
            source = source, beatTimesMs = emptyList(), beatMs = 0L, score = 0f,
            rawPeakCount = 0, snappedCount = 0, onsetMean = 0f, onsetStd = 0f, onsetMax = 0f,
            acPeak = 0f, snapRatio = 0f, reason = "env too short"
        )
        val smooth = movingAverage(env, params.onsetSmoothWindow)
        val diff = positiveDiff(smooth)
        val onset = normalize01(diff)

        val mean = meanOf(onset)
        val std = stdOf(onset, mean)
        val onsetMax = maxOfList(onset)

        val threshold = max(params.minPeakAbs, mean + std * params.peakThresholdK)
        val minPeakFrames = max(1, (params.minPeakDistanceMs / params.hopMs).toInt())
        val rawPeaks = findPeaks(onset, threshold, minPeakFrames)

        val segDurationMs = env.size.toLong() * params.hopMs
        val effectiveMinChain = if (segDurationMs < 15_000L) 2 else params.minChainCount

        val projFrames = gridProjectBeats(
            rawPeakFrames = rawPeaks,
            onset = onset,
            beatMs = fixedBeatMs,
            hopMs = params.hopMs,
            snapToleranceMs = params.snapToleranceMs,
            minBeats = effectiveMinChain
        ) ?: return TrialResult(
            source = source, beatTimesMs = emptyList(), beatMs = fixedBeatMs, score = 0.1f,
            rawPeakCount = rawPeaks.size, snappedCount = 0, onsetMean = mean, onsetStd = std,
            onsetMax = onsetMax, acPeak = 0f, snapRatio = 0f, reason = "grid-project failed"
        )

        val projBeatsMs = projFrames.map { it.toLong() * params.hopMs }
        val tolF = max(1, (params.snapToleranceMs / params.hopMs).toInt())
        val projSnapRatio = if (rawPeaks.isEmpty()) 0f else
            projFrames.count { pf -> rawPeaks.any { abs(it - pf) <= tolF } }
                .toFloat() / rawPeaks.size.toFloat()
        val expBeats = max(1, (segDurationMs / fixedBeatMs).toInt())
        val densProj = min(1f, projBeatsMs.size.toFloat() / expBeats.toFloat())
        val scoreProj = (densProj * 0.40f + projSnapRatio * 0.30f +
                min(1f, onsetMax) * 0.10f).coerceIn(0f, 1f)
        Log.d(TAG, "SEG[$segmentIndex] fixedBeat: beatMs=$fixedBeatMs beats=${projBeatsMs.size} score=${fmt(scoreProj)}")
        return TrialResult(
            source = source,
            beatTimesMs = projBeatsMs,
            beatMs = fixedBeatMs,
            score = scoreProj,
            rawPeakCount = rawPeaks.size,
            snappedCount = projFrames.size,
            onsetMean = mean,
            onsetStd = std,
            onsetMax = onsetMax,
            acPeak = 0f,
            snapRatio = projSnapRatio,
            reason = "grid-projected-fixed"
        )
    }

    // =========================================================================
    // D. estimateMedianInterval — 유효 범위를 Params와 정렬
    // =========================================================================

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
            // D. 수정: 고정 범위(250..1200) → Params 기반 범위
            if (d in minBeatMs..maxBeatMs) diffs += d
        }
        if (diffs.isEmpty()) return 500L
        val sorted = diffs.sorted()
        return sorted[sorted.size / 2]
    }

    // =========================================================================
    // Utility
    // =========================================================================

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