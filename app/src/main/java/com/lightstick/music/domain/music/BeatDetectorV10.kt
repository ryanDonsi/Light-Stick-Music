package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BeatDetectorV10
 *
 * V9 대비 개선 사항:
 * ① Multi-Hypothesis Tracking  — 세그먼트 간 BPM 연속성 가중치로 템포 오검출 감소
 * ② Downbeat 감지              — 마디 첫 박(1박) 위치를 에너지 패턴으로 추정
 * ③ 박자 분모 감지              — 4/4, 3/4, 6/8 자동 판별 (전곡 ODF 자기상관 기반)
 * ④ 비트별 신뢰도(confidence)   — ODF 피크 값 기반 TimedBeat 반환 (하위 호환 beatTimesMs 유지)
 * ⑤ 다중 대역 Spectral Flux    — 3개 대역 독립 ODF 합산, SPECTRAL_FLUX 소스 추가
 * ⑥ 적응형 segmentMs           — 곡 길이·추정 BPM에 따라 20s/30s/전체 자동 선택
 */
object BeatDetectorV10 {

    private const val TAG = "AutoTimeline"

    // 고조파 접기 비율 (V9와 동일)
    private const val HARMONIC_FOLD_HALF_RATIO   = 0.40f
    private const val HARMONIC_FOLD_THIRD_RATIO  = 0.35f
    private const val HARMONIC_DOUBLE_RATIO      = 0.80f
    private const val HARMONIC_TWO_THIRDS_RATIO  = 0.75f

    // ODF 정규화 윈도우
    private const val LOCAL_NORM_WINDOW  = 60   // 세그먼트 ODF (3초 @ 50ms hop)
    private const val GLOBAL_NORM_WINDOW = 80   // 전곡 ODF (4초 @ 50ms hop)

    // ③ 박자 감지 임계값
    private const val TIME_SIG_THREE_RATIO = 1.20f  // 3/4: lag×3 corr이 lag×4보다 20% 이상
    private const val TIME_SIG_SIX_RATIO   = 1.25f  // 6/8: lag×6 corr이 lag×4보다 25% 이상

    // ① Multi-Hypothesis 연속성 판정 범위 (12% 이내면 이전 세그먼트 BPM과 연속)
    private const val CONTINUITY_MAX_RATIO = 0.12f

    // ⑤ Spectral Flux 대역별 가중치
    private const val FLUX_LOW_WEIGHT  = 0.50f
    private const val FLUX_MID_WEIGHT  = 0.35f
    private const val FLUX_FULL_WEIGHT = 0.15f

    // ④ 비트 신뢰도 데이터 클래스
    data class TimedBeat(
        val timeMs: Long,
        val confidence: Float  // 0.0~1.0, ODF 값 기반
    )

    // ③ 박자 타입
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

    // ⑤ SPECTRAL_FLUX 소스 추가
    enum class BeatSource {
        LOW, MID, FULL, LOW_MID, MID_FULL, LOW_FULL,
        SPECTRAL_FLUX
    }

    data class Params(
        val hopMs: Long             = 50L,
        val minBeatMs: Long         = 290L,
        val maxBeatMs: Long         = 1200L,
        val minPeakDistanceMs: Long = 140L,
        val onsetSmoothWindow: Int  = 5,
        val segmentMs: Long         = 20_000L,
        val peakThresholdK: Float   = 0.55f,
        val minPeakAbs: Float       = 0.08f,
        val snapToleranceMs: Long   = 100L,
        val chainToleranceMs: Long  = 120L,
        val minChainCount: Int      = 3,
        // V10 신규
        val useAdaptiveSegment: Boolean = true,   // ⑥
        val continuityBonus: Float      = 0.08f   // ①
    )

