package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * BeatDetectorV11
 *
 * V9 기반 + 정박자(다운비트) 최우선 감지:
 * ① 저역 강조 다운비트 감지    — LOW 밴드(킥드럼) 에너지 + 마디 콤 필터 + 일관성 점수로 위상 확정
 * ② 다운비트 기준 그리드 재정렬 — 감지된 다운비트를 앵커로 비트 위치를 정박자 그리드에 스냅
 * ③ 박자표 자동 감지            — 전곡 ODF 자기상관으로 4/4, 3/4, 6/8 판별
 * ④ 비트별 신뢰도               — ODF 피크값 기반 TimedBeat 반환 (하위 호환 beatTimesMs 유지)
 * ⑤ Multi-Hypothesis 연속성    — 세그먼트 간 BPM 연속성 가중치로 템포 오검출 감소
 */
object BeatDetectorV11 {

    private const val TAG = "AutoTimeline"

    private const val HARMONIC_FOLD_HALF_RATIO  = 0.45f  // 0.55 → 0.45: 빠른 댄스곡 half-time 오탐 교정 강화
    private const val HARMONIC_FOLD_THIRD_RATIO = 0.35f
    private const val HARMONIC_DOUBLE_RATIO     = 0.80f
    private const val HARMONIC_TWO_THIRDS_RATIO = 0.75f
    private const val HARMONIC_FOLD_HALF_MIN_MS = 340L   // 340ms(~176BPM) 미만으론 half-fold 금지

    private const val LOCAL_NORM_WINDOW  = 60   // 3초 @ 50ms hop
    private const val GLOBAL_NORM_WINDOW = 80   // 4초 @ 50ms hop

    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f

    // ⑤ 연속성 판정: 이전 세그먼트 BPM과 12% 이내면 연속으로 간주
    private const val CONTINUITY_MAX_RATIO = 0.12f

    // ① 다운비트 위상 점수 가중치
    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB   = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    // ② 그리드 재정렬 스냅 허용 범위
    private const val REALIGN_SNAP_MS = 80L

    // ⑥ 갭 채우기 / 밀도 축소 후처리
    private const val THIN_RATIO      = 0.55f  // 간격 < beatMs × 0.55 → 밀도 축소
    private const val FILL_CONFIDENCE = 0.20f  // 합성 비트 신뢰도

    // ⑦ 약한 신호 자동 재시도: FAIL 세그먼트 비율이 이 값을 초과하면 완화 파라미터로 재시도
    private const val WEAK_SIGNAL_FAIL_RATE = 0.60f

    // ⑧ BPM·위상 신뢰도: 감지된 비트가 예상 대비 이 비율 미만이면 전곡 자기상관 BPM·ODF 위상 우선
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

    data class Params(
        val hopMs: Long             = 50L,
        val minBeatMs: Long         = 320L,   // 이전 290ms → 320ms(~188BPM) 이하 오탐 방지
        val maxBeatMs: Long         = 1200L,
        val minPeakDistanceMs: Long = 140L,
        val onsetSmoothWindow: Int  = 3,
        val segmentMs: Long         = 20_000L,
        val peakThresholdK: Float   = 0.22f,
        val minPeakAbs: Float       = 0.04f,
        val snapToleranceMs: Long   = 150L,
        val chainToleranceMs: Long  = 170L,
        val minChainCount: Int      = 3,
        val continuityBonus: Float  = 0.08f  // ⑤
    )

