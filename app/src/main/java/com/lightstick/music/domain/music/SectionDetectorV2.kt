package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SectionDetectorV2 — CHORUS 판정을 "반복 패턴 탐지" 기반으로 사용.
 *
 * 예전(V1) 방식은 CHORUS를 "이 순간이 이 곡 기준으로 얼마나 시끄러운가"(score >= highTh)로만
 * 판정했다. 이 방식은 "CHORUS = 곡에서 제일 크고 반복되는 후크"라는 실제 음악적 의미를
 * 반영하지 못한다 — 크기만 보기 때문에, 크지만 반복되지 않는 구간(예: 브릿지 클라이맥스)도
 * CHORUS로 잡히고, 반대로 진짜 후크인데 다른 구간보다 약간 조용하면 놓칠 수 있다.
 *
 * 지금은 곡 전체를 마디(bar) 단위 구간(기본 8마디)으로 나눠 구간별 특징 벡터를 뽑고,
 * 서로 떨어진 구간끼리 얼마나 닮았는지(코사인 유사도)를 전부 비교해서 "반복되는 구간 그룹"을
 * 찾는다. 그중 평균 에너지가 제일 높은 그룹을 CHORUS로 확정하고, 그 그룹에 속한 모든 구간
 * (첫 등장 포함)에 CHORUS를 붙인다 — 순차 처리라면 필연적으로 "처음 나올 때는 반복인지 알
 * 수 없는" 문제가 생기는데, 곡 전체를 미리 다 분석한 뒤 거꾸로 라벨링하는 2-pass 구조라서
 * 첫 등장부터 CHORUS로 잡힌다.
 *
 * 마디 정보(beatMs/beatsPerBar/downbeatMs)가 없거나 신뢰할 수 없으면 고정 길이 청크로
 * 폴백하고, 그래도 반복 그룹을 하나도 못 찾으면(스루컴포즈드 곡 등) 예전 방식인
 * loudness percentile(score >= highTh)로 폴백한다 — CHORUS를 아예 못 찾는 것보다
 * 안전한 기본값이라고 판단했다.
 *
 * 추가로:
 * - BRIDGE는 "VERSE나 CHORUS가 이 곡에서 한 번이라도 먼저 등장한 뒤"에만 인정한다. BRIDGE는
 *   음악적으로 "이미 진행되던 곡 구조를 한 번 전환하는 삽입부"라, 인트로 직후 첫 저에너지
 *   구간(아직 곡의 본 구조 자체가 시작 안 한 시점)까지 BRIDGE로 부르는 건 맞지 않다. 그
 *   전이라면 BRIDGE 대신 VERSE로 처리한다.
 * - CLIMAX는 국소적으로 튀는 지점(직전 대비 급상승)만으로 판정하던 기존 방식에 "이 곡
 *   전체의 절대 피크 대비 일정 비율 이상이어야 한다"는 절대 기준을 추가한다. 곡이 전체적으로
 *   잦아드는 구간(브릿지/아웃트로 진입부 등)에서 국소적으로만 살짝 튀는 지점이 절대 음량은
 *   한참 낮은데도 CLIMAX로 잘못 잡히던 문제를 막기 위함.
 *
 * INTRO/OUTRO/END 판정(detectIntroEnd/detectOutroStart/markIntroUpTo/markOutroFrom)과
 * 비트 단위 특징값 보강(annotateBeats)은 이전 버전과 동일하게 유지한다.
 *
 * 알려진 한계: 반복 그룹 판정은 에너지/스펙트럼비율/onset밀도/리듬 규칙성(periodicity)
 * 특징 벡터의 코사인 유사도에만 의존한다. 실제 멜로디/가사 리듬이 다른데도 편성 밀도가
 * 비슷하게 편곡된 구간(예: 2절을 코러스급으로 키운 곡)은 이 특징들로는 VERSE와 CHORUS가
 * 구분이 안 될 수 있다 — LE SSERAFIM 'SPAGHETTI' 86~101초 구간에서 score/periodicity/
 * onset 타이밍 상관계수 전부로 확인. 멜로디/보컬 패턴(피치·크로마) 비교 없이는 원리적
 * 한계로 보고 있다.
 */