    data class DetectResult(
        val beats: List<TimedBeat>,         // ④ confidence 포함 비트 목록
        val beatMs: Long,
        val source: BeatSource?,
        val reason: String,
        val downbeatOffsetMs: Long,         // ② 첫 비트로부터 첫 downbeat까지 오프셋(ms)
        val timeSignature: TimeSignature,   // ③ 박자
        val debugSegments: List<SegmentResult>
    ) {
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }  // 하위 호환
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
        val timedBeats: List<TimedBeat>,   // ④ confidence 포함
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
            return DetectResult(emptyList(), 0L, null, "empty env",
                0L, TimeSignature.FOUR_FOUR, emptyList())
        }

        val minSize = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        val low  = lowEnv.take(minSize)
        val mid  = midEnv.take(minSize)
        val full = fullEnv.take(minSize)
        val durationMs = minSize * params.hopMs

        // ⑤ 다중 대역 Spectral Flux로 전곡 글로벌 BPM 추정 (globalOnset도 함께 반환)
        val (globalBeatMs, globalOnset) = estimateGlobalBpmMultiBand(low, mid, full, params)
        Log.d(TAG, "V10 globalBeatMs=$globalBeatMs durationMs=$durationMs")

        // ⑥ 적응형 segmentMs
        val effectiveSegmentMs = if (params.useAdaptiveSegment) {
            adaptiveSegmentMs(durationMs, globalBeatMs)
        } else {
            if (durationMs < 60_000L) durationMs else params.segmentMs
        }
        val segmentFrames = max(1, (effectiveSegmentMs / params.hopMs).toInt())
        val segmentCount  = (minSize + segmentFrames - 1) / segmentFrames

        Log.d(TAG, "V10 segments=$segmentCount effectiveSegmentMs=$effectiveSegmentMs " +
                "minBeatMs=${params.minBeatMs} maxBeatMs=${params.maxBeatMs}")

        val segResults       = ArrayList<SegmentResult>()
        val mergedTimedBeats = ArrayList<TimedBeat>()
        val sourceVotes      = LinkedHashMap<BeatSource, Int>()
        var prevBeatMs: Long? = null  // ① 이전 세그먼트 BPM 연속성 추적

        for (segIndex in 0 until segmentCount) {
            val s = segIndex * segmentFrames
            val e = min(minSize, s + segmentFrames)
            if (e - s < 8) continue

            val lowSeg  = low.subList(s, e)
            val midSeg  = mid.subList(s, e)
            val fullSeg = full.subList(s, e)
            val segStartMs = s.toLong() * params.hopMs
            val segEndMs   = e.toLong() * params.hopMs

            // ⑤ SPECTRAL_FLUX 소스용 다중 대역 ODF 사전 계산
            val spectralFluxOdf = computeMultiBandFlux(
                lowSeg, midSeg, fullSeg, params.onsetSmoothWindow)

            val srcOrder = buildSourceOrder(lowSeg, midSeg, fullSeg, spectralFluxOdf)
            Log.d(TAG,
                "SEG[$segIndex] srcOrder=$srcOrder " +
                "lowVar=${fmt(varOf(lowSeg))} midVar=${fmt(varOf(midSeg))} " +
                "fullVar=${fmt(varOf(fullSeg))} fluxVar=${fmt(varOf(spectralFluxOdf))}")

            val trials   = ArrayList<TrialResult>()
            val okTrials = ArrayList<TrialResult>()

            for (src in srcOrder) {
                val combinedEnv: List<Float>
                val precomputedOdf: List<Float>?
                if (src == BeatSource.SPECTRAL_FLUX) {
                    combinedEnv    = emptyList()
                    precomputedOdf = spectralFluxOdf
                } else {
                    combinedEnv    = combineSource(src, lowSeg, midSeg, fullSeg)
                    precomputedOdf = null
                }

                val trial = detectSingleSource(
                    segmentIndex   = segIndex,
                    source         = src,
                    env            = combinedEnv,
                    precomputedOdf = precomputedOdf,
                    globalBeatMs   = globalBeatMs,
                    params         = params
                )
                trials += trial

                Log.d(TAG,
                    "SEG[$segIndex] try=${trial.source} beats=${trial.timedBeats.size} " +
                    "beatMs=${trial.beatMs} score=${fmt(trial.score)} " +
                    "acPeak=${fmt(trial.acPeak)} snapRatio=${fmt(trial.snapRatio)} " +
                    "reason=${trial.reason}")

                if (trial.reason == "ok") okTrials += trial
            }

            // ① Multi-Hypothesis: 연속성 가중치 적용 후 최선 선택
            val best = selectBestWithContinuity(okTrials, prevBeatMs, params.continuityBonus)

            if (best == null) {
                Log.w(TAG, "SEG[$segIndex] $segStartMs-$segEndMs FAIL -> skip")
                segResults += SegmentResult(segIndex, segStartMs, segEndMs,
                    null, emptyList(), 0L, 0f, "all failed", trials)
                continue
            }

            val absTimedBeats = best.timedBeats.map { it.copy(timeMs = it.timeMs + segStartMs) }
            mergedTimedBeats += absTimedBeats
            sourceVotes[best.source] = (sourceVotes[best.source] ?: 0) + 1
            prevBeatMs = best.beatMs  // ① 다음 세그먼트로 전달

            Log.d(TAG,
                "SEG[$segIndex] $segStartMs-$segEndMs best=${best.source} " +
                "beats=${best.timedBeats.size} beatMs=${best.beatMs} " +
                "score=${fmt(best.score)} prevBeatMs=$prevBeatMs")

            segResults += SegmentResult(segIndex, segStartMs, segEndMs,
                best.source, absTimedBeats, best.beatMs, best.score, best.reason, trials)
        }

        val dedupedBeats = dedupeCloseTimedBeats(
            mergedTimedBeats.sortedBy { it.timeMs }, params.minPeakDistanceMs)

        if (dedupedBeats.isEmpty()) {
            Log.w(TAG, "V10 beat detect FAIL -> return empty")
            return DetectResult(emptyList(), 0L, null, "all segments failed",
                0L, TimeSignature.FOUR_FOUR, segResults)
        }

        val beatTimesList = dedupedBeats.map { it.timeMs }
        val rawBeatMs = estimateMedianInterval(beatTimesList, params.minBeatMs, params.maxBeatMs, params.hopMs)

        val finalBeatMs = if (globalBeatMs != null &&
            globalBeatMs < rawBeatMs &&
            rawBeatMs - globalBeatMs < rawBeatMs / 6L) {
            Log.d(TAG, "V10 finalBeatMs: globalBeatMs=$globalBeatMs preferred over rawBeatMs=$rawBeatMs")
            globalBeatMs
        } else {
            rawBeatMs
        }
        val finalSource = sourceVotes.maxByOrNull { it.value }?.key

        // ③ 전곡 ODF로 박자 감지
        val timeSignature = detectTimeSignature(globalOnset, finalBeatMs, params.hopMs)
        Log.d(TAG, "V10 timeSignature=${timeSignature.type} " +
                "(${timeSignature.numerator}/${timeSignature.denominator})")

        // ② 다운비트 감지
        val downbeatMs = detectDownbeat(beatTimesList, full, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (beatTimesList.firstOrNull() ?: 0L)).coerceAtLeast(0L)
        Log.d(TAG, "V10 downbeatMs=$downbeatMs offsetMs=${downbeatOffsetMs}ms")

        Log.d(TAG,
            "V10 detect OK source=$finalSource totalBeats=${dedupedBeats.size} " +
            "beatMs=$finalBeatMs timeSignature=${timeSignature.type} " +
            "downbeatOffset=${downbeatOffsetMs}ms " +
            "first=${beatTimesList.firstOrNull()} last=${beatTimesList.lastOrNull()}")

        return DetectResult(
            beats             = dedupedBeats,
            beatMs            = finalBeatMs,
            source            = finalSource,
            reason            = "ok",
            downbeatOffsetMs  = downbeatOffsetMs,
            timeSignature     = timeSignature,
            debugSegments     = segResults
        )
    }

    // =========================================================================
    // ⑤ 전곡 다중 대역 Spectral Flux 기반 글로벌 BPM 추정
    // =========================================================================

    private fun estimateGlobalBpmMultiBand(
        low: List<Float>, mid: List<Float>, full: List<Float>,
        params: Params
    ): Pair<Long?, List<Float>> {
        val onset  = computeMultiBandFlux(low, mid, full,
            smoothWindow = 5, normWindow = GLOBAL_NORM_WINDOW)
        val beatMs = autoCorrelateBeat(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)?.first
        return Pair(beatMs, onset)
    }

    // ⑤ 3개 대역 독립 ODF → 가중 합산 (Spectral Flux 근사)
    private fun computeMultiBandFlux(
        low: List<Float>, mid: List<Float>, full: List<Float>,
        smoothWindow: Int,
        normWindow: Int = LOCAL_NORM_WINDOW
    ): List<Float> {
        val n       = minOf(low.size, mid.size, full.size)
        val lowOdf  = computeOdf(low.take(n),  smoothWindow, normWindow)
        val midOdf  = computeOdf(mid.take(n),  smoothWindow, normWindow)
        val fullOdf = computeOdf(full.take(n), smoothWindow, normWindow)
        return List(n) { i ->
            (lowOdf[i]  * FLUX_LOW_WEIGHT +
             midOdf[i]  * FLUX_MID_WEIGHT +
             fullOdf[i] * FLUX_FULL_WEIGHT).coerceIn(0f, 1f)
        }
    }

    // =========================================================================
    // Single source detection
    // =========================================================================

    private fun detectSingleSource(
        segmentIndex: Int,
        source: BeatSource,
        env: List<Float>,
        precomputedOdf: List<Float>? = null,   // ⑤ SPECTRAL_FLUX용 사전 계산 ODF
        globalBeatMs: Long?,
        params: Params
    ): TrialResult {
        val envSize = precomputedOdf?.size ?: env.size
        if (envSize < 8) {
            return TrialResult(source, emptyList(), 0L, 0f, 0, 0, 0f, 0f, 0f, 0f, 0f, "env too short")
        }

        // ⑤ 사전 계산 ODF(SPECTRAL_FLUX) 또는 단일 소스 ODF
        val onset    = precomputedOdf ?: computeOdf(env, params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val mean     = meanOf(onset)
        val std      = stdOf(onset, mean)
        val onsetMax = maxOfList(onset)

        val threshold     = max(params.minPeakAbs, mean + std * params.peakThresholdK)
        val minPeakFrames = max(1, (params.minPeakDistanceMs / params.hopMs).toInt())
        val rawPeaks      = findPeaks(onset, threshold, minPeakFrames)

        val acResult = autoCorrelateBeat(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)

        val beatMs: Long
        val acPeak: Float
        when {
            acResult != null -> {
                beatMs = acResult.first; acPeak = acResult.second
            }
            globalBeatMs != null -> {
                beatMs = globalBeatMs; acPeak = 0.5f
                Log.d(TAG, "SEG[$segmentIndex] autocorr weak → globalBeatMs=$globalBeatMs fallback")
            }
            else -> {
                val fallbackMs = rawPeakMedianInterval(rawPeaks, params)
                if (fallbackMs != null) {
                    val snappedFb = snapPeaksToGrid(rawPeaks, onset, fallbackMs,
                        params.hopMs, params.snapToleranceMs)
                    val chainedFb = keepConsistentChain(snappedFb, fallbackMs,
                        params.hopMs, params.chainToleranceMs)
                    if (chainedFb.size >= 2) {
                        val snapRatioFb = chainedFb.size.toFloat() / rawPeaks.size.coerceAtLeast(1).toFloat()
                        val segDur      = onset.size.toLong() * params.hopMs
                        val expectedFb  = max(1, (segDur / fallbackMs).toInt())
                        val densityFb   = min(1f, chainedFb.size.toFloat() / expectedFb.toFloat())
                        val scoreFb     = (densityFb * 0.35f + snapRatioFb * 0.30f + 0.10f).coerceIn(0f, 1f)
                        // ④ 폴백 비트는 낮은 신뢰도(ODF × 0.6)
                        val timedFb = chainedFb.map { fr ->
                            TimedBeat(fr.toLong() * params.hopMs,
                                (onset.getOrElse(fr) { 0f } * 0.6f).coerceIn(0f, 1f))
                        }
                        Log.d(TAG, "onset fallback: beatMs=$fallbackMs beats=${chainedFb.size} score=${fmt(scoreFb)}")
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
            return TrialResult(source, emptyList(), beatMs, 0.2f + acPeak * 0.5f + snapRatio * 0.1f,
                rawPeaks.size, chained.size, mean, std, onsetMax, acPeak, snapRatio, "chain too short")
        }

        // ④ 비트별 confidence: ODF 피크값 그대로 사용
        val timedBeats = chained.map { frame ->
            TimedBeat(
                timeMs     = frame.toLong() * params.hopMs,
                confidence = onset.getOrElse(frame) { 0f }.coerceIn(0f, 1f)
            )
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
    // ① Multi-Hypothesis: 연속성 가중치 기반 최선 선택
    // =========================================================================

    private fun selectBestWithContinuity(
        okTrials: List<TrialResult>,
        prevBeatMs: Long?,
        continuityBonus: Float
    ): TrialResult? {
        if (okTrials.isEmpty()) return null
        if (okTrials.size == 1 || prevBeatMs == null || prevBeatMs <= 0L) {
            return okTrials.maxByOrNull { it.score }
        }

        val best = okTrials.maxByOrNull { trial ->
            val bonus = if (trial.beatMs > 0L) {
                val ratio = abs(trial.beatMs - prevBeatMs).toFloat() / prevBeatMs.toFloat()
                if (ratio <= CONTINUITY_MAX_RATIO) continuityBonus else 0f
            } else 0f
            trial.score + bonus
        }

        if (best != null) {
            val boosted = okTrials.any { it != best && abs(it.beatMs - prevBeatMs) * 100L < prevBeatMs * CONTINUITY_MAX_RATIO.toLong() }
            Log.d(TAG, "selectBestWithContinuity prevBeatMs=$prevBeatMs → best=${best.source} " +
                    "beatMs=${best.beatMs} score=${fmt(best.score)} continuityBoosted=$boosted")
        }
        return best
    }

    // =========================================================================
    // ⑥ 적응형 segmentMs
    // =========================================================================

    private fun adaptiveSegmentMs(durationMs: Long, estimatedBeatMs: Long?): Long = when {
        durationMs < 60_000L                                       -> durationMs   // 60초 미만: 전체
        durationMs < 120_000L                                      -> 30_000L      // 60~120초: 30초
        estimatedBeatMs != null && estimatedBeatMs > 900L          -> 30_000L      // BPM < 67: 더 긴 세그먼트
        else                                                        -> 20_000L      // 기본 20초
    }

    // =========================================================================
    // ③ 박자 감지 (Time Signature Detection)
    // =========================================================================

    private fun detectTimeSignature(
        onset: List<Float>,
        beatMs: Long,
        hopMs: Long
    ): TimeSignature {
        if (onset.size < 8 || beatMs <= 0L) return TimeSignature.FOUR_FOUR

        val beatFrames = (beatMs / hopMs).toInt().coerceAtLeast(1)
        val lag3 = beatFrames * 3
        val lag4 = beatFrames * 4
        val lag6 = beatFrames * 6

        val corr3 = computeLagCorrelation(onset, lag3)
        val corr4 = computeLagCorrelation(onset, lag4)
        val corr6 = computeLagCorrelation(onset, lag6)

        Log.d(TAG, "V10 timeSig beatMs=$beatMs corr3=${fmt(corr3)} corr4=${fmt(corr4)} corr6=${fmt(corr6)}")

        return when {
            // 3/4: 3비트 마디 상관이 4비트보다 확연히 높음
            corr3 > corr4 * TIME_SIG_THREE_RATIO ->
                TimeSignature.THREE_FOUR
            // 6/8: 6비트 마디 상관이 높고 3비트 패턴도 동반
            corr6 > corr4 * TIME_SIG_SIX_RATIO && corr3 > corr4 * 0.85f ->
                TimeSignature.SIX_EIGHT
            else ->
                TimeSignature.FOUR_FOUR
        }
    }

    private fun computeLagCorrelation(onset: List<Float>, lag: Int): Float {
        if (lag <= 0 || lag >= onset.size) return 0f
        var sum = 0f; var count = 0; var i = 0
        while (i + lag < onset.size) { sum += onset[i] * onset[i + lag]; count++; i++ }
        return if (count > 0) sum / count.toFloat() else 0f
    }

    // =========================================================================
    // ② 다운비트 감지 (Downbeat Detection)
    // =========================================================================

    private fun detectDownbeat(
        beatTimesMs: List<Long>,
        fullEnv: List<Float>,
        beatsPerBar: Int,
        hopMs: Long
    ): Long {
        if (beatTimesMs.isEmpty()) return 0L
        if (beatTimesMs.size < beatsPerBar) return beatTimesMs.first()

        // 각 위상(0..beatsPerBar-1)에 해당하는 비트들의 에너지 합산
        val phaseScores = FloatArray(beatsPerBar)
        for (i in beatTimesMs.indices) {
            val phase = i % beatsPerBar
            val frame = (beatTimesMs[i] / hopMs).toInt().coerceIn(0, fullEnv.lastIndex)
            phaseScores[phase] += fullEnv[frame]
        }

        val bestPhase = phaseScores.indices.maxByOrNull { phaseScores[it] } ?: 0
        Log.d(TAG, "V10 downbeat phaseScores=[${phaseScores.joinToString { fmt(it) }}] bestPhase=$bestPhase")

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }

    // =========================================================================
    // ⑤ 소스 우선순위 — LOW/LOW_MID + SPECTRAL_FLUX 보너스
    // =========================================================================

    private fun buildSourceOrder(
        low: List<Float>, mid: List<Float>, full: List<Float>,
        spectralFluxOdf: List<Float>
    ): List<BeatSource> {
        val lowVar  = varOf(low)
        val midVar  = varOf(mid)
        val fullVar = varOf(full)
        val fluxVar = varOf(spectralFluxOdf)
        val BASS_BONUS = 0.003f

        val scored = listOf(
            BeatSource.LOW           to (lowVar + BASS_BONUS),
            BeatSource.LOW_MID       to ((lowVar + midVar) * 0.5f + min(lowVar, midVar) * 0.2f + BASS_BONUS),
            BeatSource.SPECTRAL_FLUX to (fluxVar + BASS_BONUS * 0.7f),   // ⑤
            BeatSource.MID           to midVar,
            BeatSource.FULL          to fullVar,
            BeatSource.MID_FULL      to ((midVar + fullVar) * 0.5f + min(midVar, fullVar) * 0.2f),
            BeatSource.LOW_FULL      to ((lowVar + fullVar) * 0.5f + min(lowVar, fullVar) * 0.2f)
        ).sortedByDescending { it.second }

        return scored.map { it.first }
    }

    private fun combineSource(
        source: BeatSource, low: List<Float>, mid: List<Float>, full: List<Float>
    ): List<Float> = when (source) {
        BeatSource.LOW           -> low
        BeatSource.MID           -> mid
        BeatSource.FULL          -> full
        BeatSource.LOW_MID       -> mix(low, mid,  0.55f, 0.45f)
        BeatSource.MID_FULL      -> mix(mid, full, 0.60f, 0.40f)
        BeatSource.LOW_FULL      -> mix(low, full, 0.60f, 0.40f)
        BeatSource.SPECTRAL_FLUX -> mix(low, mid,  0.55f, 0.45f)  // 사전 계산 ODF 우선 사용 시 fallback
    }

    // =========================================================================
    // Autocorrelation + harmonic folding (V9와 동일)
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
        val confidence = (bestValue - secondValue).coerceAtLeast(0f) + bestValue
        if (confidence < 0.012f) return null

        // harmonic fold /2
        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfValue = corrArray[halfLag]
            if (halfValue >= bestValue * HARMONIC_FOLD_HALF_RATIO) {
                val halfBeatMs = halfLag * hopMs
                Log.d(TAG, "harmonic /2: $bestLag→$halfLag (${halfBeatMs}ms)")
                val quarterLag = halfLag / 2
                if (quarterLag >= minLag && corrArray[quarterLag] >= halfValue * HARMONIC_FOLD_HALF_RATIO) {
                    return quarterLag * hopMs to corrArray[quarterLag].coerceIn(0f, 1f)
                }
                return halfBeatMs to halfValue.coerceIn(0f, 1f)
            }
        }

        // harmonic fold /3
        val thirdLag = bestLag / 3
        if (thirdLag >= minLag) {
            val thirdValue = corrArray[thirdLag]
            if (thirdValue >= bestValue * HARMONIC_FOLD_THIRD_RATIO) {
                Log.d(TAG, "harmonic /3: $bestLag→$thirdLag (${thirdLag * hopMs}ms)")
                return thirdLag * hopMs to thirdValue.coerceIn(0f, 1f)
            }
        }

        // harmonic ×2/3
        val twoThirdLag    = bestLag * 2 / 3
        val twoThirdBeatMs = twoThirdLag * hopMs
        if (twoThirdLag >= minLag && twoThirdBeatMs in minBeatMs..(maxBeatMs * 0.65f).toLong()) {
            val ttValue = if (twoThirdLag < corrArray.size) corrArray[twoThirdLag] else {
                var sum = 0f; var count = 0; var i = 0
                while (i + twoThirdLag < onset.size) { sum += onset[i] * onset[i + twoThirdLag]; count++; i++ }
                if (count > 0) sum / count.toFloat() else 0f
            }
            if (ttValue >= bestValue * HARMONIC_TWO_THIRDS_RATIO) {
                Log.d(TAG, "harmonic ×2/3: $bestLag→$twoThirdLag (${twoThirdBeatMs}ms)")
                return twoThirdBeatMs to ttValue.coerceIn(0f, 1f)
            }
        }

        // harmonic ×2
        val doubleGuardMs = (maxBeatMs * 0.55f).toLong()
        val doubleLag     = bestLag * 2
        val doubleBeatMs  = doubleLag * hopMs
        val doubleValue: Float = when {
            doubleLag <= maxLag -> corrArray[doubleLag]
            doubleLag + 2 < onset.size -> {
                var sum = 0f; var count = 0; var i = 0
                while (i + doubleLag < onset.size) { sum += onset[i] * onset[i + doubleLag]; count++; i++ }
                if (count > 0) sum / count.toFloat() else 0f
            }
            else -> 0f
        }
        if (bestLag * hopMs > doubleGuardMs &&
            doubleValue >= bestValue * HARMONIC_DOUBLE_RATIO &&
            doubleBeatMs <= maxBeatMs) {
            Log.d(TAG, "harmonic ×2: $bestLag→$doubleLag (${doubleBeatMs}ms)")
            return doubleBeatMs to doubleValue.coerceIn(0f, 1f)
        }

        return bestLag * hopMs to bestValue.coerceIn(0f, 1f)
    }

    // =========================================================================
    // Peak snapping & chain (V9와 동일)
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
                abs(diffMs - expected)        <= tol           -> kept += cur
                abs(diffMs - expected * 2f)   <= tol * 0.6f   -> kept += cur
                abs(diffMs - expected * 0.5f) <= tol * 0.8f   -> kept += cur
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
        beats: List<Long>, minBeatMs: Long, maxBeatMs: Long, hopMs: Long = 50L
    ): Long {
        if (beats.size < 2) return 500L
        val diffs = ArrayList<Long>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d in minBeatMs..maxBeatMs) diffs += d
        }
        if (diffs.isEmpty()) return 500L
        val binned = diffs.map { (it / hopMs) * hopMs }
        val mode   = binned.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return mode ?: diffs.sorted()[diffs.size / 2]
    }

    // ④ confidence 보존 dedup
    private fun dedupeCloseTimedBeats(beats: List<TimedBeat>, minDistanceMs: Long): List<TimedBeat> {
        if (beats.isEmpty()) return emptyList()
        val out = ArrayList<TimedBeat>(); var last = Long.MIN_VALUE / 4
        for (b in beats) {
            if (b.timeMs - last >= minDistanceMs) { out += b; last = b.timeMs }
        }
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

    private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> {
        val smooth = movingAverage(env, smoothWindow)
        val diff   = positiveDiff(smooth)
        return localNormalize(diff, normWindow)
    }

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
        if (v.isEmpty()) return 0f; var s = 0f; for (x in v) { val d = x - mean; s += d * d }
        return sqrt(s / v.size)
    }
    private fun varOf(v: List<Float>): Float {
        val m = meanOf(v); var s = 0f; for (x in v) { val d = x - m; s += d * d }
        return if (v.isEmpty()) 0f else s / v.size
    }
    private fun maxOfList(v: List<Float>): Float = v.maxOrNull() ?: 0f
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