    data class DetectResult(
        val beats: List<TimedBeat>,
        val beatMs: Long,
        val source: BeatSource?,
        val reason: String,
        val downbeatOffsetMs: Long,        // ① 첫 비트 대비 다운비트 오프셋
        val timeSignature: TimeSignature,   // ③
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

        val globalBeatMs = estimateGlobalBpm(low, mid, params)
        Log.d(TAG, "V11 globalBeatMs=$globalBeatMs durationMs=$durationMs")

        val effectiveSegmentMs = if (durationMs < 60_000L) durationMs else params.segmentMs
        val segmentFrames      = max(1, (effectiveSegmentMs / params.hopMs).toInt())

        Log.d(TAG, "V11 segmentMs=$effectiveSegmentMs minBeatMs=${params.minBeatMs} maxBeatMs=${params.maxBeatMs}")

        var loop = runSegmentLoop(low, mid, full, globalBeatMs, params, segmentFrames, minSize)

        val failCount = loop.segResults.count { it.selectedSource == null }
        val totalSegs = loop.segResults.size
        if (totalSegs > 1 && failCount.toFloat() / totalSegs > WEAK_SIGNAL_FAIL_RATE) {
            Log.d(TAG, "V11 weak signal ($failCount/$totalSegs segs FAIL) → retry relaxed params")
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
            Log.w(TAG, "V11 beat detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all segments failed", 0L, TimeSignature.FOUR_FOUR, loop.segResults)
        }

        val rawBeatMs   = estimateMedianInterval(dedupedBeats.map { it.timeMs }, params.minBeatMs, params.maxBeatMs, params.hopMs)
        // globalBeatMs vs rawBeatMs 하모닉 교차 검증:
        //   global이 raw의 2배 → global이 half-time 오탐, raw 채택 (신메뉴: raw=375ms, global=750ms)
        //   raw가 global의 2배 → raw가 half-time 오탐, global 채택 (이미 globalBeatMs 우선이지만 명시 로그)
        val finalBeatMs: Long = when {
            globalBeatMs == null -> rawBeatMs
            kotlin.math.abs(globalBeatMs - rawBeatMs * 2L) < 60L -> {
                Log.d(TAG, "V11 finalBeatMs: global($globalBeatMs) ≈ 2×raw($rawBeatMs) → raw 채택 (global half-time)")
                rawBeatMs
            }
            kotlin.math.abs(rawBeatMs - globalBeatMs * 2L) < 60L -> {
                Log.d(TAG, "V11 finalBeatMs: raw($rawBeatMs) ≈ 2×global($globalBeatMs) → global 채택 (raw half-time)")
                globalBeatMs
            }
            else -> globalBeatMs
        }
        val finalSource = loop.sourceVotes.maxByOrNull { it.value }?.key

        // [핵심] 누적 오차 없는 로컬 갭 채우기 (고정 그리드 강제 스냅 제거)
        val filledBeats = ArrayList<TimedBeat>()
        filledBeats.add(dedupedBeats.first())
        for (i in 1 until dedupedBeats.size) {
            val prev = filledBeats.last()
            val curr = dedupedBeats[i]
            val gap  = curr.timeMs - prev.timeMs
            if (gap > finalBeatMs * 1.4f) {
                val missingCount = (gap.toDouble() / finalBeatMs.toDouble()).roundToLong()
                if (missingCount > 1) {
                    val step = gap / missingCount
                    for (j in 1 until missingCount) {
                        filledBeats.add(TimedBeat(prev.timeMs + j * step, 0.5f))
                    }
                }
            }
            filledBeats.add(curr)
        }

        // 곡 앞/뒤 빈 구간 채우기
        val firstTime    = filledBeats.first().timeMs
        val initialFills = ArrayList<TimedBeat>()
        var tStart = firstTime - finalBeatMs
        while (tStart >= 0) { initialFills.add(TimedBeat(tStart, 0.5f)); tStart -= finalBeatMs }
        initialFills.reverse()

        val withHead  = initialFills + filledBeats
        val lastTime  = withHead.last().timeMs
        val tailFills = ArrayList<TimedBeat>()
        var tEnd = lastTime + finalBeatMs
        while (tEnd <= durationMs) { tailFills.add(TimedBeat(tEnd, 0.5f)); tEnd += finalBeatMs }

        val completeBeats = withHead + tailFills

        val globalOnset   = computeGlobalOnset(low, mid, params)
        val timeSignature = detectTimeSignature(globalOnset, finalBeatMs, params.hopMs)
        Log.d(TAG, "V11 timeSignature=${timeSignature.type}")

        val downbeatMs = detectDownbeatEnhanced(
            completeBeats.map { it.timeMs }, low, finalBeatMs, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (completeBeats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V11 detect OK source=$finalSource beats=${completeBeats.size} " +
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
    // ⑦ 세그먼트 루프 — detect() 에서 분리된 내부 헬퍼
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
                segResults += SegmentResult(segIndex, segStartMs,
                    e.toLong() * params.hopMs, null, emptyList(), 0L, 0f, "all failed", trials)
                continue
            }

            Log.d(TAG, "SEG[$segIndex] WINNER=${best.source} beatMs=${best.beatMs} " +
                "beats=${best.timedBeats.size} score=${fmt(best.score)}")

            val absBeats    = best.timedBeats.map { it.copy(timeMs = it.timeMs + segStartMs) }
            val halfBeatMs  = best.beatMs / 2

            // [핵심] 엇박(Snare Trap) 인버전 자동 교정
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
    // 전곡 글로벌 BPM / ODF (V9와 동일: LOW_MID 기반)
    // =========================================================================

    private fun estimateGlobalBpm(low: List<Float>, mid: List<Float>, params: Params): Long? {
        val onset = computeOdf(mix(low, mid, 0.55f, 0.45f), 5, GLOBAL_NORM_WINDOW)
        return autoCorrelateBeat(onset, params.hopMs, params.minBeatMs, params.maxBeatMs)?.first
    }

    private fun computeGlobalOnset(low: List<Float>, mid: List<Float>, params: Params): List<Float> =
        computeOdf(mix(low, mid, 0.55f, 0.45f), 5, GLOBAL_NORM_WINDOW)

    /**
     * 전곡 ODF에서 비트 위상(phase)을 추정한다.
     *
     * 비트 감지 커버리지가 낮아 detectDownbeatEnhanced가 부정확할 때 사용.
     * 0..beatMs-1 범위의 모든 위상 후보에 대해 그리드 위치의 ODF 합산을 구하고,
     * 합산이 가장 큰 위상 = 비트가 가장 많이 일치하는 시작점으로 반환한다.
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
        Log.d(TAG, "V11 timeSig corr3=${fmt(corr3)} corr4=${fmt(corr4)} corr6=${fmt(corr6)}")
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

    /**
     * LOW 밴드(킥드럼)를 중심으로 세 가지 관점에서 정박자 위상을 결정한다.
     *
     * Factor A — 위상별 LOW 에너지 평균   : 킥이 가장 강한 위상 = 1박
     * Factor B — 마디 콤 필터             : barFrames 간격으로 lowEnv를 합산해 주기 위상 확정
     * Factor C — 위상별 에너지 일관성     : 매 마디마다 에너지가 안정적인 위상 선호
     */
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

        // Factor A: 위상별 LOW 에너지 평균
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

        // Factor B: 마디 콤 필터 — 위상 p에서 출발해 barFrames 간격으로 lowEnv 합산
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

        // Factor C: 일관성 — 위상 p의 에너지 표준편차 ÷ 평균(CV)이 낮을수록 안정
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
            "V11 downbeat avgE=[${avgEnergy.joinToString { fmt(it) }}] " +
            "comb=[${combScore.joinToString { fmt(it) }}] " +
            "consist=[${consistScore.joinToString { fmt(it) }}] " +
            "bestPhase=$bestPhase")

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }

