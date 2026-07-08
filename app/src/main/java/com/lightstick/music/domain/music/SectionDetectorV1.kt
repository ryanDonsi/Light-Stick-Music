package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SectionDetectorV1 — SectionDetectorV0 속도 개선 버전
 *
 * V0 대비 주요 변경:
 *  - estimateBeatMsByAutocorr() 제거 → 전역 beatMs 직접 사용 (O(N²) → O(1))
 *  - estimatePeriodicityStrength() 전역 1회 계산 (윈도우마다 반복 → 전역 한 번)
 *  - subList() 슬라이싱 → 인덱스 직접 접근으로 allocation 최소화
 *  - STRIDE_MS 1000ms → 2000ms (처리 윈도우 수 절반)
 *  - novelty FloatArray를 IntArray 인덱스로 직접 참조
 */
class SectionDetectorV1 : SectionDetector {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val WINDOW_MS      = 2_000L
        private const val STRIDE_MS      = 2_000L   // V0=1000ms → 2배 스트라이드
        private const val MIN_SECTION_MS  = 4_000L
        private const val COMPACT_MIN_MS  = 10_000L  // 10s 미만 파편 흡수

        private const val SECTION_STRONG_CHANGE_TH = 0.24f
        private const val SECTION_MEDIUM_CHANGE_TH = 0.14f

        private const val ALIGN_SNAP_MS = 500L

