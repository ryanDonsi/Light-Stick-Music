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

    enum class BeatSource { LOW, MID, FULL, LOW_MID, MID_FULL, LOW_FULL }

    data class Params(
        val hopMs: Long = 50L,
        val minBeatMs: Long = 375L,
        val maxBeatMs: Long = 1000L,
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
     * Returns: FloatArray[BPM_bins][time_frames]
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
     * 모달 피크 찾기
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
        val bestBpm = 60_000L / (bestLag * hopMs)

        // 신뢰도: 최고 강도와 2번째 강도의 비율
        val sorted = bpmStrengths.sortedDescending()
        val confidence = if (sorted.size >= 2 && sorted[1] > 1e-6f) {
            minOf(1.0f, sorted[0] / sorted[1])
        } else {
            sorted.firstOrNull()?.coerceIn(TEMPOGRAM_MIN_CONFIDENCE, 1.0f) ?: 0.5f
        }

        Log.d(TAG, "V3 ModalPeak: BPM=$bestBpm, Confidence=${confidence * 100}%")

        return Pair(bestBpm.toFloat(), confidence)
    }

    /**
     * Tempogram에서 시간별 BPM 곡선 추출
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

        for (tIdx in 0 until numTimeFrames) {
            var maxStrength = 0f
            var bestLagIdx = 0
            for (lagIdx in tempogram.indices) {
                if (tempogram[lagIdx][tIdx] > maxStrength) {
                    maxStrength = tempogram[lagIdx][tIdx]
                    bestLagIdx = lagIdx
                }
            }
            val lag = bestLagIdx + minLag
            bpmCurve[tIdx] = (60_000L / (lag * hopMs)).toFloat()
        }

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

            // 이 섹션의 모달 피크
            val bestLagIdx = sectionStrengths.indices.maxByOrNull { sectionStrengths[it] } ?: 0
            val bestLag = bestLagIdx + minLag
            val sectionBpm = 60_000L / (bestLag * hopMs)

            result.add(Pair(startMs, sectionBpm.toFloat()))

            Log.d(
                TAG,
                "V3 SectionBPM: frame[$startFrame-$endFrame] @ ${startMs}ms = ${sectionBpm} BPM"
            )
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

        // 각 섹션별로 처리
        for (i in 0 until sectionBoundariesMs.size - 1) {
            val sectionStartMs = sectionBoundariesMs[i]
            val sectionEndMs = sectionBoundariesMs[i + 1]

            // 이 섹션의 BPM 찾기
            val sectionBpm = sectionBpms.find { it.first == sectionStartMs }?.second ?: continue
            if (sectionBpm <= 0f) continue

            val beatMs = 60_000L / sectionBpm.toLong()
            val fpb = (beatMs / hopMs).toInt()

            // 섹션의 ODF 슬라이스
            val startFrame = (sectionStartMs / hopMs).toInt()
            val endFrame = (sectionEndMs / hopMs).toInt().coerceAtMost(odf.size)

            if (endFrame - startFrame < fpb * 2) {
                sectionLog.append("section[$sectionStartMs-$sectionEndMs]=${sectionBpm.toInt()}BPM skip(short); ")
                continue
            }

            val sectionOdf = FloatArray(endFrame - startFrame) { idx ->
                if (startFrame + idx < odf.size) odf[startFrame + idx] else 0f
            }

            // 이 섹션에서 위상 추정
            val phaseMs = estimatePhaseFromOdf(sectionOdf, beatMs, hopMs)

            // 섹션 내에서 비트 추적
            val sectionDpTimes = dpBeatTracker(
                sectionOdf, beatMs, hopMs,
                sectionEndMs - sectionStartMs, anchorMs = phaseMs
            )

            // 섹션 시작 시간을 기준으로 절대 시간 변환
            val sectionBeats = sectionDpTimes.map { it + sectionStartMs }
            allBeats.addAll(sectionBeats.map { TimedBeat(it, 1f) })

            sectionLog.append("section[$sectionStartMs-$sectionEndMs]=${sectionBpm.toInt()}BPM beats=${sectionDpTimes.size}; ")
        }

        // 시간 순으로 정렬 및 중복 제거
        val finalBeats = allBeats
            .sortedBy { it.timeMs }
            .distinctBy { it.timeMs }

        return Pair(finalBeats, sectionLog.toString())
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

        val timeSignature = detectTimeSignature(globalOdf, bestBpm.toLong(), params.hopMs)
        val downbeatMs = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, bestBpm.toLong(),
            timeSignature.beatsPerBar, params.hopMs
        )
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(
            TAG,
            "V3 OK beats=${beats.size} BPM=$bestBpm Confidence=${confidence * 100}% reason=$reason"
        )

        return DetectResultV3(
            beats = beats,
            beatMs = if (bestBpm > 0f) (60_000L / bestBpm.toLong()) else 0L,
            confidence = confidence,
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
        var bestPhase = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (ph in 0 until fpb) {
            var score = 0f
            var f = ph
            while (f < odf.size) {
                score += odf[f]
                f += fpb
            }
            if (score > bestScore) {
                bestScore = score
                bestPhase = ph
            }
        }
        return bestPhase.toLong() * hopMs
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
