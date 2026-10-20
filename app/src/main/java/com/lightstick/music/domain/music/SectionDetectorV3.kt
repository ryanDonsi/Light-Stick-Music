package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SectionDetectorV3 — 마디(bar) 단위 가변 길이 윈도우로 섹션 경계를 판정하는 섹션 감지기.
 * CHORUS는 반복 패턴 탐지(코사인 유사도)로, BREAK는 절대 무음 구간으로, CLIMAX는 CHORUS
 * 구간 내 절대 피크 기준으로 판정한다.
 */
class SectionDetectorV3 : SectionDetector {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        // 비트 정보가 없을 때 쓰는 고정 길이 폴백 윈도우.
        private const val FALLBACK_WINDOW_MS = 2_000L
        private const val FALLBACK_STRIDE_MS = 2_000L
        private const val MIN_SECTION_MS  = 4_000L
        private const val COMPACT_MIN_MS  = 10_000L

        private const val SECTION_STRONG_CHANGE_TH = 0.24f
        private const val SECTION_MEDIUM_CHANGE_TH = 0.14f

        private const val ALIGN_SNAP_MS = 500L

        private const val CLIMAX_WINDOW_HALF_MS = 2_000L
        private const val CLIMAX_MIN_CV         = 0.35f
        private const val CLIMAX_MIN_PEAK_RATIO = 2.0f
        // CLIMAX 후보로 인정할 곡 전체 절대 피크 대비 최소 비율.
        private const val CLIMAX_ABS_FLOOR_RATIO = 0.70f
        private const val CLIMAX_MAX_COUNT = 5

        private const val BREAK_MAX_MS = 8_000L
        // BREAK로 인정할 곡 전체 절대 피크 대비 최대 비율(= 거의 무음인 구간만 BREAK로 인정).
        private const val BREAK_SILENCE_RATIO = 0.05f

        private const val INTRO_SUSTAIN_RATIO = 0.8f
        // INTRO 종료는 threshold를 넘는 첫 윈도우 하나로 판정하고, OUTRO 시작은 연속 유지를
        // 요구한다.
        private const val INTRO_SUSTAIN_WINDOWS = 1
        private const val OUTRO_SUSTAIN_WINDOWS = 2

        // ── 반복 패턴(CHORUS) 탐지 파라미터 ──
        // 청크(비교 단위) 길이: 마디 정보가 있으면 이 마디 수, 없으면 고정 ms로 대체.
        private const val CHORUS_PHRASE_BARS = 8
        private const val CHORUS_FALLBACK_CHUNK_MS = 8_000L
        // 반복 탐지를 시도하기 위한 최소 청크 개수.
        private const val CHORUS_MIN_CHUNKS = 4
        // "닮았다"의 기준: 곡 내 상대 percentile과 절대 하한을 동시에 만족해야 한다.
        private const val CHORUS_SIM_ABS_FLOOR = 0.90f
        private const val CHORUS_SIM_PERCENTILE = 0.85f
        // 반복 그룹에 속한 청크라도 그 순간 score가 highTh의 이 비율 미만이면 CHORUS로
        // 확정하지 않는다.
        private const val CHORUS_SPAN_SCORE_FLOOR_RATIO = 0.85f
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

        val low  = if (lowEnv  is FloatList) lowEnv.array  else FloatArray(lowEnv.size)  { lowEnv[it] }
        val mid  = if (midEnv  is FloatList) midEnv.array  else FloatArray(midEnv.size)  { midEnv[it] }
        val full = if (fullEnv is FloatList) fullEnv.array else FloatArray(fullEnv.size) { fullEnv[it] }
        val high = if (highEnv.isNotEmpty()) FloatArray(highEnv.size) { highEnv[it] } else FloatArray(0)

        val novelty = computeNovelty(low, mid, full)
        val globalPeriodicity = estimatePeriodicityGlobal(novelty, beatMs, hopMs)
        val globalPeakFull = full.max()

        val windows = buildFeatureWindows(low, mid, full, high, novelty, globalPeriodicity, durationMs, hopMs, beatMs, beatsPerBar, downbeatMs)

        val frameScores = windows.map { it.score }
        val lowTh  = if (frameScores.isNotEmpty()) percentile(frameScores, 0.35f) else 0f
        val highTh = if (frameScores.isNotEmpty()) percentile(frameScores, 0.70f) else 1f
        Log.d(TAG, "SectionDetectorV3 thresholds: lowTh=${"%.3f".format(lowTh)} highTh=${"%.3f".format(highTh)}")

        val chorusSpans = detectChorusSpansByRepetition(windows, durationMs, beatMs, beatsPerBar, downbeatMs, highTh, novelty, hopMs)
        Log.d(TAG, "SectionDetectorV3 chorusSpans(repetition)=${chorusSpans.map { "${it.first}~${it.last}" }}")

