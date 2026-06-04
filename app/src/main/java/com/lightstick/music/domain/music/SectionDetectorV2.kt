package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SectionDetectorV2
 *
 * V1 대비 섹션 타입을 음악적으로 직관적인 7종으로 재정의.
 *
 *  INTRO   — 곡 시작부, 에너지 낮음
 *  VOCAL   — 보컬 위주 (미드/하이 비율 높음, onset 낮음)
 *  BEAT    — 비트/리듬 강조 (로우 높음, onset↑, periodicity↑)
 *  BUILD   — 빌드업 (에너지 상승 추세)
 *  CLIMAX  — 클라이맥스 (전곡 상위 25% 에너지 + onset 높음)
 *  BREAK   — 간주/브레이크 (에너지 낮고 onset 낮음)
 *  OUTRO   — 곡 끝부분, 에너지 감소 추세
 *
 * 처리 순서:
 * ① 슬라이딩 윈도우 → 에너지/밴드비율/onset/periodicity/에너지트렌드 계산
 * ② 전곡 에너지 통계 → CLIMAX 임계값 결정 (2-pass)
 * ③ 윈도우 분류 → 섹션 병합
 * ④ 짧은 섹션 병합 + 전체 커버 정규화
 * ⑤ 섹션 경계를 비트에 스냅
 * ⑥ 비트 배분
 */
class SectionDetectorV2 : SectionDetector {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val WINDOW_MS     = 2_000L
        private const val STRIDE_MS     = 500L
        private const val MIN_SECTION_MS = 4_000L

        private const val MIN_BEAT_MS    = 250L
        private const val MAX_BEAT_MS    = 900L
        private const val DEFAULT_BEAT_MS = 450L

        // 위치 기반 임계값
        private const val INTRO_RATIO  = 0.12f
        private const val OUTRO_RATIO  = 0.08f
        private const val INTRO_MAX_MS = 18_000L
        private const val OUTRO_MIN_MS = 8_000L

        // 에너지
        private const val LOW_ENERGY_TH   = 0.15f   // BREAK / OUTRO 판별
        private const val CLIMAX_PERCENTILE = 0.75f  // 상위 25% = CLIMAX 후보

        // 스펙트럼 — VOCAL
        private const val VOCAL_LOW_RATIO_MAX    = 0.42f  // 베이스 낮음
        private const val VOCAL_MID_HIGH_MIN     = 0.55f  // 미드+하이 합산 비율 하한
        private const val VOCAL_ONSET_MAX        = 0.22f  // onset 낮음

        // BEAT
        private const val BEAT_LOW_RATIO_MIN     = 0.42f
        private const val BEAT_ONSET_MIN         = 0.30f
        private const val BEAT_PERIODICITY_MIN   = 0.35f

        // BUILD (에너지 상승 기울기)
        private const val BUILD_TREND_MIN        = 0.12f  // 구간 내 에너지 기울기 하한

        // 변화 감지
        private const val STRONG_CHANGE_TH = 0.22f
        private const val MEDIUM_CHANGE_TH = 0.12f