class SectionDetectorV2 : SectionDetector {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val WINDOW_MS      = 2_000L
        private const val STRIDE_MS      = 2_000L
        private const val MIN_SECTION_MS  = 4_000L
        private const val COMPACT_MIN_MS  = 10_000L

        private const val SECTION_STRONG_CHANGE_TH = 0.24f
        private const val SECTION_MEDIUM_CHANGE_TH = 0.14f

        private const val ALIGN_SNAP_MS = 500L

        private const val CLIMAX_WINDOW_HALF_MS = 2_000L
        private const val CLIMAX_MIN_CV         = 0.35f
        private const val CLIMAX_MIN_PEAK_RATIO = 2.0f
        // 국소 스파이크가 아무리 뚜렷해도, 곡 전체 절대 피크의 이 비율 미만이면 CLIMAX 후보에서
        // 제외한다 — 잦아드는 구간에서 "직전보다 튀었다"만으로 CLIMAX가 잘못 잡히는 걸 방지.
        private const val CLIMAX_ABS_FLOOR_RATIO = 0.70f

        private const val BREAK_MAX_MS = 8_000L
        // BREAK는 "상대적으로 조용한 구간"이 아니라 "곡 전체 피크 대비 거의 무음인 구간"만
        // 가리키도록 제한한다 — 이펙트 쪽에서 BREAK를 BREATH(은은하게 깜빡임) 대신
        // 특정 색을 켠 채 고정(freeze)하는 용도로 쓰기 때문에, 단순히 조용한 VERSE/BRIDGE
        // 까지 여기 걸리면 안 된다. 윈도우 안에서 가장 큰 순간(peakEnergy)조차 곡 전체
        // 절대 피크의 이 비율 미만이어야 "완전 무음"으로 인정한다.
        private const val BREAK_SILENCE_RATIO = 0.05f

        private const val INTRO_SUSTAIN_RATIO = 0.8f
        // INTRO 종료 판정: 발라드/EDM 등에서 보컬·비트가 이미 시작됐는데도 프레이즈 사이
        // 숨쉬는 구간(순간적으로 점수가 threshold 아래로 떨어지는 지점) 때문에 "연속 N개 윈도우
        // 유지" 조건이 한참 뒤에야 만족되어 INTRO가 실제보다 훨씬 길게 잡히는 문제가 있었다
        // (실측: 사랑 참 21초→48초, 아모르 파티 9초→30초). 그래서 INTRO는 threshold를 넘는
        // 첫 윈도우 하나로 종료 판정하고, OUTRO는 기존처럼 연속 유지를 요구해 곡 말미의
        // 일시적 스파이크로 OUTRO가 너무 일찍 시작되지 않게 한다.
        private const val INTRO_SUSTAIN_WINDOWS = 1
        private const val OUTRO_SUSTAIN_WINDOWS = 2

        // ── 반복 패턴(CHORUS) 탐지 파라미터 ──
        // 청크(비교 단위) 길이: 마디 정보가 있으면 이 마디 수, 없으면 고정 ms로 대체.
        private const val CHORUS_PHRASE_BARS = 8
        private const val CHORUS_FALLBACK_CHUNK_MS = 8_000L
        // 반복 탐지를 시도하기 위한 최소 청크 개수 — 너무 적으면 통계적으로 의미가 없어 폴백.
        private const val CHORUS_MIN_CHUNKS = 4
        // "닮았다"의 기준: 곡 내 상대 percentile과 절대 하한을 동시에 만족해야 한다.
        private const val CHORUS_SIM_ABS_FLOOR = 0.90f
        private const val CHORUS_SIM_PERCENTILE = 0.85f
        // 반복 그룹에 속한 청크라도, 그 순간 score가 highTh의 이 비율 미만이면 CHORUS로
        // 확정하지 않는다 — 청크(8마디) 안에 섞인 프리코러스 꼬리 등이 청크 전체를
        // CHORUS로 물들이는 것을 막기 위함 (아래 classifyType 주석 참고).
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

