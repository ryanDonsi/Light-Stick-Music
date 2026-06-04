package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SectionDetectorV1
 *
 * BeatDetector가 이미 계산한 beats 목록을 받아 섹션 경계를 분석한다.
 *
 * 처리 순서:
 * ① 2000ms 슬라이딩 윈도우 → 에너지/저역비/어택밀도/주기성 계산
 * ② 윈도우 연속성 평가 → 변화점에서 섹션 분할
 * ③ 짧은 섹션 병합 + 전체 길이 커버 정규화
 * ④ 섹션 경계를 가장 가까운 비트 위치로 스냅 (±500ms 이내)
 * ⑤ 비트 배분 — 각 섹션 구간에 속한 TimedBeat 할당; 부족 시 전역 그리드로 채움
 */
class SectionDetectorV1 : SectionDetector {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val WINDOW_MS = 2_000L
        private const val STRIDE_MS = 1_000L
        private const val MIN_SECTION_MS = 4_000L

        private const val MIN_BEAT_MS = 250L
        private const val MAX_BEAT_MS = 900L
        private const val DEFAULT_BEAT_MS = 450L

        private const val SECTION_STRONG_CHANGE_TH = 0.24f
        private const val SECTION_MEDIUM_CHANGE_TH = 0.14f

        private const val ALIGN_SNAP_MS = 500L
        private const val MIN_BEATS_IN_SECTION = 3