        private const val ALIGN_SNAP_MS     = 500L
        private const val MIN_BEATS_IN_SECTION = 3
    }

    // ──────────────────────────────────────────────────────────────
    // Internal model
    // ──────────────────────────────────────────────────────────────

    private data class FeatureWindow(
        val startMs: Long,
        val endMs: Long,
        val energy: Float,
        val peakEnergy: Float,
        val lowRatio: Float,
        val midRatio: Float,
        val highRatio: Float,
        val onsetDensity: Float,
        val periodicity: Float,
        val energyTrend: Float,     // 양수 = 에너지 상승, 음수 = 하강
        val beatMsHint: Long,
        val sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VOCAL,
        val changeStrength: SectionDetector.ChangeStrength = SectionDetector.ChangeStrength.NONE
    )

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    override fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        durationMs: Long,
        hopMs: Long,
        highEnv: List<Float>,
        beatsPerBar: Int,
        downbeatMs: Long
    ): List<SectionDetector.Section> {
        if (fullEnv.isEmpty()) return emptyList()

        // ① 윈도우 피처 계산 (타입 미결정)
        val rawWindows = buildFeatureWindows(lowEnv, midEnv, fullEnv, highEnv, durationMs, hopMs)

        // ② 전곡 에너지 통계 → CLIMAX 임계값
        val climaxTh = percentile(rawWindows.map { it.energy }, CLIMAX_PERCENTILE)

        // ③ 타입 분류
        val classified = rawWindows.mapIndexed { i, w ->
            var prev = if (i > 0) rawWindows[i - 1] else null
            val type = classifyType(w, durationMs, climaxTh)
            val change = estimateChangeStrength(prev?.copy(sectionType = classifyType(prev, durationMs, climaxTh)), w.copy(sectionType = type))
            w.copy(sectionType = type, changeStrength = change)
        }

        // ④ 병합 + 정규화
        val rawSections = buildSectionsFromWindows(classified, durationMs)

        // ⑤ 비트 스냅
        val sortedBeatTimes = beats.map { it.timeMs }.toLongArray().also { it.sort() }
        val aligned = alignBoundariesToBeats(rawSections, sortedBeatTimes, durationMs)

        // ⑥ 비트 배분
        return distributeBeatsToSections(aligned, beats, beatMs, sortedBeatTimes)
    }

    // ──────────────────────────────────────────────────────────────
    // ① Feature windows
    // ──────────────────────────────────────────────────────────────

    private fun buildFeatureWindows(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        highEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<FeatureWindow> {
        val windows      = ArrayList<FeatureWindow>()
        val windowFrames = max(1, (WINDOW_MS / hopMs).toInt())
        val strideFrames = max(1, (STRIDE_MS / hopMs).toInt())
        val novelty      = computeNovelty(lowEnv, midEnv, fullEnv)

        var startIdx = 0
        while (startIdx < fullEnv.size) {
            val endIdx = min(fullEnv.size, startIdx + windowFrames)
            if (endIdx <= startIdx) break

            val startMs   = startIdx.toLong() * hopMs
            val endMs     = min(durationMs, endIdx.toLong() * hopMs)

            val fullSlice = fullEnv.subList(startIdx, endIdx)
            val lowSlice  = lowEnv.subList(startIdx, endIdx)
            val midSlice  = midEnv.subList(startIdx, endIdx)
            val highSlice = if (highEnv.size >= endIdx) highEnv.subList(startIdx, endIdx) else emptyList()
            val novSlice  = novelty.copyOfRange(startIdx, endIdx)

            val avgFull      = average(fullSlice).coerceAtLeast(1e-6f)
            val avgLow       = average(lowSlice)
            val avgMid       = average(midSlice)
            val avgHigh      = if (highSlice.isNotEmpty()) average(highSlice) else 0f
            val bandSum      = (avgLow + avgMid + avgHigh).coerceAtLeast(1e-6f)

            val beatMsHint   = estimateBeatMsByAutocorr(novSlice, hopMs, MIN_BEAT_MS, MAX_BEAT_MS)

            windows += FeatureWindow(
                startMs      = startMs,
                endMs        = endMs,
                energy       = avgFull,
                peakEnergy   = fullSlice.maxOrNull() ?: 0f,
                lowRatio     = avgLow  / bandSum,
                midRatio     = avgMid  / bandSum,
                highRatio    = avgHigh / bandSum,
                onsetDensity = densityAbove(novSlice, 0.12f),
                periodicity  = estimatePeriodicityStrength(novSlice, beatMsHint, hopMs),
                energyTrend  = computeEnergyTrend(fullSlice),
                beatMsHint   = beatMsHint
            )
            startIdx += strideFrames
        }
        return windows
    }

    // ──────────────────────────────────────────────────────────────
    // ② 타입 분류 (2-pass: percentile은 외부에서 주입)
    // ──────────────────────────────────────────────────────────────

    private fun classifyType(
        w: FeatureWindow,
        durationMs: Long,
        climaxTh: Float
    ): SectionDetector.SectionType {
        val introLimit = min(INTRO_MAX_MS, (durationMs * INTRO_RATIO).toLong())
        val outroLimit = max(OUTRO_MIN_MS, (durationMs * OUTRO_RATIO).toLong())

        // 위치 우선
        if (w.startMs < introLimit && w.energy < 0.55f) return SectionDetector.SectionType.INTRO
        if (w.endMs >= durationMs - outroLimit)         return SectionDetector.SectionType.OUTRO

        // CLIMAX: 전곡 상위 에너지 + onset 높음
        if (w.energy >= climaxTh && w.onsetDensity >= 0.35f) return SectionDetector.SectionType.CLIMAX

        // BUILD: 에너지 상승 추세 + 중간 이상 에너지
        if (w.energyTrend >= BUILD_TREND_MIN && w.energy >= 0.18f) return SectionDetector.SectionType.BUILD

        // BREAK: 에너지 낮고 조용함
        if (w.energy < LOW_ENERGY_TH && w.onsetDensity < 0.20f) return SectionDetector.SectionType.BREAK

        // VOCAL: 미드+하이 중심, 베이스 낮음, onset 낮음
        if (w.lowRatio < VOCAL_LOW_RATIO_MAX &&
            (w.midRatio + w.highRatio) >= VOCAL_MID_HIGH_MIN &&
            w.onsetDensity < VOCAL_ONSET_MAX) return SectionDetector.SectionType.VOCAL

        // BEAT: 베이스 강하고 규칙적인 비트
        if (w.lowRatio >= BEAT_LOW_RATIO_MIN &&
            w.onsetDensity >= BEAT_ONSET_MIN &&
            w.periodicity >= BEAT_PERIODICITY_MIN) return SectionDetector.SectionType.BEAT

        // 나머지: 특성에 따라 VOCAL or BEAT
        return if (w.midRatio + w.highRatio > w.lowRatio) SectionDetector.SectionType.VOCAL
               else SectionDetector.SectionType.BEAT
    }

    private fun estimateChangeStrength(
        prev: FeatureWindow?,
        cur: FeatureWindow
    ): SectionDetector.ChangeStrength {
        if (prev == null) return SectionDetector.ChangeStrength.STRONG
        val score =
            abs(cur.energy       - prev.energy)       * 0.35f +
            abs(cur.onsetDensity - prev.onsetDensity) * 0.30f +
            abs(cur.lowRatio     - prev.lowRatio)     * 0.10f +
            abs(cur.periodicity  - prev.periodicity)  * 0.10f +
            if (cur.sectionType != prev.sectionType) 0.25f else 0f

        return when {
            score >= STRONG_CHANGE_TH -> SectionDetector.ChangeStrength.STRONG
            score >= MEDIUM_CHANGE_TH -> SectionDetector.ChangeStrength.MEDIUM
            else                      -> SectionDetector.ChangeStrength.NONE
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ③ 병합 → 섹션
    // ──────────────────────────────────────────────────────────────

    private fun buildSectionsFromWindows(
        windows: List<FeatureWindow>,
        durationMs: Long
    ): List<FeatureWindow> {
        if (windows.isEmpty()) return emptyList()

        val merged = ArrayList<FeatureWindow>()
        var cur = windows.first()

        for (i in 1 until windows.size) {
            val next = windows[i]
            val split = next.changeStrength == SectionDetector.ChangeStrength.STRONG ||
                        next.sectionType != cur.sectionType

            if (split) {
                merged += cur.copy(endMs = next.startMs)
                cur = next.copy(startMs = next.startMs)
            } else {
                cur = cur.copy(
                    endMs        = next.endMs,
                    energy       = (cur.energy       + next.energy)       * 0.5f,
                    lowRatio     = (cur.lowRatio     + next.lowRatio)     * 0.5f,
                    midRatio     = (cur.midRatio     + next.midRatio)     * 0.5f,
                    highRatio    = (cur.highRatio    + next.highRatio)    * 0.5f,
                    onsetDensity = (cur.onsetDensity + next.onsetDensity) * 0.5f,
                    periodicity  = (cur.periodicity  + next.periodicity)  * 0.5f,
                    energyTrend  = (cur.energyTrend  + next.energyTrend)  * 0.5f,
                    peakEnergy   = max(cur.peakEnergy, next.peakEnergy),
                    beatMsHint   = normalizeBeatMs(cur.beatMsHint, next.beatMsHint)
                )
            }
        }
        merged += cur.copy(endMs = durationMs)

        return normalizeSections(merged, durationMs)
    }

    private fun normalizeSections(sections: List<FeatureWindow>, durationMs: Long): List<FeatureWindow> {
        if (sections.isEmpty()) return emptyList()
        val out = ArrayList<FeatureWindow>()

        for (s in sections.sortedBy { it.startMs }) {
            val fixedStart = if (out.isEmpty()) 0L else max(out.last().endMs, s.startMs)
            val fixedEnd   = min(durationMs, max(fixedStart + 1L, s.endMs))
            if (fixedEnd <= fixedStart) continue

            val fixed = s.copy(startMs = fixedStart, endMs = fixedEnd)

            if (out.isNotEmpty()) {
                val prev = out.last()
                if (fixed.endMs - fixed.startMs < MIN_SECTION_MS &&
                    prev.sectionType == fixed.sectionType) {
                    out[out.lastIndex] = prev.copy(
                        endMs        = fixed.endMs,
                        energy       = (prev.energy       + fixed.energy)       * 0.5f,
                        lowRatio     = (prev.lowRatio     + fixed.lowRatio)     * 0.5f,
                        midRatio     = (prev.midRatio     + fixed.midRatio)     * 0.5f,
                        highRatio    = (prev.highRatio    + fixed.highRatio)    * 0.5f,
                        onsetDensity = (prev.onsetDensity + fixed.onsetDensity) * 0.5f,
                        periodicity  = (prev.periodicity  + fixed.periodicity)  * 0.5f,
                        peakEnergy   = max(prev.peakEnergy, fixed.peakEnergy),
                        beatMsHint   = normalizeBeatMs(prev.beatMsHint, fixed.beatMsHint)
                    )
                    continue
                }
            }
            out += fixed
        }

        if (out.isNotEmpty() && out.last().endMs < durationMs) {
            out[out.lastIndex] = out.last().copy(endMs = durationMs)
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────
    // ④ 비트 경계 스냅
    // ──────────────────────────────────────────────────────────────

    private fun alignBoundariesToBeats(
        sections: List<FeatureWindow>,
        sortedBeatTimes: LongArray,
        durationMs: Long
    ): List<FeatureWindow> {
        if (sections.size <= 1 || sortedBeatTimes.isEmpty()) return sections

        val result  = ArrayList<FeatureWindow>(sections.size)
        var prevEnd = 0L

        for (i in sections.indices) {
            val s      = sections[i]
            val isLast = i == sections.lastIndex
            val snappedEnd = if (isLast) durationMs
                             else snapToNearestBeat(s.endMs, sortedBeatTimes, ALIGN_SNAP_MS)

            val start = prevEnd
            val end   = max(start + 1L, snappedEnd)
            result += s.copy(startMs = start, endMs = end)
            prevEnd = end
        }
        return result
    }

    private fun snapToNearestBeat(targetMs: Long, sortedBeatTimes: LongArray, snapMs: Long): Long {
        if (sortedBeatTimes.isEmpty()) return targetMs
        var lo = 0; var hi = sortedBeatTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedBeatTimes[mid] < targetMs) lo = mid + 1 else hi = mid
        }
        var best = targetMs; var bestDist = Long.MAX_VALUE
        for (idx in max(0, lo - 1)..min(sortedBeatTimes.lastIndex, lo + 1)) {
            val d = abs(sortedBeatTimes[idx] - targetMs)
            if (d <= snapMs && d < bestDist) { bestDist = d; best = sortedBeatTimes[idx] }
        }
        return best
    }

    // ──────────────────────────────────────────────────────────────
    // ⑤ 비트 배분
    // ──────────────────────────────────────────────────────────────

    private fun distributeBeatsToSections(
        sections: List<FeatureWindow>,
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        globalBeatMs: Long,
        sortedBeatTimes: LongArray
    ): List<SectionDetector.Section> {
        val sortedBeats = beats.sortedBy { it.timeMs }
        val result      = ArrayList<SectionDetector.Section>(sections.size)

        for ((idx, s) in sections.withIndex()) {
            val sBeats = sortedBeats.filter { it.timeMs >= s.startMs && it.timeMs < s.endMs }

            val beatTimesMs: LongArray
            val confidence: Float

            if (sBeats.size >= MIN_BEATS_IN_SECTION) {
                beatTimesMs = LongArray(sBeats.size) { sBeats[it].timeMs }
                confidence  = sBeats.map { it.confidence }.average().toFloat()
            } else {
                beatTimesMs = buildGridBeats(s.startMs, s.endMs, globalBeatMs, sortedBeatTimes)
                confidence  = 0.20f
            }

            Log.d(TAG, "SectionDetectorV2[$idx] ${s.startMs}~${s.endMs} " +
                "type=${s.sectionType} beats=${beatTimesMs.size} " +
                "energy=${"%.2f".format(s.energy)} trend=${"%.2f".format(s.energyTrend)} " +
                "low=${"%.2f".format(s.lowRatio)} onset=${"%.2f".format(s.onsetDensity)}")

            result += SectionDetector.Section(
                startMs        = s.startMs,
                endMs          = s.endMs,
                type           = s.sectionType,
                changeStrength = s.changeStrength,
                beatTimesMs    = beatTimesMs,
                beatMs         = globalBeatMs,
                beatConfidence = confidence,
                energy         = s.energy,
                peakEnergy     = s.peakEnergy,
                lowRatio       = s.lowRatio,
                midRatio       = s.midRatio,
                highRatio      = s.highRatio,
                onsetDensity   = s.onsetDensity,
                periodicity    = s.periodicity
            )
        }
        return result
    }

    private fun buildGridBeats(startMs: Long, endMs: Long, beatMs: Long, sortedBeatTimes: LongArray): LongArray {
        if (beatMs <= 0) return LongArray(0)
        val phase = if (sortedBeatTimes.isEmpty()) 0L else sortedBeatTimes[0] % beatMs
        val out   = ArrayList<Long>()
        var t = phase; while (t < startMs) t += beatMs
        while (t < endMs) { out += t; t += beatMs }
        return out.toLongArray()
    }

    // ──────────────────────────────────────────────────────────────
    // 신호 분석 헬퍼
    // ──────────────────────────────────────────────────────────────

    /** novelty = 저역·중역·전역 양의 차분 가중합 */
    private fun computeNovelty(lowEnv: List<Float>, midEnv: List<Float>, fullEnv: List<Float>): FloatArray {
        val n = FloatArray(fullEnv.size)
        for (i in 1 until fullEnv.size) {
            n[i] = max(0f, lowEnv[i]  - lowEnv[i - 1])  * 0.45f +
                   max(0f, midEnv[i]  - midEnv[i - 1])  * 0.35f +
                   max(0f, fullEnv[i] - fullEnv[i - 1]) * 0.20f
        }
        normalize01InPlace(n)
        smoothInPlace(n, 2)
        return n
    }

    /**
     * 구간 내 에너지 기울기 (선형 회귀 기울기 근사).
     * 양수 = 점점 강해짐, 음수 = 점점 약해짐.
     */
    private fun computeEnergyTrend(frames: List<Float>): Float {
        if (frames.size < 4) return 0f
        val n  = frames.size.toFloat()
        val mx = frames.indices.sumOf { it.toDouble() }.toFloat() / n  // mean x
        val my = frames.average().toFloat()
        var num = 0f; var den = 0f
        for (i in frames.indices) {
            val dx = i - mx
            num += dx * (frames[i] - my)
            den += dx * dx
        }
        return if (den < 1e-8f) 0f else (num / den).coerceIn(-1f, 1f)
    }

    private fun estimateBeatMsByAutocorr(novelty: FloatArray, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): Long {
        if (novelty.size < 8) return DEFAULT_BEAT_MS
        val minLag  = max(1, (minBeatMs / hopMs).toInt())
        val maxLag  = max(minLag + 1, min((maxBeatMs / hopMs).toInt(), novelty.size - 1))
        var bestLag = (DEFAULT_BEAT_MS / hopMs).toInt().coerceIn(minLag, maxLag)
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var s = 0.0; var i = lag
            while (i < novelty.size) { s += novelty[i] * novelty[i - lag]; i++ }
            if (s > bestScore) { bestScore = s; bestLag = lag }
        }
        return (bestLag.toLong() * hopMs).coerceIn(minBeatMs, maxBeatMs)
    }

    private fun estimatePeriodicityStrength(novelty: FloatArray, beatMs: Long, hopMs: Long): Float {
        if (novelty.isEmpty()) return 0f
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag >= novelty.size) return 0f
        var ac = 0f; var raw = 0f
        for (i in lag until novelty.size) ac += novelty[i] * novelty[i - lag]
        for (v in novelty) raw += v * v
        return if (raw <= 1e-6f) 0f else (ac / raw).coerceIn(0f, 1f)
    }

    private fun normalizeBeatMs(a: Long, b: Long): Long {
        val beatMs = ((a + b) / 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val g      = a.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val ratio  = beatMs.toFloat() / g.toFloat()
        return when {
            ratio in 0.45f..0.65f -> (beatMs * 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            ratio in 1.70f..2.20f -> (beatMs / 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            else                  -> beatMs
        }
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx    = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun average(src: List<Float>): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f; for (v in src) sum += v
        return sum / src.size
    }

    private fun densityAbove(src: FloatArray, th: Float): Float {
        if (src.isEmpty()) return 0f
        var count = 0; for (v in src) if (v >= th) count++
        return count.toFloat() / src.size
    }

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f; for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.size < win + 2) return
        val copy = x.copyOf()
        for (i in x.indices) {
            var s = 0f; var c = 0
            for (j in (i - win).coerceAtLeast(0)..(i + win).coerceAtMost(x.lastIndex)) { s += copy[j]; c++ }
            x[i] = s / max(1, c)
        }
    }
}