        val windows = buildFeatureWindows(low, mid, full, high, novelty, globalPeriodicity, durationMs, hopMs, beatMs)

        val frameScores = windows.map { it.score }
        val lowTh  = if (frameScores.isNotEmpty()) percentile(frameScores, 0.35f) else 0f
        val highTh = if (frameScores.isNotEmpty()) percentile(frameScores, 0.70f) else 1f
        Log.d(TAG, "SectionDetectorV2 thresholds: lowTh=${"%.3f".format(lowTh)} highTh=${"%.3f".format(highTh)}")

        val chorusSpans = detectChorusSpansByRepetition(windows, durationMs, beatMs, beatsPerBar, downbeatMs, highTh, novelty, hopMs)
        Log.d(TAG, "SectionDetectorV2 chorusSpans(repetition)=${chorusSpans.map { "${it.first}~${it.last}" }}")

        windows.forEachIndexed { idx, w ->
            Log.d(TAG, "SectionDetectorV2 rawWindow[$idx] ${w.startMs}~${w.endMs} " +
                "energy=${"%.3f".format(w.energy)} onset=${"%.3f".format(w.onsetDensity)} " +
                "activity=${"%.3f".format(w.activity)} low=${"%.3f".format(w.lowRatio)} " +
                "mid=${"%.3f".format(w.midRatio)} high=${"%.3f".format(w.highRatio)} " +
                "periodicity=${"%.3f".format(w.periodicity)} score=${"%.3f".format(w.score)} " +
                "type=${classifyType(w, lowTh, highTh, chorusSpans, globalPeakFull)} change=${w.changeStrength}")
        }

        val introEndMs = detectIntroEnd(windows, lowTh)
        val outroStartMs = detectOutroStart(windows, lowTh, durationMs)
        Log.d(TAG, "SectionDetectorV2 introEndMs=$introEndMs outroStartMs=$outroStartMs")

        val rawSections = buildSectionsFromWindows(windows, durationMs, lowTh, highTh, chorusSpans, globalPeakFull)

        val beatBoundaries = beats.map { it.timeMs }.sorted().toLongArray()
        val alignedSections = alignBoundariesToBars(rawSections, beatBoundaries, durationMs)
        // BRIDGE 위치 제약(demoteLongBreaks)보다 INTRO/OUTRO 낙인(applyIntroOutro)을 먼저 적용한다.
        // 순서가 반대면 demoteLongBreaks가 "VERSE/CHORUS 등장 여부"를 셀 때 아직 INTRO로
        // 지워지기 전인 인트로 구간 내부의 원시 분류(예: 인트로 막판의 짧은 VERSE/CHORUS 구간)
        // 까지 "곡 구조 시작"으로 잘못 세어버려서, INTRO 바로 뒤에 오는 첫 섹션이 BRIDGE로
        // 남는 문제가 있었다 (실측: 사랑 참/아모르 파티/TOMBOY 등 다수 곡에서 재현).
        val introLabeledSections = applyIntroOutro(alignedSections, introEndMs, outroStartMs)
        val labeledSections = demoteLongBreaks(introLabeledSections)

        val sections = toSections(labeledSections)
        val climaxMoments = detectClimaxMoments(full, durationMs, hopMs, beatMs)
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