        windows.forEachIndexed { idx, w ->
            Log.d(TAG, "SectionDetectorV3 rawWindow[$idx] ${w.startMs}~${w.endMs} " +
                "energy=${"%.3f".format(w.energy)} onset=${"%.3f".format(w.onsetDensity)} " +
                "activity=${"%.3f".format(w.activity)} low=${"%.3f".format(w.lowRatio)} " +
                "mid=${"%.3f".format(w.midRatio)} high=${"%.3f".format(w.highRatio)} " +
                "periodicity=${"%.3f".format(w.periodicity)} score=${"%.3f".format(w.score)} " +
                "type=${classifyType(w, lowTh, highTh, chorusSpans, globalPeakFull)} change=${w.changeStrength}")
        }

        val introEndMs = detectIntroEnd(windows, lowTh)
        val outroStartMs = detectOutroStart(windows, lowTh, durationMs)
        Log.d(TAG, "SectionDetectorV3 introEndMs=$introEndMs outroStartMs=$outroStartMs")

        val rawSections = buildSectionsFromWindows(windows, durationMs, lowTh, highTh, chorusSpans, globalPeakFull)

        val beatBoundaries = beats.map { it.timeMs }.sorted().toLongArray()
        val alignedSections = alignBoundariesToBars(rawSections, beatBoundaries, durationMs)
        // BRIDGE 위치 제약(gatePrematureBridge)보다 INTRO/OUTRO 낙인(applyIntroOutro)을 먼저 적용한다.
        val introLabeledSections = applyIntroOutro(alignedSections, introEndMs, outroStartMs)
        val gatedSections = gatePrematureBridge(introLabeledSections)

        // BREAK만 마디 단위가 아니라 비트 단위로 다시 확인한다.
        val breakSpans = detectBreakSpansByBeat(full, hopMs, beatMs, globalPeakFull, durationMs)
        val labeledSections = markBreakSpans(gatedSections, breakSpans)

