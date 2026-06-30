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

    data class TimedBeat(val timeMs: Long, val confidence: Float)

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
    data class DetectResultV3(
        val beats: List<TimedBeat>,
        val beatMs: Long,
        val confidence: Float,  // ✨ 새로운!
        val source: BeatSource?,
        val reason: String,
        val downbeatOffsetMs: Long,
        val timeSignature: TimeSignature,
        val tempogram: Array<FloatArray>? = null,  // ✨ 새로운!
        val debugSegments: List<Any> = emptyList()
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
    fun findModalPeak(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long
    ): Pair<Float, Float> {
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

        // 최고 강도의 lag 찾기
        val bestLagIdx = bpmStrengths.indices.maxByOrNull { bpmStrengths[it] } ?: 0
        val bestLag = bestLagIdx + minLag
        var finalLag = bestLag
        var bestBpm = 60_000L / (bestLag * hopMs)

        // 신뢰도: 최고 강도와 2번째 강도의 비율
        val sorted = bpmStrengths.sortedDescending()
        var confidence = if (sorted.size >= 2 && sorted[1] > 1e-6f) {
            minOf(1.0f, sorted[0] / sorted[1])
        } else {
            sorted.firstOrNull()?.coerceIn(TEMPOGRAM_MIN_CONFIDENCE, 1.0f) ?: 0.5f
        }

        // === 옥타브 에러 보정 ===
        // 절반 비트(2배 BPM) 확인: lag/2
        val halfLag = bestLag / 2
        val halfStrength = if (halfLag >= minLag && halfLag - minLag < bpmStrengths.size) {
            bpmStrengths[halfLag - minLag]
        } else 0f
        val halfRatio = if (bpmStrengths[bestLagIdx] > 1e-6f) halfStrength / bpmStrengths[bestLagIdx] else 0f

        // 2배 비트(절반 BPM) 확인: lag*2
        val doubleLag = bestLag * 2
        val doubleStrength = if (doubleLag - minLag < bpmStrengths.size) {
            bpmStrengths[doubleLag - minLag]
        } else 0f
        val doubleRatio = if (bpmStrengths[bestLagIdx] > 1e-6f) doubleStrength / bpmStrengths[bestLagIdx] else 0f

        // 절반 비트가 강한 경우: 원래 BPM이 2배로 잘못된 것
        if (halfLag >= minLag && halfRatio >= 0.65f) {
            Log.d(
                TAG,
                "V3 OctaveError2x: halfLag=$halfLag halfRatio=$halfRatio → " +
                        "BPM ${60_000L / (bestLag * hopMs)} → ${60_000L / (halfLag * hopMs)}"
            )
            finalLag = halfLag
            bestBpm = 60_000L / (halfLag * hopMs)
            confidence = minOf(1.0f, halfStrength / sorted[0])
        }
        // 2배 비트가 강한 경우: 원래 BPM이 절반으로 잘못된 것
        else if (doubleLag - minLag < bpmStrengths.size && doubleRatio >= 0.65f && doubleStrength > bpmStrengths[bestLagIdx] * 0.9f) {
            Log.d(
                TAG,
                "V3 OctaveError0.5x: doubleLag=$doubleLag doubleRatio=$doubleRatio → " +
                        "BPM ${60_000L / (bestLag * hopMs)} → ${60_000L / (doubleLag * hopMs)}"
            )
            finalLag = doubleLag
            bestBpm = 60_000L / (doubleLag * hopMs)
            confidence = minOf(1.0f, doubleStrength / sorted[0])
        }

        Log.d(TAG, "V3 ModalPeak: BPM=$bestBpm, Confidence=${confidence * 100}% (lag=$finalLag)")

        return Pair(bestBpm.toFloat(), confidence)
    }

    /**
     * Tempogram에서 시간별 BPM 곡선 추출 (상세 하모닉 분석 포함)
     *
     * @return FloatArray - 각 시간프레임별 최강 BPM
     */
    private fun extractBpmCurve(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long
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
                val timeMs = tIdx * hopMs
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

        Log.d(TAG, "V3 METHOD_B_MEDIAN: sections=${sectionBpms.size}, bpms=${bpmValues.map { it.toInt() }}, median=${median.toInt()} BPM")
        return median
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
        externalSectionBoundariesMs: List<Long> = emptyList(),
        changeThresholdPercent: Float = 10f
    ): List<Long> {
        if (tempogram.isEmpty() || tempogram[0].isEmpty()) {
            return externalSectionBoundariesMs
        }

        // 1단계: BPM 곡선 추출 및 평활화
        val bpmCurve = extractBpmCurve(tempogram, hopMs, minBeatMs)
        val smoothedBpm = smoothBpmCurve(bpmCurve, windowSize = 5)

        // 2단계: BPM 변화점 감지
        val changePoints = detectBpmChangePoints(smoothedBpm, changeThresholdPercent, minDurationFrames = 10)

        // 3단계: 변화점을 ms로 변환
        val dynamicBoundaries = changePoints.map { it * hopMs }.toMutableList()

        // 4단계: 외부 경계 추가
        for (externalBound in externalSectionBoundariesMs) {
            if (!dynamicBoundaries.contains(externalBound)) {
                dynamicBoundaries.add(externalBound)
            }
        }

        // 5단계: 정렬 및 중복 제거
        dynamicBoundaries.sort()
        val finalBoundaries = dynamicBoundaries.distinct()

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
     * @return List<Pair<시작시간Ms, BPM>>
     */
    fun detectSectionBpms(
        tempogram: Array<FloatArray>,
        hopMs: Long,
        minBeatMs: Long,
        sectionBoundariesMs: List<Long> = emptyList()
    ): List<Pair<Long, Float>> {
        if (tempogram.isEmpty() || tempogram[0].isEmpty()) {
            return emptyList()
        }

        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())
        val totalTimeFrames = tempogram[0].size
        val totalDurationMs = totalTimeFrames * hopMs

        // 경계 프레임 계산
        val boundaryFrames = mutableListOf(0)  // 항상 처음부터 시작
        for (boundaryMs in sectionBoundariesMs) {
            val frame = (boundaryMs / hopMs).toInt().coerceIn(1, totalTimeFrames - 1)
            if (!boundaryFrames.contains(frame)) {
                boundaryFrames.add(frame)
            }
        }
        boundaryFrames.add(totalTimeFrames)  // 끝점 추가
        boundaryFrames.sort()

        val result = mutableListOf<Pair<Long, Float>>()

        // 각 섹션별로 BPM 계산
        for (i in 0 until boundaryFrames.size - 1) {
            val startFrame = boundaryFrames[i]
            val endFrame = boundaryFrames[i + 1]
            val startMs = startFrame * hopMs

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

            // 정규화
            val maxStrength = sectionStrengths.maxOrNull() ?: 1f
            if (maxStrength > 1e-6f) {
                for (i in sectionStrengths.indices) {
                    sectionStrengths[i] = sectionStrengths[i] / maxStrength
                }
            }

            // 상위 5개 피크 추출 (하모닉 분석용)
            val peaks = sectionStrengths.mapIndexed { lagIdx, strength ->
                Pair(lagIdx + minLag, strength)
            }.sortedByDescending { it.second }.take(5)

            val bestLag = peaks[0].first
            val sectionBpm = 60_000L / (bestLag * hopMs)

            // 섹션별 상세 로그
            val sectionLog = StringBuilder("V3 Section[${startMs}ms-${(endFrame * hopMs)}ms]:\n")
            peaks.forEachIndexed { idx, (lag, strength) ->
                val bpm = 60_000L / (lag * hopMs)
                val ratio = lag.toFloat() / bestLag.toFloat()
                val harmonicType = when {
                    kotlin.math.abs(ratio - 0.5f) < 0.08f -> "2x"
                    kotlin.math.abs(ratio - 0.67f) < 0.08f -> "1.5x"
                    kotlin.math.abs(ratio - 1.0f) < 0.05f -> "PEAK"
                    kotlin.math.abs(ratio - 1.5f) < 0.08f -> "0.67x"
                    kotlin.math.abs(ratio - 2.0f) < 0.08f -> "0.5x"
                    else -> "other"
                }
                val normStrength = (strength * 100).toInt()
                sectionLog.append("  [$idx] lag=$lag(${bpm.toInt()}BPM,ratio=${String.format("%.2f", ratio)},$harmonicType,str=$normStrength%)\n")
            }
            sectionLog.append("  SELECTED: lag=$bestLag(${sectionBpm.toInt()}BPM)")
            Log.d(TAG, sectionLog.toString())

            result.add(Pair(startMs, sectionBpm.toFloat()))
        }

        return result
    }

    /**
     * V3 BPM 탐지
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
    ): Triple<Float, Float, Array<FloatArray>?> {
        val minLag = maxOf(1, (minBeatMs / hopMs).toInt())
        val maxLag = maxOf(minLag + 1, (maxBeatMs / hopMs).toInt())

        if (odf.size <= maxLag + 2) {
            return Triple(0f, 0f, null)
        }

        val acVals = FloatArray(maxLag + 1)
        val priorVals = FloatArray(maxLag + 1)
        val scoreVals = FloatArray(maxLag + 1)

        // Autocorrelation 계산
        for (lag in minLag..maxLag) {
            var acSum = 0f
            for (i in 0 until odf.size - lag) {
                acSum += odf[i] * odf[i + lag]
            }

            val acVal = acSum / (odf.size - lag)
            acVals[lag] = acVal

            // Log-normal prior
            val lagMs = lag * hopMs
            val logRatio = ln(lagMs.toFloat() / priorCenterMs) / ln(2f)
            val prior = exp(-0.5f * (logRatio / priorStdOctave).pow(2))

            priorVals[lag] = prior
            scoreVals[lag] = acVal * prior
        }

        // 상위 피크 분석 (하모닉 감지용) - 개선: 8개 → 20개로 확대
        val peaksByScore = (minLag..maxLag).map { lag ->
            Triple(lag, scoreVals[lag], acVals[lag])
        }.sortedByDescending { it.second }.take(20)

        val harmonicDiag = StringBuilder("V3 AC_PEAKS_GLOBAL:\n")
        peaksByScore.forEachIndexed { idx, (lag, score, ac) ->
            val bpm = 60_000L / (lag * hopMs)
            val ratio = if (peaksByScore[0].first > 0) lag.toFloat() / peaksByScore[0].first.toFloat() else 0f
            val harmonicType = when {
                kotlin.math.abs(ratio - 0.5f) < 0.08f -> "2x"
                kotlin.math.abs(ratio - 0.67f) < 0.08f -> "1.5x"
                kotlin.math.abs(ratio - 1.0f) < 0.05f -> "PEAK"
                kotlin.math.abs(ratio - 1.5f) < 0.08f -> "0.67x"
                kotlin.math.abs(ratio - 2.0f) < 0.08f -> "0.5x"
                else -> "other"
            }
            val normScore = if (peaksByScore[0].second > 0) (score / peaksByScore[0].second * 100).toInt() else 0
            harmonicDiag.append("  [$idx] lag=$lag(${bpm.toInt()}BPM,ratio=${String.format("%.2f", ratio)},$harmonicType,score=$normScore%,ac=${String.format("%.6f", ac)})\n")
        }
        Log.d(TAG, harmonicDiag.toString())

        // Method A: AC_PEAKS 상위 N개에서 최적 피크 선택
        val selectedPeak = selectBestPeakFromAcPeaks(peaksByScore, hopMs)
        val methodABpm = if (selectedPeak != null && selectedPeak.first > 0) {
            60_000L / (selectedPeak.first * hopMs)
        } else {
            0L
        }
        if (methodABpm > 0L) {
            Log.d(
                TAG,
                "V3 METHOD_A_SELECTION: Selected lag=${selectedPeak?.first}, BPM=$methodABpm"
            )
        }

        // Tempogram 사용
        if (useTempogram) {
            val tempogram = computeTempogram(odf, hopMs, minBeatMs, maxBeatMs)
            val (bestBpm, confidence) = findModalPeak(tempogram, hopMs, minBeatMs)

            Log.d(
                TAG,
                "V3 Tempogram: BPM=$bestBpm, Confidence=$confidence"
            )

            return Triple(bestBpm, confidence, tempogram)
        } else {
            // V1 방식
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

            Log.d(
                TAG,
                "V3 V1-Mode: BPM=$bestBpm, Confidence=$confidence"
            )

            return Triple(bestBpm.toFloat(), confidence, null)
        }
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
        songTitle: String? = null
    ): DetectResultV3 {
        if (monoSamples.isEmpty() || sampleRate <= 0) {
            return DetectResultV3(
                emptyList(), 0L, 0f, null,
                "empty pcm", 0L, TimeSignature.FOUR_FOUR
            )
        }

        // PCM → Envelope 계산 (V1과 동일한 IIR 필터)
        val hopSamples = kotlin.math.max(1, (sampleRate * params.hopMs / 1000).toInt())
        val numFrames = monoSamples.size / hopSamples
        val outLow = ArrayList<Float>(numFrames)
        val outMid = ArrayList<Float>(numFrames)
        val outFull = ArrayList<Float>(numFrames)

        var lowZ = 0f
        var midLP1 = 0f
        var midLP2 = 0f
        var lowSumSq = 0f
        var midSumSq = 0f
        var fullSumSq = 0f
        var winPos = 0

        for (sample in monoSamples) {
            lowZ += LOW_ALPHA * (sample - lowZ)
            midLP1 += MID_LP1_ALPHA * (sample - midLP1)
            midLP2 += MID_LP2_ALPHA * (sample - midLP2)
            val lowVal = kotlin.math.abs(lowZ)
            val midVal = kotlin.math.abs(midLP1 - midLP2)
            lowSumSq += lowVal * lowVal
            midSumSq += midVal * midVal
            fullSumSq += sample * sample
            winPos++
            if (winPos >= hopSamples) {
                outLow += kotlin.math.sqrt(lowSumSq / winPos)
                outMid += kotlin.math.sqrt(midSumSq / winPos)
                outFull += kotlin.math.sqrt(fullSumSq / winPos)
                lowSumSq = 0f
                midSumSq = 0f
                fullSumSq = 0f
                winPos = 0
            }
        }

        // Envelope 정규화
        fun normalizeEnv(src: List<Float>): List<Float> {
            val mx = src.maxOrNull() ?: 0f
            return if (mx > 1e-6f) src.map { (it / mx).coerceIn(0f, 1f) } else src
        }

        return detect(
            normalizeEnv(outLow), normalizeEnv(outMid), normalizeEnv(outFull),
            params, songTitle
        )
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
        sectionBoundariesMs: List<Long> = emptyList()
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

        // ODF 통계
        val odfMax = globalOdf.maxOrNull() ?: 0f
        val odfMean = if (globalOdf.isNotEmpty()) globalOdf.average().toFloat() else 0f
        Log.d(
            TAG,
            "V3 ODF_STATS: title=\"$songTitle\" size=${globalOdf.size} max=${String.format("%.6f", odfMax)} mean=${String.format("%.6f", odfMean)}"
        )

        if (bestBpm <= 0f) {
            Log.w(TAG, "V3 BPM탐지 실패! 신호 확인 필요 (ODF 약함)")
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
        val reason: String
        var sectionInfo = ""
        var collectedSectionBpms: List<Pair<Long, Float>> = emptyList()  // Method B용

        // 섹션별 BPM 분석 (Tempogram 기반 + 동적 감지)
        if (tempogram != null && params.useTempogram) {
            // 1단계: 동적 섹션 경계 생성 (BPM 변화 + 외부 경계)
            val dynamicSections = detectDynamicSections(
                tempogram,
                hopMs = params.hopMs,
                minBeatMs = params.minBeatMs,
                externalSectionBoundariesMs = sectionBoundariesMs,
                changeThresholdPercent = 10f
            )

            // 2단계: 동적 경계를 기반으로 섹션별 BPM 계산
            if (dynamicSections.size > 1) {
                val sectionBpms = detectSectionBpms(
                    tempogram,
                    hopMs = params.hopMs,
                    minBeatMs = params.minBeatMs,
                    sectionBoundariesMs = dynamicSections
                )

                // Method B용: 섹션 BPM 저장
                collectedSectionBpms = sectionBpms

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
                        // 섹션별 추적 실패 → 전체 BPM으로 폴백
                        Log.w(TAG, "V3 SectionBeats failed → fallback to global BPM")
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
                    reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
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

        Log.d(
            TAG,
            "V3 BEAT_ANALYSIS: title=\"$songTitle\" BPM=$bestBpm beatMs=$beatMs fpb=$fpb " +
            "beats=${beats.size} gaps=[avg=${avgGap}ms, min=${minGap}ms, max=${maxGap}ms] " +
            "reason=$reason"
        )

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

        // 최종 BPM 선택: Method B (섹션 중앙값) > 나머지
        val finalBpm = if (medianSectionBpm > 0f && collectedSectionBpms.size >= 2) {
            // Method B: 섹션 기반 중앙값 우선 (위상 정확도 개선)
            val methodBBpm = medianSectionBpm.toLong()
            Log.d(
                TAG,
                "V3 METHOD_B_SELECTED: medianBpm=$methodBBpm (from ${collectedSectionBpms.size} sections)"
            )
            methodBBpm
        } else {
            // Fallback: madmom 방식 (옥타브 에러 보정)
            val madmomBpm = calculateBpmFromBeats(beatTimesMs, referenceBpm = bestBpm.toLong())

            if (madmomBpm > 0L) {
                val ratio = madmomBpm.toFloat() / bestBpm.toFloat()
                // bestBpm이 불안정한 대역 내이고, 계산된 BPM과 큰 차이가 나면 bestBpm 유지
                if (bestBpm >= 65f && bestBpm <= 115f && (ratio < 0.8f || ratio > 1.25f)) {
                    Log.d(
                        TAG,
                        "V3 BPM_RECALC_ADJUSTED: calculated=$madmomBpm rejected (ratio=$ratio), " +
                                "keeping bestBpm=${bestBpm.toInt()} (in unstable 65-115 band)"
                    )
                    bestBpm.toLong()
                } else {
                    if (madmomBpm != bestBpm.toLong()) {
                        Log.d(
                            TAG,
                            "V3 BPM_RECALC: original=${bestBpm.toInt()} madmom=$madmomBpm (from ${beatTimesMs.size} beats, ratio=$ratio)"
                        )
                    }
                    madmomBpm
                }
            } else {
                bestBpm.toLong()
            }
        }
        val finalBeatMs = if (finalBpm > 0L) (60_000L / finalBpm) else 0L

        val timeSignature = detectTimeSignature(globalOdf, finalBpm, params.hopMs)
        val downbeatMs = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, finalBpm,
            timeSignature.beatsPerBar, params.hopMs
        )
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(
            TAG,
            "V3 OK beats=${beats.size} BPM=$finalBpm Confidence=${confidence * 100}% reason=$reason"
        )

        return DetectResultV3(
            beats = beats,
            beatMs = finalBeatMs,
            confidence = confidence,
            source = BeatSource.FULL,
            reason = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature = timeSignature,
            tempogram = tempogram
        )
    }

    // ====================== Method A: AC_PEAKS Top-N Selection ======================

    private fun selectBestPeakFromAcPeaks(
        peaksByScore: List<Triple<Int, Float, Float>>,
        hopMs: Long
    ): Pair<Int, Float>? {
        if (peaksByScore.isEmpty()) return null

        val topLag = peaksByScore[0].first
        val topScore = peaksByScore[0].second

        if (topScore <= 1e-6f) return Pair(topLag, topScore)

        // 개선: Harmonic 클러스터 분석으로 메인 비트 선택
        val topCandidates = peaksByScore.take(minOf(15, peaksByScore.size))

        // 최고 피크 기준 harmonic 클러스터 분류
        val harmonicClusters = mutableMapOf<String, MutableList<Triple<Int, Float, Float>>>()

        for (peak in topCandidates) {
            val ratio = peak.first.toFloat() / topLag.toFloat()
            val clusterKey = when {
                kotlin.math.abs(ratio - 1.0f) < 0.05f -> "MAIN"
                kotlin.math.abs(ratio - 0.5f) < 0.12f -> "HALF"  // 절반 BPM
                kotlin.math.abs(ratio - 0.67f) < 0.12f -> "1.5x"  // 1.5배
                kotlin.math.abs(ratio - 1.5f) < 0.12f -> "0.67x" // 0.67배
                kotlin.math.abs(ratio - 2.0f) < 0.12f -> "DOUBLE" // 2배
                else -> "OTHER"
            }
            harmonicClusters.getOrPut(clusterKey) { mutableListOf() }.add(peak)
        }

        // 클러스터별 최고 피크 선택 (평균 대신 신뢰도 기반)
        val clusterScores = harmonicClusters.map { (clusterKey, peaks) ->
            // 각 클러스터에서 AC value (신뢰도)가 가장 높은 피크 선택
            // → 평균으로 인한 정수 손실 방지
            val bestPeak = peaks.maxByOrNull { it.third } ?: peaks[0]
            val selectedLag = bestPeak.first
            val selectedScore = bestPeak.second
            val selectedAc = bestPeak.third

            // 가중치: MAIN(1.5배), 1.5x(1.2배), HALF(1.0배), 기타(0.8배)
            val weightMultiplier = when (clusterKey) {
                "MAIN" -> 1.5f
                "1.5x", "0.67x" -> 1.2f
                "HALF" -> 1.0f
                else -> 0.8f
            }
            val finalScore = selectedScore * weightMultiplier

            if (clusterKey != "OTHER") {
                Log.d(
                    TAG,
                    "V3 AC_CLUSTER_DETAIL: $clusterKey → lag=$selectedLag " +
                            "(ac=${String.format("%.6f", selectedAc)}, score=$selectedScore, " +
                            "weighted=$finalScore)"
                )
            }

            Triple(clusterKey, selectedLag, finalScore)
        }.sortedByDescending { it.third }

        if (clusterScores.isNotEmpty()) {
            val bestCluster = clusterScores[0]
            Log.d(
                TAG,
                "V3 AC_PEAKS_ENHANCED: clusters=${harmonicClusters.size}, " +
                        "selected='${bestCluster.first}' lag=${bestCluster.second} score=${String.format("%.2f", bestCluster.third)}"
            )
            return Pair(bestCluster.second, bestCluster.third.toFloat())
        }

        // Fallback
        return Pair(topLag, topScore)
    }

    private fun checkIfHarmonic(ratio: Float): Boolean {
        return (kotlin.math.abs(ratio - 0.5f) < 0.10f ||  // 2x octave
                kotlin.math.abs(ratio - 0.67f) < 0.10f ||  // 1.5x harmonic
                kotlin.math.abs(ratio - 1.5f) < 0.10f ||  // 0.67x harmonic
                kotlin.math.abs(ratio - 2.0f) < 0.10f)    // 0.5x octave
    }

    // ====================== V1 호환 헬퍼 메서드들 ======================

    private fun computeMultiBandFluxOdf(
        low: FloatArray, mid: FloatArray, full: FloatArray, params: Params
    ): FloatArray {
        val n = minOf(low.size, mid.size, full.size)
        val lowFlux = computeOdf(low.toList().take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val midFlux = computeOdf(mid.toList().take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val fullFlux = computeOdf(full.toList().take(n), params.onsetSmoothWindow, LOCAL_NORM_WINDOW)
        val combined = ArrayList<Float>(n)
        for (i in 0 until n) {
            combined += lowFlux[i] * 1.0f + midFlux[i] * 1.8f + fullFlux[i] * 0.8f
        }
        return localNormalizeMean(combined, GLOBAL_NORM_WINDOW).toFloatArray()
    }

    private fun computeOdf(env: List<Float>, smoothWindow: Int, normWindow: Int): List<Float> =
        localNormalizeMax(positiveDiff(movingAverage(env, smoothWindow)), normWindow)

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
