package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.*

/**
 * BeatDetectorV3 (Tempogram 기반)
 *
 * V1의 기능 + Tempogram
 * - V1의 IIR 필터 기반 ODF 유지
 * - Tempogram 기반 모달 피크 검출
 * - 신뢰도(confidence) 점수 추가
 * - 반박자 체크(half-tempo) 유지
 *
 * 개선점:
 * 1. 시간-BPM 2D 분석으로 더 정확한 BPM 탐지
 * 2. 모든 시간에서 일관되게 강한 BPM 선택
 * 3. 약한 노이즈 자동 필터링
 * 4. 신뢰도 점수로 BPM의 확실성 판단 가능
 *
 * Ed Sheeran 문제 해결:
 * - V1: 133.3 BPM (틀림)
 * - V3: 96.77 BPM (맞음, confidence 87%)
 */
object BeatDetectorV3 {

    private const val TAG = "AutoTimeline_BeatDetectorV3"
    private const val PERF_TAG = "AutoTimeline_BeatDetectorV3_Perf"

    /** 정확도 진단용 JSON 저장(v3_analysis/bpm_results.jsonl) On/Off. 평상시 OFF, 분석 필요할 때만 켜서 사용. */
    private const val ENABLE_JSON_EXPORT = false

    // ════════════════════════════════════════════════════════════════════
    // 단위 변환 함수 - 모든 코드에서 일관된 단위 사용을 강제
    // beatMs: Long = milliseconds between beats = 60_000 / BPM
    // bpm: Float = beats per minute
    // ════════════════════════════════════════════════════════════════════

    /** BPM 값 → 비트 간격(밀리초) 변환 */
    private fun beatMsFromBpm(bpm: Float): Long {
        return if (bpm > 0f) (60_000 / bpm).toLong() else 0L
    }

    /** 비트 간격(밀리초) → BPM 값 변환 */
    private fun bpmFromBeatMs(beatMs: Long): Float {
        return if (beatMs > 0) 60_000f / beatMs else 0f
    }

    // V1 상수들
    private const val FILL_CONFIDENCE = 0.20f
    private const val LOCAL_NORM_WINDOW = 60
    private const val GLOBAL_NORM_WINDOW = 80
    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO = 1.25f
    private const val DOWNBEAT_W_LOW_ENERGY = 0.50f
    private const val DOWNBEAT_W_BAR_COMB = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    // BPM 탐지 파라미터
    private const val PRIOR_CENTER_MS = 500L
    private const val PRIOR_STD_OCTAVE = 2.0f  // V3: Tempogram은 V1과 동일한 범위 탐지 필요
    private const val HALF_TEMPO_RATIO = 0.60f
    private const val DP_MIN_BEAT_RATIO = 0.25f

    // Tempogram 파라미터
    private const val TEMPOGRAM_TIME_FRAMES = 200  // 시간 축 프레임 수
    private const val TEMPOGRAM_MIN_CONFIDENCE = 0.3f  // 신뢰도 최소값

    // IIR 필터 상수 (V1과 동일)
    private const val LOW_ALPHA = 0.12f
    private const val MID_LP1_ALPHA = 0.35f
    private const val MID_LP2_ALPHA = 0.08f

    // V3.1: 5-band 추가 필터 상수
    private const val VERYLOW_ALPHA = 0.08f   // 20-100Hz
    private const val LOWMID_ALPHA = 0.20f    // 100-400Hz
    private const val HIGH_LP1_ALPHA = 0.50f  // High band LP1
    private const val HIGH_LP2_ALPHA = 0.15f  // High band LP2

    data class TimedBeat(val timeMs: Long, val confidence: Float)

    // V3.8: Tempogram 분석용 데이터 구조
    data class AcPeakInfo(
        val lagMs: Long,
        val bpm: Float,
        val acValue: Float,
        val score: Float,
        val scorePercent: Int,
        val ratio: Float,
        val peakType: String  // PEAK, 2x, 0.5x, etc
    )

    data class TempogramInsights(
        val bestBpm: Float,
        val confidence: Float,
        val topPeaks: List<AcPeakInfo>,
        val halfTempoRatio: Float?,
        val doubleTempoRatio: Float?,
        val globalAcMagnitude: Float,
        val peakStrength: Float,
        val secondaryPeakRatio: Float  // 2nd peak / 1st peak
    )

    /** V4.0: findModalPeak의 다중 피크 평가 후보 하나에 대한 디버그 정보 (JSON 저장용) */
    data class PeakCandidateDebugInfo(
        val bpm: Float,
        val acStrength: Float,
        val prior: Float,
        val temporalConsistency: Float,
        val compositeScore: Float,
        val selected: Boolean
    )

    /** V4.0: findModalPeak의 전체 평가 결과 (선택된 BPM + 후보 목록 + 옥타브 보정 여부) */
    data class ModalPeakResult(
        val bpm: Float,
        val confidence: Float,
        val candidates: List<PeakCandidateDebugInfo>,
        val octaveCorrected: Boolean
    )

    /** V4.0: detectAndCorrectOctaveError의 결과 + 어떤 판단 근거로 결정했는지 (JSON 저장용) */
    data class OctaveCorrectionResult(val bpm: Float, val reason: String)

    /** V4.0: selectBetweenMethodsAandB의 결과 + 어떤 판단 근거로 결정했는지 (JSON 저장용) */
    data class MethodSelectionResult(val beatMs: Long, val reason: String)

    enum class TimeSignatureType { FOUR_FOUR, THREE_FOUR, SIX_EIGHT }

    data class TimeSignature(
        val type: TimeSignatureType,
        val numerator: Int,
        val denominator: Int
    ) {
        companion object {
            val FOUR_FOUR = TimeSignature(TimeSignatureType.FOUR_FOUR, 4, 4)
            val THREE_FOUR = TimeSignature(TimeSignatureType.THREE_FOUR, 3, 4)
            val SIX_EIGHT = TimeSignature(TimeSignatureType.SIX_EIGHT, 6, 8)
        }
        val beatsPerBar: Int get() = numerator
    }

    enum class BeatSource { FULL }

    data class Params(
        val hopMs: Long = 10L,  // madmom과 동일하게 10ms로 변경
        val minBeatMs: Long = 375L,
        val maxBeatMs: Long = 1200L,  // madmom 범위와 일치
        val minPeakDistanceMs: Long = 120L,
        val onsetSmoothWindow: Int = 3,
        val segmentMs: Long = 20_000L,
        val peakThresholdK: Float = 0.22f,
        val minPeakAbs: Float = 0.04f,
        val snapToleranceMs: Long = 130L,
        val chainToleranceMs: Long = 150L,
        val minChainCount: Int = 3,
        val continuityBonus: Float = 0.08f,
        // V3 추가 파라미터
        val useTempogram: Boolean = true,  // Tempogram 사용 여부
        val halfTempoRatio: Float = 0.60f,
        val doubleTempoRatio: Float = 0.55f
    )

    /**
     * V3 DetectResult: 신뢰도 추가
     */
    // 분석 데이터 저장 클래스
    data class AnalysisMetadata(
        val methodABpm: Float = 0f,
        val methodBBpm: Float = 0f,
        val methodAScore: Float = 0f,
        val acPeaks: List<Map<String, Any>> = emptyList(),  // AC_PEAKS top 10
        val tempogramStats: Map<String, Any> = emptyMap(),   // Tempogram 통계
        val sectionDetails: List<Map<String, Any>> = emptyList(),  // Section 상세정보
        val odfStats: Map<String, Any> = emptyMap(),         // ODF 통계
        val dpResults: Map<String, Any> = emptyMap(),         // DP tracking 결과
        val selectionReason: String = ""                     // 최종 선택 이유
    )

    data class DetectResultV3(
        val beats: List<TimedBeat>,
        val beatMs: Long,
        val confidence: Float,  // ✨ 새로운!
        val source: BeatSource?,
        val reason: String,
        val downbeatOffsetMs: Long,
        val timeSignature: TimeSignature,
        val tempogram: Array<FloatArray>? = null,  // ✨ 새로운!
        val debugSegments: List<Any> = emptyList(),
        val analysisMetadata: AnalysisMetadata = AnalysisMetadata()  // ✨ 새로운!
    ) {
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DetectResultV3) return false

            if (beats != other.beats) return false
            if (beatMs != other.beatMs) return false
            if (confidence != other.confidence) return false
            if (source != other.source) return false
            if (reason != other.reason) return false
            if (downbeatOffsetMs != other.downbeatOffsetMs) return false
            if (timeSignature != other.timeSignature) return false
            if (tempogram != null) {
                if (other.tempogram == null) return false
                if (!tempogram.contentDeepEquals(other.tempogram)) return false
            } else if (other.tempogram != null) return false
            if (debugSegments != other.debugSegments) return false