        val rawTypedSections = toSections(labeledSections)
        // 비트 수가 너무 적은 BRIDGE는 VERSE로 재분류한다.
        val sections = reclassifyShortBridgeAsVerse(rawTypedSections, beatBoundaries)
        val chorusRanges = sections.filter { it.type == SectionDetector.SectionType.CHORUS }
            .map { it.startMs until it.endMs }
        // CLIMAX 검출은 현재 비활성화. detectClimaxMoments 함수는 복원을 대비해 남겨둔다.
        val climaxMoments = emptyList<Long>()
        // val climaxMoments = detectClimaxMoments(full, durationMs, hopMs, beatMs, chorusRanges)
        return annotateBeats(
            beats, sections, climaxMoments,
            novelty = novelty, low = low, full = full, high = high, hopMs = hopMs,
            beatMs = beatMs, beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
        )
    }

    private fun annotateBeats(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        sections: List<SectionDetector.Section>,
        climaxMoments: List<Long>,
        novelty: FloatArray,
        low: FloatArray,
        full: FloatArray,
        high: FloatArray,
        hopMs: Long,
        beatMs: Long,
        beatsPerBar: Int,
        downbeatMs: Long
    ): List<SectionDetector.AnnotatedBeat> = beats.map { beat ->
        val section = sections.find { beat.timeMs >= it.startMs && beat.timeMs < it.endMs }
        val sectionType = section?.type ?: SectionDetector.SectionType.VERSE
        val type = if (sectionType == SectionDetector.SectionType.CHORUS &&
                       climaxMoments.any { abs(it - beat.timeMs) <= CLIMAX_WINDOW_HALF_MS })
            SectionDetector.SectionType.CLIMAX else sectionType

        val idx = (beat.timeMs / hopMs).toInt().coerceIn(0, full.lastIndex)
        val localEnergy = full[idx]
        val onsetStrength = novelty.getOrElse(idx) { 0f }
        val denom = max(0.0001f, localEnergy)
        val lowRatio  = low[idx] / denom
        val highRatio = if (high.isNotEmpty()) high.getOrElse(idx) { 0f } / denom else 0f
        val beatInBar = if (beatMs > 0L && beatsPerBar > 0) {
            val steps = Math.round((beat.timeMs - downbeatMs).toDouble() / beatMs.toDouble())
            Math.floorMod(steps, beatsPerBar.toLong()).toInt()
        } else 0

        SectionDetector.AnnotatedBeat(
            timeMs        = beat.timeMs,
            confidence    = beat.confidence,
            sectionType   = type,
            localEnergy   = localEnergy,
            onsetStrength = onsetStrength,
            lowRatio      = lowRatio,
            highRatio     = highRatio,
            beatInBar     = beatInBar,
            sectionEnergy       = section?.energy ?: 0f,
            sectionPeakEnergy   = section?.peakEnergy ?: 0f,
            sectionLowRatio     = section?.lowRatio ?: 0f,
            sectionMidRatio     = section?.midRatio ?: 0f,
            sectionHighRatio    = section?.highRatio ?: 0f,
            sectionOnsetDensity = section?.onsetDensity ?: 0f,
            sectionPeriodicity  = section?.periodicity ?: 0f
        )
    }

    // ──────────────────────────────────────────────────────────────
    // ① Feature windows — 섹션 경계 판정용 구간 평균
    // ──────────────────────────────────────────────────────────────

    // 마디(bar) 길이를 윈도우 크기로 쓴다. beatMs/beatsPerBar가 없으면 고정 길이
    // 폴백 윈도우를 쓴다.
    private fun buildFeatureWindows(
        low: FloatArray, mid: FloatArray, full: FloatArray, high: FloatArray,
        novelty: FloatArray,
        globalPeriodicity: Float,
        durationMs: Long, hopMs: Long, beatMs: Long, beatsPerBar: Int, downbeatMs: Long
    ): List<FeatureWindow> {
        val n = full.size
        val barMs = if (beatMs > 0L && beatsPerBar > 0) beatMs * beatsPerBar else 0L

        val boundariesMs = ArrayList<Long>()
        if (barMs > 0L) {
            val base = downbeatMs % barMs
            var t = if (base > 0L) base else barMs
            while (t < durationMs) { boundariesMs += t; t += barMs }
        } else {
            var t = FALLBACK_WINDOW_MS
            while (t < durationMs) { boundariesMs += t; t += FALLBACK_STRIDE_MS }
        }
        if (boundariesMs.isEmpty() || boundariesMs.last() < durationMs) boundariesMs += durationMs

        val windows = ArrayList<FeatureWindow>(boundariesMs.size)
        var prev: FeatureWindow? = null
        var startMs = 0L
        for (boundaryMs in boundariesMs) {
            val startIdx = (startMs / hopMs).toInt().coerceIn(0, n)
            val endIdx   = (boundaryMs / hopMs).toInt().coerceIn(startIdx, n)
            if (endIdx > startIdx) {
                val win = computeWindowFeature(
                    low, mid, full, high, novelty, globalPeriodicity,
                    durationMs, hopMs, beatMs, startIdx, endIdx, prev
                )
                windows += win
                prev = win
            }
            startMs = boundaryMs
        }

        return windows
    }

    private fun computeWindowFeature(
        low: FloatArray, mid: FloatArray, full: FloatArray, high: FloatArray,
        novelty: FloatArray, globalPeriodicity: Float,
        durationMs: Long, hopMs: Long, beatMs: Long,
        startIdx: Int, endIdx: Int, prev: FeatureWindow?
    ): FeatureWindow {
        val count = endIdx - startIdx
        val fCount = count.toFloat()

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

        // 윈도우 구간만으로 로컬 재계산하고, 샘플 부족 시 곡 전체 값으로 폴백한다.
        val localPeriodicity = estimatePeriodicityLocal(novelty, startIdx, endIdx, beatMs, hopMs)
        val periodicity = if (localPeriodicity > 0f) localPeriodicity else globalPeriodicity

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
        return draft.copy(changeStrength = change)
    }

    // ──────────────────────────────────────────────────────────────
    // ② CHORUS 반복 패턴 탐지
    // ──────────────────────────────────────────────────────────────

    // 곡을 마디(bar) 기준 청크로 나눠 서로 닮은 청크 그룹을 찾고, 그중 평균 에너지가 제일 높은
    // 그룹을 CHORUS로 확정해 그 그룹에 속한 청크들의 시간 범위를 반환한다. 반복을 못 찾으면
    // 빈 리스트를 반환한다(classifyType이 score 기반 폴백으로 처리).
    private fun detectChorusSpansByRepetition(
        windows: List<FeatureWindow>,
        durationMs: Long,
        beatMs: Long,
        beatsPerBar: Int,
        downbeatMs: Long,
        highTh: Float,
        novelty: FloatArray,
        hopMs: Long
    ): List<LongRange> {
        if (windows.isEmpty()) return emptyList()

        val barMs = if (beatMs > 0L && beatsPerBar > 0) beatMs * beatsPerBar else 0L
        val chunkMs = if (barMs > 0L) barMs * CHORUS_PHRASE_BARS else CHORUS_FALLBACK_CHUNK_MS
        if (chunkMs <= 0L) return emptyList()

        val chunkStartBase = if (barMs > 0L) downbeatMs % barMs.coerceAtLeast(1L) else 0L
        val chunks = ArrayList<LongRange>()
        var s = chunkStartBase
        while (s < durationMs) {
            val e = min(durationMs, s + chunkMs)
            if (e > s) chunks += s until e
            s += chunkMs
        }
        if (chunks.size < CHORUS_MIN_CHUNKS) {
            Log.d(TAG, "SectionDetectorV3 chorus-repeat: 청크 부족(${chunks.size}개) → score 폴백")
            return emptyList()
        }

        // 청크별 특징 벡터: 겹치는 2초 윈도우들을 겹침 길이로 가중 평균.
        val vectors = ArrayList<FloatArray>(chunks.size)
        val chunkScores = ArrayList<Float>(chunks.size)
        for (chunk in chunks) {
            var wEnergy = 0f; var wLow = 0f; var wMid = 0f; var wHigh = 0f
            var wOnset = 0f; var wPeriod = 0f; var wScore = 0f; var totalOverlap = 0L
            for (w in windows) {
                val overlap = min(chunk.last, w.endMs) - max(chunk.first, w.startMs)
                if (overlap <= 0L) continue
                val ov = overlap.toFloat()
                wEnergy += w.energy * ov; wLow += w.lowRatio * ov; wMid += w.midRatio * ov
                wHigh += w.highRatio * ov; wOnset += w.onsetDensity * ov; wPeriod += w.periodicity * ov
                wScore += w.score * ov
                totalOverlap += overlap
            }
            if (totalOverlap <= 0L) {
                vectors += FloatArray(6)
                chunkScores += 0f
                continue
            }
            val denom = totalOverlap.toFloat()
            vectors += floatArrayOf(
                wEnergy / denom, wLow / denom, wMid / denom, wHigh / denom, wOnset / denom, wPeriod / denom
            )
            chunkScores += wScore / denom
        }

        // 차원별 min-max 정규화 — 값 범위가 다른 특징끼리 코사인 유사도에서 공평하게 반영되도록.
        val dims = 6
        val mins = FloatArray(dims) { Float.MAX_VALUE }
        val maxs = FloatArray(dims) { -Float.MAX_VALUE }
        for (v in vectors) for (d in 0 until dims) { mins[d] = min(mins[d], v[d]); maxs[d] = max(maxs[d], v[d]) }
        val normVectors = vectors.map { v ->
            FloatArray(dims) { d ->
                val range = maxs[d] - mins[d]
                if (range > 1e-6f) (v[d] - mins[d]) / range else 0f
            }
        }

        // 모든 청크 쌍의 코사인 유사도.
        val n = chunks.size
        val sims = HashMap<Long, Float>() // key = i*10000L+j (i<j)
        val allSims = ArrayList<Float>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sim = cosineSimilarity(normVectors[i], normVectors[j])
                sims[i.toLong() * 10000L + j] = sim
                allSims += sim
            }
        }
        if (allSims.isEmpty()) return emptyList()

        // 진단용 로그: 모든 청크 쌍의 유사도. 동작에는 영향 없음.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sim = sims[i.toLong() * 10000L + j] ?: continue
                Log.d(TAG, "SectionDetectorV3 chunk-sim [$i]${chunks[i].first}~${chunks[i].last} <-> " +
                    "[$j]${chunks[j].first}~${chunks[j].last} sim=${"%.4f".format(sim)}")
            }
        }

        // 진단용 로그: 청크 쌍별 onset(어택 타이밍) 패턴 상관계수. 판정에는 반영하지 않는다.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val corr = onsetPatternCorrelation(novelty, hopMs, chunks[i], chunks[j])
                Log.d(TAG, "SectionDetectorV3 onset-corr [$i]${chunks[i].first}~${chunks[i].last} <-> " +
                    "[$j]${chunks[j].first}~${chunks[j].last} corr=${"%.4f".format(corr)}")
            }
        }

        val simThreshold = max(CHORUS_SIM_ABS_FLOOR, percentile(allSims, CHORUS_SIM_PERCENTILE))
        Log.d(TAG, "SectionDetectorV3 chorus-repeat: chunks=$n simThreshold=${"%.3f".format(simThreshold)}")

        // Union-Find로 반복 그룹 묶기.
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for (i in 0 until n) for (j in i + 1 until n) {
            val sim = sims[i.toLong() * 10000L + j] ?: continue
            if (sim >= simThreshold) union(i, j)
        }

        val groups = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        val medianScore = percentile(chunkScores, 0.5f)

        val candidateGroups = groups.values.filter { it.size >= 2 }
        if (candidateGroups.isEmpty()) {
            Log.d(TAG, "SectionDetectorV3 chorus-repeat: 반복 그룹 없음 → score 폴백")
            return emptyList()
        }

        val best = candidateGroups.maxByOrNull { g -> g.map { chunkScores[it] }.average() } ?: return emptyList()
        val bestAvgScore = best.map { chunkScores[it] }.average().toFloat()
        if (bestAvgScore < medianScore) {
            Log.d(TAG, "SectionDetectorV3 chorus-repeat: 최선 그룹 에너지(${"%.3f".format(bestAvgScore)})가 " +
                "중앙값(${"%.3f".format(medianScore)})보다 낮음 → score 폴백")
            return emptyList()
        }

        Log.d(TAG, "SectionDetectorV3 chorus-repeat: 그룹 선택 idx=${best.sorted()} avgScore=${"%.3f".format(bestAvgScore)}")
        return best.map { chunks[it] }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    // 두 구간의 novelty(어택 강도) 시계열을 z-score 정규화 후 0-lag 상관계수로 비교한다.
    // 참고용 로그로만 사용한다. 길이가 다르면 짧은 쪽에 맞춰 자른다.
    private fun onsetPatternCorrelation(novelty: FloatArray, hopMs: Long, rangeA: LongRange, rangeB: LongRange): Float {
        val hop = max(1L, hopMs)
        val startA = (rangeA.first / hop).toInt().coerceIn(0, novelty.size)
        val endA   = (rangeA.last  / hop).toInt().coerceIn(0, novelty.size)
        val startB = (rangeB.first / hop).toInt().coerceIn(0, novelty.size)
        val endB   = (rangeB.last  / hop).toInt().coerceIn(0, novelty.size)
        val len = min(endA - startA, endB - startB)
        if (len <= 1) return 0f

        fun zNorm(start: Int): FloatArray {
            val x = FloatArray(len) { novelty[start + it] }
            val mean = x.average().toFloat()
            var variance = 0f
            for (v in x) variance += (v - mean) * (v - mean)
            variance /= x.size
            val std = sqrt(variance)
            return if (std > 1e-6f) FloatArray(len) { (x[it] - mean) / std } else FloatArray(len)
        }

        val za = zNorm(startA); val zb = zNorm(startB)
        var dot = 0f
        for (i in za.indices) dot += za[i] * zb[i]
        return (dot / len).coerceIn(-1f, 1f)
    }

    // ──────────────────────────────────────────────────────────────
    // ③ Classification & merge
    // ──────────────────────────────────────────────────────────────

    // chorusSpans가 비어있지 않으면 CHORUS는 그 스팬 안에 있을 때만 붙는다. 비어있으면
    // score >= highTh로 판정하는 폴백 경로를 탄다.
    private fun classifyType(
        w: FeatureWindow, lowTh: Float, highTh: Float, chorusSpans: List<LongRange>, globalPeakFull: Float
    ): SectionDetector.SectionType {
        val mid = (w.startMs + w.endMs) / 2
        if (chorusSpans.isNotEmpty()) {
            // 반복 그룹에 속해도 그 순간 score가 실제로 충분히 높을 때만 CHORUS로 확정한다.
            if (chorusSpans.any { mid in it } && w.score >= highTh * CHORUS_SPAN_SCORE_FLOOR_RATIO)
                return SectionDetector.SectionType.CHORUS
        } else if (w.score >= highTh) {
            return SectionDetector.SectionType.CHORUS
        }
        val bridgeTh = lowTh * 0.85f
        val breakTh  = lowTh * 0.45f
        // BREAK는 윈도우 안의 최대 순간 에너지(peakEnergy)가 곡 전체 절대 피크 대비
        // BREAK_SILENCE_RATIO 미만일 때만 인정한다(거의 무음). 넘으면 BRIDGE로 떨어진다.
        val isNearSilent = w.peakEnergy <= globalPeakFull * BREAK_SILENCE_RATIO
        return when {
            w.score <= breakTh && isNearSilent -> SectionDetector.SectionType.BREAK
            w.score <= bridgeTh -> SectionDetector.SectionType.BRIDGE
            else                -> SectionDetector.SectionType.VERSE
        }
    }

    // BRIDGE는 VERSE/CHORUS가 한 번도 나오지 않은 시점(=인트로 직후)엔 인정하지 않고
    // VERSE로 처리한다. applyIntroOutro로 INTRO 낙인이 적용된 뒤의 섹션 단위로 수행한다.
    private fun gatePrematureBridge(sections: List<FeatureWindow>): List<FeatureWindow> {
        var seenVerseOrChorus = false
        return sections.map { s ->
            val result = if (s.sectionType == SectionDetector.SectionType.BRIDGE && !seenVerseOrChorus)
                s.copy(sectionType = SectionDetector.SectionType.VERSE)
            else s
            if (result.sectionType == SectionDetector.SectionType.VERSE ||
                result.sectionType == SectionDetector.SectionType.CHORUS) seenVerseOrChorus = true
            result
        }
    }

    // BREAK 전용 — 원본 신호(full)를 비트 하나 단위로 스캔해서, 그 비트의 순간 피크가
    // 곡 전체 절대 피크 대비 BREAK_SILENCE_RATIO 미만인 구간만 찾아 합친다. beatMs가
    // 없으면 빈 리스트를 반환한다. 재개(무음→유음) 판정은 연속 2개 비트가 모두 플로어를
    // 넘어야 확정하고(어택/잔향 꼬리 흡수), BREAK 시작 쪽도 비-무음 비트 바로 다음이
    // 무음으로 확정되면 그 비트는 감쇠 꼬리로 보고 BREAK에 흡수한다.
    private fun detectBreakSpansByBeat(
        full: FloatArray, hopMs: Long, beatMs: Long, globalPeakFull: Float, durationMs: Long
    ): List<LongRange> {
        if (beatMs <= 0L || full.isEmpty()) return emptyList()
        val silenceFloor = globalPeakFull * BREAK_SILENCE_RATIO

        val beatStarts = ArrayList<Long>()
        val rawSilent = ArrayList<Boolean>()
        var t = 0L
        while (t < durationMs) {
            val endT = min(durationMs, t + beatMs)
            val startIdx = (t / hopMs).toInt().coerceIn(0, full.size)
            val endIdx = (endT / hopMs).toInt().coerceIn(startIdx, full.size)
            var peak = 0f
            for (i in startIdx until endIdx) if (full[i] > peak) peak = full[i]
            beatStarts += t
            rawSilent += (peak <= silenceFloor)
            t = endT
        }

        val silent = BooleanArray(rawSilent.size) { rawSilent[it] }
        for (i in 0 until rawSilent.size - 1) {
            if (!rawSilent[i] && rawSilent[i + 1]) silent[i] = true
        }

        val spans = ArrayList<LongRange>()
        var spanStart = -1L
        var pendingResume = false
        for (i in silent.indices) {
            val tCur = beatStarts[i]
            if (silent[i]) {
                if (spanStart < 0L) spanStart = tCur
                pendingResume = false
            } else if (spanStart >= 0L) {
                if (!pendingResume) {
                    pendingResume = true
                } else {
                    spans += spanStart until tCur
                    spanStart = -1L
                    pendingResume = false
                }
            }
        }
        if (spanStart >= 0L) spans += spanStart until durationMs
        return spans
    }

    // detectBreakSpansByBeat가 찾은 비트 단위 무음 구간을 최종 섹션 목록 위에 사후로 잘라
    // 붙인다. INTRO/OUTRO/CHORUS/이미 BREAK인 구간은 건드리지 않는다.
    private fun markBreakSpans(sections: List<FeatureWindow>, breakSpans: List<LongRange>): List<FeatureWindow> {
        if (breakSpans.isEmpty() || sections.isEmpty()) return sections
        var result = sections
        for (span in breakSpans) {
            result = carveBreakSpan(result, span.first, span.last + 1)
        }
        return result
    }

    private fun carveBreakSpan(sections: List<FeatureWindow>, spanStart: Long, spanEnd: Long): List<FeatureWindow> {
        val out = ArrayList<FeatureWindow>(sections.size + 2)
        for (s in sections) {
            val overlapStart = max(s.startMs, spanStart)
            val overlapEnd = min(s.endMs, spanEnd)
            val carvable = s.sectionType == SectionDetector.SectionType.VERSE ||
                s.sectionType == SectionDetector.SectionType.BRIDGE
            if (overlapEnd <= overlapStart || !carvable) {
                out += s
                continue
            }
            if (s.startMs < overlapStart) out += s.copy(endMs = overlapStart)
            out += s.copy(startMs = overlapStart, endMs = overlapEnd, sectionType = SectionDetector.SectionType.BREAK)
            if (overlapEnd < s.endMs) out += s.copy(startMs = overlapEnd)
        }
        return out
    }

    private fun detectIntroEnd(windows: List<FeatureWindow>, lowTh: Float): Long {
        if (windows.size < INTRO_SUSTAIN_WINDOWS) return 0L
        val threshold = lowTh * INTRO_SUSTAIN_RATIO
        for (i in 0..windows.size - INTRO_SUSTAIN_WINDOWS) {
            if ((i until i + INTRO_SUSTAIN_WINDOWS).all { windows[it].score >= threshold }) {
                return windows[i].startMs
            }
        }
        return 0L
    }

    private fun detectOutroStart(windows: List<FeatureWindow>, lowTh: Float, durationMs: Long): Long {
        if (windows.size < OUTRO_SUSTAIN_WINDOWS) return durationMs
        val threshold = lowTh * INTRO_SUSTAIN_RATIO
        for (i in windows.size - OUTRO_SUSTAIN_WINDOWS downTo 0) {
            if ((i until i + OUTRO_SUSTAIN_WINDOWS).all { windows[it].score >= threshold }) {
                return windows[i + OUTRO_SUSTAIN_WINDOWS - 1].endMs
            }
        }
        return durationMs
    }

    private fun markIntroUpTo(sections: List<FeatureWindow>, introEndMs: Long): List<FeatureWindow> {
        val out = ArrayList<FeatureWindow>(sections.size + 1)
        var i = 0
        while (i < sections.size && sections[i].endMs <= introEndMs) {
            out += sections[i].copy(sectionType = SectionDetector.SectionType.INTRO)
            i++
        }
        if (i < sections.size && sections[i].startMs < introEndMs) {
            val s = sections[i]
            out += s.copy(endMs = introEndMs, sectionType = SectionDetector.SectionType.INTRO)
            out += s.copy(startMs = introEndMs)
            i++
        }
        while (i < sections.size) { out += sections[i]; i++ }
        return out
    }

    private fun markOutroFrom(sections: List<FeatureWindow>, outroStartMs: Long): List<FeatureWindow> {
        val out = ArrayList<FeatureWindow>(sections.size + 1)
        var i = 0
        while (i < sections.size && sections[i].endMs <= outroStartMs) {
            out += sections[i]
            i++
        }
        if (i < sections.size && sections[i].startMs < outroStartMs) {
            val s = sections[i]
            out += s.copy(endMs = outroStartMs)
            out += s.copy(startMs = outroStartMs, sectionType = SectionDetector.SectionType.OUTRO)
            i++
        }
        while (i < sections.size) {
            out += sections[i].copy(sectionType = SectionDetector.SectionType.OUTRO)
            i++
        }
        return out
    }

    private fun applyIntroOutro(sections: List<FeatureWindow>, introEndMs: Long, outroStartMs: Long): List<FeatureWindow> {
        if (sections.size < 2) return sections
        val startsWithChorus = sections.first().sectionType == SectionDetector.SectionType.CHORUS
        val effectiveIntroEnd = if (introEndMs > 0L && !startsWithChorus) introEndMs else 0L
        var out = if (effectiveIntroEnd > 0L) markIntroUpTo(sections, effectiveIntroEnd) else sections

        val safeOutroStart = max(outroStartMs, effectiveIntroEnd)
        if (safeOutroStart < out.last().endMs) {
            out = markOutroFrom(out, safeOutroStart)
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
        windows: List<FeatureWindow>, durationMs: Long, lowTh: Float, highTh: Float, chorusSpans: List<LongRange>,
        globalPeakFull: Float
    ): List<FeatureWindow> {
        if (windows.isEmpty()) return emptyList()
        // BRIDGE의 "VERSE/CHORUS 등장 후에만" 위치 제약은 여기(윈도우 단위)가 아니라
        // gatePrematureBridge()에서 압축·정렬이 끝난 섹션 단위로 한 번만 적용한다. 자세한
        // 이유는 gatePrematureBridge 주석 참고.
        val merged = ArrayList<FeatureWindow>()
        var cur = windows.first().copy(sectionType = classifyType(windows.first(), lowTh, highTh, chorusSpans, globalPeakFull))

        for (i in 1 until windows.size) {
            val next = windows[i].copy(sectionType = classifyType(windows[i], lowTh, highTh, chorusSpans, globalPeakFull))
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
        return compactSections(normalized, chorusSpans)
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

    // list[startIdx]부터 step 방향으로, COMPACT_MIN_MS 미만인 완충용 구간들을 건너뛰면서
    // 그 너머에 CHORUS가 있는지 찾는다.
    private fun chorusReachableThroughShortBuffer(list: List<FeatureWindow>, startIdx: Int, step: Int): Boolean {
        var j = startIdx
        while (j in list.indices) {
            val t = list[j].sectionType
            if (t == SectionDetector.SectionType.CHORUS) return true
            if (list[j].endMs - list[j].startMs >= COMPACT_MIN_MS) return false
            j += step
        }
        return false
    }

    private fun compactSections(input: List<FeatureWindow>, chorusSpans: List<LongRange>): List<FeatureWindow> {
        if (input.size <= 1) return input
        val list = input.toMutableList()
        var changed = true
        while (changed && list.size > 1) {
            changed = false
            var shortIdx = -1; var shortDur = Long.MAX_VALUE
            for (i in list.indices) {
                val d = list[i].endMs - list[i].startMs
                // BREAK_MAX_MS를 넘는 BREAK는 길이와 무관하게 압축 대상에서 제외한다(FREEZE
                // 이펙트 유지 목적).
                val protectedLongBreak = list[i].sectionType == SectionDetector.SectionType.BREAK &&
                    d >= BREAK_MAX_MS
                // CHORUS가 아닌 구간이 CHORUS와 맞닿아 있으면 함부로 압축하지 않는다. 반대쪽
                // (직접 이웃)이 같은 타입일 때만 그쪽으로의 흡수를 허용한다.
                val prevType = if (i > 0) list[i - 1].sectionType else null
                val nextType = if (i < list.lastIndex) list[i + 1].sectionType else null
                val prevIsChorus = chorusReachableThroughShortBuffer(list, i - 1, -1)
                val nextIsChorus = chorusReachableThroughShortBuffer(list, i + 1, 1)
                val safeNonChorusMerge = (prevIsChorus && nextType == list[i].sectionType) ||
                    (nextIsChorus && prevType == list[i].sectionType)
                val protectedChorusAdjacent = chorusSpans.isNotEmpty() &&
                    list[i].sectionType != SectionDetector.SectionType.CHORUS &&
                    (prevIsChorus || nextIsChorus) && !safeNonChorusMerge
                // 반복 탐지가 확정한 CHORUS 자체도 길이만으로 압축되지 않도록 보호한다.
                val protectedShortChorus = chorusSpans.isNotEmpty() &&
                    list[i].sectionType == SectionDetector.SectionType.CHORUS
                if (d < COMPACT_MIN_MS && d < shortDur && !protectedLongBreak &&
                    !protectedChorusAdjacent && !protectedShortChorus) {
                    shortDur = d; shortIdx = i
                }
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
    // ④ Align to bar boundaries
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
    // ⑤ FeatureWindow → Section
    // ──────────────────────────────────────────────────────────────

    private fun toSections(windows: List<FeatureWindow>): List<SectionDetector.Section> =
        windows.mapIndexed { idx, s ->
            Log.d(TAG, "SectionDetectorV3[$idx] ${s.startMs}~${s.endMs} type=${s.sectionType} change=${s.changeStrength}")
            SectionDetector.Section(
                startMs        = s.startMs,    endMs          = s.endMs,
                type           = s.sectionType, changeStrength = s.changeStrength,
                energy         = s.energy,     peakEnergy     = s.peakEnergy,
                lowRatio       = s.lowRatio,   midRatio       = s.midRatio,
                highRatio      = s.highRatio,  onsetDensity   = s.onsetDensity,
                periodicity    = s.periodicity
            )
        }

    /** BRIDGE로 분류된 구간 중 포함된 비트 수가 6개 미만인 짧은 구간을 VERSE로 재분류한다. */
    private fun reclassifyShortBridgeAsVerse(
        sections: List<SectionDetector.Section>,
        sortedBeatBoundaries: LongArray
    ): List<SectionDetector.Section> = sections.map { s ->
        if (s.type != SectionDetector.SectionType.BRIDGE) return@map s
        var lo = 0; var hi = sortedBeatBoundaries.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (sortedBeatBoundaries[m] < s.startMs) lo = m + 1 else hi = m
        }
        var count = 0
        var i = lo
        while (i < sortedBeatBoundaries.size && sortedBeatBoundaries[i] < s.endMs) { count++; i++ }
        if (count < 6) {
            Log.d(TAG, "SectionDetectorV3 짧은 BRIDGE→VERSE 재분류: ${s.startMs}~${s.endMs} beats=$count")
            s.copy(type = SectionDetector.SectionType.VERSE)
        } else s
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

    private fun estimatePeriodicityGlobal(novelty: FloatArray, beatMs: Long, hopMs: Long): Float {
        if (novelty.isEmpty()) return 0f
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag >= novelty.size) return 0f
        var ac = 0f; var raw = 0f
        for (i in lag until novelty.size) ac += novelty[i] * novelty[i - lag]
        for (v in novelty) raw += v * v
        return if (raw <= 1e-6f) 0f else (ac / raw).coerceIn(0f, 1f)
    }

    // estimatePeriodicityGlobal과 동일한 beat-lag 자기상관 계산을 startIdx~endIdx
    // 구간(윈도우 하나)의 novelty 샘플만으로 수행한다.
    private fun estimatePeriodicityLocal(novelty: FloatArray, startIdx: Int, endIdx: Int, beatMs: Long, hopMs: Long): Float {
        val lag = max(1, (beatMs / hopMs).toInt())
        val s = max(startIdx, 0)
        val e = min(endIdx, novelty.size)
        if (e - s <= lag) return 0f
        var ac = 0f; var raw = 0f
        for (i in (s + lag) until e) ac += novelty[i] * novelty[i - lag]
        for (i in s until e) raw += novelty[i] * novelty[i]
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
    // Climax detection — 절대 피크 하한 포함
    // ──────────────────────────────────────────────────────────────

    // CLIMAX는 CHORUS로 판정된 구간 안에서만 뽑는다.
    private fun detectClimaxMoments(
        full: FloatArray, durationMs: Long, hopMs: Long, beatMs: Long, chorusRanges: List<LongRange>
    ): List<Long> {
        if (full.size < 8 || chorusRanges.isEmpty()) return emptyList()

        val globalPeakFull = full.max()
        val absFloor = globalPeakFull * CLIMAX_ABS_FLOOR_RATIO

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

        // CHORUS별로 가장 센 후보를 하나씩 뽑아 모든 CHORUS가 최소 1개는 대표를 갖게 하고,
        // 남는 슬롯만 전체 후보 중 점수 높은 순으로 채운다.
        data class Candidate(val tMs: Long, val score: Float, val chorusIdx: Int)
        val candidates = ArrayList<Candidate>()
        for (i in 2 until scoreArray.size - 2) {
            val sc = scoreArray[i]; if (sc <= 0f) continue
            val tMs = i.toLong() * hopMs
            if (full[i] < absFloor) continue
            val chorusIdx = chorusRanges.indexOfFirst { tMs in it }
            if (chorusIdx < 0) continue
            if (sc >= scoreArray[i-1] && sc >= scoreArray[i-2] && sc >= scoreArray[i+1] && sc >= scoreArray[i+2] &&
                sc >= p90 * 1.18f && sc >= envMean + envStd * 1.30f) {
                candidates += Candidate(tMs, sc, chorusIdx)
            }
        }

        // minGapMs 이내로 붙은 후보는 점수가 가장 높은 것만 남긴다(국소 중복 제거).
        val deduped = ArrayList<Candidate>()
        for (c in candidates.sortedBy { it.tMs }) {
            val last = deduped.lastOrNull()
            if (last != null && c.tMs - last.tMs < minGapMs) {
                if (c.score > last.score) deduped[deduped.lastIndex] = c
            } else {
                deduped += c
            }
        }

        val selected = ArrayList<Candidate>()
        val perChorusBest = deduped.groupBy { it.chorusIdx }.toSortedMap().values
            .mapNotNull { group -> group.maxByOrNull { it.score } }
        for (c in perChorusBest) {
            if (selected.size >= CLIMAX_MAX_COUNT) break
            selected += c
        }
        if (selected.size < CLIMAX_MAX_COUNT) {
            val remaining = deduped.filter { cand -> selected.none { it.tMs == cand.tMs } }
                .sortedByDescending { it.score }
            for (c in remaining) {
                if (selected.size >= CLIMAX_MAX_COUNT) break
                if (selected.none { abs(it.tMs - c.tMs) < minGapMs }) selected += c
            }
        }

        val result = selected.map { it.tMs }.sorted().map { it.coerceIn(0L, durationMs) }
        Log.d(TAG, "SectionDetectorV3 climax moments=${result.joinToString()} (absFloor=${"%.3f".format(absFloor)})")
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

    private class FloatList(val array: FloatArray) : AbstractList<Float>() {
        override val size get() = array.size
        override fun get(index: Int) = array[index]
    }
}