            // 곡 전체 한 번 계산한 globalPeriodicity를 그대로 복사하면 모든 윈도우가
            // 똑같은 값(예: 0.703)을 갖게 되어 구간별 리듬 규칙성 차이를 전혀 구분 못 한다
            // (CHORUS는 보통 마디 패턴이 딱 맞게 반복되고, 보컬 위주 VERSE는 상대적으로
            // 덜 규칙적인 경우가 많은데 이 신호를 못 쓰고 있었다). 이 윈도우 구간만으로
            // 로컬하게 재계산하고, 윈도우가 너무 짧아 lag만큼 샘플이 안 나오면(마지막
            // partial 윈도우 등) 곡 전체 값으로 폴백한다.
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
            val win = draft.copy(changeStrength = change)
            windows += win
            prev = win
            startIdx += strideFrames
        }

        return windows
    }

    // ──────────────────────────────────────────────────────────────
    // ② CHORUS 반복 패턴 탐지
    // ──────────────────────────────────────────────────────────────

    // 곡을 마디(bar) 기준 청크로 나눠 서로 닮은 청크 그룹을 찾고, 그중 평균 에너지가 제일 높은
    // 그룹을 CHORUS로 확정해 그 그룹에 속한 모든 청크(첫 등장 포함)의 시간 범위를 반환한다.
    // 반복을 못 찾으면(청크가 너무 적거나, 닮은 쌍이 하나도 없거나) 빈 리스트를 반환하고,
    // 호출부(classifyType)가 이걸 보고 예전 방식(score >= highTh)으로 폴백한다.
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
            Log.d(TAG, "SectionDetectorV2 chorus-repeat: 청크 부족(${chunks.size}개) → score 폴백")
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

        // 진단용: 모든 청크 쌍의 유사도 원본값 — verse/chorus 클러스터 경계에 걸치는
        // "다리" 청크가 있는지 확인하는 용도(예: verse2가 chorus 그룹과도, verse1 그룹과도
        // 애매하게 닮아 union-find가 두 그룹을 잘못 합치는 경우). adb logcat | grep
        // "chunk-sim"으로 캡처. 동작에는 영향 없음.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sim = sims[i.toLong() * 10000L + j] ?: continue
                Log.d(TAG, "SectionDetectorV2 chunk-sim [$i]${chunks[i].first}~${chunks[i].last} <-> " +
                    "[$j]${chunks[j].first}~${chunks[j].last} sim=${"%.4f".format(sim)}")
            }
        }

        // 진단용: 청크 쌍별 onset(어택 타이밍) 패턴 상관계수 — 평균 통계(위 chunk-sim)가
        // 아니라 novelty 시계열 "모양" 자체가 얼마나 겹치는지 본다. 실측 결과 확실한 반복
        // 쌍(chunk-sim이 매우 높은 쌍)끼리도 이 상관계수가 낮게 나오는 경우가 있어(0-lag
        // 비교라 타이밍이 조금만 어긋나도 틀어짐), 현재는 판정에 반영하지 않고 참고용으로만
        // 남겨둔다. adb logcat | grep "onset-corr"으로 캡처.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val corr = onsetPatternCorrelation(novelty, hopMs, chunks[i], chunks[j])
                Log.d(TAG, "SectionDetectorV2 onset-corr [$i]${chunks[i].first}~${chunks[i].last} <-> " +
                    "[$j]${chunks[j].first}~${chunks[j].last} corr=${"%.4f".format(corr)}")
            }
        }

        val simThreshold = max(CHORUS_SIM_ABS_FLOOR, percentile(allSims, CHORUS_SIM_PERCENTILE))
        Log.d(TAG, "SectionDetectorV2 chorus-repeat: chunks=$n simThreshold=${"%.3f".format(simThreshold)}")

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
            Log.d(TAG, "SectionDetectorV2 chorus-repeat: 반복 그룹 없음 → score 폴백")
            return emptyList()
        }

        val best = candidateGroups.maxByOrNull { g -> g.map { chunkScores[it] }.average() } ?: return emptyList()
        val bestAvgScore = best.map { chunkScores[it] }.average().toFloat()
        if (bestAvgScore < medianScore) {
            Log.d(TAG, "SectionDetectorV2 chorus-repeat: 최선 그룹 에너지(${"%.3f".format(bestAvgScore)})가 " +
                "중앙값(${"%.3f".format(medianScore)})보다 낮음 → score 폴백")
            return emptyList()
        }

        Log.d(TAG, "SectionDetectorV2 chorus-repeat: 그룹 선택 idx=${best.sorted()} avgScore=${"%.3f".format(bestAvgScore)}")
        return best.map { chunks[it] }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    // 두 구간(rangeA/rangeB)의 novelty(어택 강도) 시계열을 0-lag로 직접 비교한다.
    // chunk-sim(평균 통계 비교)과 달리, 각 구간을 z-score 정규화해 크기(평균 에너지)
    // 차이를 지운 뒤 "모양"만 상관계수로 비교한다 — 같은 멜로디/리듬 프레이즈가
    // 반복되면 onset 타이밍이 거의 그대로 겹칠 것이라는 가설을 검증하기 위함. 다만 실측
    // 결과 확실한 반복 쌍에서도 낮게 나오는 경우가 있어(타이밍 미세 흔들림에 취약한
    // 0-lag 비교의 한계) 현재는 참고용 로그로만 사용한다.
    // 두 구간 길이가 다르면 짧은 쪽에 맞춰 자른다(마디 정렬돼 있어 0-lag 비교로 충분,
    // 템포가 곡 내내 일정하다고 가정).
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

    // chorusSpans가 비어있지 않으면(반복 탐지 성공) CHORUS는 오직 그 스팬 안에 있을 때만 붙는다
    // (score와 무관). chorusSpans가 비어있으면(반복 탐지 실패) 예전 방식과 동일하게
    // score >= highTh로 CHORUS를 판정하는 폴백 경로를 탄다.
    private fun classifyType(
        w: FeatureWindow, lowTh: Float, highTh: Float, chorusSpans: List<LongRange>, globalPeakFull: Float
    ): SectionDetector.SectionType {
        val mid = (w.startMs + w.endMs) / 2
        if (chorusSpans.isNotEmpty()) {
            // 청크(8마디, ~17초)가 반복 그룹에 속해도, 그 청크 안에 프리코러스 꼬리처럼
            // 아직 덜 올라온 구간이 섞여 있으면 청크 경계 그대로 CHORUS를 칠하는 게 아니라
            // 그 순간 score가 실제로 충분히 높을 때만 CHORUS로 확정한다 — 청크 경계가 아닌
            // 실제 음향 전환 지점에서 갈리도록. (LE SSERAFIM 'SPAGHETTI' 실측: 청크는
            // 35초부터 반복 그룹이지만 실제 score 도약은 42초에 일어남 — 0.178→0.336,
            // change=MEDIUM)
            if (chorusSpans.any { mid in it } && w.score >= highTh * CHORUS_SPAN_SCORE_FLOOR_RATIO)
                return SectionDetector.SectionType.CHORUS
        } else if (w.score >= highTh) {
            return SectionDetector.SectionType.CHORUS
        }
        val bridgeTh = lowTh * 0.85f
        val breakTh  = lowTh * 0.45f
        // score가 낮다고 전부 BREAK는 아니다 — BREAK는 "거의 무음"인 구간만 가리켜야 하므로
        // 윈도우 안의 최대 순간 에너지(peakEnergy)가 곡 전체 절대 피크 대비 BREAK_SILENCE_RATIO
        // 미만일 때만 인정한다. 이 조건을 못 넘으면(= score는 낮지만 소리는 나는 구간) BRIDGE로
        // 떨어진다.
        val isNearSilent = w.peakEnergy <= globalPeakFull * BREAK_SILENCE_RATIO
        return when {
            w.score <= breakTh && isNearSilent -> SectionDetector.SectionType.BREAK
            w.score <= bridgeTh -> SectionDetector.SectionType.BRIDGE
            else                -> SectionDetector.SectionType.VERSE
        }
    }

    // BRIDGE는 "이미 진행되던 곡 구조를 전환하는 삽입부"라는 의미라, VERSE/CHORUS가 한 번도
    // 나오지 않은 시점(=인트로 직후)엔 맞지 않는다. 이 판정은 합쳐지고(compact) 마디에 맞춰
    // 정렬된(align) 최종 섹션 단위로, 그리고 반드시 applyIntroOutro로 INTRO 구간이 이미
    // 낙인찍힌 뒤에 수행한다. 원래 윈도우 단위(2초짜리)로 하면 인트로 안에서의 순간적인
    // 에너지 튐(예: 훅 한 소절)만으로도 "VERSE 등장"으로 오판되어 그 직후 다시 잦아드는
    // 구간이 BRIDGE로 풀려버리는 문제가 있었다. 섹션 단위로 옮긴 뒤에도 INTRO 낙인을 나중에
    // 적용하면, 아직 INTRO로 지워지기 전인 인트로 구간 내부에 살아남은 VERSE/CHORUS 섹션이
    // "곡 구조 시작"으로 잘못 세어져 INTRO 바로 다음 섹션이 BRIDGE로 남는 문제가 재발했다
    // (실측: 사랑 참/아모르 파티/TOMBOY 등). INTRO 낙인을 먼저 적용해두면 인트로 구간은
    // 전부 타입이 INTRO로 바뀌어 있어 VERSE/CHORUS로 집계되지 않으므로, 진짜 인트로 이후에
    // 살아남은 VERSE/CHORUS만 "곡 구조 시작"으로 인정하게 된다.
    // 같은 이유로 BREAK가 너무 길게 이어지는 구간(반주만 계속되는 구간)도 이 시점 이전이면
    // BRIDGE로 격상하지 않고 VERSE로 둔다.
    private fun demoteLongBreaks(sections: List<FeatureWindow>): List<FeatureWindow> {
        var seenVerseOrChorus = false
        return sections.map { s ->
            val result = when {
                s.sectionType == SectionDetector.SectionType.BREAK &&
                    (s.endMs - s.startMs) > BREAK_MAX_MS ->
                    if (seenVerseOrChorus) s.copy(sectionType = SectionDetector.SectionType.BRIDGE)
                    else s.copy(sectionType = SectionDetector.SectionType.VERSE)
                s.sectionType == SectionDetector.SectionType.BRIDGE && !seenVerseOrChorus ->
                    s.copy(sectionType = SectionDetector.SectionType.VERSE)
                else -> s
            }
            if (result.sectionType == SectionDetector.SectionType.VERSE ||
                result.sectionType == SectionDetector.SectionType.CHORUS) seenVerseOrChorus = true
            result
        }
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
        // demoteLongBreaks()에서 압축·정렬이 끝난 섹션 단위로 한 번만 적용한다. 자세한 이유는
        // demoteLongBreaks 주석 참고.
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

    private fun compactSections(input: List<FeatureWindow>, chorusSpans: List<LongRange>): List<FeatureWindow> {
        if (input.size <= 1) return input
        val list = input.toMutableList()
        var changed = true
        while (changed && list.size > 1) {
            changed = false
            var shortIdx = -1; var shortDur = Long.MAX_VALUE
            for (i in list.indices) {
                val d = list[i].endMs - list[i].startMs
                // BREAK_MAX_MS(8초)를 넘는 BREAK는 이미 "끊김이 아니라 구조적으로 의미있다"는
                // 뜻이라(demoteLongBreaks가 BRIDGE로 승격시킬 대상), COMPACT_MIN_MS(10초) 미만
                // 이라는 이유만으로 인접 구간에 흡수돼 사라지면 안 된다. demoteLongBreaks는
                // 이 compactSections보다 나중에(정렬/바 스냅 이후) 실행되므로, 여기서 먼저
                // 지워지면 그 기회 자체가 없어진다.
                val protectedLongBreak = list[i].sectionType == SectionDetector.SectionType.BREAK &&
                    d >= BREAK_MAX_MS
                // CHORUS가 아닌 구간이 CHORUS 섹션 양쪽에 끼여 있으면(=반복 탐지가 이미
                // 비-CHORUS로 판정한 구간이 10초 미만 조각들로 쪼개져 CHORUS 사이에 낀 경우)
                // 어느 쪽으로도 흡수시킬 수 없다 — 흡수시키면 반복 탐지가 가려낸 CHORUS 경계가
                // 지워져서 VERSE/BRIDGE 구간이 거대한 CHORUS 한 덩어리로 뭉개진다 (실측:
                // ILLIT 'It's Me' 40~54초 구간 — chunk 반복 탐지는 이 구간을 CHORUS 그룹에서
                // 정확히 제외했는데, VERSE(6초)+BRIDGE(6초)+VERSE(2초)로 쪼개져 있다 보니
                // 하나씩 압축되며 양옆 CHORUS에 흡수되어 사라졌다). 이런 경우는 압축 대상에서
                // 제외해 그대로 남긴다.
                val bothNeighborsChorus = i > 0 && i < list.lastIndex &&
                    list[i - 1].sectionType == SectionDetector.SectionType.CHORUS &&
                    list[i + 1].sectionType == SectionDetector.SectionType.CHORUS
                val protectedChorusGap = chorusSpans.isNotEmpty() &&
                    list[i].sectionType != SectionDetector.SectionType.CHORUS && bothNeighborsChorus
                if (d < COMPACT_MIN_MS && d < shortDur && !protectedLongBreak && !protectedChorusGap) {
                    shortDur = d; shortIdx = i
                }
            }
            if (shortIdx < 0) break
            val s = list[shortIdx]
            val prevOk = shortIdx > 0
            val nextOk = shortIdx < list.lastIndex
            // 위와 같은 이유로, CHORUS가 아닌 구간은 한쪽 이웃만 CHORUS인 경우에도 그
            // CHORUS 쪽으로는 흡수되지 않고 반대쪽(비-CHORUS) 이웃으로만 흡수된다.
            val prevIsChorus = prevOk && list[shortIdx - 1].sectionType == SectionDetector.SectionType.CHORUS
            val nextIsChorus = nextOk && list[shortIdx + 1].sectionType == SectionDetector.SectionType.CHORUS
            val avoidChorusAbsorb = chorusSpans.isNotEmpty() && s.sectionType != SectionDetector.SectionType.CHORUS
            val absorberIdx = when {
                !prevOk  -> shortIdx + 1
                !nextOk  -> shortIdx - 1
                avoidChorusAbsorb && prevIsChorus && !nextIsChorus -> shortIdx + 1
                avoidChorusAbsorb && nextIsChorus && !prevIsChorus -> shortIdx - 1
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
            Log.d(TAG, "SectionDetectorV2[$idx] ${s.startMs}~${s.endMs} type=${s.sectionType} change=${s.changeStrength}")
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

    private fun estimatePeriodicityGlobal(novelty: FloatArray, beatMs: Long, hopMs: Long): Float {
        if (novelty.isEmpty()) return 0f
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag >= novelty.size) return 0f
        var ac = 0f; var raw = 0f
        for (i in lag until novelty.size) ac += novelty[i] * novelty[i - lag]
        for (v in novelty) raw += v * v
        return if (raw <= 1e-6f) 0f else (ac / raw).coerceIn(0f, 1f)
    }

    // estimatePeriodicityGlobal과 동일한 beat-lag 자기상관 계산을, 곡 전체가 아니라
    // startIdx~endIdx 구간(윈도우 하나)의 novelty 샘플만으로 수행한다. 곡 전체 한 번
    // 계산해서 모든 윈도우에 복사하던 기존 방식은 구간별 리듬 규칙성 차이를 전혀 반영하지
    // 못했다.
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

    private fun detectClimaxMoments(
        full: FloatArray, durationMs: Long, hopMs: Long, beatMs: Long
    ): List<Long> {
        if (full.size < 8) return emptyList()

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
        val selected = ArrayList<Long>()

        val climaxIntroLimit = (durationMs * 0.30f).toLong()
        for (i in 2 until scoreArray.size - 2) {
            val sc = scoreArray[i]; if (sc <= 0f) continue
            val tMs = i.toLong() * hopMs
            if (tMs < climaxIntroLimit) continue
            if (full[i] < absFloor) continue
            if (sc >= scoreArray[i-1] && sc >= scoreArray[i-2] && sc >= scoreArray[i+1] && sc >= scoreArray[i+2] &&
                sc >= p90 * 1.18f && sc >= envMean + envStd * 1.30f) {
                if (selected.none { abs(it - tMs) < minGapMs }) {
                    selected += tMs
                    if (selected.size >= 3) break
                }
            }
        }

        val result = selected.sorted().map { it.coerceIn(0L, durationMs) }
        Log.d(TAG, "SectionDetectorV2 climax moments=${result.joinToString()} (absFloor=${"%.3f".format(absFloor)})")
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