            return true
        }

        override fun hashCode(): Int {
            var result = beats.hashCode()
            result = 31 * result + beatMs.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (source?.hashCode() ?: 0)
            result = 31 * result + reason.hashCode()
            result = 31 * result + downbeatOffsetMs.hashCode()
            result = 31 * result + timeSignature.hashCode()
            result = 31 * result + (tempogram?.contentDeepHashCode() ?: 0)
            result = 31 * result + debugSegments.hashCode()
            return result
        }
    }

    /**
     * Tempogram 계산
     *
     * ODF의 시간-BPM 2D 표현
     * Returns: FloatArray[BPM_bins][timeFrames]
     */
    fun computeTempogram(
        odf: FloatArray,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long,
        timeFrames: Int = TEMPOGRAM_TIME_FRAMES
    ): Array<FloatArray> {
        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())
        val maxLag = maxOf(minLag + 1, (maxBeatMs / hopMs).toInt())
        val numBpms = maxLag - minLag + 1

        val tempogram = Array(numBpms) { FloatArray(timeFrames) }

        val nFrames = odf.size
        val step = maxOf(1, nFrames / (timeFrames / 2))

        for (tIdx in 0 until timeFrames) {
            // 시간 윈도우 설정
            val center = (tIdx * step) + (step / 2)
            val windowHalf = step / 2
            val startFrame = maxOf(0, center - windowHalf)
            val endFrame = minOf(nFrames, center + windowHalf)

            if (startFrame >= endFrame) continue

            // 이 시간 윈도우에서 autocorrelation 계산
            for (lagIdx in 0 until numBpms) {
                val lag = lagIdx + minLag

                if (lag >= nFrames) continue

                // Autocorrelation 계산
                var acSum = 0f
                var count = 0
                for (i in startFrame until (endFrame - lag)) {
                    acSum += odf[i] * odf[i + lag]
                    count++
                }

                if (count > 0) {
                    val acVal = acSum / count
                    tempogram[lagIdx][tIdx] = acVal
                }
            }
        }

        // 정규화
        val maxVal = tempogram.maxOfOrNull { row -> row.maxOrNull() ?: 0f } ?: 1f
        if (maxVal > 1e-6f) {
            for (row in tempogram) {
                for (i in row.indices) {
                    row[i] = row[i] / maxVal
                }
            }
        }

        return tempogram
    }

    /**
     * 모달 피크 찾기 (옥타브 에러 보정 포함)
     *
     * Returns: Pair<BPM, Confidence>
     */
    /**
     * Tempogram에서 모달 피크(주요 BPM)를 찾음
     * @return ModalPeakResult - BPM, 신뢰도, V4.0 다중 피크 평가 후보 목록, 옥타브 보정 적용 여부
     */
    fun findModalPeak(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long
    ): ModalPeakResult {
        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())

        // 각 BPM별로 시간을 통합
        val bpmStrengths = FloatArray(tempogram.size)
        for (i in tempogram.indices) {
            bpmStrengths[i] = tempogram[i].sum() / tempogram[i].size
        }

        // 정규화
        val maxStrength = bpmStrengths.maxOrNull() ?: 1f
        if (maxStrength > 1e-6f) {
            for (i in bpmStrengths.indices) {
                bpmStrengths[i] = bpmStrengths[i] / maxStrength
            }
        }

        // === Harmonic Peak Filtering (부음 필터링) ===
        // 절반 박자(하모닉)를 감지하고 필터링
        val harmonicFiltered = filterHarmonicPeaks(bpmStrengths, minLag)

        // V4.2: 전체 lag를 채점 대상으로 사용 (BeatDetectorV1의 "raw AC로 미리 추리지 않고
        // score=ac×prior를 전체 lag에 연속으로 적용해 argmax"하는 방식을 반영).
        // V4.0~V4.1은 raw AC 상위 N개로 먼저 추린 뒤 그 안에서만 정교하게 채점했는데,
        // 이 사전 필터링 자체가 정답 후보를 통째로 배제하는 경우가 있었음
        // (Ed Sheeran: raw AC 순위 15위 밖, IYKYK: top15에도 안 들어옴).
        // minLag~maxLag 범위가 보통 80~90개 수준(375~1200ms)이라 전부 채점해도 비용이 작음.
        val allCandidates = harmonicFiltered.indices
            .map { idx ->
                val lag = idx + minLag
                Triple(lag, harmonicFiltered[idx], bpmFromBeatMs(lag * hopMs))
            }
            .filter { it.second > 1e-6f }  // 강도가 0에 가까운 lag는 제외 (노이즈)

        // === V4.0: 다중 피크 평가 (Multi-peak evaluation) ===
        // 상위 5개 피크 각각을 3가지 기준으로 평가:
        // 1. AC 강도 (Tempogram에서의 절대값)
        // 2. Log-Normal Prior (음악 BPM 자연 분포)
        // 3. 시간 일관성 (Temporal Consistency) - 시간을 통해 일정하게 강한가?

        data class PeakCandidate(
            val lag: Int,
            val bpm: Float,
            val acStrength: Float,
            val prior: Float,
            val temporalConsistency: Float,
            val compositeScore: Float
        )

        // V4.0: 시간 일관성 점수 계산
        fun calculateTemporalConsistency(lagIdx: Int): Float {
            if (tempogram.isEmpty() || tempogram[0].isEmpty()) return 0.5f
            val lagStrengths = tempogram.getOrNull(lagIdx) ?: return 0.5f
            if (lagStrengths.isEmpty()) return 0.5f

            // 각 시간 윈도우에서 이 lag의 강도 분포 분석
            val strengths = lagStrengths.filter { it > 1e-6f }
            if (strengths.size < 2) return 0.5f

            // 평균과 표준편차 계산
            val mean = strengths.average().toFloat()
            val variance = strengths.map { (it - mean) * (it - mean) }.average().toFloat()
            val stdDev = sqrt(variance.toDouble()).toFloat().coerceAtLeast(1e-6f)

            // CV (Coefficient of Variation): stdDev / mean
            // CV가 낮을수록 일관성이 높음 (score 높음)
            val cv = if (mean > 1e-6f) stdDev / mean else 1f
            val consistency = (1f / (1f + cv)).coerceIn(0f, 1f)  // CV=0 → score=1, CV=1 → score=0.5

            return consistency
        }


        // V4.2: 전체 lag를 대상으로 채점 (raw AC 사전 필터링 없음 — BeatDetectorV1 방식)
        val candidateScores = allCandidates.map { (lag, acStrength, bpm) ->
            val lagIdx = lag - minLag
            val prior = calculateLogNormalPrior(bpm)
            val temporalConsistency = calculateTemporalConsistency(lagIdx)

            // 가중치 조합 (V4.0):
            // - AC 강도: 30% (절대값이 높은 것)
            // - Log-Normal Prior: 30% (자연스러운 BPM)
            // - 시간 일관성: 40% (곡 전체에서 일정한 것)
            val compositeScore = (acStrength * 0.3f) + (prior * 0.3f) + (temporalConsistency * 0.4f)

            PeakCandidate(
                lag = lag,
                bpm = bpm,
                acStrength = acStrength,
                prior = prior,
                temporalConsistency = temporalConsistency,
                compositeScore = compositeScore
            )
        }

        // 최고 점수의 후보 선택 (전체 lag 중 composite score 최고값)
        val bestCandidate = candidateScores.maxByOrNull { it.compositeScore }
            ?: candidateScores.firstOrNull()
            ?: PeakCandidate(minLag, bpmFromBeatMs(minLag * hopMs), 1f, 1f, 0.5f, 1f)

        // V4.2: 로깅/JSON 저장은 전체(~80여개)가 아닌 composite score 상위 20개만
        // (선택된 후보는 항상 1위이므로 이 목록에 반드시 포함됨)
        val topScoredCandidates = candidateScores.sortedByDescending { it.compositeScore }.take(20)

        // V4.0: JSON 저장용 후보 디버그 목록 (선택된 후보 표시, 상위 20개만 저장)
        val candidateDebugList = topScoredCandidates.map { cand ->
            PeakCandidateDebugInfo(
                bpm = cand.bpm,
                acStrength = cand.acStrength,
                prior = cand.prior,
                temporalConsistency = cand.temporalConsistency,
                compositeScore = cand.compositeScore,
                selected = (cand.lag == bestCandidate.lag)
            )
        }

        val bestLagIdx = bestCandidate.lag - minLag
        val bestLag = bestCandidate.lag
        val bestBeatMs = bestLag * hopMs
        var bestBpm = bestCandidate.bpm
        var octaveCorrected = false

        // 신뢰도: 필터링된 값 기준
        val sorted = harmonicFiltered.sortedDescending()
        var confidence = if (sorted.size >= 2 && sorted[1] > 1e-6f) {
            minOf(1.0f, sorted[0] / sorted[1])
        } else {
            sorted.firstOrNull()?.coerceIn(TEMPOGRAM_MIN_CONFIDENCE, 1.0f) ?: 0.5f
        }

        // === V4.0: 강화된 옥타브 에러 검증 (Octave Error Validation) ===
        // V4.0: 옥타브 보정 검증 함수 (bestLagIdx 정의 이후)
        fun shouldApplyOctaveCorrection(
            candidateBpm: Float,
            candidateStrength: Float,
            ratio: Float
        ): Boolean {
            // 기본 강도 비율 요건
            if (ratio < 0.45f) return false

            // 후보 BPM의 시간 일관성 확인
            val candidateLag = beatMsFromBpm(candidateBpm) / hopMs
            if (candidateLag < 1L || candidateLag > harmonicFiltered.size) return false

            val candidateConsistency = calculateTemporalConsistency(candidateLag.toInt() - minLag)
            val originalConsistency = calculateTemporalConsistency(bestLagIdx)

            // V4.0: 시간 일관성이 충분히 개선되어야만 보정 적용
            if (candidateConsistency - originalConsistency < 0.10f) {
                return false
            }

            // 원래 BPM이 이미 좋은 일관성을 가지면 보정하지 않음 (회귀 방지)
            if (originalConsistency >= 0.65f) {
                return false
            }

            return true
        }

        // 절반 비트(2배 BPM) 확인: lag/2
        val halfLag = bestLag / 2
        val halfStrength = if (halfLag >= minLag && halfLag - minLag < harmonicFiltered.size) {
            harmonicFiltered[halfLag - minLag]
        } else 0f
        val halfRatio = if (harmonicFiltered[bestLagIdx] > 1e-6f) halfStrength / harmonicFiltered[bestLagIdx] else 0f

        // 2배 비트(절반 BPM) 확인: lag*2
        val doubleLag = bestLag * 2
        val doubleStrength = if (doubleLag - minLag < harmonicFiltered.size) {
            harmonicFiltered[doubleLag - minLag]
        } else 0f
        val doubleRatio = if (harmonicFiltered[bestLagIdx] > 1e-6f) doubleStrength / harmonicFiltered[bestLagIdx] else 0f

        // V4.0: 절반 비트 보정 (2배 오류 수정)
        if (halfLag >= minLag && shouldApplyOctaveCorrection(bpmFromBeatMs(halfLag * hopMs), halfStrength, halfRatio)) {
            val halfBeatMs = halfLag * hopMs
            val halfBpm = bpmFromBeatMs(halfBeatMs)
            bestBpm = halfBpm
            confidence = minOf(1.0f, halfStrength / sorted[0])
            octaveCorrected = true
        }
        // V4.0: 2배 비트 보정 (절반 오류 수정)
        else if (doubleLag - minLag < harmonicFiltered.size && doubleRatio >= 0.45f &&
                 doubleStrength > harmonicFiltered[bestLagIdx] * 0.7f &&
                 shouldApplyOctaveCorrection(bpmFromBeatMs(doubleLag * hopMs), doubleStrength, doubleRatio)) {
            val doubleBeatMs = doubleLag * hopMs
            val doubleBpm = bpmFromBeatMs(doubleBeatMs)
            bestBpm = doubleBpm
            confidence = minOf(1.0f, doubleStrength / sorted[0])
            octaveCorrected = true
        }

        return ModalPeakResult(bestBpm, confidence, candidateDebugList, octaveCorrected)
    }

    /**
     * 부음(하모닉) 필터링: 절반 박자를 감지하고 강도를 감소시킴
     *
     * 인접한 피크들의 관계를 분석하여 다음을 감지:
     * - 2x 하모닉: lag*2가 존재하고 강할 경우, 현재 lag는 절반 박자(half-tempo)
     * - 0.5x 하모닉: lag/2가 존재하고 강할 경우, 현재 lag는 두배 박자(double-tempo)
     *
     * @param bpmStrengths 각 lag별 강도 배열 (인덱스 0 = lag minLag에 해당)
     * @param minLag 최소 lag 값
     * @return 하모닉 필터링 후 강도 배열
     */
    private fun filterHarmonicPeaks(bpmStrengths: FloatArray, minLag: Int): FloatArray {
        val filtered = bpmStrengths.copyOf()

        for (idx in filtered.indices) {
            val lag = idx + minLag
            val strength = filtered[idx]

            if (strength < 1e-6f) continue  // 무시할 정도로 작은 강도

            // 2x 하모닉 확인: lag*2가 존재하고 강한지 확인
            val doubleLagIdx = lag * 2 - minLag
            if (doubleLagIdx in filtered.indices) {
                val doubleStrength = filtered[doubleLagIdx]
                val ratio = doubleStrength / strength
                // 2배 lag이 현재 lag와 비슷하거나 강하면, 현재 lag는 절반 박자(하모닉)
                // V3.3: 강화된 필터링 (0.65 → 0.50, 0.3 → 0.1)
                if (ratio >= 0.50f) {
                    // 절반 박자로 판정되었으므로 강도 감소 (0.3배 → 0.1배)
                    filtered[idx] = strength * 0.1f
                    continue
                }
            }

            // 0.5x 하모닉 확인: lag/2가 존재하고 강한지 확인
            if (lag > 1) {
                val halfLagIdx = lag / 2 - minLag
                if (halfLagIdx >= 0 && halfLagIdx in filtered.indices) {
                    val halfStrength = filtered[halfLagIdx]
                    val ratio = halfStrength / strength
                    // 절반 lag이 현재 lag와 비슷하거나 강하면, 현재 lag는 두배 박자(하모닉)
                    // V3.3: 강화된 필터링 (0.65 → 0.50, 0.3 → 0.1)
                    if (ratio >= 0.50f) {
                        // 두배 박자로 판정되었으므로 강도 감소 (0.3배 → 0.1배)
                        filtered[idx] = strength * 0.1f
                        continue
                    }
                }
            }
        }

        return filtered
    }

    /**
     * Log-Normal Prior: 음악 BPM 확률 분포 (V3.9 개선)
     *
     * V3.9: Prior 중심을 120 BPM으로 이동하고 std를 증가시켜
     * 높은 BPM(140~180)의 가중치를 높이고 낮은 BPM(60~80)의 페널티 감소
     *
     * @param bpm BPM 값
     * @return 확률 가중치 (0~1)
     */
    private fun calculateLogNormalPrior(bpm: Float): Float {
        if (bpm < 30 || bpm > 360) return 0.01f

        // V3.9: 파라미터 개선 - 중심을 120 BPM으로, std를 0.45로 증가
        val muLn = ln(120.0)   // V3.9: 평균 120 BPM의 로그값 (기존 110)
        val sigma = 0.45f      // V3.9: 표준편차 증가 0.35f → 0.45f (더 광범위 허용)

        val lnBpm = ln(bpm.toDouble())
        val exponent = -((lnBpm - muLn) * (lnBpm - muLn)) / (2 * sigma * sigma)

        // 정규분포 확률밀도함수 (로그 스케일)
        val density = (1.0 / (bpm * sigma * sqrt(2 * PI))).toFloat() *
                      exp(exponent).toFloat()

        // 0~1로 정규화 (최대값 약 0.010 at BPM=120)
        // V3.9: 정규화 계수 조정 (기존 0.011 → 0.009, 더 넓은 분포)
        return (density / 0.009f).coerceIn(0.01f, 1.0f)
    }

    /**
     * Tempogram AC 개선: 스펙트럼 농도 기반 가중치 적용
     *
     * 원리: 각 lag에서 ODF 신호의 주기성 강도를 추정하여,
     * 진정한 박자 주파수의 AC값에는 가중치를 높이고,
     * 하모닉 주파수의 AC값에는 가중치를 낮춤.
     *
     * @param globalOdf 글로벌 ODF 신호
     * @param acVals 각 lag별 AC값 배열
     * @param minLag 최소 lag
     * @return 가중치 적용된 AC값
     */
    private fun improveAcValuesWithSpectralWeighting(
        globalOdf: FloatArray,
        acVals: FloatArray,
        minLag: Int
    ): FloatArray {
        val improved = acVals.copyOf()

        if (globalOdf.size < minLag + 10) {
            return improved
        }

        // 1단계: 각 lag별 스펙트럼 농도 계산 (주기성의 일관성)
        val spectralConcentration = FloatArray(acVals.size)
        for (idx in improved.indices) {
            val lag = idx + minLag
            if (lag >= globalOdf.size) break

            // 스펙트럼 농도 = 인접 값들의 분산 역수
            // 주기가 일관되면 인접 값들의 분산이 작음
            val variance = mutableListOf<Float>()

            // i * lag이 배열 범위를 벗어나지 않도록 상한 계산
            val maxI = globalOdf.size / lag
            for (i in 1..minOf(3, maxI - 1)) {  // 최대 3개 구간만 검사 (효율성)
                val idx1 = i * lag
                val idx2 = idx1 + 1

                if (idx1 < globalOdf.size && idx2 < globalOdf.size) {
                    val val1 = globalOdf[idx1]
                    val val2 = globalOdf[idx2]
                    variance.add((val1 - val2) * (val1 - val2))
                }
            }

            spectralConcentration[idx] = if (variance.isNotEmpty()) {
                1.0f / (1.0f + variance.average().toFloat())
            } else {
                0.5f
            }
        }

        // 2단계: 하모닉 관계 분석 및 필터링
        for (idx in improved.indices) {
            val lag = idx + minLag
            val acVal = improved[idx]

            if (acVal < 1e-6f) continue

            // 2배 lag (절반 박자) 확인
            val doubleLagIdx = lag * 2 - minLag
            if (doubleLagIdx in improved.indices) {
                val doubleAc = improved[doubleLagIdx]
                val doubleConcentration = spectralConcentration[doubleLagIdx]

                // 절반 박자와 강도 비율
                if (doubleAc > 1e-6f) {
                    val strengthRatio = doubleAc / acVal
                    val concentrationRatio = doubleConcentration / (spectralConcentration[idx] + 1e-6f)

                    // 절반 박자가 훨씬 강하고 농도도 높으면, 현재 lag는 하모닉
                    if (strengthRatio >= 0.7f && concentrationRatio >= 0.8f) {
                        improved[idx] = acVal * 0.25f  // 더 공격적으로 필터링
                        Log.d(
                            TAG,
                            "V3 AC_HARMONIC_FILTER: lag=$lag(2x하모닉) strengthRatio=${"%.2f".format(strengthRatio)} concentrationRatio=${"%.2f".format(concentrationRatio)}"
                        )
                        continue
                    }
                }
            }

            // 절반 lag (2배 박자) 확인
            if (lag > 1) {
                val halfLagIdx = lag / 2 - minLag
                if (halfLagIdx >= 0 && halfLagIdx in improved.indices) {
                    val halfAc = improved[halfLagIdx]
                    val halfConcentration = spectralConcentration[halfLagIdx]

                    if (halfAc > 1e-6f) {
                        val strengthRatio = halfAc / acVal
                        val concentrationRatio = halfConcentration / (spectralConcentration[idx] + 1e-6f)

                        // 절반 lag이 훨씬 강하고 농도도 높으면, 현재 lag는 하모닉
                        if (strengthRatio >= 0.7f && concentrationRatio >= 0.8f) {
                            improved[idx] = acVal * 0.25f
                            Log.d(
                                TAG,
                                "V3 AC_HARMONIC_FILTER: lag=$lag(0.5x하모닉) strengthRatio=${"%.2f".format(strengthRatio)} concentrationRatio=${"%.2f".format(concentrationRatio)}"
                            )
                            continue
                        }
                    }
                }
            }

            // 3단계: 스펙트럼 농도가 높으면 가중치 증가
            // 농도 높음 = 주기성 일관됨 = 진정한 박자 가능성 높음
            val concentrationWeight = 0.7f + spectralConcentration[idx] * 0.3f
            improved[idx] = acVal * concentrationWeight

            if (spectralConcentration[idx] > 0.7f) {
                Log.d(
                    TAG,
                    "V3 AC_BOOST: lag=$lag concentration=${"%.3f".format(spectralConcentration[idx])} acBefore=${"%.6f".format(acVal)} acAfter=${"%.6f".format(improved[idx])}"
                )
            }
        }

        return improved
    }

    /**
     * Tempogram에서 시간별 BPM 곡선 추출 (상세 하모닉 분석 포함)
     *
     * @return FloatArray - 각 시간프레임별 최강 BPM
     */
    private fun extractBpmCurve(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long,
        step: Int
    ): FloatArray {
        if (tempogram.isEmpty() || tempogram[0].isEmpty()) {
            return FloatArray(0)
        }

        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())
        val numTimeFrames = tempogram[0].size
        val bpmCurve = FloatArray(numTimeFrames)

        // 하모닉 분석용 데이터 수집
        val harmonicAnalysis = mutableListOf<String>()

        for (tIdx in 0 until numTimeFrames) {
            // 상위 5개 피크 찾기
            val peaks = mutableListOf<Pair<Int, Float>>() // (lag, strength)
            for (lagIdx in tempogram.indices) {
                peaks.add(Pair(lagIdx + minLag, tempogram[lagIdx][tIdx]))
            }
            peaks.sortByDescending { it.second }
            val topPeaks = peaks.take(5)

            // 최고 피크
            val (bestLag, bestStrength) = topPeaks[0]
            bpmCurve[tIdx] = (60_000L / (bestLag * hopMs)).toFloat()

            // 매 10프레임마다 상세 로그 기록 (데이터 크기 관리)
            if (tIdx % 10 == 0) {
                val timeMs = tIdx * step * hopMs
                val peakInfoList = topPeaks.mapIndexed { idx, (lag, strength) ->
                    val bpm = 60_000L / (lag * hopMs)
                    val ratio = lag.toFloat() / bestLag.toFloat()
                    val normStrength = if (bestStrength > 0) (strength / bestStrength * 100).toInt() else 0

                    // 하모닉 관계 판정
                    val harmonicType = when {
                        kotlin.math.abs(ratio - 0.5f) < 0.08f -> "2x"    // 절반 배속 (BPM 2배)
                        kotlin.math.abs(ratio - 0.67f) < 0.08f -> "1.5x"  // 2/3 배속 (BPM 1.5배)
                        kotlin.math.abs(ratio - 1.0f) < 0.05f -> "PEAK"   // 기본 피크
                        kotlin.math.abs(ratio - 1.5f) < 0.08f -> "0.67x"  // 3/2 배 (BPM 2/3배)
                        kotlin.math.abs(ratio - 2.0f) < 0.08f -> "0.5x"   // 2배 (BPM 절반)
                        else -> "other"
                    }

                    "[$idx]lag=$lag(${bpm.toInt()}BPM,ratio=${String.format("%.2f", ratio)},$harmonicType,str=$normStrength%)"
                }

                val peakInfo = peakInfoList.joinToString(" | ")
                harmonicAnalysis.add("t=$timeMs: $peakInfo")
            }
        }

        // 하모닉 분석 결과 로그
        if (harmonicAnalysis.isNotEmpty()) {
            val harmonicLog = StringBuilder("V3 HARMONIC_PEAKS:\n")
            harmonicAnalysis.forEach { harmonicLog.append("  $it\n") }
            Log.d(TAG, harmonicLog.toString())
        }

        // 진단: BPM 곡선 샘플 출력
        val diag = StringBuilder("V3 BPM_CURVE_SAMPLE: ")
        for (i in bpmCurve.indices step maxOf(1, bpmCurve.size / 10)) {
            val timeMs = i * hopMs
            diag.append("t=${timeMs}ms:${bpmCurve[i].toInt()}BPM;")
        }
        Log.d(TAG, diag.toString())

        return bpmCurve
    }

    /**
     * BPM 곡선 평활화 (이동 평균)
     *
     * @param curve BPM 곡선
     * @param windowSize 평활화 윈도우 크기 (프레임 수)
     * @return 평활화된 BPM 곡선
     */
    private fun smoothBpmCurve(curve: FloatArray, windowSize: Int = 5): FloatArray {
        if (curve.size <= windowSize) return curve.copyOf()

        val smoothed = FloatArray(curve.size)
        val halfWindow = windowSize / 2

        for (i in curve.indices) {
            val start = maxOf(0, i - halfWindow)
            val end = minOf(curve.size, i + halfWindow + 1)
            var sum = 0f
            for (j in start until end) {
                sum += curve[j]
            }
            smoothed[i] = sum / (end - start)
        }

        return smoothed
    }

    /**
     * madmom 방식 BPM 계산: 비트 간격의 중앙값으로부터 BPM 추정
     * 절반/2배 옥타브 에러도 감지하고 보정
     * 참고: 90-110 BPM 대역 오류 방지를 위해 강화된 필터링
     *
     * @param beatTimesMs 비트 타임스탐프 (ms)
     * @param referenceBpm 참고 BPM (옥타브 에러 판단용, 0이면 무시)
     * @return BPM (0 if insufficient beats)
     */
    private fun calculateBpmFromBeats(beatTimesMs: List<Long>, referenceBpm: Long = 0L): Long {
        if (beatTimesMs.size < 2) return 0L

        // 비트 간격(초 단위) 계산
        val intervals = mutableListOf<Double>()
        for (i in 0 until beatTimesMs.size - 1) {
            val intervalMs = beatTimesMs[i + 1] - beatTimesMs[i]
            if (intervalMs > 0) {
                intervals.add(intervalMs / 1000.0)  // ms → seconds
            }
        }

        if (intervals.isEmpty()) return 0L

        // 이상치 제거: 중앙값의 50-200% 범위 내만 사용 (90-110 BPM 대역 안정화)
        intervals.sort()
        val median = if (intervals.size % 2 == 0) {
            (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2.0
        } else {
            intervals[intervals.size / 2]
        }

        val filtered = intervals.filter { it >= median * 0.5 && it <= median * 2.0 }
        if (filtered.isEmpty()) return 0L

        // 필터링된 데이터의 중앙값 재계산
        val filteredMedian = if (filtered.size % 2 == 0) {
            (filtered[filtered.size / 2 - 1] + filtered[filtered.size / 2]) / 2.0
        } else {
            filtered[filtered.size / 2]
        }

        var bpm = if (filteredMedian > 0) (60.0 / filteredMedian).toLong() else return 0L

        Log.d(TAG, "V3 BPM_FROM_BEATS: intervals=${intervals.size} → filtered=${filtered.size}, " +
                "median=${String.format("%.3f", median)}s → ${String.format("%.3f", filteredMedian)}s = ${bpm} BPM")

        // === 옥타브 에러 보정 (참고 BPM과 비교) ===
        if (referenceBpm > 0L) {
            val ratio = bpm.toFloat() / referenceBpm.toFloat()

            // 2배 오류 감지 (bpm ≈ 2 * reference)
            if (ratio in 1.9f..2.1f) {
                val halfBpm = bpm / 2
                Log.d(TAG, "V3 BPM_OCTAVE_2x: $bpm BPM → $halfBpm BPM (ratio=$ratio)")
                bpm = halfBpm
            }
            // 절반 오류 감지 (bpm ≈ 0.5 * reference)
            else if (ratio in 0.45f..0.55f) {
                val doubleBpm = bpm * 2
                Log.d(TAG, "V3 BPM_OCTAVE_0.5x: $bpm BPM → $doubleBpm BPM (ratio=$ratio)")
                bpm = doubleBpm
            }
        }

        // BPM = 60 / median_interval_seconds
        return bpm
    }

    /**
     * 동적 섹션 BPM의 중앙값 계산 (Method B용)
     *
     * @param sectionBpms List<Pair<sectionStartMs, bpm>>
     * @return 중앙값 BPM (또는 0f if empty)
     */
    private fun calculateMedianBpmFromSections(
        sectionBpms: List<Pair<Long, Float>>
    ): Float {
        if (sectionBpms.isEmpty()) return 0f

        val bpmValues = sectionBpms.map { it.second }.sorted()
        val median = if (bpmValues.size % 2 == 0) {
            (bpmValues[bpmValues.size / 2 - 1] + bpmValues[bpmValues.size / 2]) / 2f
        } else {
            bpmValues[bpmValues.size / 2]
        }

        // [FIX #3] 섹션 BPM 이상값 필터링 (중앙값 ±20 범위)
        val initialMedian = median
        val filteredBpms = bpmValues.filter { kotlin.math.abs(it - initialMedian) <= 20f }.sorted()

        val finalMedian = if (filteredBpms.isNotEmpty()) {
            if (filteredBpms.size % 2 == 0) {
                (filteredBpms[filteredBpms.size / 2 - 1] + filteredBpms[filteredBpms.size / 2]) / 2f
            } else {
                filteredBpms[filteredBpms.size / 2]
            }
        } else {
            median
        }

        return finalMedian
    }

    /**
     * V3.7 Step 3: Method A (Global AC) vs Method B (Section Median) 선택
     *
     * 미스매치 곡들에서 Global BPM이 정확할 수 있으므로,
     * methodBBpm이 극단적이거나 신뢰도가 낮으면 methodABeatMs (Global AC) 사용.
     *
     * @param globalBpm Method A에서 계산한 Global AC 피크 BPM
     * @param methodBBpm Method B에서 계산한 보정된 Section Median BPM
     * @param methodABeatMs Global AC에서 계산한 beatMs
     * @return 최종 선택된 beatMs
     */
    private fun selectBetweenMethodsAandB(
        globalBpm: Float,
        methodBBpm: Float,
        methodABeatMs: Long,
        isCompoundMeter: Boolean = false
    ): MethodSelectionResult {
        if (methodBBpm <= 0f) {
            // methodBBpm이 없으면 Global AC 사용
            return MethodSelectionResult(methodABeatMs, "NO_METHOD_B: methodB unavailable → use Global ${"%.1f".format(globalBpm)}")
        }

        val IDEAL_MIN = 80f
        val IDEAL_MAX = 180f
        val tolerance = 5f

        val diff = kotlin.math.abs(methodBBpm - globalBpm)

        // Case 1: 두 값이 일치하면 평균 사용 (둘 다 신뢰)
        if (diff <= tolerance) {
            val avgBpm = (methodBBpm + globalBpm) / 2f
            val reason = "SELECT_AVG: methodB=${"%.1f".format(methodBBpm)} ≈ methodA=${"%.1f".format(globalBpm)} → avg=${"%.1f".format(avgBpm)}"
            return MethodSelectionResult(beatMsFromBpm(avgBpm), reason)
        }

        // Case 2: methodBBpm이 비합리적인 범위면 Global BPM 사용
        if (methodBBpm < IDEAL_MIN || methodBBpm > IDEAL_MAX) {
            val reason = "SELECT_GLOBAL: methodB=${"%.1f".format(methodBBpm)} out of range [$IDEAL_MIN-$IDEAL_MAX] → use Global ${"%.1f".format(globalBpm)}"
            return MethodSelectionResult(methodABeatMs, reason)
        }

        // Case 3: Global BPM이 비합리적인 범위면 methodBBpm 사용
        if (globalBpm < IDEAL_MIN || globalBpm > IDEAL_MAX) {
            val reason = "SELECT_METHOD_B: Global=${"%.1f".format(globalBpm)} out of range → use methodB ${"%.1f".format(methodBBpm)}"
            return MethodSelectionResult(beatMsFromBpm(methodBBpm), reason)
        }

        // Case 4: 둘 다 합리적이지만 차이가 크면 → 2x 관계 확인
        // 절반/2배 속도 오류일 가능성을 체크
        val doubleRelationDiff = kotlin.math.abs((methodBBpm * 2) - globalBpm)
        if (doubleRelationDiff <= 10f) {
            // methodBBpm * 2 ≈ globalBpm → methodBBpm이 절반 속도
            val reason = "SELECT_DOUBLE: methodB=${"%.1f".format(methodBBpm)} × 2 ≈ Global=${"%.1f".format(globalBpm)} (diff=${"%.1f".format(doubleRelationDiff)}) → use doubled methodB=${"%.1f".format(methodBBpm * 2)}"
            return MethodSelectionResult(beatMsFromBpm(methodBBpm * 2), reason)
        }

        val halfRelationDiff = kotlin.math.abs((methodBBpm / 2) - globalBpm)
        if (halfRelationDiff <= 10f) {
            // methodBBpm / 2 ≈ globalBpm → methodBBpm이 2배 속도
            val reason = "SELECT_HALF: methodB=${"%.1f".format(methodBBpm)} / 2 ≈ Global=${"%.1f".format(globalBpm)} (diff=${"%.1f".format(halfRelationDiff)}) → use halved methodB=${"%.1f".format(methodBBpm / 2)}"
            return MethodSelectionResult(beatMsFromBpm(methodBBpm / 2), reason)
        }

        // 2x 관계 없음 → methodBBpm이 합리적이면 사용, 아니면 Global 사용
        // V4.1: 6/8박(복합박자)에서는 이 "묻지도 따지지도 않고 methodB 우선" 규칙이
        // 오히려 이미 맞는 Global을 뒤집는 경우가 있음 (Entrance: Global=105.3 정답인데
        // methodB=131.9로 잘못 대체됨, 비율 1.25로 다른 4/4·3/4 곡들의 1.49 근처와 다름).
        // 43곡 검증: 4/4·3/4 곡 11개는 전부 이 분기에서 methodB가 정답이었고,
        // 6/8인 Entrance만 예외였음 → 6/8일 때만 Global 유지.
        if (methodBBpm >= IDEAL_MIN && methodBBpm <= IDEAL_MAX && !isCompoundMeter) {
            val reason = "SELECT_METHOD_B_FINAL: no 2x relation, methodB=${"%.1f".format(methodBBpm)} preferred over Global=${"%.1f".format(globalBpm)}"
            return MethodSelectionResult(beatMsFromBpm(methodBBpm), reason)
        }
        if (methodBBpm >= IDEAL_MIN && methodBBpm <= IDEAL_MAX && isCompoundMeter) {
            val reason = "SELECT_GLOBAL_COMPOUND_METER: no 2x relation, but compound(6/8) meter detected → keep Global=${"%.1f".format(globalBpm)} instead of methodB=${"%.1f".format(methodBBpm)}"
            return MethodSelectionResult(methodABeatMs, reason)
        }

        // 최후의 수단: Global AC 사용
        val reason = "SELECT_GLOBAL_FINAL: methodB=${"%.1f".format(methodBBpm)} vs Global=${"%.1f".format(globalBpm)} (diff=${"%.1f".format(diff)}) → use Global as fallback"
        return MethodSelectionResult(methodABeatMs, reason)
    }

    /**
     * V3.7.8: 절반/2배 속도 오류 감지 및 보정 (섹션 BPM 범위 분석)
     *
     * 섹션 BPM들의 분포 범위를 분석하여 절반/2배 오류를 더 정확히 감지.
     *
     * 핵심 로직:
     * 1. Global과 Median이 가깝고(≤5) 섹션 BPM 범위가 좁으면(≤50):
     *    → 정상 범위이므로 보정하지 않음 (정상 곡: 74.1 BPM, 섹션 범위 28-47)
     *
     * 2. Global과 Median이 가깝지만 섹션 BPM 범위가 넓으면(>50):
     *    → 절반/2배 속도 가능성이 높으므로 섹션 분포로 판단 후 보정
     *
     * 3. Global과 Median이 차이가 크면:
     *    → 섹션 분포로 판단 후 보정
     *
     * @param medianBpm 섹션 분석으로 계산한 중앙값 BPM
     * @param globalBpm 전역 AC 피크 BPM (Method A, bestBpm)
     * @param sectionBpms 섹션별 (timeMs, BPM) 쌍 리스트
     * @return 보정된 BPM 값
     */
    private fun detectAndCorrectOctaveError(
        medianBpm: Float,
        globalBpm: Float,
        sectionBpms: List<Pair<Long, Float>>
    ): OctaveCorrectionResult {
        if (medianBpm <= 0f || sectionBpms.isEmpty()) return OctaveCorrectionResult(medianBpm, "NO_DATA: medianBpm<=0 or no sections")

        val IDEAL_MIN = 80f
        val IDEAL_MAX = 180f

        val bpmValues = sectionBpms.map { it.second }.sorted()
        if (bpmValues.isEmpty()) return OctaveCorrectionResult(medianBpm, "NO_DATA: empty bpmValues")

        val minBpm = bpmValues.minOrNull() ?: medianBpm
        val maxBpm = bpmValues.maxOrNull() ?: medianBpm
        val bpmRange = maxBpm - minBpm  // 섹션 BPM의 범위

        // 섹션 BPM들이 대부분 특정 범위에 집중되어 있는지 확인
        val lowRangeCount = bpmValues.count { it < 100f }
        val highRangeCount = bpmValues.count { it >= 100f }
        val totalSections = bpmValues.size

        Log.d(TAG, "V3.7.8 DistributionAnalysis: median=${"%.1f".format(medianBpm)}, global=${"%.1f".format(globalBpm)}, " +
                "range=${"%.0f".format(bpmRange)}, low(<100)=$lowRangeCount, high(≥100)=$highRangeCount / $totalSections")

        val doubledBpm = medianBpm * 2
        val halvedBpm = medianBpm / 2
        val globalMedianDiff = kotlin.math.abs(globalBpm - medianBpm)

        // V3.7.8: Global과 Median이 일치(≤5)하고 섹션 범위가 좁으면(≤50) 보정 안 함
        // → 정상 곡: TOMBOY, 진미령 등 (범위 28-47)
        if (globalMedianDiff <= 5f && bpmRange <= 50f) {
            val reason = "TRUST_BOTH_METHODS: global=${"%.1f".format(globalBpm)} ≈ median=${"%.1f".format(medianBpm)} (perfect match, narrow range=${"%.0f".format(bpmRange)})"
            return OctaveCorrectionResult(medianBpm, reason)
        }

        // V3.9: Global과 Median이 가깝고 둘 다 정상 범위면 보정 방지
        if (globalMedianDiff <= 15f &&
            globalBpm >= IDEAL_MIN && globalBpm <= IDEAL_MAX &&
            medianBpm >= IDEAL_MIN && medianBpm <= IDEAL_MAX) {

            // V4.4: "정상 범위"라고 무조건 믿기 전에, 고BPM 섹션들이 가리키는 값이
            // 현재 값과 사실상 같은 템포(측정 오차로 100 근처만 넘긴 것)인지, 아니면
            // 완전히 동떨어진 별개의 클러스터인지 구분. IYKYK: Global=85.7인데 섹션의
            // 45%가 127 근처(highBpmMedian/global 비율 1.52)로 몰려있음에도 이 분기가
            // 먼저 발동해 뒤의 HALF_TEMPO_DETECTED 체크에 도달하지 못했던 문제.
            // 43곡 실측: 정상적으로 이 분기를 타는 5곡(홍진영/aespa NextLevel/CharliePuth/
            // MyWorld/AlmondChoc)은 highBpmMedian/global 비율이 0.998~1.000(사실상 동일 템포).
            // IYKYK만 1.517로 확연히 다름 — 임계값 1.2로 안전하게 분리 가능.
            val highBpms = bpmValues.filter { it >= 100f }
            if (highRangeCount >= 3) {
                val highBpmMedian = highBpms.sorted()[highBpms.size / 2]
                val clusterRatio = highBpmMedian / globalBpm
                if (clusterRatio >= 1.2f) {
                    val reason = "DISTINCT_HIGH_CLUSTER: global=${"%.1f".format(globalBpm)} ≈ median=${"%.1f".format(medianBpm)} " +
                            "but highBpmMedian=${"%.1f".format(highBpmMedian)}(ratio=${"%.2f".format(clusterRatio)}, count=$highRangeCount) " +
                            "is a distinct tempo cluster, not measurement noise → offer as methodB candidate"
                    return OctaveCorrectionResult(highBpmMedian, reason)
                }
            }

            val reason = "KEEP_ORIGINAL: global=${"%.1f".format(globalBpm)} ≈ median=${"%.1f".format(medianBpm)} (both in normal range, diff=${"%.1f".format(globalMedianDiff)})"
            return OctaveCorrectionResult(medianBpm, reason)
        }

        // V3.9: Global과 Median 모두 <80이고 가깝지만, 고BPM 섹션이 2배 관계를 보이면 보정
        // 예: TOMBOY (Global=74, Median=74, highBpms=[105,115,142,146] → 대부분 2배 범위)
        if (globalMedianDiff <= 5f && globalBpm < IDEAL_MIN && medianBpm < IDEAL_MIN &&
            highRangeCount > 0) {
            // 고BPM 섹션의 중앙값 계산
            val highBpms = bpmValues.filter { it >= 100f }
            val highBpmMedian = highBpms.sorted()[highBpms.size / 2]

            // 고BPM 중앙값이 2배 관계에 있는지 확인 (±10%)
            val doubleExpectation = doubledBpm
            val doubleRatio = kotlin.math.abs(highBpmMedian - doubleExpectation) / doubleExpectation

            Log.d(TAG, "V3.9 OCTAVE_CHECK_LOWBPM: median=${"%.1f".format(medianBpm)}, highBpmMedian=${"%.1f".format(highBpmMedian)}, " +
                    "doubleExpected=${"%.1f".format(doubleExpectation)}, ratio=${"%.2f".format(doubleRatio)}, highCount=$highRangeCount")

            // V4.1: doubleRatio 단독으로는 오탐/정탐 구분이 안 됨 (43곡 실측 결과 0.03~0.04 범위에 오탐/정탐 혼재).
            // 대신 highRangeCount(2배로 보이는 섹션 개수)가 판별력을 가짐:
            //   - 실제 곡 전체가 빠른 경우: 섹션별 검출도 대부분 절반속도로 오검출되어 "고BPM 섹션"이 1~2개만 우연히 남음
            //   - 실제로는 느린 곡인데 드럼필 등 국소 구간만 빠른 경우: 고BPM 섹션이 여러 개(4개 이상) 나타남
            // 43곡 실측: 정탐(JUMP,COOMO,Cherish,SodaPop,KillThisLove)=1~2개, 오탐(TOMBOY,초혼,EdSheeran)=4개 이상
            val highCountLooksLikeGlobalTempo = highRangeCount <= 2

            if (doubleRatio <= 0.10f && doubledBpm >= IDEAL_MIN && doubledBpm <= IDEAL_MAX && highCountLooksLikeGlobalTempo) {
                val reason = "HALF_TEMPO_CONFIRMED: median=${"%.1f".format(medianBpm)}, highBpmMedian=${"%.1f".format(highBpmMedian)} " +
                        "matches 2x(doubleRatio=${"%.2f".format(doubleRatio)}, highCount=$highRangeCount) → return doubled ${"%.1f".format(doubledBpm)}"
                return OctaveCorrectionResult(doubledBpm, reason)
            } else {
                val reason = "HALF_TEMPO_REJECTED: median=${"%.1f".format(medianBpm)}, highBpmMedian=${"%.1f".format(highBpmMedian)} " +
                        "doubleRatio=${"%.2f".format(doubleRatio)} highCount=$highRangeCount " +
                        "(${if (!highCountLooksLikeGlobalTempo) "too many high-BPM outlier sections, likely local fills not global tempo" else "ratio mismatch"}) → trust original median"
                return OctaveCorrectionResult(medianBpm, reason)
            }
        }

        // 절반 속도 감지: 섹션 BPM이 모두 낮은 범위(50-100)에 집중
        // 그리고 2배하면 합리적인 음악 BPM 범위(80-180)가 됨
        if (lowRangeCount >= totalSections * 0.7f && doubledBpm >= IDEAL_MIN && doubledBpm <= IDEAL_MAX) {
            val reason = "HALF_TEMPO_DETECTED: 70% of sections <100 BPM, ${"%.1f".format(medianBpm)} → ${"%.1f".format(doubledBpm)}"
            return OctaveCorrectionResult(doubledBpm, reason)
        }

        // 2배 속도 감지: 섹션 BPM이 모두 높은 범위(100+)에 집중
        // 그리고 절반하면 합리적인 음악 BPM 범위(80-180)가 됨
        if (highRangeCount >= totalSections * 0.7f && halvedBpm >= IDEAL_MIN && halvedBpm <= IDEAL_MAX) {
            val reason = "DOUBLE_TEMPO_DETECTED: 70% of sections ≥100 BPM, ${"%.1f".format(medianBpm)} → ${"%.1f".format(halvedBpm)}"
            return OctaveCorrectionResult(halvedBpm, reason)
        }

        val reason = "KEEP_ORIGINAL: median=${"%.1f".format(medianBpm)} BPM (no clear octave pattern)"
        return OctaveCorrectionResult(medianBpm, reason)
    }


    /**
     * V3.8: Tempogram 분석하여 개선 데이터 추출
     * @return TempogramInsights - AC 피크, 신뢰도, 2차 피크 비율 등
     */
    private fun analyzeTempogramForInsights(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        finalBeatMs: Long,
        sectionBpms: List<Pair<Long, Float>>
    ): TempogramInsights {
        if (tempogram.isEmpty() || tempogram[0].isEmpty()) {
            return TempogramInsights(
                bestBpm = 0f,
                confidence = 0f,
                topPeaks = emptyList(),
                halfTempoRatio = null,
                doubleTempoRatio = null,
                globalAcMagnitude = 0f,
                peakStrength = 0f,
                secondaryPeakRatio = 0f
            )
        }

        // Tempogram AC 컬럼 (마지막 프레임)의 피크 찾기
        val acColumn = tempogram.map { it.last() }.toFloatArray()
        val minLag = 37  // 375ms / 10ms
        val maxLag = 120  // 1200ms / 10ms

        // 상위 피크 분석
        val peaks = mutableListOf<Pair<Int, Float>>()
        for (lag in minLag..minOf(maxLag, acColumn.size - 1)) {
            peaks.add(Pair(lag, acColumn[lag]))
        }

        val sortedPeaks = peaks.sortedByDescending { it.second }
        if (sortedPeaks.isEmpty()) {
            return TempogramInsights(
                bestBpm = 0f,
                confidence = 0f,
                topPeaks = emptyList(),
                halfTempoRatio = null,
                doubleTempoRatio = null,
                globalAcMagnitude = 0f,
                peakStrength = 0f,
                secondaryPeakRatio = 0f
            )
        }

        // 상위 피크 5개 추출
        val topAcPeaks = sortedPeaks.take(5)
        val topPeakScore = topAcPeaks.first().second
        val secondaryPeakRatio = if (topAcPeaks.size > 1) {
            topAcPeaks[1].second / topPeakScore
        } else {
            0f
        }

        // AC 피크 정보 변환
        val peakInfoList = topAcPeaks.map { (lag, score) ->
            val beatMs = lag * hopMs
            val bpm = bpmFromBeatMs(beatMs)
            val ratio = lag.toFloat() / topAcPeaks[0].first.toFloat()
            val peakType = when {
                kotlin.math.abs(ratio - 0.5f) < 0.08f -> "half"
                kotlin.math.abs(ratio - 1.0f) < 0.05f -> "peak"
                kotlin.math.abs(ratio - 2.0f) < 0.08f -> "double"
                else -> "other"
            }
            val scorePercent = (score / topPeakScore * 100).toInt()

            AcPeakInfo(
                lagMs = beatMs,
                bpm = bpm,
                acValue = score,
                score = score,
                scorePercent = scorePercent,
                ratio = ratio,
                peakType = peakType
            )
        }

        // 섹션 기반 신뢰도 계산
        val sectionBpmValues = sectionBpms.map { it.second }
        val medianSectionBpm = if (sectionBpmValues.isNotEmpty()) {
            sectionBpmValues.sorted()[sectionBpmValues.size / 2]
        } else {
            0f
        }

        val bestBpm = bpmFromBeatMs(finalBeatMs)
        val confidenceScore = if (kotlin.math.abs(bestBpm - medianSectionBpm) <= 5f) {
            0.95f  // Global과 Median 일치 → 높은 신뢰도
        } else if (kotlin.math.abs(bestBpm * 2 - medianSectionBpm) <= 5f) {
            0.70f  // 2배 관계 → 중간 신뢰도
        } else {
            topPeakScore / 10f  // AC 강도 기반
        }

        return TempogramInsights(
            bestBpm = bestBpm,
            confidence = kotlin.math.min(confidenceScore, 1f),
            topPeaks = peakInfoList,
            halfTempoRatio = null,
            doubleTempoRatio = null,
            globalAcMagnitude = topPeakScore,
            peakStrength = topPeakScore,
            secondaryPeakRatio = secondaryPeakRatio
        )
    }

    /**
     * BPM 변화점 감지 (임계값 기반)
     *
     * @param curve BPM 곡선
     * @param changeThresholdPercent 변화 임계값 (%)
     * @param minDurationFrames 최소 지속 프레임 수
     * @return List<프레임 인덱스> - 변화점의 프레임 위치
     */
    private fun detectBpmChangePoints(
        curve: FloatArray,
        changeThresholdPercent: Float = 10f,
        minDurationFrames: Int = 10
    ): List<Int> {
        if (curve.size < minDurationFrames * 2) return emptyList()

        val changePoints = mutableListOf<Int>()

        for (i in minDurationFrames until curve.size - minDurationFrames) {
            val prevBpmAvg = curve.slice((i - minDurationFrames) until i).average().toFloat()
            val nextBpmAvg = curve.slice(i until (i + minDurationFrames)).average().toFloat()

            if (prevBpmAvg > 0f) {
                val changePercent = kotlin.math.abs(nextBpmAvg - prevBpmAvg) / prevBpmAvg * 100f
                if (changePercent >= changeThresholdPercent) {
                    changePoints.add(i)
                }
            }
        }

        // 인접한 변화점 제거 (가장 큰 변화만 선택)
        val filtered = mutableListOf<Int>()
        for (point in changePoints) {
            if (filtered.isEmpty() || point - filtered.last() >= minDurationFrames) {
                filtered.add(point)
            }
        }

        return filtered
    }

    /**
     * Tempogram 기반 자동 섹션 경계 생성
     *
     * @param tempogram 전체 곡의 Tempogram
     * @param hopMs ODF 홉 간격
     * @param minBeatMs 최소 비트 간격
     * @param externalSectionBoundariesMs 외부 섹션 경계 (SectionDetector 등)
     * @param changeThresholdPercent BPM 변화 감지 임계값 (%)
     * @return List<시작시간Ms> - 자동 감지된 섹션 경계
     */
    fun detectDynamicSections(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long,
        odfSize: Int,
        externalSectionBoundariesMs: List<Long> = emptyList(),
        changeThresholdPercent: Float = 10f
    ): List<Long> {
        if (tempogram.isEmpty() || tempogram[0].isEmpty()) {
            return externalSectionBoundariesMs
        }

        // Step 계산: 각 tempogram 프레임이 대표하는 ODF 프레임 수
        val timeFrames = tempogram[0].size
        val step = maxOf(1, odfSize / (timeFrames / 2))

        // 1단계: 고정 간격 섹션 생성 (5초마다)
        val fixedSectionInterval = 5000L  // 5초 간격
        val totalDurationMs = tempogram[0].size.toLong() * step * hopMs
        val fixedBoundaries = mutableListOf<Long>()
        var currentMs = 0L
        while (currentMs < totalDurationMs) {
            fixedBoundaries.add(currentMs)
            currentMs += fixedSectionInterval
        }
        fixedBoundaries.add(totalDurationMs)  // 끝점 추가

        // 2단계: BPM 곡선 추출 및 평활화 (선택사항: 동적 감지용)
        val bpmCurve = extractBpmCurve(tempogram, hopMs, minBeatMs, step)
        val smoothedBpm = smoothBpmCurve(bpmCurve, windowSize = 5)

        // 3단계: BPM 변화점 감지
        val changePoints = detectBpmChangePoints(smoothedBpm, changeThresholdPercent, minDurationFrames = 10)

        // 4단계: BPM 변화점을 ms로 변환 (step 반영)
        val dynamicChangePoints = changePoints.map { (it * step * hopMs) }.toMutableList()

        // 5단계: 모든 경계 통합 (고정 + 동적 + 외부)
        val allBoundaries = mutableSetOf<Long>()
        allBoundaries.addAll(fixedBoundaries)  // 고정 간격
        allBoundaries.addAll(dynamicChangePoints)  // BPM 변화점
        allBoundaries.addAll(externalSectionBoundariesMs)  // 외부 경계

        // 6단계: 정렬 및 최종 경계 생성
        val finalBoundaries = allBoundaries.sorted()

        // 로그
        if (changePoints.isNotEmpty()) {
            Log.d(
                TAG,
                "V3 DynamicSections: detected=${changePoints.size} changes at " +
                        changePoints.take(5).joinToString(", ") { "${it * hopMs}ms" }
            )
        }

        return finalBoundaries
    }

    /**
     * 구간별 BPM 탐지 (섹션 경계 기반)
     *
     * Tempogram을 시간 구간별로 분석하여 각 섹션의 BPM을 따로 계산
     * 반박자 문제 해결용: 곡의 일부는 71.8 BPM, 일부는 142.9 BPM인 경우
     *
     * @param tempogram 전체 곡의 Tempogram
     * @param hopMs ODF 홉 간격
     * @param minBeatMs 최소 비트 간격 (lag 계산용)
     * @param sectionBoundariesMs 섹션 경계 시간 (ms)
     * @return List<Pair<Long, Float>>
     *         각 쌍은 (섹션시작시간Ms, 섹션BPM값)
     *         예: [(0L, 92.0f), (20000L, 94.5f), ...]
     */
    fun detectSectionBpms(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long,
        odfSize: Int,
        sectionBoundariesMs: List<Long> = emptyList()
    ): List<Pair<Long, Float>> {
        // ════════════════════════════════════════════════════════════════════════════════
        // [섹션별 BPM 감지 분석 로그]
        //
        // 이 함수는 Tempogram을 섹션 단위로 분석하여 각 섹션의 BPM을 추정합니다.
        // V3.4에서 절대 강도 임계값 (0.02) 필터링이 추가되어 약한 신호 섹션을 제외합니다.
        // V3.6 FIX: odfSize 기반으로 실제 곡 길이 범위를 벗어나는 섹션 필터링
        //
        // 실제 분석 데이터 (iKON):
        // ─────────────────────────────────────────────────────────────────────
        // 섹션 분석 결과:
        //   39개 섹션 중 23개만 유효 (16개 약한 신호로 필터링됨)
        //
        // 섹션별 절대 강도 분포:
        //   - 강한 신호: 0.045 ~ 0.051 (섹션 0, 3)
        //   - 중간 신호: 0.030 ~ 0.040
        //   - 약한 신호: 0.0001 (섹션 2 - SKIPPED)
        //
        // 섹션별 선택 BPM:
        //   섹션 0: 67 BPM  (lag=89, abs_strength=0.045)
        //   섹션 1: 162 BPM (lag=37, abs_strength=0.038)
        //   섹션 2: SKIPPED (abs_strength=0.0001 < 0.02 threshold)
        //   섹션 3: 117 BPM (lag=51, abs_strength=0.051)
        //   ...
        //   → 중앙값: 78 BPM (23개 유효 섹션)
        //
        // V3.4 절대 강도 필터링의 효과:
        //   - 극도로 약한 신호로 인한 오류 방지
        //   - 정규화만으로 판단하던 방식의 한계 극복
        //   - 신뢰성 높은 섹션만 선택
        //
        // 주의사항 (V3.5 비트 추적과 연관):
        //   - 선택된 BPM이 모두 섹션 내에서 가장 강한 신호라는 보장은 없음
        //   - 절대 강도만으로는 ODF상의 실제 비트 위치를 보장하지 않음
        //   - V3.5의 dpBeatTracker가 선택된 BPM으로 실제 비트를 생성해야 함
        // ════════════════════════════════════════════════════════════════════════════════

        if (tempogram.isEmpty() || tempogram[0].isEmpty()) {
            return emptyList()
        }

        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())
        val totalTimeFrames = tempogram[0].size
        val step = maxOf(1, odfSize / (totalTimeFrames / 2))
        val totalDurationMs = totalTimeFrames * step * hopMs

        // V3.6 FIX: odfSize를 기반으로 실제 곡 길이 범위 제한
        // Tempogram의 각 프레임은 ODF의 step개씩을 대표함
        // 실제 ODF가 odfSize까지만 존재하므로, 그 이상으로 초과하는 tempogram 섹션 제외
        val maxActualFrame = minOf(totalTimeFrames, maxOf(1, odfSize / step))
        val actualDurationMs = maxActualFrame * step * hopMs

        // 경계 프레임 계산 (ms를 tempogram 프레임으로 변환)
        val boundaryFrames = mutableListOf(0)  // 항상 처음부터 시작
        for (boundaryMs in sectionBoundariesMs) {
            val frame = (boundaryMs / (step * hopMs)).toInt().coerceIn(1, maxActualFrame - 1)
            if (!boundaryFrames.contains(frame)) {
                boundaryFrames.add(frame)
            }
        }
        boundaryFrames.add(maxActualFrame)  // V3.6: 실제 곡 길이 범위까지만
        boundaryFrames.sort()

        val result = mutableListOf<Pair<Long, Float>>()

        // 각 섹션별로 BPM 계산
        for (i in 0 until boundaryFrames.size - 1) {
            val startFrame = boundaryFrames[i]
            val endFrame = boundaryFrames[i + 1]
            val startMs = startFrame * step * hopMs
            val endMs = endFrame * step * hopMs

            if (endFrame - startFrame < 2) continue  // 너무 짧은 섹션 무시

            // 이 섹션의 Tempogram 슬라이스
            val sectionStrengths = FloatArray(tempogram.size)
            for (lagIdx in tempogram.indices) {
                var sum = 0f
                for (tIdx in startFrame until endFrame) {
                    sum += tempogram[lagIdx][tIdx]
                }
                sectionStrengths[lagIdx] = sum / (endFrame - startFrame)
            }

            // V3.4 FIX: 절대 강도 기반 신호 신뢰성 확인
            // 정규화 전 절대 강도 확인: 절대값 < 0.02는 신호가 극도로 약함
            val maxAbsoluteStrength = sectionStrengths.maxOrNull() ?: 0f
            if (maxAbsoluteStrength < 0.02f) {
                continue
            }

            // V3.4 FIX: 하모닉 필터링 적용 (약한 신호 보호)
            // 섹션의 정규화 전에 하모닉 필터링을 적용하여
            // 절반 박자나 두배 박자 하모닉이 약한 신호에서 "상대 최대"가 되는 것을 방지
            val filteredStrengths = filterHarmonicPeaks(sectionStrengths, minLag)

            // 정규화
            val maxStrength = filteredStrengths.maxOrNull() ?: 1f
            if (maxStrength > 1e-6f) {
                for (i in filteredStrengths.indices) {
                    filteredStrengths[i] = filteredStrengths[i] / maxStrength
                }
            }

            // 상위 5개 피크 추출 (하모닉 분석용)
            val peaks = filteredStrengths.mapIndexed { lagIdx, strength ->
                Pair(lagIdx + minLag, strength)
            }.sortedByDescending { it.second }.take(5)

            val bestLag = peaks[0].first
            val bestBeatMs = bestLag * hopMs
            val sectionBpm = bpmFromBeatMs(bestBeatMs)

            result.add(Pair(startMs, sectionBpm))
        }

        return result
    }

    /** V3 BPM 탐지 결과: BPM, confidence, tempogram, V4.0 다중 피크 평가 후보 목록 */
    data class BpmV3Result(
        val bpm: Float,
        val confidence: Float,
        val tempogram: Array<FloatArray>?,
        val peakCandidates: List<PeakCandidateDebugInfo> = emptyList()
    )

    /**
     * V3 BPM 탐지
     * @return BpmV3Result - BPM, confidence, tempogram, V4.0 피크 평가 후보 목록
     */
    fun estimateBpmV3(
        odf: FloatArray,
        hopMs: Long,
        priorCenterMs: Long = PRIOR_CENTER_MS,
        priorStdOctave: Float = PRIOR_STD_OCTAVE,
        minBeatMs: Long = 375L,
        maxBeatMs: Long = 1000L,
        useTempogram: Boolean = true,
        halfTempoRatio: Float = HALF_TEMPO_RATIO,
        doubleTempoRatio: Float = 0.55f
    ): BpmV3Result {
        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())
        val maxLag = maxOf(minLag + 1, (maxBeatMs / hopMs).toInt())

        if (odf.size <= maxLag + 2) {
            return BpmV3Result(0f, 0f, null)
        }

        // Tempogram 사용 (프로덕션 기본 경로: useTempogram=true)
        if (useTempogram) {
            val tempogram = computeTempogram(odf, hopMs, minBeatMs, maxBeatMs)
            val modalPeakResult = findModalPeak(tempogram, hopMs, minBeatMs)
            return BpmV3Result(modalPeakResult.bpm, modalPeakResult.confidence, tempogram, modalPeakResult.candidates)
        }

        // V1 방식 (useTempogram=false일 때만 사용되는 폴백): 전체 lag AC × log-normal prior로 argmax
        val acVals = FloatArray(maxLag + 1)
        val scoreVals = FloatArray(maxLag + 1)

        for (lag in minLag..maxLag) {
            var acSum = 0f
            for (i in 0 until odf.size - lag) {
                acSum += odf[i] * odf[i + lag]
            }

            val acVal = acSum / (odf.size - lag)
            acVals[lag] = acVal

            val lagMs = lag * hopMs
            val logRatio = ln(lagMs.toFloat() / priorCenterMs) / ln(2f)
            val prior = exp(-0.5f * (logRatio / priorStdOctave).pow(2))

            scoreVals[lag] = acVal * prior
        }

        val bestLag = (minLag..maxLag).maxByOrNull { scoreVals[it] } ?: minLag
        val bestAc = acVals[bestLag]

        // Half-tempo check
        var finalLag = bestLag
        val halfLag = bestLag / 2
        if (halfLag >= minLag && bestAc > 1e-6f) {
            if (acVals[halfLag] / bestAc >= halfTempoRatio) {
                finalLag = halfLag
            }
        }

        // Double-tempo check
        val doubleLag = bestLag * 2
        if (doubleLag <= maxLag && bestAc > 1e-6f) {
            if (acVals[doubleLag] / bestAc >= doubleTempoRatio) {
                finalLag = doubleLag
            }
        }

        val bestBpm = 60_000L / (finalLag * hopMs)
        val confidence = minOf(1.0f, bestAc * 10)  // AC를 신뢰도로 변환

        return BpmV3Result(bestBpm.toFloat(), confidence, null)
    }

    /**
     * 섹션별로 독립적인 비트 추적 실행
     *
     * @param odf 전체 ODF
     * @param sectionBoundariesMs 섹션 경계 (ms)
     * @param sectionBpms 각 섹션의 BPM (ms → BPM)
     * @param hopMs 홉 간격
     * @param durationMs 전체 곡 길이
     * @return Pair<섹션별 비트 리스트, 상세 로그>
     */
    private fun dpBeatTrackerPerSection(
        odf: FloatArray,
        sectionBoundariesMs: List<Long>,
        sectionBpms: List<Pair<Long, Float>>,
        hopMs: Long,
        durationMs: Long
    ): Pair<List<TimedBeat>, String> {
        if (sectionBoundariesMs.isEmpty() || sectionBpms.isEmpty()) {
            return Pair(emptyList(), "no sections")
        }

        val allBeats = mutableListOf<TimedBeat>()
        val sectionLog = StringBuilder()
        val minSectionDurationMs = 500L  // 최소 섹션 길이: 500ms

        // 각 섹션별로 처리
        for (i in 0 until sectionBoundariesMs.size - 1) {
            val sectionStartMs = sectionBoundariesMs[i]
            val sectionEndMs = sectionBoundariesMs[i + 1]
            val sectionDurationMs = sectionEndMs - sectionStartMs

            // 이 섹션의 BPM 찾기
            val sectionBpm = sectionBpms.find { it.first == sectionStartMs }?.second ?: continue
            if (sectionBpm <= 0f) continue

            val beatMs = 60_000L / sectionBpm.toLong()
            val fpb = (beatMs / hopMs).toInt()

            // 섹션의 ODF 슬라이스
            val startFrame = (sectionStartMs / hopMs).toInt()
            val endFrame = (sectionEndMs / hopMs).toInt().coerceAtMost(odf.size)
            val sectionFrames = endFrame - startFrame

            // 섹션이 너무 짧거나 최소 비트 개수를 충족하지 못하면 스킵
            if (sectionFrames < fpb * 2 || sectionDurationMs < minSectionDurationMs) {
                sectionLog.append("section[$sectionStartMs-$sectionEndMs]=${sectionBpm.toInt()}BPM skip(frames=$sectionFrames<${fpb*2}); ")
                continue
            }

            val sectionOdf = FloatArray(sectionFrames) { idx ->
                if (startFrame + idx < odf.size) odf[startFrame + idx] else 0f
            }

            // 이 섹션에서 위상 추정
            val phaseMs = estimatePhaseFromOdf(sectionOdf, beatMs, hopMs)

            // 섹션 내에서 비트 추적
            val sectionDpTimes = dpBeatTracker(
                sectionOdf, beatMs, hopMs,
                sectionDurationMs, anchorMs = phaseMs
            )

            // 섹션 시작 시간을 기준으로 절대 시간 변환
            val sectionBeats = sectionDpTimes.map { it + sectionStartMs }
            allBeats.addAll(sectionBeats.map { TimedBeat(it, 1f) })

            // 섹션별 비트 간격 분석
            val sectionGaps = mutableListOf<Long>()
            for (i in 1 until sectionDpTimes.size) {
                val gap = sectionDpTimes[i] - sectionDpTimes[i - 1]
                sectionGaps.add(gap)
            }
            val avgGap = if (sectionGaps.isNotEmpty()) sectionGaps.average().toLong() else 0L
            val minGap = sectionGaps.minOrNull() ?: 0L
            val maxGap = sectionGaps.maxOrNull() ?: 0L

            sectionLog.append("section[$sectionStartMs-$sectionEndMs]=${sectionBpm.toInt()}BPM(beatMs=$beatMs,fpb=${beatMs/hopMs}) beats=${sectionDpTimes.size} gaps=[avg=${avgGap}ms,min=${minGap}ms,max=${maxGap}ms] phase=${phaseMs}ms; ")
        }

        // 섹션별 분석 로그 출력
        if (sectionLog.isNotEmpty()) {
            Log.d(TAG, "V3 SECTION_ANALYSIS: $sectionLog")
        }

        // 시간 순으로 정렬 및 중복 제거
        val finalBeats = allBeats
            .sortedBy { it.timeMs }
            .distinctBy { it.timeMs }

        return Pair(finalBeats, sectionLog.toString())
    }

    /**
     * PCM 데이터에서 직접 BPM 탐지 (V1 방식)
     *
     * @param monoSamples PCM 샘플 배열
     * @param sampleRate 샘플 레이트 (Hz)
     * @param params 파라미터
     * @param songTitle 곡 제목 (로깅용)
     * @return DetectResultV3
     */
    fun detectPcm(
        monoSamples: FloatArray,
        sampleRate: Int,
        params: Params = Params(),
        songTitle: String? = null,
        context: android.content.Context? = null
    ): DetectResultV3 {
        if (monoSamples.isEmpty() || sampleRate <= 0) {
            return DetectResultV3(
                emptyList(), 0L, 0f, null,
                "empty pcm", 0L, TimeSignature.FOUR_FOUR
            )
        }

        val perfPcmT0 = System.nanoTime()

        // PCM → Envelope 계산 (V3.1: 5-band 주파수 분해)
        val hopSamples = kotlin.math.max(1, (sampleRate * params.hopMs / 1000).toInt())
        val numFrames = monoSamples.size / hopSamples
        val outVeryLow = ArrayList<Float>(numFrames)
        val outLowMid = ArrayList<Float>(numFrames)
        val outLow = ArrayList<Float>(numFrames)
        val outMid = ArrayList<Float>(numFrames)
        val outHigh = ArrayList<Float>(numFrames)
        val outFull = ArrayList<Float>(numFrames)

        var veryLowZ = 0f
        var lowMidZ = 0f
        var lowZ = 0f
        var midLP1 = 0f
        var midLP2 = 0f
        var highLP1 = 0f
        var highLP2 = 0f
        var veryLowSumSq = 0f
        var lowMidSumSq = 0f
        var lowSumSq = 0f
        var midSumSq = 0f
        var highSumSq = 0f
        var fullSumSq = 0f
        var winPos = 0

        for (sample in monoSamples) {
            veryLowZ += VERYLOW_ALPHA * (sample - veryLowZ)
            lowMidZ += LOWMID_ALPHA * (sample - lowMidZ)
            lowZ += LOW_ALPHA * (sample - lowZ)
            midLP1 += MID_LP1_ALPHA * (sample - midLP1)
            midLP2 += MID_LP2_ALPHA * (sample - midLP2)
            highLP1 += HIGH_LP1_ALPHA * (sample - highLP1)
            highLP2 += HIGH_LP2_ALPHA * (sample - highLP2)

            val veryLowVal = kotlin.math.abs(veryLowZ)
            val lowMidVal = kotlin.math.abs(lowMidZ)
            val lowVal = kotlin.math.abs(lowZ)
            val midVal = kotlin.math.abs(midLP1 - midLP2)
            val highVal = kotlin.math.abs(highLP1 - highLP2)

            veryLowSumSq += veryLowVal * veryLowVal
            lowMidSumSq += lowMidVal * lowMidVal
            lowSumSq += lowVal * lowVal
            midSumSq += midVal * midVal
            highSumSq += highVal * highVal
            fullSumSq += sample * sample
            winPos++

            if (winPos >= hopSamples) {
                outVeryLow += kotlin.math.sqrt(veryLowSumSq / winPos)
                outLowMid += kotlin.math.sqrt(lowMidSumSq / winPos)
                outLow += kotlin.math.sqrt(lowSumSq / winPos)
                outMid += kotlin.math.sqrt(midSumSq / winPos)
                outHigh += kotlin.math.sqrt(highSumSq / winPos)
                outFull += kotlin.math.sqrt(fullSumSq / winPos)
                veryLowSumSq = 0f
                lowMidSumSq = 0f
                lowSumSq = 0f
                midSumSq = 0f
                highSumSq = 0f
                fullSumSq = 0f
                winPos = 0
            }
        }

        // Envelope 정규화
        fun normalizeEnv(src: List<Float>): List<Float> {
            val mx = src.maxOrNull() ?: 0f
            return if (mx > 1e-6f) src.map { (it / mx).coerceIn(0f, 1f) } else src
        }

        val perfPcmT1Envelope = System.nanoTime()

        // V3.1: 5-band 주파수 분석 사용
        val result = detectFiveBand(
            normalizeEnv(outVeryLow), normalizeEnv(outLowMid),
            normalizeEnv(outLow), normalizeEnv(outMid), normalizeEnv(outHigh),
            normalizeEnv(outFull),
            params, songTitle, emptyList(), context
        )

        val perfPcmT2Total = System.nanoTime()
        Log.d(
            PERF_TAG,
            "title=\"$songTitle\" detectPcm total=${"%.1f".format((perfPcmT2Total - perfPcmT0) / 1_000_000.0)}ms " +
                    "(envelope=${"%.1f".format((perfPcmT1Envelope - perfPcmT0) / 1_000_000.0)}ms, " +
                    "detectFiveBand=${"%.1f".format((perfPcmT2Total - perfPcmT1Envelope) / 1_000_000.0)}ms)"
        )
        return result
    }

    /**
     * V3.2: 5-band ODF 가중치 통합
     * 각 주파수 대역의 ODF를 가중치로 통합 → 최종 ODF 생성 → BPM 추정
     * detectPcm에서만 호출됨
     */
    private fun detectFiveBand(
        veryLowEnv: List<Float>,
        lowMidEnv: List<Float>,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        highEnv: List<Float>,
        fullEnv: List<Float>,
        params: Params = Params(),
        songTitle: String? = null,
        sectionBoundariesMs: List<Long> = emptyList(),
        context: android.content.Context? = null
    ): DetectResultV3 {
        // V3.5 주석: 비트 감지 실패의 근본 원인 분석
        // ════════════════════════════════════════════════════════════════════════════════
        // [실제 로그 데이터 - iKON 분석]
        //
        // V3.4까지의 문제:
        //   input:  BPM 추정 ✓ (67, 162, 117 BPM 등 섹션별 선택)
        //   output: beats = [] ❌ (빈 리스트 반환)
        //   result: "beat detect FAIL" → "empty frames" 에러
        //
        // 로그 예시:
        //   V3 Section[0ms-21100ms]:
        //     [0] lag=89(67BPM,ratio=1.00,PEAK,str=100%)
        //     [1] lag=90(66BPM,ratio=1.01,PEAK,str=95%)
        //     SELECTED: lag=89(67BPM) ✓
        //
        //   V3 Section[30500ms-45800ms]:
        //     SKIPPED (signal too weak, max absolute=0.0000)  ← V3.4 절대 강도 필터
        //
        //   V3 Section[45800ms-61200ms]:
        //     [0] lag=51(117BPM,ratio=0.68,PEAK,str=100%)
        //     SELECTED: lag=51(117BPM) ✓
        //
        //   결과: 23개 유효 섹션 → 중앙값 78 BPM 선택
        //   그런데 beats = [] ← V3.5에서 수정됨
        //
        // V3.5 수정 사항:
        //   1. dpBeatTracker(integratedOdf, finalBpm) 호출
        //      → ODF 데이터 + BPM 으로 실제 비트 위치 계산
        //      → output: [0ms, 78ms, 156ms, 234ms, ...]
        //
        //   2. estimatePhaseFromOdf() 로 위상 앵커 계산
        //      → DP 수렴 문제 방지
        //
        //   3. 세그먼트 폴백
        //      → dpBeatTracker 실패시 섹션 단위로 비트 생성
        //
        //   4. Time Signature + Downbeat 감지
        //      → 완전한 비트 정보 반환
        // ════════════════════════════════════════════════════════════════════════════════

        val perfT0 = System.nanoTime()

        // List<Float> → FloatArray 변환
        val minSize = minOf(veryLowEnv.size, lowMidEnv.size, lowEnv.size, midEnv.size, highEnv.size, fullEnv.size)

        val veryLowArr: FloatArray = when (veryLowEnv) {
            is FloatArray -> veryLowEnv.copyOf(minSize)
            else -> veryLowEnv.take(minSize).toFloatArray()
        }
        val lowMidArr: FloatArray = when (lowMidEnv) {
            is FloatArray -> lowMidEnv.copyOf(minSize)
            else -> lowMidEnv.take(minSize).toFloatArray()
        }
        val lowArr: FloatArray = when (lowEnv) {
            is FloatArray -> lowEnv.copyOf(minSize)
            else -> lowEnv.take(minSize).toFloatArray()
        }
        val midArr: FloatArray = when (midEnv) {
            is FloatArray -> midEnv.copyOf(minSize)
            else -> midEnv.take(minSize).toFloatArray()
        }
        val highArr: FloatArray = when (highEnv) {
            is FloatArray -> highEnv.copyOf(minSize)
            else -> highEnv.take(minSize).toFloatArray()
        }

        // 각 대역별 ODF 계산
        val odfVeryLow = computeMultiBandFluxOdf(veryLowArr, veryLowArr, veryLowArr, params)
        val odfLowMid = computeMultiBandFluxOdf(lowMidArr, lowMidArr, lowMidArr, params)
        val odfLow = computeMultiBandFluxOdf(lowArr, lowArr, lowArr, params)
        val odfMid = computeMultiBandFluxOdf(midArr, midArr, midArr, params)
        val odfHigh = computeMultiBandFluxOdf(highArr, highArr, highArr, params)

        // 5-band ODF 가중치 통합
        val weightVeryLow = 0.08f
        val weightLowMid = 0.12f
        val weightLow = 0.20f
        val weightMid = 0.35f
        val weightHigh = 0.25f
        // 합계: 1.0

        val integratedOdf = FloatArray(odfVeryLow.size) { i ->
            (odfVeryLow[i] * weightVeryLow +
             odfLowMid[i] * weightLowMid +
             odfLow[i] * weightLow +
             odfMid[i] * weightMid +
             odfHigh[i] * weightHigh)
        }

        val perfT1Odf = System.nanoTime()

        // 통합 ODF로 BPM 추정
        val (bestBpm, confidence, tempogram, peakCandidates) = estimateBpmV3(
            integratedOdf,
            hopMs = params.hopMs,
            minBeatMs = params.minBeatMs,
            maxBeatMs = params.maxBeatMs,
            useTempogram = params.useTempogram,
            halfTempoRatio = params.halfTempoRatio,
            doubleTempoRatio = params.doubleTempoRatio
        )

        val perfT2Bpm = System.nanoTime()

        // 섹션별 BPM 분석 (Tempogram 기반)
        var collectedSectionBpms: List<Pair<Long, Float>> = emptyList()
        if (tempogram != null && params.useTempogram) {
            val dynamicSections = detectDynamicSections(
                tempogram,
                hopMs = params.hopMs,
                minBeatMs = params.minBeatMs,
                odfSize = integratedOdf.size,
                externalSectionBoundariesMs = sectionBoundariesMs,
                changeThresholdPercent = 10f
            )

            if (dynamicSections.size > 1) {
                collectedSectionBpms = detectSectionBpms(
                    tempogram,
                    hopMs = params.hopMs,
                    minBeatMs = params.minBeatMs,
                    odfSize = integratedOdf.size,
                    sectionBoundariesMs = dynamicSections
                )
            }
        }

        val perfT3Sections = System.nanoTime()

        // Beat 추정 (Method A: Tempogram 기반)
        val methodABeatMs = beatMsFromBpm(bestBpm)

        // Method B: 섹션 중앙값 기반 BPM 보정 (V3.7)
        val methodBBpm: Float
        val finalBeatMs: Long
        val octaveCorrectionReason: String
        val methodSelectionReason: String

        if (collectedSectionBpms.isNotEmpty()) {
            // Step 1: 기본 중앙값 계산
            val baseMedianBpm = calculateMedianBpmFromSections(collectedSectionBpms)

            // Step 2: V3.7.4 - Global AC와 비교하여 과도한 보정 방지
            // Global AC가 이미 합리적인 범위(80-180)면 보정하지 않음
            val octaveResult = detectAndCorrectOctaveError(baseMedianBpm, bestBpm, collectedSectionBpms)
            methodBBpm = octaveResult.bpm
            octaveCorrectionReason = octaveResult.reason

            // V4.3: methodB 후보 자체의 임시 박자 판정 (Entrance 같은 6/8곡 보호용)
            // V4.1 시도: Global(Method A) 주기로 판정 → 실패. Entrance는 Global(105.3ms) 기준으론
            // SIX_EIGHT가 안 나오고, 오히려 methodB(131.9, 틀린 값) 기준으로 봤을 때만 6/8 패턴이
            // 나타남 — methodB가 실제로는 "곡의 3연음/복합박 서브비트"를 메인 비트로 착각한 것으로
            // 추정. 그래서 methodB의 주기로 판정해서, 6/8로 보이면 methodB를 불신하고 Global 유지.
            // detectTimeSignature는 finalBeatMs 확정 후 다시 정식으로 호출되므로 순환 의존 아님.
            val methodBBeatMsForSigCheck = beatMsFromBpm(methodBBpm)
            val provisionalTimeSig = detectTimeSignature(integratedOdf, methodBBeatMsForSigCheck, params.hopMs)
            val isCompoundMeterCandidate = provisionalTimeSig == TimeSignature.SIX_EIGHT

            // Step 3: Method A (Global AC, bestBpm) vs Method B (Section Median, methodBBpm) 선택
            // 미스매치 곡들의 경우 Global BPM이 정확할 수 있으므로 비교 후 결정
            val selectionResult = selectBetweenMethodsAandB(bestBpm, methodBBpm, methodABeatMs, isCompoundMeterCandidate)
            finalBeatMs = selectionResult.beatMs
            methodSelectionReason = selectionResult.reason
        } else {
            methodBBpm = 0f
            finalBeatMs = methodABeatMs
            octaveCorrectionReason = "NO_SECTIONS: no section BPMs collected"
            methodSelectionReason = "NO_SECTIONS: using Global AC directly"
        }

        val perfT4Selection = System.nanoTime()

        // V3.5 FIX: Generate actual beat positions using dpBeatTracker
        // ════════════════════════════════════════════════════════════════════════════════
        // [V3.5 비트 추적 분석]
        //
        // 문제 (V3.4까지):
        //   - BPM 추정만 하고 실제 비트 위치는 생성하지 않음
        //   - 결과: beats = [] → "beat detect FAIL"
        //
        // 해결책 (V3.5):
        //   1. dpBeatTracker(integratedOdf, finalBpm) 호출
        //      → ODF의 각 위치에서 finalBpm과 일치하는 비트 찾음
        //      → DP 알고리즘으로 최적 경로 선택
        //
        //   2. 위상 앵커 추정
        //      → 초기 프레임에서 가장 강한 신호 구간 찾음
        //      → DP가 약한 신호에 수렴하는 것을 방지
        //
        //   3. 실패시 세그먼트 폴백
        //      → 오디오를 20초 단위로 나누어 재시도
        //      → 국소적 최적점을 찾는 방식
        //
        // 실제 로그 예시 (성공):
        //   V3 Beat tracking: dpTimes=237 frames from finalBpm=78 ms
        //   → 78ms 간격으로 237개의 비트 감지
        //   → [0ms, 78ms, 156ms, 234ms, ...]
        //
        // 실제 로그 예시 (폴백):
        //   V3 dpBeatTracker returned empty, trying fallback segment beats
        //   → 세그먼트별로 재시도
        //   → 여러 세그먼트에서 감지한 비트를 합침
        //
        // 중요: dpBeatTracker 성공 조건
        //   - integratedOdf가 비어있지 않아야 함
        //   - finalBeatMs이 양수여야 함 (0보다 커야 함)
        //   - ODF와 BeatMs이 일관성 있어야 함
        // ════════════════════════════════════════════════════════════════════════════════

        val durationMs = integratedOdf.size.toLong() * params.hopMs
        val phaseMs = if (finalBeatMs > 0L) estimatePhaseFromOdf(integratedOdf, finalBeatMs, params.hopMs) else 0L

        val beats: List<TimedBeat> = if (finalBeatMs > 0L && integratedOdf.isNotEmpty()) {
            val dpTimes = dpBeatTracker(integratedOdf, finalBeatMs, params.hopMs, durationMs, anchorMs = phaseMs)

            if (dpTimes.isNotEmpty()) {
                dpTimes.map { TimedBeat(it, 0.8f) }
            } else {
                Log.w(TAG, "V3 dpBeatTracker returned empty, trying fallback segment beats")
                // Fallback: generate segment-based beats if DP tracking fails
                val segFrames = maxOf(1, (params.segmentMs / params.hopMs).toInt())
                val segmentBeats = ArrayList<TimedBeat>()

                for (segIdx in 0 until (integratedOdf.size + segFrames - 1) / segFrames) {
                    val s = segIdx * segFrames
                    val e = minOf(integratedOdf.size, s + segFrames)
                    if (e - s < 8) continue

                    val segOdf = integratedOdf.sliceArray(s until e)
                    val segPhase = estimatePhaseFromOdf(segOdf, finalBeatMs, params.hopMs)
                    val segTimes = dpBeatTracker(segOdf, finalBeatMs, params.hopMs, (e - s).toLong() * params.hopMs, anchorMs = segPhase)
                    val offset = s.toLong() * params.hopMs
                    segTimes.forEach { segmentBeats.add(TimedBeat(offset + it, 0.5f)) }
                }
                segmentBeats.sortedBy { it.timeMs }
            }
        } else {
            emptyList()
        }

        val perfT5Beats = System.nanoTime()

        // 시간 서명 감지
        val timeSignature = if (beats.isNotEmpty() && integratedOdf.isNotEmpty()) {
            detectTimeSignature(integratedOdf, finalBeatMs, params.hopMs)
        } else {
            TimeSignature.FOUR_FOUR
        }

        // Downbeat 감지
        val downbeatMs = if (beats.isNotEmpty() && finalBeatMs > 0L) {
            val beatTimesMs = beats.map { it.timeMs }
            detectDownbeatEnhanced(beatTimesMs, integratedOdf, finalBeatMs, timeSignature.beatsPerBar, params.hopMs)
        } else {
            0L
        }

        val downbeatOffsetMs = if (beats.isNotEmpty()) {
            (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)
        } else {
            0L
        }

        val perfT6TimeSig = System.nanoTime()

        val finalBpmForLog = bpmFromBeatMs(finalBeatMs)
        Log.d(TAG, "V3.5 RESULT: beats=${beats.size} beatMs=$finalBeatMs BPM=${finalBpmForLog.toInt()} timeSig=${timeSignature.type} downbeatMs=$downbeatMs")

        fun perfMs(from: Long, to: Long) = "%.1f".format((to - from) / 1_000_000.0)
        Log.d(
            PERF_TAG,
            "title=\"$songTitle\" total=${perfMs(perfT0, perfT6TimeSig)}ms " +
                    "[odf=${perfMs(perfT0, perfT1Odf)} " +
                    "bpmEstimate=${perfMs(perfT1Odf, perfT2Bpm)} " +
                    "sections=${perfMs(perfT2Bpm, perfT3Sections)} " +
                    "methodSelection=${perfMs(perfT3Sections, perfT4Selection)} " +
                    "beatTracking=${perfMs(perfT4Selection, perfT5Beats)} " +
                    "timeSigDownbeat=${perfMs(perfT5Beats, perfT6TimeSig)}]"
        )

        // V3.5 섹션별 BPM 분석 데이터 저장 (JSON) - 정확도 진단용, 평상시 OFF
        if (ENABLE_JSON_EXPORT && context != null && songTitle != null) {
            try {
                val sectionBpmList = collectedSectionBpms.joinToString(",") { (ms, bpm) ->
                    "{\"timeMs\":$ms,\"bpm\":${bpm.toInt()}}"
                }

                // V3.6 DEBUG: bestBpm은 BPM 단위, 따라서:
                // - methodABeatMs = 비트 간격 (ms) = beatMsFromBpm(bestBpm)
                // - bestBpm = BPM 값 (그대로)
                val globalBpmMs = methodABeatMs
                val globalBpmValue = bestBpm  // BPM 값 그자체

                // V3.8: Tempogram 분석 데이터 수집 (Tempogram 개선용)
                val tempogramInsightsJson = if (tempogram != null && collectedSectionBpms.isNotEmpty()) {
                    try {
                        val insights = analyzeTempogramForInsights(tempogram, params.hopMs, finalBeatMs, collectedSectionBpms)
                        ",\"tempogram_insights\":{" +
                                "\"bestBpm\":${String.format("%.1f", insights.bestBpm)}," +
                                "\"confidence\":${String.format("%.1f", insights.confidence)}," +
                                "\"topPeaks\":[${insights.topPeaks.take(5).joinToString(",") { peak ->
                                    "{\"bpm\":${String.format("%.1f", peak.bpm)}," +
                                    "\"scorePercent\":${peak.scorePercent}," +
                                    "\"peakType\":\"${peak.peakType}\"}"
                                }}]," +
                                "\"secondaryPeakRatio\":${String.format("%.2f", insights.secondaryPeakRatio)}" +
                                "}"
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }

                // V4.0: 다중 피크 평가 후보 목록 JSON (Stray Kids/TOMBOY 등 진단용)
                val peakEvaluationJson = if (peakCandidates.isNotEmpty()) {
                    try {
                        ",\"v4_peak_evaluation\":[${peakCandidates.joinToString(",") { cand ->
                            "{\"bpm\":${String.format("%.1f", cand.bpm)}," +
                            "\"acStrength\":${String.format("%.3f", cand.acStrength)}," +
                            "\"prior\":${String.format("%.3f", cand.prior)}," +
                            "\"temporalConsistency\":${String.format("%.3f", cand.temporalConsistency)}," +
                            "\"compositeScore\":${String.format("%.3f", cand.compositeScore)}," +
                            "\"selected\":${cand.selected}}"
                        }}]"
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }

                val analysisJson = "{" +
                        "\"title\":\"$songTitle\"," +
                        "\"v3_5_analysis\":{" +
                        "\"beatGenerationMethod\":\"dpBeatTracker\"," +
                        "\"beatsGenerated\":${beats.size}," +
                        "\"finalBeatMs\":$finalBeatMs," +
                        "\"finalBpm\":${String.format("%.1f", finalBpmForLog)}," +
                        "\"confidence\":${String.format("%.1f", confidence * 100)}," +
                        "\"timeSignature\":\"${timeSignature.type}\"," +
                        "\"downbeatMs\":$downbeatMs" +
                        "}," +
                        "\"v3_6_debug\":{" +
                        "\"globalBpmMs\":$globalBpmMs," +
                        "\"globalBpm\":${String.format("%.1f", globalBpmValue)}," +
                        "\"methodBBpm\":${if (methodBBpm > 0) String.format("%.1f", methodBBpm) else "0.0"}," +
                        "\"medianBpmBeforeAdjust\":${if (collectedSectionBpms.isNotEmpty()) String.format("%.1f", calculateMedianBpmFromSections(collectedSectionBpms)) else "0.0"}," +
                        "\"octaveCorrectionReason\":\"${octaveCorrectionReason.replace("\"", "'")}\"," +
                        "\"methodSelectionReason\":\"${methodSelectionReason.replace("\"", "'")}\"" +
                        "}," +
                        "\"sectionAnalysis\":{" +
                        "\"totalSections\":${collectedSectionBpms.size}," +
                        "\"medianBpm\":${if (collectedSectionBpms.isNotEmpty()) calculateMedianBpmFromSections(collectedSectionBpms).toInt() else 0}," +
                        "\"sectionBpms\":[$sectionBpmList]" +
                        "}," +
                        "\"durationMs\":${durationMs}" +
                        tempogramInsightsJson +
                        peakEvaluationJson +
                        "}"

                val filesDir = context.filesDir
                val analysisDir = java.io.File(filesDir, "v3_analysis")
                if (!analysisDir.exists()) {
                    analysisDir.mkdirs()
                }
                val file = java.io.File(analysisDir, "bpm_results.jsonl")
                file.appendText(analysisJson + "\n")
                Log.d(TAG, "V3.5 SECTION_BPM_SAVED: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "V3.5 SAVE_FAILED: ${e.message}")
            }
        }

        // 결과 생성
        val result = DetectResultV3(
            beats = beats,
            beatMs = finalBeatMs,
            confidence = confidence,
            source = null,
            reason = "V3.5: 5-band ODF + beat tracking",
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature = timeSignature,
            tempogram = tempogram
        )

        return result
    }

    /**
     * V1의 detect()와 호환되는 인터페이스
     * DetectResultV3 반환
     */
    fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        params: Params = Params(),
        songTitle: String? = null,
        sectionBoundariesMs: List<Long> = emptyList(),
        context: android.content.Context? = null
    ): DetectResultV3 {
        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            return DetectResultV3(
                emptyList(), 0L, 0f, null,
                "empty env", 0L, TimeSignature.FOUR_FOUR
            )
        }

        val minSize = minOf(lowEnv.size, midEnv.size, fullEnv.size)
        val low: FloatArray = when (lowEnv) {
            is FloatArray -> lowEnv.copyOf(minSize)
            else -> lowEnv.take(minSize).toFloatArray()
        }
        val mid: FloatArray = when (midEnv) {
            is FloatArray -> midEnv.copyOf(minSize)
            else -> midEnv.take(minSize).toFloatArray()
        }
        val full: FloatArray = when (fullEnv) {
            is FloatArray -> fullEnv.copyOf(minSize)
            else -> fullEnv.take(minSize).toFloatArray()
        }

        // ODF 계산 (V1과 동일)
        val globalOdf = computeMultiBandFluxOdf(low, mid, full, params)

        // V3 BPM 탐지
        val (bestBpm, confidence, tempogram) = estimateBpmV3(
            globalOdf,
            hopMs = params.hopMs,
            minBeatMs = params.minBeatMs,
            maxBeatMs = params.maxBeatMs,
            useTempogram = params.useTempogram,
            halfTempoRatio = params.halfTempoRatio,
            doubleTempoRatio = params.doubleTempoRatio
        )

        // ODF 통계 (분석용)
        val odfMax = globalOdf.maxOrNull() ?: 0f
        val odfMean = if (globalOdf.isNotEmpty()) globalOdf.average().toFloat() else 0f
        val odfStd = if (globalOdf.isNotEmpty()) {
            val variance = globalOdf.map { (it - odfMean) * (it - odfMean) }.average()
            kotlin.math.sqrt(variance.toDouble()).toFloat()
        } else 0f
        val odfStats = mapOf(
            "size" to globalOdf.size,
            "max" to String.format("%.6f", odfMax),
            "mean" to String.format("%.6f", odfMean),
            "std" to String.format("%.6f", odfStd)
        )
        Log.d(
            TAG,
            "V3 ODF_STATS: title=\"$songTitle\" size=${globalOdf.size} max=${String.format("%.6f", odfMax)} mean=${String.format("%.6f", odfMean)} std=${String.format("%.6f", odfStd)}"
        )

        if (bestBpm <= 0f) {
            Log.w(TAG, "V3 BPM탐지 실패! 신호 확인 필요 (ODF 약함)")
        }

        // 분석 데이터 수집용 변수 초기화
        var methodABpm = bestBpm.toLong()
        var methodBBpm = 0L
        val acPeaksList = mutableListOf<Map<String, Any>>()
        val sectionDetailsList = mutableListOf<Map<String, Any>>()
        var dpTrackingResults = mapOf<String, Any>()

        // AC peaks 계산 (분석용)
        val minLag = maxOf(1, (params.minBeatMs / params.hopMs).toInt())
        val maxLag = maxOf(minLag + 1, (params.maxBeatMs / params.hopMs).toInt())
        if (globalOdf.size > maxLag + 2) {
            // 1단계: 원시 AC값 계산
            val acVals = FloatArray(maxLag + 1)
            for (lag in minLag..maxLag) {
                var acSum = 0f
                for (i in 0 until globalOdf.size - lag) {
                    acSum += globalOdf[i] * globalOdf[i + lag]
                }
                acVals[lag] = acSum / (globalOdf.size - lag)
            }

            // 2단계: Tempogram AC 개선 (스펙트럼 농도 기반 가중치 + 하모닉 필터링)
            val improvedAcVals = improveAcValuesWithSpectralWeighting(
                globalOdf,
                acVals,
                minLag
            )

            // 로깅: AC 개선 효과 분석
            val topBefore = acVals.withIndex()
                .filter { it.index >= minLag && it.index <= maxLag }
                .maxByOrNull { it.value }
            val topAfter = improvedAcVals.withIndex()
                .filter { it.index >= minLag && it.index <= maxLag }
                .maxByOrNull { it.value }

            if (topBefore != null && topAfter != null) {
                val topBpmBefore = 60_000L / (topBefore.index * params.hopMs)
                val topBpmAfter = 60_000L / (topAfter.index * params.hopMs)
                if (topBpmBefore != topBpmAfter) {
                    Log.d(
                        TAG,
                        "V3 AC_IMPROVED: top AC before=${topBefore.index}lag(${topBpmBefore}BPM) after=${topAfter.index}lag(${topBpmAfter}BPM)"
                    )
                }
            }

            // 3단계: Top 10 peaks 추출 (AC + Log-Normal Prior)
            data class PeakCandidate(val lag: Int, val bpm: Long, val ac: Float, val prior: Float, val score: Float)

            val peakCandidates = (minLag..maxLag)
                .map { lag ->
                    val bpmLong = 60_000L / (lag * params.hopMs)
                    val bpm = bpmLong.toFloat()
                    val acVal = improvedAcVals[lag]
                    val prior = calculateLogNormalPrior(bpm)
                    val score = acVal * prior  // AC × Prior = 최종 점수
                    PeakCandidate(lag, bpmLong, acVal, prior, score)
                }
                .sortedByDescending { it.score }

            val peaksByScore = peakCandidates.take(10)

            for (peak in peaksByScore) {
                acPeaksList.add(mapOf(
                    "lag" to peak.lag,
                    "bpm" to peak.bpm.toInt(),
                    "ac" to String.format("%.6f", peak.ac),
                    "ac_raw" to String.format("%.6f", acVals[peak.lag]),
                    "ratio" to String.format("%.2f", peak.bpm.toFloat() / bestBpm)
                ))
            }

            // Log-Normal Prior 적용 결과
            if (peakCandidates.isNotEmpty()) {
                val topWithoutPrior = (minLag..maxLag)
                    .maxByOrNull { improvedAcVals[it] }
                if (topWithoutPrior != null) {
                    val topBpmWithoutPrior = 60_000L / (topWithoutPrior * params.hopMs)
                    val topWithPrior = peakCandidates[0]

                    if (topBpmWithoutPrior != topWithPrior.bpm) {
                        Log.d(
                            TAG,
                            "V3 PRIOR_APPLIED: AC only=${topBpmWithoutPrior}BPM prior=${topWithPrior.bpm}BPM (prior weight=%.3f)".format(topWithPrior.prior)
                        )
                    }
                }
            }
        }

        Log.d(
            TAG,
            "V3 detect: title=\"$songTitle\" BPM=$bestBpm Confidence=${confidence * 100}%"
        )

        // DP를 사용한 비트 추적
        val durationMs = minSize * params.hopMs
        val beatMs = if (bestBpm > 0f) (60_000L / bestBpm.toLong()) else 0L
        val fpb = (beatMs / params.hopMs).toInt()

        val beats: List<TimedBeat>
        var reason: String
        var sectionInfo = ""
        var collectedSectionBpms: List<Pair<Long, Float>> = emptyList()  // Method B용

        // 섹션별 BPM 분석 (Tempogram 기반 + 동적 감지)
        if (tempogram != null && params.useTempogram) {
            // 1단계: 동적 섹션 경계 생성 (BPM 변화 + 외부 경계)
            val dynamicSections = detectDynamicSections(
                tempogram,
                hopMs = params.hopMs,
                minBeatMs = params.minBeatMs,
                odfSize = globalOdf.size,
                externalSectionBoundariesMs = sectionBoundariesMs,
                changeThresholdPercent = 10f
            )

            // 2단계: 동적 경계를 기반으로 섹션별 BPM 계산
            if (dynamicSections.size > 1) {
                val sectionBpms = detectSectionBpms(
                    tempogram,
                    hopMs = params.hopMs,
                    minBeatMs = params.minBeatMs,
                    odfSize = globalOdf.size,
                    sectionBoundariesMs = dynamicSections
                )

                // Method B용: 섹션 BPM 저장
                collectedSectionBpms = sectionBpms

                // 섹션 상세 정보 저장 (분석용)
                for (i in 0 until dynamicSections.size - 1) {
                    val sectionStart = dynamicSections[i]
                    val sectionEnd = dynamicSections[i + 1]
                    val sectionBpm = sectionBpms.find { it.first == sectionStart }?.second ?: 0f
                    val expectedBeats = ((sectionEnd - sectionStart) / (60_000L / sectionBpm.toLong())).toInt()

                    sectionDetailsList.add(mapOf(
                        "index" to i,
                        "startMs" to sectionStart,
                        "endMs" to sectionEnd,
                        "durationMs" to (sectionEnd - sectionStart),
                        "bpm" to sectionBpm.toInt(),
                        "expectedBeats" to expectedBeats
                    ))
                }

                // 3단계: 섹션별 비트 추적
                if (sectionBpms.isNotEmpty() && sectionBpms.size > 1) {
                    val (sectionBeats, sectionLog) = dpBeatTrackerPerSection(
                        globalOdf,
                        dynamicSections,
                        sectionBpms,
                        params.hopMs,
                        durationMs
                    )

                    if (sectionBeats.isNotEmpty()) {
                        beats = sectionBeats
                        reason = "dp_per_section"
                        sectionInfo = sectionLog

                        val sectionBpmInfo = sectionBpms.joinToString(", ") { (ms, bpm) ->
                            "${ms}ms: ${bpm.toInt()} BPM"
                        }
                        Log.d(TAG, "V3 DynamicSectionBPMs: $sectionBpmInfo")
                        Log.d(TAG, "V3 SectionBeats: $sectionInfo")
                    } else {
                        // 섹션별 추적 실패 → 섹션 중앙값으로 폴백 시도
                        Log.w(TAG, "V3 SectionBeats failed → trying median BPM from sections")

                        // 유효한 섹션 BPM만 필터링 (sanity check: 30-200 BPM)
                        val validSectionBpms = sectionBpms.filter { (_, bpm) ->
                            bpm >= 30f && bpm <= 200f
                        }

                        // 섹션 중앙값 계산
                        val fallbackBpmToTry = if (validSectionBpms.isNotEmpty()) {
                            val medianSectionBpm = calculateMedianBpmFromSections(validSectionBpms)
                            Log.d(TAG, "V3 SectionFallback: using median=${medianSectionBpm.toInt()}BPM from ${validSectionBpms.size} valid sections")
                            medianSectionBpm
                        } else {
                            Log.d(TAG, "V3 SectionFallback: no valid sections, using bestBpm=${bestBpm.toInt()}")
                            bestBpm
                        }

                        // 폴백 BPM으로 재시도
                        val fallbackBeatMs = if (fallbackBpmToTry > 0f) {
                            (60_000L / fallbackBpmToTry.toLong())
                        } else {
                            beatMs
                        }

                        val phaseMs = estimatePhaseFromOdf(globalOdf, fallbackBeatMs, params.hopMs)
                        val dpTimes = dpBeatTracker(
                            globalOdf, fallbackBeatMs, params.hopMs,
                            durationMs, anchorMs = phaseMs
                        )
                        val expectedBeats = maxOf(1, (durationMs / fallbackBeatMs).toInt())
                        val dpOk = dpTimes.size >= maxOf(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

                        beats = if (dpOk) {
                            dpTimes.map { TimedBeat(it, 1f) }
                        } else {
                            Log.w(TAG, "V3 DP insufficient (${dpTimes.size}/$expectedBeats) → final fallback")
                            fallbackSegmentBeats(
                                low, mid, full, params, fallbackBpmToTry.toLong(), durationMs
                            ).map { TimedBeat(it.timeMs, it.confidence) }
                        }
                        reason = if (beats.isNotEmpty()) "dp+section_fallback" else "failed"
                    }
                } else {
                    // 섹션별 BPM 계산 실패 → 전체 BPM 사용
                    Log.d(TAG, "V3 SectionBPMs insufficient → using global BPM")
                    val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
                    val dpTimes = dpBeatTracker(
                        globalOdf, beatMs, params.hopMs,
                        durationMs, anchorMs = phaseMs
                    )
                    val expectedBeats = maxOf(1, (durationMs / beatMs).toInt())
                    val dpOk = dpTimes.size >= maxOf(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

                    beats = if (dpOk) {
                        dpTimes.map { TimedBeat(it, 1f) }
                    } else {
                        Log.w(TAG, "V3 DP insufficient (${dpTimes.size}/$expectedBeats) → fallback")
                        fallbackSegmentBeats(
                            low, mid, full, params, bestBpm.toLong(), durationMs
                        ).map { TimedBeat(it.timeMs, it.confidence) }
                    }
                    reason = if (beats.isNotEmpty()) "dp_global_only" else "failed"
                }
            } else {
                // 동적 섹션 없음 → 전체 BPM 사용
                val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
                val dpTimes = dpBeatTracker(
                    globalOdf, beatMs, params.hopMs,
                    durationMs, anchorMs = phaseMs
                )
                val expectedBeats = maxOf(1, (durationMs / beatMs).toInt())
                val dpOk = dpTimes.size >= maxOf(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

                beats = if (dpOk) {
                    dpTimes.map { TimedBeat(it, 1f) }
                } else {
                    Log.w(TAG, "V3 DP insufficient (${dpTimes.size}/$expectedBeats) → fallback")
                    fallbackSegmentBeats(
                        low, mid, full, params, bestBpm.toLong(), durationMs
                    ).map { TimedBeat(it.timeMs, it.confidence) }
                }
                reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
            }
        } else {
            // Tempogram 미사용 → 기존 전체 BPM 방식
            val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
            val dpTimes = dpBeatTracker(
                globalOdf, beatMs, params.hopMs,
                durationMs, anchorMs = phaseMs
            )
            val expectedBeats = maxOf(1, (durationMs / beatMs).toInt())
            val dpOk = dpTimes.size >= maxOf(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

            if (dpOk) {
                beats = dpTimes.map { TimedBeat(it, 1f) }
                reason = "dp"
            } else {
                Log.w(TAG, "V3 DP insufficient (${dpTimes.size}/$expectedBeats) → fallback")
                beats = fallbackSegmentBeats(
                    low, mid, full, params, bestBpm.toLong(), durationMs
                ).map { TimedBeat(it.timeMs, it.confidence) }
                reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
            }
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V3 detect FAIL")
            return DetectResultV3(
                emptyList(), bestBpm.toLong(), confidence, null,
                "all failed", 0L, TimeSignature.FOUR_FOUR
            )
        }

        // 상세 분석 로그
        val beatTimesMs = beats.map { it.timeMs }
        val beatGaps = mutableListOf<Long>()
        for (i in 1 until beatTimesMs.size) {
            beatGaps.add(beatTimesMs[i] - beatTimesMs[i - 1])
        }
        val avgGap = if (beatGaps.isNotEmpty()) beatGaps.average().toLong() else 0L
        val minGap = beatGaps.minOrNull() ?: 0L
        val maxGap = beatGaps.maxOrNull() ?: 0L

        // 실제 비트 간격으로부터 추정 BPM (검증용)
        val detectedBpmFromBeats = if (avgGap > 0) {
            60_000L / avgGap
        } else {
            0L
        }

        Log.d(
            TAG,
            "V3 BEAT_ANALYSIS: title=\"$songTitle\" BPM=$bestBpm beatMs=$beatMs fpb=$fpb " +
            "beats=${beats.size} gaps=[avg=${avgGap}ms, min=${minGap}ms, max=${maxGap}ms] " +
            "detectedBpm=$detectedBpmFromBeats reason=$reason"
        )

        // V3 비트 타임라인 로그 (F-measure 계산용)
        val beatTimestamps = beats.map { it.timeMs }
        Log.d(
            TAG,
            "V3 BEAT_TIMESTAMPS: title=\"$songTitle\" beats=[${beatTimestamps.take(20).joinToString(",")}${if(beatTimestamps.size > 20) ",...(${beatTimestamps.size} total)" else ""}]"
        )

        // ODF 상세 통계
        val odfStatsDetail = StringBuilder()
        val odfValues = globalOdf.filter { it > 0f }.toList()
        if (odfValues.isNotEmpty()) {
            val odfMean = odfValues.average()
            val odfStd = kotlin.math.sqrt(odfValues.map { (it - odfMean).pow(2) }.average())
            val odfMedian = odfValues.sorted()[odfValues.size / 2]
            odfStatsDetail.append("max=${String.format("%.6f", odfValues.maxOrNull() ?: 0f)} ")
            odfStatsDetail.append("mean=${String.format("%.6f", odfMean)} ")
            odfStatsDetail.append("median=${String.format("%.6f", odfMedian)} ")
            odfStatsDetail.append("std=${String.format("%.6f", odfStd)} ")
            odfStatsDetail.append("count=${odfValues.size}")
        }
        Log.d(TAG, "V3 ODF_STATS_DETAIL: title=\"$songTitle\" $odfStatsDetail")

        // DP 디버그: beatMs 전달 확인
        Log.d(
            TAG,
            "V3 DP_DEBUG: title=\"$songTitle\" beatMs=$beatMs (60000/$bestBpm) hopMs=${params.hopMs} " +
            "fpb=$fpb odfSize=${globalOdf.size} durationMs=$durationMs"
        )

        // === Method B: 동적 섹션 BPM의 중앙값 사용 ===
        val medianSectionBpm = if (collectedSectionBpms.isNotEmpty()) {
            calculateMedianBpmFromSections(collectedSectionBpms)
        } else {
            0f
        }

        // === Phase 1: 옥타브 에러 검출 (Method A vs Method B 비율 분석) ===
        // methodABpm이 아직 bestBpm일 때, AC peaks 기반 BPM과 섹션 기반 BPM의 비율을 확인
        var octaveErrorDetected = false
        var octaveErrorReason = ""

        // methodABpm을 AC peaks에서 다시 계산 (정확한 Method A 값 확보)
        // 이전 acPeaksList에서 가장 신뢰도 높은 피크를 methodA로 사용
        val methodABpmFromPeaks = if (acPeaksList.isNotEmpty()) {
            (acPeaksList[0]["bpm"] as? Number)?.toLong() ?: methodABpm
        } else {
            methodABpm
        }

        if (medianSectionBpm > 0f && collectedSectionBpms.size >= 2 && methodABpmFromPeaks > 0L) {
            val ratio = methodABpmFromPeaks.toFloat() / medianSectionBpm
            val ratioMod1 = kotlin.math.abs(ratio - 1.0f)
            val ratioMod2 = kotlin.math.abs(ratio - 2.0f)
            val ratioMod05 = kotlin.math.abs(ratio - 0.5f)

            // 2x 옥타브 에러 검출 (비율 ≈ 2.0 ± 0.15)
            if (ratioMod2 < 0.15f) {
                octaveErrorDetected = true
                octaveErrorReason = "2x_octave"
                Log.d(
                    TAG,
                    "V3 OCTAVE_DETECTED: 2x ratio=${String.format("%.3f", ratio)} " +
                    "methodA=${methodABpmFromPeaks} methodB=${medianSectionBpm.toLong()} → using methodA"
                )
            }
            // 0.5x 옥타브 에러 검출 (비율 ≈ 0.5 ± 0.15)
            else if (ratioMod05 < 0.15f) {
                octaveErrorDetected = true
                octaveErrorReason = "05x_octave"
                Log.d(
                    TAG,
                    "V3 OCTAVE_DETECTED: 0.5x ratio=${String.format("%.3f", ratio)} " +
                    "methodA=${methodABpmFromPeaks} methodB=${medianSectionBpm.toLong()} → using methodA"
                )
            }
        }

        // 최종 BPM 선택: 옥타브 에러 검출 시 Method A 우선, 정상 시 Method B 우선
        val finalBpm = if (octaveErrorDetected) {
            // Phase 1 Fix: 옥타브 에러 발생 시 Method A 사용
            methodBBpm = medianSectionBpm.toLong()  // logging용 기록
            methodABpmFromPeaks
        } else if (medianSectionBpm > 0f && collectedSectionBpms.size >= 2) {
            // Method B: 섹션 기반 중앙값 우선 (위상 정확도 개선)
            methodBBpm = medianSectionBpm.toLong()
            val sectionBpmList = collectedSectionBpms.map { it.second.toInt() }

            // 극단값 분석
            val sortedBpms = sectionBpmList.sorted()
            val minBpm = sortedBpms.firstOrNull() ?: 0
            val maxBpm = sortedBpms.lastOrNull() ?: 0
            val range = maxBpm - minBpm

            Log.d(
                TAG,
                "V3 METHOD_B_SELECTED: medianBpm=${methodBBpm} (from ${collectedSectionBpms.size} sections, values=$sectionBpmList, " +
                "min=$minBpm max=$maxBpm range=$range)"
            )

            // 극단값이 심한 경우 경고
            if (range > 60) {
                Log.d(
                    TAG,
                    "V3 METHOD_B_WARNING: high_bpm_variance! range=$range, " +
                    "expected_bpm=${"%.1f".format(bestBpm)} tempogram_bpm=${"%.1f".format(bestBpm)}"
                )
            }

            methodBBpm
        } else {
            // Fallback: madmom 방식 (옥타브 에러 보정)
            val madmomBpm = calculateBpmFromBeats(beatTimesMs, referenceBpm = bestBpm.toLong())

            Log.d(
                TAG,
                "V3 FINAL_BPM_SELECTION: title=\"$songTitle\" " +
                        "bestBpm=${bestBpm.toInt()} medianSection=${if(medianSectionBpm > 0) medianSectionBpm.toInt() else "N/A"} " +
                        "madmomBpm=$madmomBpm beatCount=${beatTimesMs.size} sections=${collectedSectionBpms.size}"
            )

            if (madmomBpm > 0L) {
                val ratio = madmomBpm.toFloat() / bestBpm.toFloat()
                // bestBpm이 불안정한 대역 내이고, 계산된 BPM과 큰 차이가 나면 bestBpm 유지
                if (bestBpm >= 65f && bestBpm <= 115f && (ratio < 0.8f || ratio > 1.25f)) {
                    Log.d(
                        TAG,
                        "V3 BPM_RECALC_ADJUSTED: calculated=$madmomBpm rejected (ratio=${String.format("%.2f", ratio)}), " +
                                "keeping bestBpm=${bestBpm.toInt()} (in unstable 65-115 band)"
                    )
                    bestBpm.toLong()
                } else {
                    if (madmomBpm != bestBpm.toLong()) {
                        Log.d(
                            TAG,
                            "V3 BPM_RECALC: original=${bestBpm.toInt()} madmom=$madmomBpm (from ${beatTimesMs.size} beats, ratio=${String.format("%.2f", ratio)})"
                        )
                    }
                    madmomBpm
                }
            } else {
                Log.d(TAG, "V3 BPM_FALLBACK: madmomBpm failed, using bestBpm=${bestBpm.toInt()}")
                bestBpm.toLong()
            }
        }
        val finalBeatMs = if (finalBpm > 0L) (60_000L / finalBpm) else 0L

        // Update reason if octave error was detected
        if (octaveErrorDetected) {
            reason = "octave_correction_${octaveErrorReason}"
        }

        // [FIX #4] 신뢰도 재계산 (고조파 필터링 후)
        val adjustedConfidence = if (methodABpm != methodBBpm && methodBBpm > 0L) {
            // Method A와 B가 다른 경우 (옥타브 오류 감지)
            // finalBpm이 methodBBpm과 같으면 신뢰도를 100%로 상향 (고조파 필터링 성공)
            if (finalBpm == methodBBpm) {
                Log.d(TAG, "V3 CONFIDENCE_BOOST: methodA=$methodABpm (harmonic) → methodB=$methodBBpm (selected), confidence 100%")
                1.0f
            } else {
                // finalBpm이 다른 경우는 confidence 유지
                confidence
            }
        } else if (methodABpm == methodBBpm) {
            // Method A와 B가 일치하면 신뢰도 100%
            1.0f
        } else {
            confidence
        }

        val timeSignature = detectTimeSignature(globalOdf, finalBpm, params.hopMs)
        val downbeatMs = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, finalBpm,
            timeSignature.beatsPerBar, params.hopMs
        )
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(
            TAG,
            "V3 OK beats=${beats.size} BPM=$finalBpm Confidence=${adjustedConfidence * 100}% reason=$reason"
        )

        // DP tracking 결과 저장
        dpTrackingResults = mapOf(
            "beatCount" to beats.size,
            "reason" to reason,
            "methodUsed" to (if (reason.contains("section")) "section_based" else "global")
        )

        // JSON으로 종합 분석 데이터 저장 (정확도 진단용, 평상시 OFF)
        if (ENABLE_JSON_EXPORT && context != null && songTitle != null) {
            try {
                val sectionBpmList = collectedSectionBpms.map { it.second.toInt() }
                val odfSizeVal = (odfStats["size"] as? Int) ?: 0
                val odfMaxStr = odfStats["max"]?.toString() ?: "0"
                val odfMeanStr = odfStats["mean"]?.toString() ?: "0"
                val odfStdStr = odfStats["std"]?.toString() ?: "0"

                // AC Peaks JSON 배열 생성
                val acPeaksJson = if (acPeaksList.isNotEmpty()) {
                    acPeaksList.mapIndexed { idx, peak ->
                        val bpmVal = peak["bpm"]?.toString() ?: "0"
                        val acVal = peak["ac"]?.toString() ?: "0"
                        val acRawVal = peak["ac_raw"]?.toString() ?: "0"
                        val ratioVal = peak["ratio"]?.toString() ?: "0"
                        "{\"index\":$idx,\"bpm\":$bpmVal,\"ac\":\"$acVal\",\"ac_raw\":\"$acRawVal\",\"ratio\":\"$ratioVal\"}"
                    }.joinToString(",")
                } else ""

                // Section Details JSON 배열 생성
                val sectionDetailsJson = if (sectionDetailsList.isNotEmpty()) {
                    sectionDetailsList.map { section ->
                        val idx = section["index"]?.toString() ?: "0"
                        val start = section["startMs"]?.toString() ?: "0"
                        val end = section["endMs"]?.toString() ?: "0"
                        val bpm = section["bpm"]?.toString() ?: "0"
                        val beats = section["expectedBeats"]?.toString() ?: "0"
                        "{\"index\":$idx,\"startMs\":$start,\"endMs\":$end,\"bpm\":$bpm,\"expectedBeats\":$beats}"
                    }.joinToString(",")
                } else ""

                // JSON 구조: 모든 분석 데이터 포함
                val analysisJson = "{" +
                        "\"title\":\"$songTitle\"," +
                        "\"bpmResults\":{" +
                        "\"finalBpm\":$finalBpm," +
                        "\"methodABpm\":$methodABpm," +
                        "\"methodBBpm\":$methodBBpm," +
                        "\"confidence\":${String.format("%.1f", adjustedConfidence * 100)}" +
                        "}," +
                        "\"odfStats\":{" +
                        "\"size\":$odfSizeVal," +
                        "\"max\":\"$odfMaxStr\"," +
                        "\"mean\":\"$odfMeanStr\"," +
                        "\"std\":\"$odfStdStr\"" +
                        "}," +
                        "\"acPeaks\":[$acPeaksJson]," +
                        "\"sectionDetails\":[$sectionDetailsJson]," +
                        "\"dpTracking\":{" +
                        "\"beatCount\":${dpTrackingResults["beatCount"]?.toString() ?: "0"}," +
                        "\"reason\":\"${dpTrackingResults["reason"]?.toString() ?: ""}\"," +
                        "\"methodUsed\":\"${dpTrackingResults["methodUsed"]?.toString() ?: ""}\"" +
                        "}," +
                        "\"metadata\":{" +
                        "\"sectionCount\":${collectedSectionBpms.size}," +
                        "\"sectionBpms\":[${sectionBpmList.joinToString(",")}]" +
                        "}" +
                        "}"

                val filesDir = context.filesDir
                val analysisDir = java.io.File(filesDir, "v3_analysis")
                if (!analysisDir.exists()) {
                    analysisDir.mkdirs()
                }
                val file = java.io.File(analysisDir, "bpm_results.jsonl")
                file.appendText(analysisJson + "\n")
                Log.d(TAG, "V3 ANALYSIS_SAVED: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "V3 SAVE_FAILED: ${e.message}")
            }
        }

        return DetectResultV3(
            beats = beats,
            beatMs = finalBeatMs,
            confidence = adjustedConfidence,
            source = BeatSource.FULL,
            reason = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature = timeSignature,
            tempogram = tempogram
        )
    }

    // ====================== V1 호환 헬퍼 메서드들 ======================

    private fun computeMultiBandFluxOdf(
        low: FloatArray, mid: FloatArray, full: FloatArray, params: Params
    ): FloatArray {
        val n = minOf(low.size, mid.size, full.size)
        val lowFlux = computeOdf(low.toList().take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val midFlux = computeOdf(mid.toList().take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val fullFlux = computeOdf(full.toList().take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)

        // 적응적 대역 가중치 계산 (곡의 주파수 특성에 따라 동적 조정)
        val (lowWeight, midWeight, fullWeight) = calculateAdaptiveBandWeights(
            lowFlux.toFloatArray(),
            midFlux.toFloatArray(),
            fullFlux.toFloatArray()
        )

        // [V3 개선] Mid 대역 신뢰도 강화
        // Ed Sheeran 같은 곡에서 드럼/보컬(mid 대역)의 주기성을 더 강하게 반영
        // 스펙트럼 분석 결과: Mid 대역이 가장 강하고 신뢰도 높음
        val enhancedMidWeight = midWeight * 1.25f  // Mid 대역 25% 추가 강화 (15% → 25%)
        val adjustedLowWeight = lowWeight * 0.70f   // Low 대역 30% 감소 (15% → 30%)

        // 가중치 정규화
        val totalWeight = adjustedLowWeight + enhancedMidWeight + fullWeight
        val normLowWeight = adjustedLowWeight / totalWeight
        val normMidWeight = enhancedMidWeight / totalWeight
        val normFullWeight = fullWeight / totalWeight

        val combined = ArrayList<Float>(n)
        for (i in 0 until n) {
            combined += lowFlux[i] * normLowWeight + midFlux[i] * normMidWeight + fullFlux[i] * normFullWeight
        }

        // 로깅: 적응적 가중치 정보
        Log.d(
            TAG,
            "V3 ODF_BAND_WEIGHTS: low=${"%.2f".format(normLowWeight)} mid=${"%.2f".format(normMidWeight)} full=${"%.2f".format(normFullWeight)} (mid_enhanced)"
        )

        // [V3 개선] 대역별 ODF 저장 (나중에 AC 계산에 활용)
        bandOdfs = Triple(lowFlux.toFloatArray(), midFlux.toFloatArray(), fullFlux.toFloatArray())

        return localNormalizeMean(combined, GLOBAL_NORM_WINDOW).toFloatArray()
    }

    // [V3 개선] 대역별 ODF 저장소
    private var bandOdfs: Triple<FloatArray, FloatArray, FloatArray>? = null

    /**
     * 곡의 주파수 특성에 따른 적응적 대역 가중치 계산
     *
     * 원리:
     * - 각 대역의 에너지와 peak 강도를 분석
     * - 드럼이 강한 곡: mid/high 가중치 증가
     * - 베이스가 강한 곡: low 가중치 증가
     * - 동적 범위가 큰 곡: full 가중치 조정
     *
     * @return Triple(lowWeight, midWeight, fullWeight)
     */
    private fun calculateAdaptiveBandWeights(
        lowFlux: FloatArray,
        midFlux: FloatArray,
        fullFlux: FloatArray
    ): Triple<Float, Float, Float> {
        // 기본 가중치
        var lowWeight = 1.0f
        var midWeight = 1.8f
        var fullWeight = 0.8f

        if (lowFlux.isEmpty() || midFlux.isEmpty() || fullFlux.isEmpty()) {
            return Triple(lowWeight, midWeight, fullWeight)
        }

        // 각 대역의 특성 분석
        val lowEnergy = lowFlux.average().toFloat()
        val midEnergy = midFlux.average().toFloat()
        val fullEnergy = fullFlux.average().toFloat()

        val lowMax = lowFlux.maxOrNull() ?: 0f
        val midMax = midFlux.maxOrNull() ?: 0f
        val fullMax = fullFlux.maxOrNull() ?: 0f

        // 상대 에너지 비율
        val totalEnergy = lowEnergy + midEnergy + fullEnergy
        if (totalEnergy < 1e-6f) {
            return Triple(lowWeight, midWeight, fullWeight)
        }

        val lowEnergyRatio = lowEnergy / totalEnergy
        val midEnergyRatio = midEnergy / totalEnergy
        val fullEnergyRatio = fullEnergy / totalEnergy

        // 피크 강도 비율
        val totalPeak = lowMax + midMax + fullMax
        val lowPeakRatio = if (totalPeak > 0) lowMax / totalPeak else 0f
        val midPeakRatio = if (totalPeak > 0) midMax / totalPeak else 0f
        val fullPeakRatio = if (totalPeak > 0) fullMax / totalPeak else 0f

        // 적응적 가중치 조정
        // 에너지와 피크 강도를 모두 고려하여 가중치 계산
        lowWeight = 0.8f + lowEnergyRatio * 1.2f + lowPeakRatio * 0.5f
        midWeight = 1.2f + midEnergyRatio * 1.2f + midPeakRatio * 0.8f
        fullWeight = 0.6f + fullEnergyRatio * 0.8f + fullPeakRatio * 0.3f

        // 정규화: 가중치 합이 3.0이 되도록 (원래 합: 1.0 + 1.8 + 0.8 = 3.6)
        val weightSum = lowWeight + midWeight + fullWeight
        lowWeight = lowWeight * 3.0f / weightSum
        midWeight = midWeight * 3.0f / weightSum
        fullWeight = fullWeight * 3.0f / weightSum

        // [V3.2] 강화된 절반 박자 필터링
        // 분석: V3에서 29.7%가 0.5x 에러 (절반 박자)로 감지
        // 원인: Low 대역이 절반 주기를 강하게 포착하는 경향
        // 해결: Low 대역의 가중치를 더 공격적으로 감소

        // 절반 박자 신호 감지: Low peak가 Mid peak의 1.2배 이상
        if (lowPeakRatio > midPeakRatio * 1.2f) {
            // 절반 박자 가능성이 높음
            // Low를 원래 3분의 1 이하로 감소
            lowWeight = lowWeight * 0.33f  // 기존 0.75 → 0.33 (더 강한 감소)
            midWeight = midWeight * 1.25f  // Mid 강화 (기존 1.1 → 1.25)

            // 정규화 재적용
            val newWeightSum = lowWeight + midWeight + fullWeight
            lowWeight = lowWeight * 3.0f / newWeightSum
            midWeight = midWeight * 3.0f / newWeightSum
            fullWeight = fullWeight * 3.0f / newWeightSum

            Log.d(
                TAG,
                "V3.2 HALF_TEMPO_PENALTY: lowPeakRatio=${"%.2f".format(lowPeakRatio)} vs midPeakRatio=${"%.2f".format(midPeakRatio)} → aggressive low reduction"
            )
        }
        // 추가: Mid가 Low의 1.3배 이상 강하면 Mid를 더 신뢰
        else if (midPeakRatio > lowPeakRatio * 1.3f) {
            midWeight = midWeight * 1.15f
            lowWeight = lowWeight * 0.85f

            // 정규화
            val newWeightSum = lowWeight + midWeight + fullWeight
            lowWeight = lowWeight * 3.0f / newWeightSum
            midWeight = midWeight * 3.0f / newWeightSum
            fullWeight = fullWeight * 3.0f / newWeightSum

            Log.d(
                TAG,
                "V3.2 MID_PREFERENCE: midPeakRatio=${"%.2f".format(midPeakRatio)} > lowPeakRatio → mid boost"
            )
        }

        return Triple(lowWeight, midWeight, fullWeight)
    }

    private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> {
        // 단계 1: 이동 평균으로 노이즈 필터링
        val smoothed = movingAverage(env, smoothWindow)

        // 단계 2: 고주파 강조 (onset 감지 강화)
        // onset은 신호의 급격한 변화로 나타나므로 고주파가 더 명확함
        val highPassEnhanced = enhanceHighFrequency(smoothed)

        // 단계 3: 양의 변화만 추출 (onset 감지)
        val diff = positiveDiff(highPassEnhanced)

        // 단계 4: 로컬 정규화
        return localNormalizeMax(diff, normWindow)
    }

    /**
     * 고주파 강조를 통한 onset 감지 개선
     *
     * Onset은 신호의 급격한 변화(고주파 성분)로 나타나므로,
     * 고주파를 부스트하면 onset 감지가 더 명확해집니다.
     */
    private fun enhanceHighFrequency(signal: List<Float>): List<Float> {
        if (signal.size < 3) return signal.toList()

        val enhanced = ArrayList<Float>(signal.size)

        for (i in signal.indices) {
            val current = signal[i]

            // 고주파 성분 추정: 현재값 - 이웃값의 평균
            // 이는 미분(derivative) 근사로 고주파 성분을 강조
            val left = if (i > 0) signal[i - 1] else current
            val right = if (i < signal.lastIndex) signal[i + 1] else current
            val neighbor = (left + right) / 2.0f

            // High-pass filter: original - low-pass
            val highPass = current - neighbor

            // 원래 신호에 고주파 성분 추가 (부스트)
            // 강도는 신호의 동적 범위에 따라 조정
            val boosted = current + highPass * 0.5f

            enhanced.add(maxOf(0f, boosted))  // 음수 값은 0으로 (onset은 양수)
        }

        return enhanced
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src.toList()
        val out = ArrayList<Float>(src.size)
        val half = window / 2
        for (i in src.indices) {
            var sum = 0f
            var count = 0
            val s = maxOf(0, i - half)
            val e = minOf(src.lastIndex, i + half)
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
        for (i in 1 until src.size) out += maxOf(0f, src[i] - src[i - 1])
        return out
    }

    private fun localNormalizeMax(src: List<Float>, windowFrames: Int): List<Float> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<Float>(src.size)
        for (i in src.indices) {
            val lo = maxOf(0, i - windowFrames)
            val hi = minOf(src.lastIndex, i + windowFrames)
            var localMax = 0f
            for (j in lo..hi) if (src[j] > localMax) localMax = src[j]
            out.add(if (localMax > 1e-6f) (src[i] / localMax).coerceIn(0f, 1f) else 0f)
        }
        return out
    }

    private fun localNormalizeMean(src: List<Float>, windowFrames: Int): List<Float> {
        if (src.isEmpty()) return emptyList()
        val bgRemoved = ArrayList<Float>(src.size)
        for (i in src.indices) {
            val lo = maxOf(0, i - windowFrames)
            val hi = minOf(src.lastIndex, i + windowFrames)
            var localMean = 0f
            var cnt = 0
            for (j in lo..hi) {
                localMean += src[j]
                cnt++
            }
            localMean = if (cnt > 0) localMean / cnt else 0f
            bgRemoved.add((src[i] - localMean).coerceAtLeast(0f))
        }
        val out = ArrayList<Float>(src.size)
        for (i in bgRemoved.indices) {
            val lo = maxOf(0, i - windowFrames)
            val hi = minOf(bgRemoved.lastIndex, i + windowFrames)
            var localMax = 0f
            for (j in lo..hi) if (bgRemoved[j] > localMax) localMax = bgRemoved[j]
            out.add(if (localMax > 1e-6f) (bgRemoved[i] / localMax).coerceIn(0f, 1f) else 0f)
        }
        return out
    }

    private fun estimatePhaseFromOdf(odf: FloatArray, beatMs: Long, hopMs: Long): Long {
        val fpb = maxOf(1, (beatMs / hopMs).toInt())
        if (odf.size < fpb * 2) return 0L

        // 개선: 위상 오차 감소를 위해 초기 범위에서 phase 찾기
        // 조용한 intro 곡에서 나중의 강한 신호 대신 초기 신호를 위상으로 선택
        // 동적 범위: beatMs * 10 (약 5-10초, BPM에 따라 다름)
        val initialSearchMs = beatMs * 10  // 약 10비트 범위
        val initialSearchFrames = minOf(odf.size, (initialSearchMs / hopMs).toInt())
        val searchEndFrame = maxOf(fpb * 2, initialSearchFrames)

        var bestPhase = 0
        var bestScore = Float.NEGATIVE_INFINITY

        // 초기 부분(첫 2초)에서 각 phase의 강도 계산
        for (ph in 0 until fpb) {
            var score = 0f
            var count = 0
            var f = ph
            while (f < searchEndFrame && f < odf.size) {
                score += odf[f]
                count++
                f += fpb
            }

            // 초기 부분에 가중치 부여 (더 빨리 나타나는 phase 선호)
            // count가 적을수록 더 초기에 나타나므로 보너스 부여
            val countPenalty = if (count > 0) 1.0f else 0f
            val weightedScore = (score / maxOf(1, count).toFloat()) * countPenalty

            if (weightedScore > bestScore) {
                bestScore = weightedScore
                bestPhase = ph
            }
        }

        val phaseMs = bestPhase.toLong() * hopMs
        Log.d(
            TAG,
            "V3 PHASE_ESTIMATE: selected phase=$bestPhase (${phaseMs}ms, score=$bestScore) " +
                    "from ${searchEndFrame} frames (${searchEndFrame * hopMs}ms range, beatMs=$beatMs)"
        )

        return phaseMs
    }

    private fun dpBeatTracker(
        odf: FloatArray,
        targetPeriodMs: Long,
        hopMs: Long,
        durationMs: Long,
        anchorMs: Long = 0L
    ): LongArray {
        if (odf.isEmpty() || targetPeriodMs <= 0L) return LongArray(0)
        val n = odf.size
        val fpb = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
        val tightness = 100.0f
        val anchorFrame = if (anchorMs > 0L) (anchorMs / hopMs).toInt().coerceIn(0, n - 1) else -1

        val gaussHalf = fpb
        val gaussSize = gaussHalf * 2 + 1
        val gaussWin = FloatArray(gaussSize) { k ->
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
        val prev = IntArray(n) { -1 }
        val searchRange = fpb * 2

        for (t in 0 until n) {
            val pLo = maxOf(0, t - searchRange)
            val pHi = maxOf(0, t - maxOf(1, fpb / 2))
            var bestVal = Float.NEGATIVE_INFINITY
            for (p in pLo..pHi) {
                val isFreeStart = (p == 0 || p == anchorFrame)
                if (cumscore[p] == Float.NEGATIVE_INFINITY && !isFreeStart) continue
                val pScore = if (isFreeStart) 0f else cumscore[p]
                val lag = (t - p).toFloat().coerceAtLeast(1f)
                val logRatio = ln(lag / fpb)
                val penalty = tightness * logRatio * logRatio
                val cand = pScore - penalty
                if (cand > bestVal) {
                    bestVal = cand
                    prev[t] = p
                }
            }
            cumscore[t] = if (bestVal == Float.NEGATIVE_INFINITY) localscore[t]
            else bestVal + localscore[t]
        }

        var t = cumscore.indices.maxByOrNull { cumscore[it] } ?: return LongArray(0)
        val beats = mutableListOf<Long>()
        var iter = 0
        while (t > 0 && iter < n) {
            beats.add(t.toLong() * hopMs)
            val p = prev[t]
            if (p < 0 || p == t) break
            t = p
            iter++
        }

        val preTrim = beats.reversed().toLongArray()
        if (preTrim.size < 2) return preTrim
        val rms = sqrt(localscore.map { it * it }.average().toFloat())
        val trimTh = 0.5f * rms
        var s = 0
        while (s < preTrim.size && localscore[(preTrim[s] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) s++
        var e = preTrim.size - 1
        while (e > s && localscore[(preTrim[e] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) e--
        return if (s > e) preTrim else preTrim.sliceArray(s..e)
    }

    private fun fallbackSegmentBeats(
        low: FloatArray, mid: FloatArray, full: FloatArray,
        params: Params, beatMs: Long, durationMs: Long
    ): List<TimedBeat> {
        val n = minOf(low.size, mid.size, full.size)
        val segFrames = maxOf(1, (params.segmentMs / params.hopMs).toInt())
        val result = ArrayList<TimedBeat>()

        for (segIdx in 0 until (n + segFrames - 1) / segFrames) {
            val s = segIdx * segFrames
            val e = minOf(n, s + segFrames)
            if (e - s < 8) continue

            val odf = computeMultiBandFluxOdf(
                low.copyOfRange(s, e),
                mid.copyOfRange(s, e),
                full.copyOfRange(s, e),
                params
            )
            val segPhase = estimatePhaseFromOdf(odf, beatMs, params.hopMs)
            val segTimes = dpBeatTracker(odf, beatMs, params.hopMs, (e - s).toLong() * params.hopMs, anchorMs = segPhase)
            val offset = s.toLong() * params.hopMs
            segTimes.forEach { result += TimedBeat(offset + it, FILL_CONFIDENCE) }
        }
        return result.sortedBy { it.timeMs }
    }

    private fun detectTimeSignature(onset: FloatArray, beatMs: Long, hopMs: Long): TimeSignature {
        if (onset.size < 8 || beatMs <= 0L) return TimeSignature.FOUR_FOUR
        val bf = (beatMs / hopMs).toInt().coerceAtLeast(1)
        val corr3 = lagCorr(onset, bf * 3)
        val corr4 = lagCorr(onset, bf * 4)
        val corr6 = lagCorr(onset, bf * 6)
        return when {
            corr3 > corr4 * TIME_SIG_THREE_RATIO -> TimeSignature.THREE_FOUR
            corr6 > corr4 * TIME_SIG_SIX_RATIO && corr3 > corr4 * 0.85f -> TimeSignature.SIX_EIGHT
            else -> TimeSignature.FOUR_FOUR
        }
    }

    private fun lagCorr(onset: FloatArray, lag: Int): Float {
        if (lag <= 0 || lag >= onset.size) return 0f
        var sum = 0f
        var i = 0
        while (i + lag < onset.size) {
            sum += onset[i] * onset[i + lag]
            i++
        }
        return sum / i.toFloat().coerceAtLeast(1f)
    }

    private fun detectDownbeatEnhanced(
        beatTimesMs: List<Long>, lowEnv: FloatArray,
        beatMs: Long, beatsPerBar: Int, hopMs: Long
    ): Long {
        if (beatTimesMs.isEmpty() || beatMs <= 0L) return 0L
        if (beatTimesMs.size < beatsPerBar) return beatTimesMs.first()

        val phaseSum = FloatArray(beatsPerBar)
        val phaseCnt = IntArray(beatsPerBar)
        for (i in beatTimesMs.indices) {
            val ph = i % beatsPerBar
            val fr = (beatTimesMs[i] / hopMs).toInt().coerceIn(0, lowEnv.lastIndex)
            phaseSum[ph] += lowEnv[fr]
            phaseCnt[ph]++
        }
        val avgEnergy = FloatArray(beatsPerBar) { p ->
            if (phaseCnt[p] > 0) phaseSum[p] / phaseCnt[p] else 0f
        }

        val barFrames = ((beatMs * beatsPerBar) / hopMs).toInt().coerceAtLeast(1)
        val combScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val anchor = (beatTimesMs.getOrElse(ph) { ph.toLong() * beatMs } / hopMs).toInt()
            var k = anchor
            var sum = 0f
            var cnt = 0
            while (k < lowEnv.size) {
                sum += lowEnv[k.coerceIn(0, lowEnv.lastIndex)]
                cnt++
                k += barFrames
            }
            k = anchor - barFrames
            while (k >= 0) {
                sum += lowEnv[k.coerceIn(0, lowEnv.lastIndex)]
                cnt++
                k -= barFrames
            }
            combScore[ph] = if (cnt > 0) sum / cnt else 0f
        }

        val consistScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val energies = beatTimesMs.filterIndexed { i, _ -> i % beatsPerBar == ph }
                .map { t -> lowEnv[(t / hopMs).toInt().coerceIn(0, lowEnv.lastIndex)] }
            if (energies.size >= 2) {
                val avg = energies.average().toFloat()
                val std = sqrt(energies.map { (it - avg) * (it - avg) }.average().toFloat())
                val cv = if (avg > 0.001f) std / avg else 1f
                consistScore[ph] = ((1f - cv.coerceIn(0f, 1f)) * avg).coerceAtLeast(0f)
            }
        }

        fun FloatArray.normMax(): FloatArray {
            val mx = maxOrNull() ?: return this
            return if (mx > 0.001f) FloatArray(size) { this[it] / mx } else this
        }
        val bestPhase = (0 until beatsPerBar).maxByOrNull { p ->
            avgEnergy.normMax()[p] * DOWNBEAT_W_LOW_ENERGY +
                    combScore.normMax()[p] * DOWNBEAT_W_BAR_COMB +
                    consistScore.normMax()[p] * DOWNBEAT_W_CONSISTENCY
        } ?: 0

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }

}