        // ── Climax detection (V8 로직) ───────────────────────────────
        private const val CLIMAX_WINDOW_HALF_MS = 4_000L
        private const val CLIMAX_MIN_CV         = 0.35f
        private const val CLIMAX_MIN_PEAK_RATIO = 2.0f
    }

    // ──────────────────────────────────────────────────────────────
    // Internal model
    // ──────────────────────────────────────────────────────────────

    private data class FeatureWindow(
        val startMs: Long,
        val endMs: Long,
        val energy: Float,
        val lowRatio: Float,
        val onsetDensity: Float,
        val periodicity: Float,
        val beatMsHint: Long,
        val sectionType: SectionDetector.SectionType,
        val changeStrength: SectionDetector.ChangeStrength,
        val peakEnergy: Float = 0f,
        val midRatio: Float   = 0f,
        val highRatio: Float  = 0f,
        val score: Float      = 0f,
        val activity: Float   = 0f
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

        val windows = buildFeatureWindows(lowEnv, midEnv, fullEnv, highEnv, durationMs, hopMs)

        // V8 방식: score 기반 임계값 계산
        val frameScores = windows.map { it.score }
        val lowTh  = if (frameScores.isNotEmpty()) percentile(frameScores, 0.35f) else 0f
        val highTh = if (frameScores.isNotEmpty()) percentile(frameScores, 0.70f) else 1f
        Log.d(TAG, "SectionDetectorV1 thresholds: lowTh=${"%.3f".format(lowTh)} highTh=${"%.3f".format(highTh)}")

        val rawSections = buildSectionsFromWindows(windows, durationMs, lowTh, highTh)

        val sortedBeatTimes = beats.map { it.timeMs }.toLongArray().also { it.sort() }
        val alignedSections = alignBoundariesToBeats(rawSections, sortedBeatTimes, durationMs, beatMs, beatsPerBar, downbeatMs)

        val sections = distributeBeatsToSections(alignedSections, beats, beatMs, sortedBeatTimes)
        val climaxMoments = detectClimaxMoments(fullEnv, durationMs, hopMs, beatMs)
        return reclassifyClimax(sections, climaxMoments)
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
        val windows = ArrayList<FeatureWindow>()
        val windowFrames = max(1, (WINDOW_MS / hopMs).toInt())
        val strideFrames  = max(1, (STRIDE_MS / hopMs).toInt())

        val novelty = computeNovelty(lowEnv, midEnv, fullEnv)

        var startIdx = 0
        var prev: FeatureWindow? = null

        while (startIdx < fullEnv.size) {
            val endIdx = min(fullEnv.size, startIdx + windowFrames)
            if (endIdx <= startIdx) break

            val startMs = startIdx.toLong() * hopMs
            val endMs   = min(durationMs, endIdx.toLong() * hopMs)

            val lowSlice  = lowEnv.subList(startIdx, endIdx)
            val midSlice  = midEnv.subList(startIdx, endIdx)
            val fullSlice = fullEnv.subList(startIdx, endIdx)
            val highSlice = if (highEnv.size >= endIdx) highEnv.subList(startIdx, endIdx) else emptyList()
            val novSlice  = novelty.copyOfRange(startIdx, endIdx)

            val avgFull      = average(fullSlice)
            val energy       = avgFull
            val lowRatio     = average(lowSlice)  / max(0.0001f, avgFull)
            val midRatio     = average(midSlice)  / max(0.0001f, avgFull)
            val highRatio    = if (highSlice.isNotEmpty()) average(highSlice) / max(0.0001f, avgFull) else 0f
            val peakEnergy   = fullSlice.maxOrNull() ?: 0f
            val onsetDensity = densityAbove(novSlice, 0.12f)
            val beatMsHint   = estimateBeatMsByAutocorr(novSlice, hopMs, MIN_BEAT_MS, MAX_BEAT_MS)
            val periodicity  = estimatePeriodicityStrength(novSlice, beatMsHint, hopMs)

            // V8 방식: activity (에너지 변화) 계산
            var activity = 0f
            var prevEnergy = if (fullSlice.isNotEmpty()) fullSlice[0] else 0f
            for (v in fullSlice) { activity += abs(v - prevEnergy); prevEnergy = v }
            activity = if (fullSlice.isNotEmpty()) activity / fullSlice.size else 0f

            // V8 방식: score 계산 (mean*0.60 + activity*0.20 + maxV*0.10 + onsetBonus - lowPenalty)
            val onsetBonus = (onsetDensity * 0.12f)
            val lowPenalty = (lowRatio * 0.08f).coerceIn(0f, 0.08f)
            val score = (energy * 0.60f + activity * 0.20f + peakEnergy * 0.10f + onsetBonus - lowPenalty)
                .coerceIn(0f, 1f)

            // 임시 분류 (나중에 임계값으로 재분류)
            val type = SectionDetector.SectionType.VERSE

            val draft = FeatureWindow(
                startMs        = startMs,
                endMs          = endMs,
                energy         = energy,
                lowRatio       = lowRatio,
                onsetDensity   = onsetDensity,
                periodicity    = periodicity,
                beatMsHint     = beatMsHint,
                sectionType    = type,
                changeStrength = SectionDetector.ChangeStrength.NONE,
                peakEnergy     = peakEnergy,
                midRatio       = midRatio,
                highRatio      = highRatio,
                score          = score,
                activity       = activity
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
    // ② Section type classification (V8 방식)
    // ──────────────────────────────────────────────────────────────

    private fun classifyType(score: Float, lowTh: Float, highTh: Float): SectionDetector.SectionType {
        val bridgeTh = lowTh * 0.85f
        return when {
            score >= highTh   -> SectionDetector.SectionType.CHORUS
            score <= bridgeTh -> SectionDetector.SectionType.BRIDGE
            else              -> SectionDetector.SectionType.VERSE
        }
    }

    private fun estimateChangeStrength(
        prev: FeatureWindow?,
        cur: FeatureWindow
    ): SectionDetector.ChangeStrength {
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

    // ──────────────────────────────────────────────────────────────
    // ③ Merge windows → sections
    // ──────────────────────────────────────────────────────────────

    private fun buildSectionsFromWindows(
        windows: List<FeatureWindow>,
        durationMs: Long,
        lowTh: Float = 0f,
        highTh: Float = 1f
    ): List<FeatureWindow> {
        if (windows.isEmpty()) return emptyList()

        val merged = ArrayList<FeatureWindow>()
        var cur = windows.first().copy(sectionType = classifyType(windows.first().score, lowTh, highTh))

        for (i in 1 until windows.size) {
            val next = windows[i].copy(sectionType = classifyType(windows[i].score, lowTh, highTh))
            val shouldSplit =
                next.changeStrength == SectionDetector.ChangeStrength.STRONG ||
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
                    beatMsHint   = normalizeBeatMsAgainstGlobal(cur.beatMsHint, next.beatMsHint),
                    score        = (cur.score       + next.score)       * 0.5f,
                    activity     = (cur.activity     + next.activity)     * 0.5f
                )
            }
        }
        merged += cur.copy(endMs = durationMs)

        return normalizeSections(merged, durationMs)
    }

    private fun normalizeSections(
        sections: List<FeatureWindow>,
        durationMs: Long
    ): List<FeatureWindow> {
        if (sections.isEmpty()) return emptyList()

        val sorted = sections.sortedBy { it.startMs }
        val out    = ArrayList<FeatureWindow>()

        for (s in sorted) {
            val fixedStart = if (out.isEmpty()) 0L else max(out.last().endMs, s.startMs)
            val fixedEnd   = min(durationMs, max(fixedStart + 1L, s.endMs))
            if (fixedEnd <= fixedStart) continue

            val fixed = s.copy(startMs = fixedStart, endMs = fixedEnd)

            if (out.isNotEmpty()) {
                val prev = out.last()
                if (fixed.endMs - fixed.startMs < MIN_SECTION_MS &&
                    prev.sectionType == fixed.sectionType
                ) {
                    out[out.lastIndex] = prev.copy(
                        endMs        = fixed.endMs,
                        energy       = (prev.energy       + fixed.energy)       * 0.5f,
                        lowRatio     = (prev.lowRatio     + fixed.lowRatio)     * 0.5f,
                        midRatio     = (prev.midRatio     + fixed.midRatio)     * 0.5f,
                        highRatio    = (prev.highRatio    + fixed.highRatio)    * 0.5f,
                        onsetDensity = (prev.onsetDensity + fixed.onsetDensity) * 0.5f,
                        periodicity  = (prev.periodicity  + fixed.periodicity)  * 0.5f,
                        peakEnergy   = max(prev.peakEnergy, fixed.peakEnergy),
                        beatMsHint   = normalizeBeatMsAgainstGlobal(prev.beatMsHint, fixed.beatMsHint),
                        score        = (prev.score       + fixed.score)       * 0.5f,
                        activity     = (prev.activity     + fixed.activity)     * 0.5f
                    )
                    continue
                }
            }
            out += fixed
        }

        if (out.isNotEmpty()) {
            val last = out.last()
            if (last.endMs < durationMs) {
                out[out.lastIndex] = last.copy(endMs = durationMs)
            }
        }

        return out
    }

    // ──────────────────────────────────────────────────────────────
    // ④ Align section boundaries to beat positions
    // ──────────────────────────────────────────────────────────────

    private fun alignBoundariesToBeats(
        sections: List<FeatureWindow>,
        sortedBeatTimes: LongArray,
        durationMs: Long,
        beatMs: Long,
        beatsPerBar: Int,
        downbeatMs: Long
    ): List<FeatureWindow> {
        if (sections.size <= 1) return sections

        val result  = ArrayList<FeatureWindow>(sections.size)
        var prevEnd = 0L

        // Bar 경계 생성 (downbeatMs, downbeatMs + barMs, downbeatMs + 2*barMs, ...)
        val barMs = beatMs * beatsPerBar.coerceAtLeast(1)
        val barBoundaries = generateBarBoundaries(downbeatMs, durationMs, barMs)

        for (i in sections.indices) {
            val s      = sections[i]
            val isLast = i == sections.lastIndex
            val rawEnd = if (isLast) durationMs else s.endMs

            val snappedEnd = if (isLast) durationMs
                             else snapToNearestBar(rawEnd, barBoundaries, ALIGN_SNAP_MS)

            val start = prevEnd
            val end   = max(start + 1L, snappedEnd)
            result += s.copy(startMs = start, endMs = end)
            prevEnd = end
        }

        return result
    }

    private fun snapToNearestBeat(
        targetMs: Long,
        sortedBeatTimes: LongArray,
        snapWindowMs: Long
    ): Long {
        if (sortedBeatTimes.isEmpty()) return targetMs

        // Binary search for insertion point
        var lo = 0; var hi = sortedBeatTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedBeatTimes[mid] < targetMs) lo = mid + 1 else hi = mid
        }

        var best     = targetMs
        var bestDist = Long.MAX_VALUE

        for (idx in max(0, lo - 1)..min(sortedBeatTimes.lastIndex, lo + 1)) {
            val d = abs(sortedBeatTimes[idx] - targetMs)
            if (d <= snapWindowMs && d < bestDist) {
                bestDist = d
                best     = sortedBeatTimes[idx]
            }
        }

        return best
    }

    // ── Bar 경계 기반 스냅 ────────────────────────────────────────
    private fun generateBarBoundaries(downbeatMs: Long, durationMs: Long, barMs: Long): LongArray {
        val boundaries = ArrayList<Long>()
        var t = downbeatMs
        while (t <= durationMs) {
            if (t >= 0L) boundaries += t
            t += barMs
        }
        return boundaries.toLongArray()
    }

    private fun snapToNearestBar(
        targetMs: Long,
        barBoundaries: LongArray,
        snapWindowMs: Long
    ): Long {
        if (barBoundaries.isEmpty()) return targetMs

        // Binary search for insertion point
        var lo = 0; var hi = barBoundaries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (barBoundaries[mid] < targetMs) lo = mid + 1 else hi = mid
        }

        var best     = targetMs
        var bestDist = Long.MAX_VALUE

        for (idx in max(0, lo - 1)..min(barBoundaries.lastIndex, lo + 1)) {
            val d = abs(barBoundaries[idx] - targetMs)
            if (d <= snapWindowMs && d < bestDist) {
                bestDist = d
                best     = barBoundaries[idx]
            }
        }

        return best
    }

    // ──────────────────────────────────────────────────────────────
    // ⑤ Distribute beats to sections
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
            val sectionBeats = sortedBeats.filter { it.timeMs >= s.startMs && it.timeMs < s.endMs }

            val beatTimesMs: LongArray
            val confidence: Float

            if (sectionBeats.size >= MIN_BEATS_IN_SECTION) {
                beatTimesMs = LongArray(sectionBeats.size) { sectionBeats[it].timeMs }
                confidence  = sectionBeats.map { it.confidence }.average().toFloat()
            } else {
                beatTimesMs = buildGridBeats(s.startMs, s.endMs, globalBeatMs, sortedBeatTimes)
                confidence  = 0.20f
            }

            Log.d(TAG, "SectionDetectorV1[$idx] ${s.startMs}~${s.endMs} " +
                "type=${s.sectionType} beats=${beatTimesMs.size} beatMs=$globalBeatMs " +
                "confidence=${"%.2f".format(confidence)} change=${s.changeStrength}")

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

    private fun buildGridBeats(
        startMs: Long,
        endMs: Long,
        beatMs: Long,
        sortedBeatTimes: LongArray
    ): LongArray {
        if (beatMs <= 0) return LongArray(0)
        // Phase from first detected beat
        val phase = if (sortedBeatTimes.isEmpty()) 0L else sortedBeatTimes[0] % beatMs
        val out   = ArrayList<Long>()
        var t = phase
        while (t < startMs) t += beatMs
        while (t < endMs) {
            out += t
            t += beatMs
        }
        return out.toLongArray()
    }

    // ──────────────────────────────────────────────────────────────
    // Signal analysis helpers
    // ──────────────────────────────────────────────────────────────

    private fun computeNovelty(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>
    ): FloatArray {
        val n = FloatArray(fullEnv.size)
        for (i in 1 until fullEnv.size) {
            val dLow  = max(0f, lowEnv[i]  - lowEnv[i - 1])
            val dMid  = max(0f, midEnv[i]  - midEnv[i - 1])
            val dFull = max(0f, fullEnv[i] - fullEnv[i - 1])
            n[i] = dLow * 0.45f + dMid * 0.35f + dFull * 0.20f
        }
        normalize01InPlace(n)
        smoothInPlace(n, 2)
        return n
    }

    private fun estimateBeatMsByAutocorr(
        novelty: FloatArray,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Long {
        if (novelty.size < 8) return DEFAULT_BEAT_MS

        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, min((maxBeatMs / hopMs).toInt(), novelty.size - 1))

        var bestLag   = (DEFAULT_BEAT_MS / hopMs).toInt().coerceIn(minLag, maxLag)
        var bestScore = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var s = 0.0
            var i = lag
            while (i < novelty.size) {
                s += novelty[i] * novelty[i - lag]
                i++
            }
            if (s > bestScore) {
                bestScore = s
                bestLag   = lag
            }
        }

        return (bestLag.toLong() * hopMs).coerceIn(minBeatMs, maxBeatMs)
    }

    private fun estimatePeriodicityStrength(
        novelty: FloatArray,
        beatMs: Long,
        hopMs: Long
    ): Float {
        if (novelty.isEmpty()) return 0f
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag >= novelty.size) return 0f

        var ac  = 0f
        var raw = 0f
        for (i in lag until novelty.size) ac += novelty[i] * novelty[i - lag]
        for (v in novelty) raw += v * v

        return if (raw <= 1e-6f) 0f else (ac / raw).coerceIn(0f, 1f)
    }

    private fun normalizeBeatMsAgainstGlobal(globalBeatMs: Long, rawBeatMs: Long): Long {
        var beatMs = rawBeatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val g      = globalBeatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val ratio  = beatMs.toFloat() / g.toFloat()
        beatMs = when {
            ratio in 0.45f..0.65f -> (beatMs * 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            ratio in 1.70f..2.20f -> (beatMs / 2L).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            else                  -> beatMs
        }
        return beatMs
    }

    private fun average(src: List<Float>): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f
        for (v in src) sum += v
        return sum / src.size.toFloat()
    }

    private fun densityAbove(src: FloatArray, th: Float): Float {
        if (src.isEmpty()) return 0f
        var count = 0
        for (v in src) if (v >= th) count++
        return count.toFloat() / src.size.toFloat()
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
            val a = (i - win).coerceAtLeast(0)
            val b = (i + win).coerceAtMost(x.lastIndex)
            for (j in a..b) { s += copy[j]; c++ }
            x[i] = s / max(1, c)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Climax detection (V8 로직 이식)
    // ──────────────────────────────────────────────────────────────

    private fun detectClimaxMoments(
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long,
        beatMs: Long
    ): List<Long> {
        if (fullEnv.size < 8) return emptyList()

        data class PeakCandidate(val tMs: Long, val score: Float)

        val scoreArray = FloatArray(fullEnv.size) { 0f }
        for (i in 2 until fullEnv.size - 2) {
            val energy   = fullEnv[i]
            val rise     = max(0f, fullEnv[i] - fullEnv[i - 1])
            val localAvg = (fullEnv[i-2] + fullEnv[i-1] + fullEnv[i+1] + fullEnv[i+2]) / 4f
            val contrast = max(0f, energy - localAvg)
            scoreArray[i] = energy * 0.50f + rise * 0.30f + contrast * 0.20f
        }

        val scoreList = scoreArray.toList().filter { it > 0f }
        if (scoreList.isEmpty()) return emptyList()

        val envMean  = scoreList.average().toFloat()
        val envStd   = sqrt(scoreList.fold(0f) { acc, v -> acc + (v - envMean) * (v - envMean) } / scoreList.size)
        val cv       = if (envMean > 0f) envStd / envMean else 0f
        val peakScore = scoreList.max()
        val peakRatio = if (envMean > 0f) peakScore / envMean else 0f

        if (cv < CLIMAX_MIN_CV || peakRatio < CLIMAX_MIN_PEAK_RATIO) {
            Log.d(TAG, "SectionDetectorV1 climax skip: CV=${"%.3f".format(cv)} peakRatio=${"%.2f".format(peakRatio)}")
            return emptyList()
        }
        Log.d(TAG, "SectionDetectorV1 climax CV=${"%.3f".format(cv)} peakRatio=${"%.2f".format(peakRatio)} → detecting")

        val candidates = ArrayList<PeakCandidate>()
        for (i in 2 until scoreArray.size - 2) {
            val score = scoreArray[i]
            if (score <= 0f) continue
            val isLocalPeak = score >= scoreArray[i-1] && score >= scoreArray[i-2] &&
                    score >= scoreArray[i+1] && score >= scoreArray[i+2]
            if (isLocalPeak) candidates += PeakCandidate(tMs = i.toLong() * hopMs, score = score)
        }
        if (candidates.isEmpty()) return emptyList()

        val sortedScores = scoreList.sorted()
        val p90 = sortedScores[(sortedScores.lastIndex * 0.90f).toInt().coerceIn(0, sortedScores.lastIndex)]

        val strongCandidates = candidates
            .filter { it.score >= p90 * 1.18f && it.score >= envMean + envStd * 1.30f }
            .sortedByDescending { it.score }

        if (strongCandidates.isEmpty()) return emptyList()

        val minGapMs = max(800L, beatMs * 4L)
        val selected = ArrayList<PeakCandidate>()
        for (c in strongCandidates) {
            if (selected.none { abs(it.tMs - c.tMs) < minGapMs }) selected += c
            if (selected.size >= 3) break
        }

        val result = selected.sortedBy { it.tMs }.map { it.tMs.coerceIn(0L, durationMs) }
        Log.d(TAG, "SectionDetectorV1 climax moments=${result.joinToString()}")
        return result
    }

    private fun reclassifyClimax(
        sections: List<SectionDetector.Section>,
        climaxMoments: List<Long>
    ): List<SectionDetector.Section> {
        if (climaxMoments.isEmpty()) return sections
        return sections.map { s ->
            val midMs = (s.startMs + s.endMs) / 2L
            val nearClimax = climaxMoments.any { abs(it - midMs) <= CLIMAX_WINDOW_HALF_MS }
            val isHighEnergy = s.energy >= 0.50f
            when {
                nearClimax && (s.type == SectionDetector.SectionType.CHORUS ||
                              (s.type == SectionDetector.SectionType.VERSE && isHighEnergy)) -> {
                    Log.d(TAG, "SectionDetectorV1 reclassify ${s.type}→CLIMAX [${s.startMs}~${s.endMs}] energy=${s.energy}")
                    s.copy(type = SectionDetector.SectionType.CLIMAX)
                }
                else -> s
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Percentile (V8 방식 임계값 계산)
    // ──────────────────────────────────────────────────────────────

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