        private const val CLIMAX_WINDOW_HALF_MS = 2_000L
        private const val CLIMAX_MIN_CV         = 0.35f
        private const val CLIMAX_MIN_PEAK_RATIO = 2.0f
    }

    private data class FeatureWindow(
        val startMs: Long,
        val endMs: Long,
        val energy: Float,
        val lowRatio: Float,
        val midRatio: Float,
        val highRatio: Float,
        val onsetDensity: Float,
        val periodicity: Float,
        val peakEnergy: Float,
        val activity: Float,
        val score: Float,
        val sectionType: SectionDetector.SectionType,
        val changeStrength: SectionDetector.ChangeStrength
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
    ): List<SectionDetector.AnnotatedBeat> {
        if (fullEnv.isEmpty()) return emptyList()

        // FloatArray로 변환 (인덱스 직접 접근용)
        val low  = if (lowEnv  is FloatList) lowEnv.array  else FloatArray(lowEnv.size)  { lowEnv[it] }
        val mid  = if (midEnv  is FloatList) midEnv.array  else FloatArray(midEnv.size)  { midEnv[it] }
        val full = if (fullEnv is FloatList) fullEnv.array else FloatArray(fullEnv.size) { fullEnv[it] }
        val high = if (highEnv.isNotEmpty()) FloatArray(highEnv.size) { highEnv[it] } else FloatArray(0)

        // 전역 periodicity 1회 계산 (beatMs 기반)
        val novelty = computeNovelty(low, mid, full)
        val globalPeriodicity = estimatePeriodicityGlobal(novelty, beatMs, hopMs)

        val windows = buildFeatureWindows(low, mid, full, high, novelty, globalPeriodicity, durationMs, hopMs, beatMs)

        val frameScores = windows.map { it.score }
        val lowTh  = if (frameScores.isNotEmpty()) percentile(frameScores, 0.35f) else 0f
        val highTh = if (frameScores.isNotEmpty()) percentile(frameScores, 0.70f) else 1f
        Log.d(TAG, "SectionDetectorV1 thresholds: lowTh=${"%.3f".format(lowTh)} highTh=${"%.3f".format(highTh)}")

        val rawSections = buildSectionsFromWindows(windows, durationMs, lowTh, highTh)

        // 실제 beat 타임스탬프로 경계 정렬 (synthetic bar grid 대신)
        // → section.startMs가 항상 실제 beat 위치에 snap됨
        val beatBoundaries = beats.map { it.timeMs }.sorted().toLongArray()
        val alignedSections = alignBoundariesToBars(rawSections, beatBoundaries, durationMs)
        val labeledSections = applyIntroOutro(alignedSections)

        val sections = toSections(labeledSections)
        val climaxMoments = detectClimaxMoments(full, durationMs, hopMs, beatMs)
        return annotateBeats(beats, sections, climaxMoments)
    }

    // 입력 비트에 sectionType 태깅 후 반환 (순서 유지, climax peak ±4s 비트는 CLIMAX로 덮어씀)
    private fun annotateBeats(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        sections: List<SectionDetector.Section>,
        climaxMoments: List<Long>
    ): List<SectionDetector.AnnotatedBeat> = beats.map { beat ->
        val sectionType = sections.find { beat.timeMs >= it.startMs && beat.timeMs < it.endMs }?.type
            ?: SectionDetector.SectionType.VERSE
        // CLIMAX는 CHORUS 구간 안에서만 적용 — BRIDGE/VERSE 초반 에너지 스파이크 제외
        val type = if (sectionType == SectionDetector.SectionType.CHORUS &&
                       climaxMoments.any { abs(it - beat.timeMs) <= CLIMAX_WINDOW_HALF_MS })
            SectionDetector.SectionType.CLIMAX else sectionType
        SectionDetector.AnnotatedBeat(beat.timeMs, beat.confidence, type)
    }

    // ──────────────────────────────────────────────────────────────
    // ① Feature windows — 인덱스 직접 접근, allocation 최소화
    // ──────────────────────────────────────────────────────────────

    private fun buildFeatureWindows(
        low: FloatArray, mid: FloatArray, full: FloatArray, high: FloatArray,
        novelty: FloatArray,
        globalPeriodicity: Float,
        durationMs: Long, hopMs: Long, beatMs: Long
    ): List<FeatureWindow> {
        val n = full.size
        val windowFrames = max(1, (WINDOW_MS / hopMs).toInt())
        val strideFrames = max(1, (STRIDE_MS / hopMs).toInt())

        val windows = ArrayList<FeatureWindow>(n / strideFrames + 2)
        var prev: FeatureWindow? = null
        var startIdx = 0

        while (startIdx < n) {
            val endIdx = min(n, startIdx + windowFrames)
            if (endIdx <= startIdx) break

            val count = endIdx - startIdx
            val fCount = count.toFloat()

            // 단일 패스: 누적합으로 모든 특성 계산
            var sumFull = 0f; var sumLow = 0f; var sumMid = 0f; var sumHigh = 0f
            var sumNov  = 0f; var novAbove = 0; var peakFull = 0f
            var sumAct  = 0f; var prevV = full[startIdx]

            for (i in startIdx until endIdx) {
                val f = full[i]; val l = low[i]; val m = mid[i]
                sumFull += f; sumLow += l; sumMid += m
                if (high.size > i) sumHigh += high[i]
                sumNov += novelty[i]
                if (novelty[i] >= 0.12f) novAbove++
                if (f > peakFull) peakFull = f
                sumAct += abs(f - prevV); prevV = f
            }

            val avgFull   = sumFull / fCount
            val denom     = max(0.0001f, avgFull)
            val energy    = avgFull
            val lowRatio  = (sumLow  / fCount) / denom
            val midRatio  = (sumMid  / fCount) / denom
            val highRatio = if (high.isNotEmpty()) (sumHigh / fCount) / denom else 0f
            val onsetDensity = novAbove.toFloat() / fCount
            val activity  = sumAct / fCount

            // periodicity: 전역값 사용 (per-window autocorr 제거)
            val periodicity = globalPeriodicity

            val onsetBonus = onsetDensity * 0.12f
            val lowPenalty = (lowRatio * 0.08f).coerceIn(0f, 0.08f)
            val score = (energy * 0.60f + activity * 0.20f + peakFull * 0.10f + onsetBonus - lowPenalty)
                .coerceIn(0f, 1f)

            val draft = FeatureWindow(
                startMs      = startIdx.toLong() * hopMs,
                endMs        = min(durationMs, endIdx.toLong() * hopMs),
                energy       = energy,
                lowRatio     = lowRatio,
                midRatio     = midRatio,
                highRatio    = highRatio,
                onsetDensity = onsetDensity,
                periodicity  = periodicity,
                peakEnergy   = peakFull,
                activity     = activity,
                score        = score,
                sectionType  = SectionDetector.SectionType.VERSE,
                changeStrength = SectionDetector.ChangeStrength.NONE
            )

            val change = estimateChangeStrength(prev, draft)
            val win = draft.copy(changeStrength = change)
            windows += win
            prev = win
            startIdx += strideFrames
        }

        return windows
    }

    // ──────────────────────────────────────────────────────────────
    // ② Classification & merge
    // ──────────────────────────────────────────────────────────────

    private fun classifyType(score: Float, lowTh: Float, highTh: Float): SectionDetector.SectionType {
        val bridgeTh = lowTh * 0.85f
        // BRIDGE보다도 훨씬 조용한 구간(브레이크다운) — bridgeTh의 절반 이하.
        // 1차 추정치라 실곡 튜닝이 더 필요할 수 있다.
        val breakTh  = lowTh * 0.45f
        return when {
            score >= highTh   -> SectionDetector.SectionType.CHORUS
            score <= breakTh  -> SectionDetector.SectionType.BREAK
            score <= bridgeTh -> SectionDetector.SectionType.BRIDGE
            else              -> SectionDetector.SectionType.VERSE
        }
    }

    // 첫/마지막 구간은 위치상 INTRO/OUTRO가 기본값이지만, 이미 CHORUS로 명백하면
    // (코러스로 시작/끝나는 곡) 위치보다 내용을 우선해 CHORUS를 유지한다.
    // (Python GT의 demucs+msaf 경로와 동일한 철학 — beat_accuracy_checker.py 참고)
    private fun applyIntroOutro(sections: List<FeatureWindow>): List<FeatureWindow> {
        if (sections.size < 2) return sections
        val out = sections.toMutableList()
        if (out.first().sectionType != SectionDetector.SectionType.CHORUS) {
            out[0] = out.first().copy(sectionType = SectionDetector.SectionType.INTRO)
        }
        if (out.last().sectionType != SectionDetector.SectionType.CHORUS) {
            out[out.lastIndex] = out.last().copy(sectionType = SectionDetector.SectionType.OUTRO)
        }
        return out
    }

    private fun estimateChangeStrength(prev: FeatureWindow?, cur: FeatureWindow): SectionDetector.ChangeStrength {
        if (prev == null) return SectionDetector.ChangeStrength.STRONG
        val score =
            abs(cur.energy       - prev.energy)       * 0.35f +
            abs(cur.onsetDensity - prev.onsetDensity) * 0.35f +
            abs(cur.lowRatio     - prev.lowRatio)     * 0.10f +
            abs(cur.periodicity  - prev.periodicity)  * 0.10f +
            if (cur.sectionType != prev.sectionType) 0.20f else 0f
        return when {
            score >= SECTION_STRONG_CHANGE_TH -> SectionDetector.ChangeStrength.STRONG
            score >= SECTION_MEDIUM_CHANGE_TH -> SectionDetector.ChangeStrength.MEDIUM
            else                              -> SectionDetector.ChangeStrength.NONE
        }
    }

    private fun buildSectionsFromWindows(
        windows: List<FeatureWindow>, durationMs: Long, lowTh: Float, highTh: Float
    ): List<FeatureWindow> {
        if (windows.isEmpty()) return emptyList()
        val merged = ArrayList<FeatureWindow>()
        var cur = windows.first().copy(sectionType = classifyType(windows.first().score, lowTh, highTh))

        for (i in 1 until windows.size) {
            val next = windows[i].copy(sectionType = classifyType(windows[i].score, lowTh, highTh))
            val shouldSplit = next.changeStrength == SectionDetector.ChangeStrength.STRONG ||
                              next.sectionType != cur.sectionType
            if (shouldSplit) {
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
                    peakEnergy   = max(cur.peakEnergy, next.peakEnergy),
                    score        = (cur.score        + next.score)        * 0.5f,
                    activity     = (cur.activity     + next.activity)     * 0.5f
                )
            }
        }
        merged += cur.copy(endMs = durationMs)
        val normalized = normalizeSections(merged, durationMs)
        return compactSections(normalized)
    }

    private fun normalizeSections(sections: List<FeatureWindow>, durationMs: Long): List<FeatureWindow> {
        if (sections.isEmpty()) return emptyList()
        val sorted = sections.sortedBy { it.startMs }
        val out = ArrayList<FeatureWindow>()
        for (s in sorted) {
            val fixedStart = if (out.isEmpty()) 0L else max(out.last().endMs, s.startMs)
            val fixedEnd   = min(durationMs, max(fixedStart + 1L, s.endMs))
            if (fixedEnd <= fixedStart) continue
            val fixed = s.copy(startMs = fixedStart, endMs = fixedEnd)
            if (out.isNotEmpty()) {
                val prev = out.last()
                if (fixed.endMs - fixed.startMs < MIN_SECTION_MS && prev.sectionType == fixed.sectionType) {
                    out[out.lastIndex] = prev.copy(
                        endMs        = fixed.endMs,
                        energy       = (prev.energy       + fixed.energy)       * 0.5f,
                        lowRatio     = (prev.lowRatio     + fixed.lowRatio)     * 0.5f,
                        midRatio     = (prev.midRatio     + fixed.midRatio)     * 0.5f,
                        highRatio    = (prev.highRatio    + fixed.highRatio)    * 0.5f,
                        onsetDensity = (prev.onsetDensity + fixed.onsetDensity) * 0.5f,
                        periodicity  = (prev.periodicity  + fixed.periodicity)  * 0.5f,
                        peakEnergy   = max(prev.peakEnergy, fixed.peakEnergy),
                        score        = (prev.score        + fixed.score)        * 0.5f,
                        activity     = (prev.activity     + fixed.activity)     * 0.5f
                    )
                    continue
                }
            }
            out += fixed
        }
        if (out.isNotEmpty() && out.last().endMs < durationMs)
            out[out.lastIndex] = out.last().copy(endMs = durationMs)
        return out
    }

    // COMPACT_MIN_MS 미만 파편을 인접한 긴 구간으로 반복 흡수
    private fun compactSections(input: List<FeatureWindow>): List<FeatureWindow> {
        if (input.size <= 1) return input
        val list = input.toMutableList()
        var changed = true
        while (changed && list.size > 1) {
            changed = false
            var shortIdx = -1; var shortDur = Long.MAX_VALUE
            for (i in list.indices) {
                val d = list[i].endMs - list[i].startMs
                if (d < COMPACT_MIN_MS && d < shortDur) { shortDur = d; shortIdx = i }
            }
            if (shortIdx < 0) break
            val s = list[shortIdx]
            val prevOk = shortIdx > 0
            val nextOk = shortIdx < list.lastIndex
            val absorberIdx = when {
                !prevOk  -> shortIdx + 1
                !nextOk  -> shortIdx - 1
                list[shortIdx - 1].sectionType == s.sectionType -> shortIdx - 1
                list[shortIdx + 1].sectionType == s.sectionType -> shortIdx + 1
                else     -> {
                    val pd = list[shortIdx - 1].endMs - list[shortIdx - 1].startMs
                    val nd = list[shortIdx + 1].endMs - list[shortIdx + 1].startMs
                    if (pd >= nd) shortIdx - 1 else shortIdx + 1
                }
            }
            if (absorberIdx < shortIdx) {
                list[absorberIdx] = list[absorberIdx].copy(
                    endMs        = s.endMs,
                    energy       = (list[absorberIdx].energy       + s.energy)       * 0.5f,
                    lowRatio     = (list[absorberIdx].lowRatio     + s.lowRatio)     * 0.5f,
                    midRatio     = (list[absorberIdx].midRatio     + s.midRatio)     * 0.5f,
                    highRatio    = (list[absorberIdx].highRatio    + s.highRatio)    * 0.5f,
                    onsetDensity = (list[absorberIdx].onsetDensity + s.onsetDensity) * 0.5f,
                    periodicity  = (list[absorberIdx].periodicity  + s.periodicity)  * 0.5f,
                    peakEnergy   = max(list[absorberIdx].peakEnergy, s.peakEnergy),
                    score        = (list[absorberIdx].score        + s.score)        * 0.5f,
                    activity     = (list[absorberIdx].activity     + s.activity)     * 0.5f
                )
            } else {
                list[absorberIdx] = list[absorberIdx].copy(
                    startMs      = s.startMs,
                    energy       = (s.energy       + list[absorberIdx].energy)       * 0.5f,
                    lowRatio     = (s.lowRatio     + list[absorberIdx].lowRatio)     * 0.5f,
                    midRatio     = (s.midRatio     + list[absorberIdx].midRatio)     * 0.5f,
                    highRatio    = (s.highRatio    + list[absorberIdx].highRatio)    * 0.5f,
                    onsetDensity = (s.onsetDensity + list[absorberIdx].onsetDensity) * 0.5f,
                    periodicity  = (s.periodicity  + list[absorberIdx].periodicity)  * 0.5f,
                    peakEnergy   = max(s.peakEnergy, list[absorberIdx].peakEnergy),
                    score        = (s.score        + list[absorberIdx].score)        * 0.5f,
                    activity     = (s.activity     + list[absorberIdx].activity)     * 0.5f
                )
            }
            list.removeAt(shortIdx)
            changed = true
        }
        return list
    }

    // ──────────────────────────────────────────────────────────────
    // ③ Align to bar boundaries
    // ──────────────────────────────────────────────────────────────

    private fun alignBoundariesToBars(
        sections: List<FeatureWindow>, barBoundaries: LongArray, durationMs: Long
    ): List<FeatureWindow> {
        if (sections.size <= 1) return sections
        val result = ArrayList<FeatureWindow>(sections.size)
        var prevEnd = 0L
        for (i in sections.indices) {
            val s = sections[i]
            val isLast = i == sections.lastIndex
            val snappedEnd = if (isLast) durationMs
                             else snapToNearestBar(s.endMs, barBoundaries, ALIGN_SNAP_MS)
            val end = max(prevEnd + 1L, snappedEnd)
            result += s.copy(startMs = prevEnd, endMs = end)
            prevEnd = end
        }
        return result
    }

    private fun generateBarBoundaries(downbeatMs: Long, durationMs: Long, barMs: Long): LongArray {
        val list = ArrayList<Long>()
        var t = downbeatMs
        while (t <= durationMs) { if (t >= 0L) list += t; t += barMs }
        return list.toLongArray()
    }

    private fun snapToNearestBar(targetMs: Long, barBoundaries: LongArray, snapWindowMs: Long): Long {
        if (barBoundaries.isEmpty()) return targetMs
        var lo = 0; var hi = barBoundaries.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (barBoundaries[m] < targetMs) lo = m + 1 else hi = m }
        var best = targetMs; var bestDist = Long.MAX_VALUE
        for (idx in max(0, lo - 1)..min(barBoundaries.lastIndex, lo + 1)) {
            val d = abs(barBoundaries[idx] - targetMs)
            if (d <= snapWindowMs && d < bestDist) { bestDist = d; best = barBoundaries[idx] }
        }
        return best
    }

    // ──────────────────────────────────────────────────────────────
    // ④ FeatureWindow → Section
    // ──────────────────────────────────────────────────────────────

    private fun toSections(windows: List<FeatureWindow>): List<SectionDetector.Section> =
        windows.mapIndexed { idx, s ->
            Log.d(TAG, "SectionDetectorV1[$idx] ${s.startMs}~${s.endMs} type=${s.sectionType} change=${s.changeStrength}")
            SectionDetector.Section(
                startMs        = s.startMs,    endMs          = s.endMs,
                type           = s.sectionType, changeStrength = s.changeStrength,
                energy         = s.energy,     peakEnergy     = s.peakEnergy,
                lowRatio       = s.lowRatio,   midRatio       = s.midRatio,
                highRatio      = s.highRatio,  onsetDensity   = s.onsetDensity,
                periodicity    = s.periodicity
            )
        }

    // ──────────────────────────────────────────────────────────────
    // Signal helpers
    // ──────────────────────────────────────────────────────────────

    private fun computeNovelty(low: FloatArray, mid: FloatArray, full: FloatArray): FloatArray {
        val n = FloatArray(full.size)
        for (i in 1 until full.size) {
            val dLow  = max(0f, low[i]  - low[i - 1])
            val dMid  = max(0f, mid[i]  - mid[i - 1])
            val dFull = max(0f, full[i] - full[i - 1])
            n[i] = dLow * 0.45f + dMid * 0.35f + dFull * 0.20f
        }
        normalize01InPlace(n)
        smoothInPlace(n, 2)
        return n
    }

    // 전역 periodicity 1회만 계산 (윈도우마다 반복 제거)
    private fun estimatePeriodicityGlobal(novelty: FloatArray, beatMs: Long, hopMs: Long): Float {
        if (novelty.isEmpty()) return 0f
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag >= novelty.size) return 0f
        var ac = 0f; var raw = 0f
        for (i in lag until novelty.size) ac += novelty[i] * novelty[i - lag]
        for (v in novelty) raw += v * v
        return if (raw <= 1e-6f) 0f else (ac / raw).coerceIn(0f, 1f)
    }

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.size < win + 2) return
        val copy = x.copyOf()
        for (i in x.indices) {
            var s = 0f; var c = 0
            for (j in max(0, i - win)..min(x.lastIndex, i + win)) { s += copy[j]; c++ }
            x[i] = s / max(1, c)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Climax detection
    // ──────────────────────────────────────────────────────────────

    private fun detectClimaxMoments(
        full: FloatArray, durationMs: Long, hopMs: Long, beatMs: Long
    ): List<Long> {
        if (full.size < 8) return emptyList()

        val scoreArray = FloatArray(full.size)
        for (i in 2 until full.size - 2) {
            val e = full[i]
            val localAvg = (full[i-2] + full[i-1] + full[i+1] + full[i+2]) * 0.25f
            scoreArray[i] = e * 0.50f + max(0f, e - full[i-1]) * 0.30f + max(0f, e - localAvg) * 0.20f
        }

        val scoreList = scoreArray.filter { it > 0f }
        if (scoreList.isEmpty()) return emptyList()

        val envMean  = scoreList.average().toFloat()
        val envStd   = sqrt(scoreList.fold(0f) { acc, v -> acc + (v - envMean) * (v - envMean) } / scoreList.size)
        val cv       = if (envMean > 0f) envStd / envMean else 0f
        val peakRatio = if (envMean > 0f) scoreList.max() / envMean else 0f

        if (cv < CLIMAX_MIN_CV || peakRatio < CLIMAX_MIN_PEAK_RATIO) return emptyList()

        val p90 = scoreList.sorted().let { it[(it.lastIndex * 0.90f).toInt().coerceIn(0, it.lastIndex)] }
        val minGapMs = max(800L, beatMs * 4L)
        val selected = ArrayList<Long>()

        val climaxIntroLimit = (durationMs * 0.30f).toLong()   // 곡 앞 30% 제외
        for (i in 2 until scoreArray.size - 2) {
            val sc = scoreArray[i]; if (sc <= 0f) continue
            val tMs = i.toLong() * hopMs
            if (tMs < climaxIntroLimit) continue                // 앞 30% 스킵
            if (sc >= scoreArray[i-1] && sc >= scoreArray[i-2] && sc >= scoreArray[i+1] && sc >= scoreArray[i+2] &&
                sc >= p90 * 1.18f && sc >= envMean + envStd * 1.30f) {
                if (selected.none { abs(it - tMs) < minGapMs }) {
                    selected += tMs
                    if (selected.size >= 3) break
                }
            }
        }

        val result = selected.sorted().map { it.coerceIn(0L, durationMs) }
        Log.d(TAG, "SectionDetectorV1 climax moments=${result.joinToString()}")
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    // List<Float>가 FloatArray 래퍼인 경우 최적화용 (현재 미사용, 향후 확장)
    private class FloatList(val array: FloatArray) : AbstractList<Float>() {
        override val size get() = array.size
        override fun get(index: Int) = array[index]
    }
}