    // =========================================================================
    // ② 비트 그리드 재정렬 — 다운비트 앵커 정박자 스냅
    // =========================================================================

    /**
     * 감지된 각 비트를 다운비트 기준 정박자 그리드(downbeatMs + n×beatMs)로 스냅한다.
     * 허용 범위(REALIGN_SNAP_MS) 내에 있는 비트만 스냅하고, 그 외는 원본 위치 유지.
     * 결과적으로 모든 비트가 정박자 위치에 정렬된다.
     */
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
                null  // 그리드와 맞지 않음 → 드롭, 합성 비트로 채움
            }
        }.distinctBy { it.timeMs }.sortedBy { it.timeMs }

        val dropped = beats.size - result.size
        Log.d(TAG, "V11 realignBeatsToGrid: in=${beats.size} kept=${result.size} " +
            "dropped=$dropped snapMs=${REALIGN_SNAP_MS}ms downbeat=${downbeatMs}ms")
        return result
    }

    // =========================================================================
    // ⑥ 갭 채우기 + 밀도 축소 후처리
    // =========================================================================

    /**
     * realignBeatsToGrid 이후 비트 목록을 정규화한다.
     *
     * ① 밀도 축소 (Thin):
     *    간격 < beatMs × THIN_RATIO → 신뢰도 낮은 쪽 제거 (이중 피크 정리)
     *
     * ② 갭 채우기 (Fill) — 다운비트 앵커 기반 전곡 단일 그리드:
     *    phase = downbeatMs % beatMs (0 이상의 첫 위상점)
     *    phase, phase+beatMs, phase+2×beatMs ... 전곡 그리드를 생성하고
     *    그리드 위치에 실제 비트(±REALIGN_SNAP_MS)가 없으면 합성 비트 삽입.
     *
     *    - 모든 합성 비트가 같은 위상 → 세그먼트 경계 위상 불일치 해소
     *    - 그리드가 0ms 부터 시작 → 곡 초반 공백 해소
     */
    private fun normalizeBeats(
        beats: List<TimedBeat>,
        beatMs: Long,
        downbeatMs: Long,
        durationMs: Long
    ): List<TimedBeat> {
        if (beatMs <= 0L) return beats

        val thinThresholdMs = (beatMs * THIN_RATIO).toLong()

        // ① 밀도 축소: 너무 촘촘한 비트 제거 (신뢰도 높은 쪽 유지)
        val thinned = ArrayList<TimedBeat>(beats.size)
        for (beat in beats) {
            val last = thinned.lastOrNull()
            when {
                last == null -> thinned += beat
                beat.timeMs - last.timeMs >= thinThresholdMs -> thinned += beat
                beat.confidence > last.confidence -> thinned[thinned.lastIndex] = beat
                // else: 신뢰도 낮음 → 드롭
            }
        }

        // ② 갭 채우기: downbeat 기반 전곡 단일 그리드
        //    phase = downbeatMs % beatMs → 0 이상 첫 위상점
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

        // ③ 최종 정리: fill 후에도 혹시 남은 이중 비트 제거 (beatMs × 0.75 이상 간격 보장)
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
        val realCount  = thinned.size   // thin 이후 남은 실 감지 비트
        val synthRatio = if (realCount + fillCount > 0) fillCount * 100 / (realCount + fillCount) else 0
        Log.d(TAG, "V11 normalizeBeats: original=${beats.size} " +
                "thinned=$thinCount filled=$fillCount cleaned=$cleanCount " +
                "final=${cleaned.size} real=$realCount synth=$fillCount(${synthRatio}%) " +
                "phase=${phase}ms beatMs=${beatMs}ms")

        return cleaned
    }

    /** 정렬된 LongArray에서 t ± snapMs 범위 내 값 존재 여부 (binary search) */
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
    // Single source detection (V9 기반, TimedBeat 반환)
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

        val threshold     = max(params.minPeakAbs, mean + std * params.peakThresholdK)
        val minPeakFrames = max(1, (params.minPeakDistanceMs / params.hopMs).toInt())
        val rawPeaks      = findPeaks(onset, threshold, minPeakFrames)
        Log.d(TAG, "SEG[$segmentIndex][$source] mean=${fmt(mean)} std=${fmt(std)} " +
            "max=${fmt(onsetMax)} threshold=${fmt(threshold)}(abs=${fmt(params.minPeakAbs)}) " +
            "rawPeaks=${rawPeaks.size}")

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
    // 소스 우선순위 (V9와 동일: LOW/LOW_MID 우선)
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
        if ((bestValue - secondValue).coerceAtLeast(0f) + bestValue < 0.012f) return null

        // harmonic /2
        // HARMONIC_FOLD_HALF_MIN_MS 이상일 때만 half-fold 허용 (250ms 등 너무 빠른 BPM 오탐 방지)
        val halfLag = bestLag / 2
        val halfMs  = halfLag * hopMs
        if (halfLag >= minLag && halfMs >= HARMONIC_FOLD_HALF_MIN_MS &&
            corrArray[halfLag] >= bestValue * HARMONIC_FOLD_HALF_RATIO) {
            Log.d(TAG, "autoCorr half-fold: bestLag=${bestLag * hopMs}ms → halfMs=${halfMs}ms")
            val quarterLag = halfLag / 2
            if (quarterLag >= minLag && corrArray[quarterLag] >= corrArray[halfLag] * HARMONIC_FOLD_HALF_RATIO)
                return quarterLag * hopMs to corrArray[quarterLag].coerceIn(0f, 1f)
            return halfMs to corrArray[halfLag].coerceIn(0f, 1f)
        }

        // harmonic /3
        val thirdLag = bestLag / 3
        if (thirdLag >= minLag && corrArray[thirdLag] >= bestValue * HARMONIC_FOLD_THIRD_RATIO)
            return thirdLag * hopMs to corrArray[thirdLag].coerceIn(0f, 1f)

        // harmonic ×2/3
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

        // harmonic ×2
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
                abs(diffMs - expected)        <= tol         -> kept += cur
                abs(diffMs - expected * 2f)   <= tol * 0.6f -> kept += cur
                abs(diffMs - expected * 0.5f) <= tol * 0.8f -> kept += cur
                abs(diffMs - expected * 3f)   <= tol * 1.2f -> kept += cur  // 3비트 스킵 허용
                abs(diffMs - expected * 4f)   <= tol * 1.4f -> kept += cur  // 4비트 스킵 허용
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
