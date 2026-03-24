package com.lightstick.music.domain.music

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AutoTimelineGeneratorBeat v8 (개선판)
 *
 * v8 원본 대비 수정 사항:
 * ① buildSectionBeatGrid — actualBeats 실제 사용 (실제 비트 >= 기대값 60% 이상이면 우선 적용,
 *                           부족한 경우에는 그리드 생성 후 실제 비트에 스냅)
 * ② climaxMoments 프레임 반영 — BLINK/STROBE 섹션에서 클라이맥스 인접 비트에 반박자 프레임 추가
 * ③ CHORUS 엔진 분리 — beatMs 기준으로 STROBE(≤400ms) / BLINK(>400ms) 배정 (v7 수준 회복)
 * ④ forceTransitFromZero 전달 — buildSections 파라미터에 추가하여 짧은 인트로 케이스 정확 처리
 * ⑤ 멀티채널 섹션 에너지 스코어 — low/mid/full 세 채널 조합으로 섹션 분류 정밀도 향상
 * ⑥ adjustBridges 중복 제거 — buildContentSection에서 엔진 결정을 완결하고 adjustBridges 제거
 * ⑦ mergeSmallSections 타입 손실 수정 — 더 긴 섹션의 타입/엔진을 채택
 */
class AutoTimelineGeneratorBeat_v8 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION = 8
        private const val HOP_MS = 50L

        // BeatDetectorV8.Params.minBeatMs와 동일하게 유지 (≈207 BPM)
        private const val MIN_BEAT_MS = 290L
        // BeatDetectorV8.Params.maxBeatMs와 동일하게 유지 (≈50 BPM)
        private const val MAX_BEAT_MS = 1200L

        private const val ON_TRANSIT = 2

        private const val INTRO_PRESTART_TRANSIT_MS = 1_000L
        private const val MIN_SECTION_MS = 1_000L
        private const val SECTION_MERGE_GAP_MS = 0L    // 600→0: 같은 타입일 때만 병합, 섹션 세분화

        // ① 실제 비트 사용 비율 기준 (기대 비트 수 대비 60% 이상이면 실제 비트 우선)
        private const val ACTUAL_BEAT_USE_RATIO = 0.45f  // 완화: 0.6→0.45 (실제 비트 활용 기준 낮춤)

        // ② 클라이맥스 윈도우 범위 (클라이맥스 피크로부터 ±4초 이내를 클라이맥스 구간으로 간주)
        private const val CLIMAX_WINDOW_HALF_MS = 4_000L

        // EFX 분석 기반 섹션 경계 전략 임계값
        // EFX 실제 최소 갭 = 2.6초 → 2초 기준
        private const val SECTION_GAP_BREATH_THRESHOLD_MS = 2_000L

        // EFX P1-2: ON_PULSE beat 체류시간 비대칭
        // EFX 실측: white = 720ms(긴 여운), cMain/colorGroup = 200ms(짧은 강조)
        // white가 FG일 때: min(BASE_HOLD, beatMs-50ms) 로 긴 여운 표현
        // colorGroup이 FG일 때: min(ACCENT_HOLD, beatMs*2/5) 로 짧은 강조 표현
        private const val ON_PULSE_BASE_HOLD_MS   = 700L   // white 체류: EFX 720ms 기준
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L   // cMain 체류: EFX 200ms 기준
    }

    private enum class EnvMode {
        LOW, MID, FULL
    }

    enum class FgEngine {
        ON_PULSE,
        BLINK,
        STROBE,
        BREATH,
        ON_TRANSIT_ROTATE,
        OFF_TRANSIT
    }

    enum class SectionType {
        INTRO,
        VERSE,
        CHORUS,
        BRIDGE,
        END
    }

    enum class ChangeLevel {
        LOW,
        MEDIUM,
        STRONG
    }

    /**
     * 엔진별 고정 컬러 세트 (FG + BG 쌍)
     * musicId 기반 1회 생성 → 곡 전체에서 동일한 색 체계 유지
     */
    data class ColorSet(val fg: LSColor, val bg: LSColor)

    data class Palette(
        // 공통
        val black: LSColor,
        val white: LSColor,
        // 엔진별 컬러 세트 (1~2개)
        val onPulseSets: List<ColorSet>,    // ON_PULSE: 2세트
        val blinkSets:   List<ColorSet>,    // BLINK: 2세트 (placeholder)
        val strokeSets:  List<ColorSet>,    // STROBE: 1세트 고정
        val breathSet:   ColorSet,          // BREATH: 1세트
        val bridgeSets:  List<ColorSet>,    // BRIDGE: 2세트 (placeholder)
        val chorusBg:    LSColor,           // legacy 호환
        // EFX 레인보우 그룹: beatIndex % 4 순환
        // [cMain, cStep1(+60°), cStep2(-60°), cStep3(-120°)]
        val colorGroup:  List<LSColor>
    )

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val engine: FgEngine,
        val beatMs: Long,
        val beats: Int,
        val source: String,
        val change: ChangeLevel,
        val energyScore: Float = 0f,   // 구간 절대 에너지 점수 (0~1)
        val relScore: Float = 0f        // 곡 내 상대 에너지 (0=최저, 1=최고, lowTh~highTh 기준 정규화)
    )

    // =========================================================================
    // Public entry point
    // =========================================================================

    fun generate(
        musicPath: String,
        musicId: Int,
        @Suppress("UNUSED_PARAMETER") paletteSize: Int = 4,
        albumBitmap: Bitmap? = null
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "v8 generate() start file=$musicPath musicId=$musicId paletteSize=$paletteSize")

        // 앨범아트 색상 추출 시도 → 흑백/무채색이면 musicId hash 방식 fallback
        val palette = run {
            val albumColors = albumBitmap?.let { AlbumColorExtractor.extract(it) }
            if (albumColors != null) {
                Log.d(TAG, "palette source=${albumColors.source} " +
                        "cMain=(${albumColors.primary.r},${albumColors.primary.g},${albumColors.primary.b})")
                buildPalette(albumColors)
            } else {
                if (albumBitmap != null) {
                    Log.d(TAG, "palette fallback to musicId — album art is grayscale/achromatic")
                }
                buildPalette(musicId)
            }
        }

        val lowEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.LOW)
        val midEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.MID)
        val fullEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.FULL)

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "env empty -> return empty")
            return emptyList()
        }

        val envSize = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        if (envSize <= 0) {
            Log.w(TAG, "envSize=0 -> return empty")
            return emptyList()
        }

        val durationMs = envSize.toLong() * HOP_MS

        val detect = BeatDetectorV8.detect(
            lowEnv = lowEnv.take(envSize),
            midEnv = midEnv.take(envSize),
            fullEnv = fullEnv.take(envSize),
            params = BeatDetectorV8.Params(
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,         // 290ms (≈207 BPM)
                maxBeatMs = MAX_BEAT_MS,          // 1200ms (≈50 BPM)
                minPeakDistanceMs = 140L,         // 290ms의 절반 수준으로 낮춤 (빠른 곡 peak 간격 대응)
                onsetSmoothWindow = 3,
                segmentMs = 20_000L,
                peakThresholdK = 0.55f,
                minPeakAbs = 0.08f,
                snapToleranceMs = 100L,           // 290ms 기준 비율 유지 (120→100)
                chainToleranceMs = 120L,          // 290ms 기준 비율 유지 (140→120)
                minChainCount = 3
            )
        )

        val beatTimes = detect.beatTimesMs
            .filter { it in 0..durationMs }
            .sorted()

        if (beatTimes.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return emptyList()
        }

        Log.d(
            TAG,
            "beat detect OK totalBeats=${beatTimes.size} " +
                    "beatMs=${detect.beatMs} first=${beatTimes.firstOrNull()} last=${beatTimes.lastOrNull()}"
        )

        val beatMs = detect.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        // 섹션별 beatMs 조회 맵
        // G1-a: 900ms 초과 값 절반 교정 (harmonic double 오작동으로 1200ms가 들어오는 경우)
        // G1-b: FAIL 세그먼트 → 직전/직후 성공 세그먼트 beatMs를 보간하여 공백 채움
        val BEAT_HALVE_THRESHOLD = 900L
        val rawBeatMsMap: MutableMap<Long, Long> = mutableMapOf()
        for (seg in detect.debugSegments) {
            if (seg.reason == "ok" && seg.beatMs > 0L) {
                var corrected = seg.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
                // 900ms 초과면 절반으로 교정 (느린 곡 harmonic double 오작동 방어)
                if (corrected > BEAT_HALVE_THRESHOLD) {
                    val half = corrected / 2L
                    if (half >= MIN_BEAT_MS) {
                        Log.d(TAG, "segMap halve: seg=${seg.startMs} ${corrected}ms→${half}ms")
                        corrected = half
                    }
                }
                rawBeatMsMap[seg.startMs] = corrected
            }
        }
        // FAIL 세그먼트 보간: 직전 또는 직후 성공값으로 채움
        val allSegStarts = detect.debugSegments.map { it.startMs }.sorted()
        val segmentBeatMsMap: Map<Long, Long> = buildMap {
            putAll(rawBeatMsMap)
            for (seg in detect.debugSegments) {
                if (!rawBeatMsMap.containsKey(seg.startMs)) {
                    // 직전 성공값 탐색
                    val prev = allSegStarts.filter { it < seg.startMs }
                        .lastOrNull { rawBeatMsMap.containsKey(it) }
                    // 직후 성공값 탐색
                    val next = allSegStarts.filter { it > seg.startMs }
                        .firstOrNull { rawBeatMsMap.containsKey(it) }
                    val interpolated = when {
                        prev != null && next != null -> (rawBeatMsMap[prev]!! + rawBeatMsMap[next]!!) / 2L
                        prev != null -> rawBeatMsMap[prev]!!
                        next != null -> rawBeatMsMap[next]!!
                        else -> null
                    }
                    if (interpolated != null) {
                        Log.d(TAG, "segMap interpolate: seg=${seg.startMs} → ${interpolated}ms (prev=$prev next=$next)")
                        put(seg.startMs, interpolated)
                    }
                }
            }
        }
        Log.d(TAG, "segmentBeatMsMap entries=${segmentBeatMsMap.size} $segmentBeatMsMap")

        val firstMusicMs = detectFirstMusicStartMs(
            energyFrames = fullEnv.take(envSize).toFloatArray(),
            hopMs = HOP_MS
        ).coerceIn(0L, durationMs)

        // ④ 수정: forceTransitFromZero를 buildSections에 전달하기 위해 여기서 계산 후 파라미터로 넘김
        val forceTransitFromZero = firstMusicMs <= INTRO_PRESTART_TRANSIT_MS

        val introEndMs = when {
            firstMusicMs <= 0L -> 0L
            firstMusicMs <= INTRO_PRESTART_TRANSIT_MS -> 0L
            else -> firstMusicMs - INTRO_PRESTART_TRANSIT_MS
        }

        Log.d(
            TAG,
            "intro tuning firstMusicMs=$firstMusicMs introEndMs=$introEndMs " +
                    "forceTransitFromZero=$forceTransitFromZero durationMs=$durationMs"
        )

        // climaxMoments를 먼저 계산해야 buildSections에 전달 가능
        val climaxMoments = detectClimaxPeakMoments(
            fullEnv = fullEnv.take(envSize),
            durationMs = durationMs,
            beatMs = beatMs
        )
        Log.d(TAG, "climax moments=${climaxMoments.joinToString()}")

        // ④⑤ 수정: forceTransitFromZero 및 lowEnv/midEnv를 buildSections에 전달
        // climaxMoments도 함께 전달하여 CHORUS 엔진 결정에 활용
        val sections = buildSections(
            beatMs = beatMs,
            lowEnv = lowEnv.take(envSize),
            midEnv = midEnv.take(envSize),
            fullEnv = fullEnv.take(envSize),
            firstMusicMs = firstMusicMs,
            durationMs = durationMs,
            forceTransitFromZero = forceTransitFromZero,
            climaxMoments = climaxMoments,
            segmentBeatMsMap = segmentBeatMsMap
        )

        sections.forEachIndexed { idx, s ->
            Log.d(
                TAG,
                "section beat idx=$idx ${s.startMs}~${s.endMs} " +
                        "type=${s.type} beats=${s.beats} beatMs=${s.beatMs} " +
                        "source=${s.source} engine=${s.engine} change=${s.change}"
            )
        }

        val frames = buildFramesFromSections(
            musicId = musicId,
            palette = palette,
            sections = sections,
            beatTimes = beatTimes,
            durationMs = durationMs,
            climaxMoments = climaxMoments
        )

        Log.d(TAG, "v8 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // =========================================================================
    // Section building
    // =========================================================================

    /**
     * ④⑤ 수정:
     * - lowEnv, midEnv 파라미터 추가 → 멀티채널 에너지 스코어 사용
     * - forceTransitFromZero 파라미터 추가 → 짧은 인트로 케이스 정확 처리
     * - adjustBridges 호출 제거 (⑥ 수정: buildContentSection에서 완결)
     */
    private fun buildSections(
        beatMs: Long,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        firstMusicMs: Long,
        durationMs: Long,
        forceTransitFromZero: Boolean,
        climaxMoments: List<Long> = emptyList(),
        segmentBeatMsMap: Map<Long, Long> = emptyMap()
    ): List<Section> {
        // 섹션 시간 범위에 해당하는 세그먼트들의 beatMs 중앙값 반환
        // 매칭되는 세그먼트가 없으면 전체 중앙값(globalBeatMs) fallback
        fun localBeatMs(startMs: Long, endMs: Long): Long {
            if (segmentBeatMsMap.isEmpty()) return beatMs
            val matching = segmentBeatMsMap.entries
                .filter { (segStart, _) -> segStart >= (startMs - 20_000L).coerceAtLeast(0L) && segStart < endMs }
                .map { it.value }
            if (matching.isEmpty()) return beatMs
            val sorted = matching.sorted()
            val median = sorted[sorted.size / 2]
            // 최종 방어: 900ms 초과 값이 남아있으면 전체 beatMs로 fallback
            return if (median > 900L) beatMs else median
        }

        val raw = ArrayList<Section>()

        // 모든 곡: 0ms에서 OFF로 시작 (음악 시작 전 불필요한 INTRO BREATH 제거)
        // firstMusicMs > INTRO_MIN_GAP_MS(2초) 이상인 경우만 INTRO BREATH 구간 생성
        // → 바로 시작하는 곡은 OFF → 첫 섹션 이펙트로 바로 진입
        val introMinGapMs = 2_000L
        if (firstMusicMs > introMinGapMs) {
            // 전주가 충분히 긴 곡: INTRO 구간에 BREATH 이펙트
            val introStartMs = (firstMusicMs - INTRO_PRESTART_TRANSIT_MS).coerceAtLeast(0L)
            raw += Section(
                startMs = introStartMs,
                endMs = firstMusicMs,
                type = SectionType.INTRO,
                engine = FgEngine.BREATH,
                beatMs = beatMs,
                beats = estimateBeatCount(introStartMs, firstMusicMs, beatMs),
                source = "intro-breath",
                change = ChangeLevel.STRONG
            )
        }
        // firstMusicMs <= 2초: INTRO 섹션 없음 → 0ms OFF_TRANSIT에서 첫 섹션으로 바로 진입

        val contentStartMs = firstMusicMs
        if (contentStartMs >= durationMs) {
            return raw.filter { it.endMs > it.startMs }
        }

        // 섹션 분류 윈도우: 8비트 단위, 최소 4초
        // beatMs*4(2초)는 에너지 변화 감지가 어려움 → beatMs*8(4초)로 확대
        // 이유: K-pop의 일반적인 구조 단위(2마디=8비트)와 일치
        val winMs = (beatMs * 4L).coerceAtLeast(2_000L)  // 8→4 beats: 섹션 변화 더 세밀하게 감지
        val windows = ArrayList<Triple<Long, Long, Float>>()

        var t = contentStartMs
        while (t < durationMs) {
            val e = min(durationMs, t + winMs)
            // ⑤ 수정: 멀티채널 에너지 스코어 사용
            val score = sectionEnergyScore(lowEnv, midEnv, fullEnv, t, e)
            windows += Triple(t, e, score)
            t = e
        }

        if (windows.isEmpty()) {
            raw += Section(
                startMs = contentStartMs,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = beatMs,
                beats = estimateBeatCount(contentStartMs, durationMs, beatMs),
                source = "end-protected",
                change = ChangeLevel.STRONG
            )
            return raw.filter { it.endMs > it.startMs }
        }

        val scores = windows.map { it.third }
        val lowTh = percentile(scores, 0.35f)
        val highTh = percentile(scores, 0.70f)

        val contentSections = ArrayList<Section>()
        var currentStart = windows.first().first
        var currentEnd = windows.first().second
        var currentType = classifyType(windows.first().third, lowTh, highTh)
        var currentScoreSum = windows.first().third
        var currentScoreCount = 1

        for (i in 1 until windows.size) {
            val w = windows[i]
            val type = classifyType(w.third, lowTh, highTh)

            if (type == currentType || w.first - currentEnd <= SECTION_MERGE_GAP_MS) {
                if (type == currentType) {
                    currentEnd = w.second
                    currentScoreSum += w.third
                    currentScoreCount++
                } else {
                    val avgScore = if (currentScoreCount > 0) currentScoreSum / currentScoreCount else 0f
                    contentSections += buildContentSection(
                        startMs = currentStart,
                        endMs = currentEnd,
                        type = currentType,
                        beatMs = localBeatMs(currentStart, currentEnd),
                        energyScore = avgScore,
                        lowTh = lowTh,
                        highTh = highTh,
                        climaxMoments = climaxMoments
                    )
                    currentStart = w.first
                    currentEnd = w.second
                    currentType = type
                    currentScoreSum = w.third
                    currentScoreCount = 1
                }
            } else {
                val avgScore = if (currentScoreCount > 0) currentScoreSum / currentScoreCount else 0f
                contentSections += buildContentSection(
                    startMs = currentStart,
                    endMs = currentEnd,
                    type = currentType,
                    beatMs = localBeatMs(currentStart, currentEnd),
                    energyScore = avgScore,
                    lowTh = lowTh,
                    highTh = highTh,
                    climaxMoments = climaxMoments
                )
                currentStart = w.first
                currentEnd = w.second
                currentType = type
                currentScoreSum = w.third
                currentScoreCount = 1
            }
        }

        val lastAvgScore = if (currentScoreCount > 0) currentScoreSum / currentScoreCount else 0f
        contentSections += buildContentSection(
            startMs = currentStart,
            endMs = currentEnd,
            type = currentType,
            beatMs = localBeatMs(currentStart, currentEnd),
            energyScore = lastAvgScore,
            lowTh = lowTh,
            highTh = highTh,
            climaxMoments = climaxMoments
        )

        // ⑥ 수정: adjustBridges 제거 — buildContentSection에서 엔진/소스를 완결하므로 불필요
        val merged = mergeSmallSections(contentSections, beatMs)
        raw += merged

        val lastEnd = raw.maxOfOrNull { it.endMs } ?: 0L
        if (lastEnd < durationMs) {
            raw += Section(
                startMs = lastEnd,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = beatMs,
                beats = estimateBeatCount(lastEnd, durationMs, beatMs),
                source = "end-protected",
                change = ChangeLevel.STRONG
            )
        }

        return raw
            .map { s ->
                val clampedStart = s.startMs.coerceIn(0L, durationMs)
                val clampedEnd = s.endMs.coerceIn(0L, durationMs)
                s.copy(
                    startMs = clampedStart,
                    endMs = clampedEnd,
                    beats = estimateBeatCount(clampedStart, clampedEnd, s.beatMs)
                )
            }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
    }

    /**
     * ③⑥ 수정:
     * - CHORUS 엔진: beatMs ≤ 400ms → STROBE, 초과 → BLINK (v7 수준 회복)
     * - BRIDGE 엔진 결정 로직을 여기서 완결 (adjustBridges 중복 제거)
     */
    /**
     * [에너지 기반 엔진 결정 — 끝판왕 버전]
     *
     * relScore = (energyScore - lowTh) / (highTh - lowTh) → 0=곡 내 최저에너지, 1=최고에너지
     *
     * VERSE:
     *   relScore < 0.30 → BREATH   (아주 조용한 구간, 도입부/중간 휴식)
     *   relScore < 0.75 → ON_PULSE (일반 구간)
     *   relScore ≥ 0.75 → BLINK    (후렴 직전 에너지 상승 구간)
     *
     * CHORUS:
     *   relScore < 0.50 → BLINK    (중간 에너지 후렴)
     *   relScore ≥ 0.50 → STROBE   (고에너지 후렴, beatMs 기준 대신 에너지 기준으로 전환)
     *   beatMs ≤ 290ms  → 항상 STROBE (빠른 곡은 항상 STROBE)
     *
     * BRIDGE: beats 기반 페이즈 엔진 유지 (bridgePhaseEngine에서 per-beat 결정)
     *   단, section.engine은 relScore를 source 이름에만 반영
     */
    private fun buildContentSection(
        startMs: Long,
        endMs: Long,
        type: SectionType,
        beatMs: Long,
        energyScore: Float = 0f,
        lowTh: Float = 0f,
        highTh: Float = 1f,
        climaxMoments: List<Long> = emptyList()
    ): Section {
        val beats = estimateBeatCount(startMs, endMs, beatMs)
        val sectionMidMs = (startMs + endMs) / 2L
        val isClimaxSection = climaxMoments.any { kotlin.math.abs(it - sectionMidMs) <= 6_000L }

        val normalizedType = when {
            type == SectionType.BRIDGE && beats < 6 -> SectionType.VERSE
            else -> type
        }

        // 곡 내 상대 에너지: 0=최저, 1=최고 (lowTh~highTh 기준 정규화)
        val range = (highTh - lowTh).coerceAtLeast(1e-6f)
        val rel = ((energyScore - lowTh) / range).coerceIn(0f, 1f)

        val engine = when (normalizedType) {
            SectionType.VERSE -> when {
                // BREATH: 곡에서 에너지가 가장 낮은 구간(하위 10%)이면서
                // beats < 8 (약 4초 미만)인 짧은 구간에만 허용
                // → 긴 BREATH(14초 등)는 ON_PULSE로 대체하여 이펙트 정지 방지
                rel < 0.10f && beats < 8 -> FgEngine.BREATH
                rel < 0.75f -> FgEngine.ON_PULSE   // 일반 verse
                else        -> FgEngine.BLINK       // 에너지 높은 verse (후렴 직전)
            }
            SectionType.CHORUS -> when {
                // STROBE: climax 근처 후렴에만 사용 (파바박 효과)
                //   - period=1, randomDelay=3 으로 극속 파편 스트로브
                //   - relScore 포화 문제를 우회해 실제 음악 구조 기반 결정
                beatMs <= 290L  -> FgEngine.STROBE  // 빠른 곡 (207+ BPM)
                isClimaxSection -> FgEngine.STROBE  // climax ±6s 후렴만 STROBE
                rel >= 0.40f    -> FgEngine.BLINK   // 일반 후렴
                else            -> FgEngine.ON_PULSE // 낮은 에너지 후렴
            }
            SectionType.BRIDGE -> when {
                // BRIDGE short: 짧은 전환구는 BLINK (STROBE는 isNearClimax 시에만)
                beats < 8 -> FgEngine.BLINK
                else      -> FgEngine.ON_PULSE      // section 대표값: SECTION_START 등에 사용
                // 실제 per-beat 엔진은 bridgePhaseEngine에서 결정
            }
            SectionType.INTRO -> FgEngine.BREATH  // ON_TRANSIT_ROTATE → BREATH
            SectionType.END -> FgEngine.OFF_TRANSIT
        }

        val source = when (normalizedType) {
            SectionType.VERSE -> when (engine) {
                FgEngine.BREATH    -> "verse-breath-black-bg"
                FgEngine.BLINK     -> "verse-blink-black-bg"
                else               -> "verse-on-pulse-black-bg"
            }
            SectionType.CHORUS -> when (engine) {
                FgEngine.STROBE  -> "chorus-strobe-color-bg"
                FgEngine.BLINK   -> "chorus-blink-color-bg"
                else             -> "chorus-on-pulse-color-bg"  // 낮은 에너지 후렴
            }
            SectionType.BRIDGE -> when {
                beats < 8  -> "bridge-blink"              // STROBE → BLINK
                beats < 16 -> "bridge-breath-to-blink"
                else       -> "bridge-breath-blink"
            }
            SectionType.INTRO -> "intro-breath"
            SectionType.END -> "end-protected"
        }

        val change = when {
            normalizedType == SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
            beats < 8 -> ChangeLevel.MEDIUM
            else -> ChangeLevel.STRONG
        }

        Log.d(
            TAG,
            "section energy type=$normalizedType engine=$engine " +
                    "energyScore=${"%.3f".format(energyScore)} relScore=${"%.3f".format(rel)} " +
                    "lowTh=${"%.3f".format(lowTh)} highTh=${"%.3f".format(highTh)} " +
                    "beatMs=${beatMs}ms beats=$beats"
        )

        return Section(
            startMs = startMs,
            endMs = endMs,
            type = normalizedType,
            engine = engine,
            beatMs = beatMs,
            beats = beats,
            source = source,
            change = change,
            energyScore = energyScore,
            relScore = rel
        )
    }

    /**
     * ⑦ 수정: 짧은 섹션 병합 시 더 긴 쪽의 타입/엔진을 채택
     * (기존: 무조건 cur의 타입 유지 → 짧은 VERSE + 긴 CHORUS = VERSE 엔진으로 잘못 처리)
     */
    private fun mergeSmallSections(sections: List<Section>, beatMs: Long): List<Section> {
        if (sections.isEmpty()) return emptyList()

        // G2: CHORUS 최소 길이 8초. 이하면 인접 섹션에 흡수
        // 실제 K-pop 후렴은 16~32초. 2초짜리 CHORUS 반복 방지
        val MIN_CHORUS_MS = 8_000L

        val out = ArrayList<Section>()
        var cur = sections.first()

        for (i in 1 until sections.size) {
            val next = sections[i]
            val curDur = cur.endMs - cur.startMs
            val tooShortChorus = cur.type == SectionType.CHORUS && curDur < MIN_CHORUS_MS

            if (curDur < MIN_SECTION_MS || cur.beats <= 2 || tooShortChorus) {
                val nextDur = next.endMs - next.startMs
                val newBeats = estimateBeatCount(cur.startMs, next.endMs, beatMs)

                // ⑦ 수정: 더 긴 섹션의 타입/엔진을 채택
                cur = if (nextDur > curDur) {
                    // next가 더 크면 next의 타입/엔진을 사용하되 시작점은 cur의 것을 유지
                    next.copy(
                        startMs = cur.startMs,
                        beats = newBeats
                    )
                } else {
                    // cur이 더 크면 cur의 타입/엔진을 유지하고 end만 확장
                    cur.copy(
                        endMs = next.endMs,
                        beats = newBeats
                    )
                }
            } else {
                out += cur
                cur = next
            }
        }

        out += cur
        return out
    }

    // ⑥ 수정: adjustBridges 완전 제거 (buildContentSection에 통합됨)

    private fun classifyType(score: Float, lowTh: Float, highTh: Float): SectionType {
        val bridgeTh = lowTh * 0.85f

        return when {
            score >= highTh -> SectionType.CHORUS
            score <= bridgeTh -> SectionType.BRIDGE
            else -> SectionType.VERSE
        }
    }

    /**
     * ⑤ 수정: 단일 fullEnv 대신 low/mid/full 세 채널을 조합한 에너지 스코어
     * - lowRatio 패널티: 베이스만 강한 구간(에너지는 높지만 비트 없음)을 CHORUS로 잘못 분류 방지
     * - onsetDensity 보너스: mid 채널 활성도(비트/온셋 밀도)가 높은 구간을 CHORUS로 분류 촉진
     */
    private fun sectionEnergyScore(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        startMs: Long,
        endMs: Long
    ): Float {
        val safeSize = minOf(lowEnv.size, midEnv.size, fullEnv.size)
        if (safeSize == 0) return 0f

        val s = (startMs / HOP_MS).toInt().coerceIn(0, safeSize - 1)
        val e = (endMs / HOP_MS).toInt().coerceIn(s + 1, safeSize)

        var fullSum = 0f
        var diffSum = 0f
        var maxV = 0f
        var lowSum = 0f
        var midSum = 0f
        var prev = fullEnv[s]

        for (i in s until e) {
            val v = fullEnv[i]
            fullSum += v
            diffSum += abs(v - prev)
            maxV = max(maxV, v)
            lowSum += lowEnv[i]
            midSum += midEnv[i]
            prev = v
        }

        val n = max(1, e - s).toFloat()
        val mean = fullSum / n
        val activity = diffSum / n

        // 저음 비율: 높으면 베이스만 강한 구간 → CHORUS 분류 억제
        val lowRatio = if (mean > 1e-6f) (lowSum / n) / mean else 0f
        // mid 채널 활성도: 비트/온셋 밀도와 상관 → CHORUS 분류 촉진
        val onsetDensity = midSum / n

        val lowPenalty = (lowRatio * 0.08f).coerceIn(0f, 0.08f)
        val onsetBonus = onsetDensity * 0.12f

        return (mean * 0.60f + activity * 0.20f + maxV * 0.10f + onsetBonus - lowPenalty)
            .coerceIn(0f, 1f)
    }

    private fun detectClimaxPeakMoments(
        fullEnv: List<Float>,
        durationMs: Long,
        beatMs: Long
    ): List<Long> {
        if (fullEnv.size < 8) return emptyList()

        data class PeakCandidate(
            val tMs: Long,
            val score: Float
        )

        val scoreArray = FloatArray(fullEnv.size) { 0f }
        for (i in 2 until fullEnv.size - 2) {
            val energy = fullEnv[i]
            val rise = max(0f, fullEnv[i] - fullEnv[i - 1])
            val localAvg = (
                    fullEnv[i - 2] +
                            fullEnv[i - 1] +
                            fullEnv[i + 1] +
                            fullEnv[i + 2]
                    ) / 4f
            val contrast = max(0f, energy - localAvg)
            scoreArray[i] = energy * 0.50f + rise * 0.30f + contrast * 0.20f
        }

        val scoreList = scoreArray.toList().filter { it > 0f }
        if (scoreList.isEmpty()) return emptyList()

        val candidates = ArrayList<PeakCandidate>()
        for (i in 2 until scoreArray.size - 2) {
            val score = scoreArray[i]
            if (score <= 0f) continue

            val isLocalPeak =
                score >= scoreArray[i - 1] &&
                        score >= scoreArray[i - 2] &&
                        score >= scoreArray[i + 1] &&
                        score >= scoreArray[i + 2]

            if (isLocalPeak) {
                candidates += PeakCandidate(
                    tMs = i.toLong() * HOP_MS,
                    score = score
                )
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val sortedScores = scoreList.sorted()
        val mean = scoreList.average().toFloat()
        val p90 = sortedScores[(sortedScores.lastIndex * 0.90f).toInt()
            .coerceIn(0, sortedScores.lastIndex)]
        val variance =
            scoreList.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / scoreList.size.toFloat()
        val std = sqrt(variance)

        val strongCandidates = candidates
            .filter {
                it.score >= p90 * 1.18f &&
                        it.score >= mean + std * 1.30f
            }
            .sortedByDescending { it.score }

        if (strongCandidates.isEmpty()) return emptyList()

        val minGapMs = max(800L, beatMs * 4L)
        val selected = ArrayList<PeakCandidate>()

        for (c in strongCandidates) {
            val tooClose = selected.any { abs(it.tMs - c.tMs) < minGapMs }
            if (!tooClose) {
                selected += c
            }
            if (selected.size >= 3) break
        }

        return selected
            .sortedBy { it.tMs }
            .map { it.tMs.coerceIn(0L, durationMs) }
    }

    // =========================================================================
    // Beat grid building
    // =========================================================================

    /**
     * ① 수정: actualBeats를 실제로 활용
     * - actualBeats.size >= expectedBeats * 60% → actualBeats 우선 반환 + 내부 갭 보간
     * - 부족한 경우 → 균일 그리드 생성 후 실제 비트에 스냅하여 정확도 개선
     *
     * [GAP 2 수정 - 내부 갭 보간]
     * actualBeats를 사용할 때 연속 비트 간격이 1.5×beatMs를 초과하면
     * 균일 간격으로 중간 비트를 삽입한다.
     * 예) beatMs=550ms, 실제 간격=1150ms → 중간 575ms 위치에 보간 비트 삽입
     */
    private fun buildSectionBeatGrid(
        section: Section,
        actualBeats: List<Long>
    ): List<Long> {
        if (section.endMs <= section.startMs || section.beatMs <= 0L) {
            return emptyList()
        }

        val expectedBeats = estimateBeatCount(section.startMs, section.endMs, section.beatMs)

        // ① 실제 비트가 기대값의 60% 이상이면 실제 비트를 우선 사용
        val minActualRequired = (expectedBeats * ACTUAL_BEAT_USE_RATIO).toInt().coerceAtLeast(2)
        if (actualBeats.size >= minActualRequired) {
            Log.d(
                TAG,
                "beatGrid section=${section.type} actualBeats=${actualBeats.size} " +
                        "expected=$expectedBeats → using actual beats (with gap fill)"
            )
            return fillBeatGaps(actualBeats.sorted(), section.beatMs, section.endMs)
        }

        // 실제 비트가 부족 → 균일 그리드 생성
        val grid = ArrayList<Long>()
        var t = section.startMs
        while (t < section.endMs) {
            grid += t
            t += section.beatMs
        }

        // actualBeats가 일부라도 있으면 그리드를 실제 비트에 스냅
        if (actualBeats.isEmpty()) {
            Log.d(
                TAG,
                "beatGrid section=${section.type} no actualBeats → pure grid size=${grid.size}"
            )
            return grid
        }

        val snapMs = section.beatMs / 4L
        val snapped = grid.map { gridBeat ->
            val closest = actualBeats.minByOrNull { abs(it - gridBeat) }
            if (closest != null && abs(closest - gridBeat) <= snapMs) closest else gridBeat
        }

        Log.d(
            TAG,
            "beatGrid section=${section.type} gridBeats=${grid.size} " +
                    "actualBeats=${actualBeats.size} snapMs=$snapMs → snapped grid"
        )
        return snapped.distinct().sorted()
    }


    /**
     * [GAP 2 수정] actualBeats 내 연속 간격이 1.5×beatMs를 초과하는 구간을 보간한다.
     *
     * 예시: beatMs=550ms, 실제 갭=1150ms (2비트 분량)
     *   → 중간 지점에 550ms 간격으로 보간 비트 삽입
     *   → [3150, 4300] → [3150, 3700, 4300] (3700ms 보간)
     *
     * 섹션 endMs를 초과하는 보간 비트는 추가하지 않는다.
     */
    private fun fillBeatGaps(
        beats: List<Long>,
        beatMs: Long,
        sectionEndMs: Long
    ): List<Long> {
        if (beats.size < 2 || beatMs <= 0L) return beats

        val gapThreshold = beatMs * 3L / 2L
        val out = ArrayList<Long>(beats.size * 2)
        out += beats.first()

        for (i in 1 until beats.size) {
            val prev = beats[i - 1]
            val cur = beats[i]
            val gap = cur - prev

            if (gap > gapThreshold) {
                val fillCount = ((gap + beatMs / 2L) / beatMs).toInt() - 1
                if (fillCount > 0) {
                    val step = gap / (fillCount + 1).toLong()
                    for (k in 1..fillCount) {
                        val interpolated = prev + step * k
                        if (interpolated < sectionEndMs) {
                            out += interpolated
                        }
                    }
                    Log.d(
                        TAG,
                        "beatGapFill prev=${prev}ms cur=${cur}ms gap=${gap}ms " +
                                "beatMs=${beatMs}ms filled=$fillCount beats"
                    )
                }
            }
            out += cur
        }

        return out.distinct().sorted()
    }

    // =========================================================================
    // Frame building
    // =========================================================================

    private fun buildFramesFromSections(
        musicId: Int,
        palette: Palette,
        sections: List<Section>,
        beatTimes: List<Long>,
        durationMs: Long,
        climaxMoments: List<Long>
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimes.size * 4 + sections.size + 8)

        fun putFrame(
            t: Long,
            payload: ByteArray,
            section: Section,
            frameType: String,
            engine: FgEngine,
            fg: LSColor? = null,
            bg: LSColor? = null,
            transit: Int? = null,
            period: Int? = null,
            randomDelay: Int? = null,
            note: String? = null
        ) {
            if (t < 0L) return

            if (frameMap.containsKey(t)) {
                Log.w(
                    TAG,
                    "timeline overwrite t=${t}ms type=$frameType " +
                            "section=${section.type} engine=$engine source=${section.source}"
                )
            }

            frameMap[t] = payload

            logTimelineFrame(
                t = t,
                section = section,
                frameType = frameType,
                engine = engine,
                fg = fg,
                bg = bg,
                transit = transit,
                period = period,
                randomDelay = randomDelay,
                note = note
            )
        }

        // ② 수정: 클라이맥스 피크로부터 ±CLIMAX_WINDOW_HALF_MS 이내인지 확인하는 헬퍼
        fun isNearClimax(tMs: Long): Boolean {
            return climaxMoments.any { abs(it - tMs) <= CLIMAX_WINDOW_HALF_MS }
        }

        // BLINK/STROBE 중복 전송 방지를 위한 키 클래스
        // engine + fg(rgb) + bg(rgb) + period + randomDelay 가 모두 같으면 기기에서 이미
        // 해당 이펙트를 자동 반복 중이므로 BLE 재전송 생략
        data class RepeatKey(
            val engine: FgEngine,
            val fgR: Int, val fgG: Int, val fgB: Int,
            val bgR: Int, val bgG: Int, val bgB: Int,
            val period: Int, val randomDelay: Int
        )
        // 섹션 루프 전체에서 공유: 섹션이 바뀌면 아래 cover/fill 전송 시 자동 초기화됨
        var lastRepeatKey: RepeatKey? = null

        // ③ 수정: 모든 곡 t=0ms OFF로 시작 — 이펙트 시작 전 응원봉이 꺼진 상태 보장
        if (!frameMap.containsKey(0L)) {
            frameMap[0L] = buildOffPayload()
            Log.d(TAG, "timeline t=0ms OFF (always)")
        }

        var prevSectionEndMs = 0L

        for ((index, section) in sections.withIndex()) {
            lastRepeatKey = null  // 섹션 전환 시 초기화

            // 전략 1 — 공백 유지: 섹션 간 gap ≥ 2초면 마지막 색 hold (별도 프레임 없음)
            // 전략 2 — 전환 마커: gap ≥ 2초 직후 섹션 startMs에 BREATH 삽입
            val interSectionGapMs = if (index > 0) (section.startMs - prevSectionEndMs).coerceAtLeast(0L) else 0L
            val insertTransitionBreath = interSectionGapMs >= SECTION_GAP_BREATH_THRESHOLD_MS &&
                    section.engine != FgEngine.BREATH &&
                    section.engine != FgEngine.OFF_TRANSIT

            val actualSectionBeats = beatTimes.filter { it >= section.startMs && it < section.endMs }
            val effectiveSectionBeats = buildSectionBeatGrid(section, actualSectionBeats)

            Log.d(
                TAG,
                "section timeline idx=$index " +
                        "type=${section.type} range=${section.startMs}~${section.endMs} " +
                        "section.beats=${section.beats} actualSectionBeats=${actualSectionBeats.size} " +
                        "gridSectionBeats=${effectiveSectionBeats.size} " +
                        "engine=${section.engine} source=${section.source}"
            )

            if (section.engine == FgEngine.OFF_TRANSIT) {
                putFrame(
                    t = section.startMs,
                    payload = buildOffPayload(),
                    section = section,
                    frameType = "SECTION_OFF",
                    engine = FgEngine.OFF_TRANSIT,
                    transit = ON_TRANSIT
                )
                prevSectionEndMs = section.endMs
                continue
            }

            if (section.engine == FgEngine.BREATH) {
                val (fg, bg) = colorsForEngine(palette, section.engine, index)

                putFrame(
                    t = section.startMs,
                    payload = buildPayload(section.engine, fg, bg, section.beatMs),
                    section = section,
                    frameType = "SECTION_START",
                    engine = FgEngine.BREATH,
                    fg = fg,
                    bg = bg,
                    period = msToBreathPeriod(section.beatMs),
                    randomDelay = 5
                )
                prevSectionEndMs = section.endMs
                continue
            }

            if (effectiveSectionBeats.isEmpty()) {
                val (fg, bg) = colorsForEngine(palette, section.engine, index)

                putFrame(
                    t = section.startMs,
                    payload = buildPayload(section.engine, fg, bg, section.beatMs),
                    section = section,
                    frameType = "SECTION_START",
                    engine = section.engine,
                    fg = fg,
                    bg = bg,
                    transit = if (
                        section.engine == FgEngine.ON_PULSE
                    ) ON_TRANSIT else null,
                    period = when (section.engine) {
                        FgEngine.BLINK  -> msToBlinkPeriod(section.beatMs)
                        FgEngine.STROBE -> msToStrobePeriod(section.beatMs)
                        else            -> null
                    },
                    note = "no-effective-beats"
                )
                continue
            }

            // [GAP 1, 3, 4 수정] SECTION_COVER: 첫 비트가 섹션 startMs보다 늦게 시작할 때
            // [EFX 전략] insertTransitionBreath=true이면 SECTION_COVER 대신 BREATH 마커 사용
            val firstBeat = effectiveSectionBeats.first()
            val coverGapMs = firstBeat - section.startMs

            if (insertTransitionBreath) {
                // 전략 2: 섹션 전환 마커 — 공백(≥2초) 직후 firstBeat에 BREATH
                // BG = breathSet.bg(메인색/cDeep), FG = white
                val mFg = palette.white
                val mBg = palette.breathSet.bg
                putFrame(
                    t = section.startMs,
                    payload = buildPayload(FgEngine.BREATH, mFg, mBg, section.beatMs),
                    section = section,
                    frameType = "TRANSITION_BREATH",
                    engine = FgEngine.BREATH,
                    fg = mFg,
                    bg = mBg,
                    period = msToBreathPeriod(section.beatMs),
                    randomDelay = 5,
                    note = "gap=${interSectionGapMs}ms transition-marker"
                )
                Log.d(TAG, "transition breath: idx=$index t=${section.startMs}ms gap=${interSectionGapMs}ms")
                // SECTION_COVER 건너뜀
            } else if (coverGapMs > 0L && section.type != SectionType.INTRO) {
                val longCoverThresholdMs = section.beatMs * 3L / 2L  // 1.5 × beatMs

                if (coverGapMs <= longCoverThresholdMs) {
                    // 짧은 갭: BREATH 커버 프레임으로 부드럽게 전환
                    // section.engine(BLINK/STROBE)을 그대로 쓰면 기기가 cover 동안 정지 상태로 보임
                    // → BREATH로 fade-in 하면서 첫 비트를 기다리는 것이 자연스러움
                    val coverEngine = when (section.engine) {
                        FgEngine.BLINK, FgEngine.STROBE -> FgEngine.BREATH
                        else -> section.engine
                    }
                    val (cvFg, cvBg) = colorsForEngine(palette, coverEngine, index)
                    putFrame(
                        t = section.startMs,
                        payload = buildPayload(coverEngine, cvFg, cvBg, section.beatMs),
                        section = section,
                        frameType = "SECTION_COVER",
                        engine = coverEngine,
                        fg = cvFg,
                        bg = cvBg,
                        transit = if (coverEngine == FgEngine.ON_PULSE) ON_TRANSIT else null,
                        period = if (coverEngine == FgEngine.BREATH) msToBreathPeriod(section.beatMs) else null,
                        randomDelay = if (coverEngine == FgEngine.BREATH) 5 else null,
                        note = "section-cover gap=${coverGapMs}ms"
                    )
                } else {
                    // 긴 갭: startMs ~ firstBeat 사이에 beatMs 간격으로 보조 비트 삽입
                    var fillT = section.startMs
                    var fillIdx = 0
                    // fill 비트의 엔진: BRIDGE면 bridgePhaseEngine, 나머지는 section.engine
                    val beatEngineForFill = if (section.type == SectionType.BRIDGE)
                        bridgePhaseEngine(0, section.beats, section.beatMs, section.relScore)
                    else section.engine
                    while (fillT < firstBeat) {
                        val (cvFg, cvBg) = colorsForEngine(palette, beatEngineForFill, index, fillIdx, section.type)
                        putFrame(
                            t = fillT,
                            payload = buildPayload(section.engine, cvFg, cvBg, section.beatMs),
                            section = section,
                            frameType = if (fillIdx == 0) "SECTION_COVER" else "SECTION_COVER_FILL",
                            engine = section.engine,
                            fg = cvFg,
                            bg = cvBg,
                            transit = if (
                                section.engine == FgEngine.ON_PULSE
                            ) ON_TRANSIT else null,
                            period = when (section.engine) {
                                FgEngine.BLINK  -> msToBlinkPeriod(section.beatMs)
                                FgEngine.STROBE -> msToStrobePeriod(section.beatMs)
                                else -> null
                            },
                            note = "section-cover-fill gap=${coverGapMs}ms fillIdx=$fillIdx"
                        )
                        if (beatEngineForFill == FgEngine.ON_PULSE) {
                            val offT = min(firstBeat, fillT + (section.beatMs * 3L / 10L))
                            if (offT > fillT) {
                                putFrame(
                                    t = offT,
                                    payload = buildPayload(FgEngine.ON_PULSE, cvBg, cvBg, section.beatMs),
                                    section = section,
                                    frameType = "SECTION_COVER_BG",
                                    engine = FgEngine.ON_PULSE,
                                    fg = cvBg,
                                    transit = ON_TRANSIT,
                                    note = "cover-restore fillIdx=$fillIdx"
                                )
                            }
                        }
                        fillT += section.beatMs
                        fillIdx++
                    }
                    Log.d(TAG, "section-cover long gap=${coverGapMs}ms filled=$fillIdx beats")
                }
            } // end else if coverGapMs

            for ((beatIndex, t) in effectiveSectionBeats.withIndex()) {

                if (beatIndex == 0 && section.type == SectionType.INTRO) {
                    // INTRO: ON_TRANSIT_ROTATE → BREATH
                    // SECTION_START로 startMs에 1개 전송, 기기가 부드럽게 호흡하며 음악을 기다림
                    val (introFg, _) = colorsForEngine(palette, FgEngine.BREATH, index)
                    putFrame(
                        t = section.startMs,
                        payload = buildPayload(FgEngine.BREATH, introFg, LSColor(0, 0, 0), section.beatMs),
                        section = section,
                        frameType = "INTRO_BREATH_START",
                        engine = FgEngine.BREATH,
                        fg = introFg,
                        period = msToBreathPeriod(section.beatMs),
                        randomDelay = 3,
                        note = if (actualSectionBeats.isEmpty()) "grid-intro" else "actual-intro"
                    )
                } else {
                    // ② 수정: 클라이맥스 구간 인접 비트 처리
                    val nearClimax = isNearClimax(t)

                    // BRIDGE per-beat 엔진: 길이 + 에너지 기반 페이즈 결정
                    val beatEngine = if (section.type == SectionType.BRIDGE) {
                        bridgePhaseEngine(
                            beatIndex = beatIndex,
                            totalBeats = effectiveSectionBeats.size,
                            beatMs = section.beatMs,
                            relScore = section.relScore
                        )
                    } else {
                        section.engine
                    }
                    // CHORUS STROBE 섹션 내 비 climax 비트 → BREATH로 대체
                    // 효과: BREATH가 배경처럼 부드럽게 깔리다가 climax 순간 STROBE period=1 폭발
                    // effectiveBeatEngine: 실제 payload/period/randomDelay 결정에 사용
                    val effectiveBeatEngine = when {
                        beatEngine == FgEngine.STROBE && !nearClimax -> FgEngine.BREATH
                        else -> beatEngine
                    }

                    // effectiveBeatEngine 기준으로 색 선택 → 엔진별 고정 ColorSet 반환
                    val (fg, bg) = colorsForEngine(palette, effectiveBeatEngine, index, beatIndex, section.type)
                    val bgNonNull: LSColor = bg ?: LSColor(0, 0, 0)

                    val beatPeriod = when (effectiveBeatEngine) {
                        FgEngine.BLINK  -> msToBlinkPeriod(section.beatMs)
                        FgEngine.STROBE -> 1  // nearClimax STROBE만 여기 도달 → period=1 파바박
                        FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                        else            -> null
                    }
                    val beatRandomDelay = when {
                        effectiveBeatEngine == FgEngine.STROBE && nearClimax -> 3  // 파바박
                        section.type == SectionType.VERSE &&
                                effectiveBeatEngine == FgEngine.ON_PULSE -> 15
                        section.type == SectionType.CHORUS &&
                                effectiveBeatEngine == FgEngine.BLINK -> 8
                        section.type == SectionType.CHORUS &&
                                effectiveBeatEngine == FgEngine.BREATH -> 5  // CHORUS BREATH 파도
                        section.type == SectionType.BRIDGE &&
                                (effectiveBeatEngine == FgEngine.STROBE || effectiveBeatEngine == FgEngine.BLINK) -> 10
                        effectiveBeatEngine == FgEngine.BREATH -> 5
                        else -> null
                    }

                    // [BLINK/STROBE 중복 전송 방지] effectiveBeatEngine 기준으로 판단
                    // BREATH는 색이 바뀔 때마다 재전송 (dedup 대상 아님)
                    val skipRepeat = if (effectiveBeatEngine == FgEngine.BLINK || effectiveBeatEngine == FgEngine.STROBE) {
                        val key = RepeatKey(
                            engine = effectiveBeatEngine,
                            fgR = fg.r, fgG = fg.g, fgB = fg.b,
                            bgR = bgNonNull.r, bgG = bgNonNull.g, bgB = bgNonNull.b,
                            period = beatPeriod ?: 0,
                            randomDelay = beatRandomDelay ?: 0
                        )
                        val dup = (key == lastRepeatKey)
                        lastRepeatKey = key
                        dup
                    } else {
                        lastRepeatKey = null   // 비-반복 엔진 구간 진입 시 키 초기화
                        false
                    }

                    if (skipRepeat) {
                        Log.d(
                            TAG,
                            "timeline skip-repeat t=${t}ms section=${section.type} " +
                                    "engine=${effectiveBeatEngine.name} beatIndex=$beatIndex → same fg/bg/period"
                        )
                    } else {
                        putFrame(
                            t = t,
                            payload = buildPayload(effectiveBeatEngine, fg, bg, section.beatMs),
                            section = section,
                            frameType = "BEAT_FG",
                            engine = effectiveBeatEngine,
                            fg = fg,
                            bg = bg,
                            transit = if (
                                effectiveBeatEngine == FgEngine.ON_PULSE
                            ) ON_TRANSIT else null,
                            period = beatPeriod,
                            randomDelay = beatRandomDelay,
                            note = buildString {
                                append("beatIndex=$beatIndex")
                                append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                                if (nearClimax) append(" [climax]")
                                if (section.type == SectionType.BRIDGE) append(" [bridge-phase=${beatEngine.name}]")
                            }
                        )
                    }

                    // BEAT_BG: beatEngine 기준으로 판단 (section.engine 아님)
                    // BRIDGE에서 section.engine=ON_PULSE라도 beatEngine=BREATH/BLINK/STROBE면
                    // 꺼짐 명령을 보내지 않음 → BREATH 이펙트 즉시 중단 버그 수정
                    //
                    // EFX P1-2: 비대칭 체류시간
                    // white(베이스색): 긴 여운 min(700ms, beatMs-50ms) — EFX 720ms 기준
                    // colorGroup(강조색): 짧은 강조 min(200ms, beatMs*2/5) — EFX 200ms 기준
                    if (beatEngine == FgEngine.ON_PULSE) {
                        val isWhiteFg = (fg.r == 255 && fg.g == 255 && fg.b == 255)
                        val holdMs = if (isWhiteFg) {
                            minOf(ON_PULSE_BASE_HOLD_MS, (section.beatMs - 50L).coerceAtLeast(100L))
                        } else {
                            minOf(ON_PULSE_ACCENT_HOLD_MS, section.beatMs * 2L / 5L).coerceAtLeast(80L)
                        }
                        val offT = min(section.endMs, t + holdMs)
                        if (offT > t) {
                            putFrame(
                                t = offT,
                                payload = LSEffectPayload.Effects.on(
                                    color = bg,
                                    transit = ON_TRANSIT
                                ).toByteArray(),
                                section = section,
                                frameType = "BEAT_BG",
                                engine = FgEngine.ON_PULSE,
                                fg = bg,
                                transit = ON_TRANSIT,
                                note = buildString {
                                    append("restore beatIndex=$beatIndex hold=${holdMs}ms")
                                    append(if (isWhiteFg) " [base-long]" else " [accent-short]")
                                    append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                                }
                            )
                        }
                    }
                }
            }
            prevSectionEndMs = section.endMs  // 다음 섹션의 gap 계산용
        }

        if (frameMap.keys.none { it >= durationMs }) {
            val endSection = Section(
                startMs = durationMs,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = 0L,
                beats = 0,
                source = "final-off",
                change = ChangeLevel.STRONG
            )

            putFrame(
                t = durationMs,
                payload = buildOffPayload(),
                section = endSection,
                frameType = "FINAL_OFF",
                engine = FgEngine.OFF_TRANSIT,
                transit = ON_TRANSIT
            )
        }

        Log.d(TAG, "timeline final uniqueFrames=${frameMap.size}")

        return frameMap.entries
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

    // =========================================================================
    // Payload builders
    // =========================================================================

    private fun buildPayload(
        engine: FgEngine,
        fg: LSColor,
        bg: LSColor,
        beatMs: Long
    ): ByteArray {
        return when (engine) {
            FgEngine.ON_PULSE -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgEngine.BLINK -> {
                LSEffectPayload.Effects.blink(
                    period = msToBlinkPeriod(beatMs),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.STROBE -> {
                LSEffectPayload.Effects.strobe(
                    period = msToStrobePeriod(beatMs),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.BREATH -> {
                LSEffectPayload.Effects.breath(
                    period = msToBreathPeriod(beatMs),
                    color = fg,
                    backgroundColor = bg,
                    randomDelay = 5
                ).toByteArray()
            }

            FgEngine.ON_TRANSIT_ROTATE -> {
                // 더 이상 직접 사용되지 않음 — ON_PULSE fallback으로 처리
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(): ByteArray {
        return LSEffectPayload.Effects.off(
            transit = ON_TRANSIT
        ).toByteArray()
    }


    /**
     * BRIDGE 섹션의 비트별 엔진을 결정한다.
     *
     * beats < 8  (짧은 BRIDGE, 전환구):
     *   → 전체 STROBE: 순간 에너지 폭발. CHORUS 직전 강한 임팩트.
     *
     * 8 ≤ beats < 16  (중간 BRIDGE, 빌드업):
     *   → 전반(0~49%) BREATH: 부드러운 호흡으로 긴장감 형성
     *   → 후반(50~100%) STROBE: 폭발적 전환으로 CHORUS 연결
     *
     * beats ≥ 16  (긴 BRIDGE, 클라이맥스 빌드업):
     *   → 1/3(0~32%) BREATH: 느린 호흡, 기대감 빌드
     *   → 2/3(33~65%) BLINK: 중간 강도 깜빡임, 에너지 상승
     *   → 3/3(66~100%) STROBE: 최고 강도, CHORUS/클라이맥스 돌입
     */
    /**
     * BRIDGE per-beat 엔진 결정 — 길이 + relScore 에너지 기반 페이즈 조정
     *
     * [페이즈 경계 에너지 적응]
     * relScore가 높을수록 STROBE로 빨리 전환 (에너지가 이미 높으면 빌드업이 짧아도 됨)
     * relScore가 낮을수록 BREATH를 오래 유지 (에너지가 낮으면 긴장감 더 천천히 쌓기)
     *
     * 예) 중간 BRIDGE (8~15 beats):
     *   relScore=0.2 (저에너지) → BREATH 70% + STROBE 30%
     *   relScore=0.5 (중간)    → BREATH 50% + STROBE 50%  (기본)
     *   relScore=0.9 (고에너지) → BREATH 25% + STROBE 75%  (빨리 폭발)
     *
     * 예) 긴 BRIDGE (16+ beats):
     *   relScore=0.2 → BREATH 45% + BLINK 35% + STROBE 20%
     *   relScore=0.5 → BREATH 33% + BLINK 34% + STROBE 33%  (기본)
     *   relScore=0.9 → BREATH 15% + BLINK 30% + STROBE 55%
     */
    private fun bridgePhaseEngine(
        beatIndex: Int,
        totalBeats: Int,
        @Suppress("UNUSED_PARAMETER") beatMs: Long,
        relScore: Float = 0.5f
    ): FgEngine {
        if (totalBeats <= 0) return FgEngine.STROBE

        // STROBE 진입 비율: relScore가 높을수록 일찍 STROBE 전환
        // 0.0 → 0.80 (저에너지, STROBE 매우 늦게)
        // 0.5 → 0.50 (중간, 기본값)
        // 1.0 → 0.25 (고에너지, STROBE 매우 빨리)
        val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)

        return when {
            // 짧은 BRIDGE: 전체 STROBE (에너지 무관)
            totalBeats < 8 -> FgEngine.STROBE

            // 중간 BRIDGE: 2페이즈 — 에너지에 따라 BREATH/BLINK 비율 조정
            // (STROBE → BLINK: 강한 연출은 클라이맥스 구간에서만)
            totalBeats < 16 -> {
                val phase = beatIndex.toFloat() / totalBeats
                if (phase < strobeEntry) FgEngine.BREATH else FgEngine.BLINK
            }

            // 긴 BRIDGE: 3페이즈 크레센도 — 에너지에 따라 경계 이동

            // 여기서는 BLINK를 최대 강도로 사용해 과도한 STROBE 남용 방지
            else -> {
                val phase = beatIndex.toFloat() / totalBeats
                val blinkEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
                when {
                    phase < blinkEntry  -> FgEngine.BREATH
                    else                -> FgEngine.BLINK   // STROBE → BLINK: BRIDGE 빌드업 마지막도 BLINK
                }
            }
        }
    }

    // =========================================================================
    // Period helpers
    // =========================================================================

    private fun msToBlinkPeriod(beatMs: Long): Int {
        return (beatMs / 10L).toInt().coerceIn(1, 255)
    }

    private fun msToStrobePeriod(beatMs: Long): Int {
        return (beatMs / 10L).toInt().coerceIn(1, 255)
    }

    private fun msToBreathPeriod(beatMs: Long): Int {
        return (beatMs / 20L).toInt().coerceIn(1, 255)
    }

    // =========================================================================
    // Color helpers
    // =========================================================================

    private fun wrap360(h: Float): Float {
        return ((h % 360f) + 360f) % 360f
    }

    /**
     * 곡별 고정 컬러 팔레트 생성
     *
     * EFX 참조 분석 (Magnetic ILLIT):
     *   - 메인색 #9900FF(276°) ↔ 흰색 교대가 주 패턴
     *   - 액센트: +60°(마젠타), -60°(코발트블루), -120°(시안) 레인보우 그룹
     *   - BG는 거의 항상 BLACK (cDeep는 BREATH BG에만)
     *   - BREATH: FG=white or 강조색 / BG=cMain (배경에 메인색 깔림)
     *
     * musicId → baseHue 생성:
     *   양수화 후 360 모듈로로 안정적 색상 결정
     *   각 hue에서 유사색 그룹(±60°, ±120°) 생성 → 곡마다 통일된 색감
     */
    /**
     * 앨범아트 색상 기반 팔레트 — AlbumColorExtractor.extract() 결과 사용
     * musicId hash 방식과 동일한 Palette 구조 반환
     */
    private fun buildPalette(albumColors: AlbumColorExtractor.AlbumColors): Palette {
        val cMain   = albumColors.primary
        val cStep1  = albumColors.secondary
        val cStep2  = albumColors.tertiary
        val cStep3  = albumColors.quaternary

        val cMainHue = run {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(
                android.graphics.Color.rgb(cMain.r, cMain.g, cMain.b), hsv
            )
            hsv[0]
        }
        val cDeep = hsvToColor(cMainHue, 1.00f, 0.48f)

        val black = LSColor(0, 0, 0)
        val white = LSColor(255, 255, 255)

        val colorGroup  = listOf(cMain, cStep1, cStep2, cStep3)
        val cMainLuma   = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg  = if (cMainLuma >= 128f) cDeep else cMain

        val onPulseSets = listOf(
            ColorSet(fg = white, bg = patternABg),
            ColorSet(fg = cMain, bg = black)
        )
        val blinkSets   = listOf(
            ColorSet(fg = cMain,  bg = black),
            ColorSet(fg = cStep1, bg = black)
        )
        val strokeSets  = listOf(
            ColorSet(fg = white, bg = black),
            ColorSet(fg = white, bg = black)
        )
        val breathSet   = ColorSet(fg = white, bg = patternABg)
        val bridgeSets  = listOf(
            ColorSet(fg = cStep2, bg = black),
            ColorSet(fg = cMain,  bg = black)
        )

        return Palette(
            black       = black,
            white       = white,
            onPulseSets = onPulseSets,
            blinkSets   = blinkSets,
            strokeSets  = strokeSets,
            breathSet   = breathSet,
            bridgeSets  = bridgeSets,
            chorusBg    = cDeep,
            colorGroup  = colorGroup
        )
    }

    private fun buildPalette(seed: Int): Palette {
        // 음수 seed에서도 고르게 분포하는 hue 생성
        // Kotlin의 Long % 360은 음수를 반환할 수 있으므로 and(0x7FFFFFFF)로 양수화 후 %360
        val rawHue = ((seed.toLong() * 2654435761L) ushr 8 and 0x7FFFFFFFL % 360L).toInt()
        val baseHue = (rawHue % 360).toFloat()

        // ── EFX 분석 기반 색상 설계 ─────────────────────────────────
        //
        // EFX 패턴 1 (짝수 섹션): FG=white, BG=cMain
        //   메인컬러 배경이 항상 켜진 채로 흰빛이 비트마다 번쩍
        //
        // EFX 패턴 2 (홀수 섹션): beat마다 colorGroup 4색 순환, BG=black
        //   [cMain → cStep1(+60°) → cStep2(-60°) → cStep3(-120°)] 순환
        //   같은 계열 인접색이라 어색하지 않고 자연스러운 레인보우 효과

        val cMain   = hsvToColor(baseHue,                 1.00f, 1.00f)
        val cStep1  = hsvToColor(wrap360(baseHue +  60f), 1.00f, 1.00f)  // +60° 마젠타 방향
        val cStep2  = hsvToColor(wrap360(baseHue -  60f), 0.85f, 0.95f)  // -60° 코발트 방향
        val cStep3  = hsvToColor(wrap360(baseHue - 120f), 1.00f, 1.00f)  // -120° 시안 방향
        val cDeep   = hsvToColor(baseHue,                 1.00f, 0.48f)  // 어두운 버전 (BREATH BG)

        val black = LSColor(0, 0, 0)
        val white = LSColor(255, 255, 255)

        // EFX 레인보우 4색 그룹
        val colorGroup = listOf(cMain, cStep1, cStep2, cStep3)

        // ON_PULSE Set A: 패턴 1용 (white / patternAbg)
        //   EFX 패턴 1: 메인컬러가 BG로 항상 켜져 있고 흰색이 번쩍
        //   단, cMain이 밝은 색(luma≥128)이면 BG=cDeep — 흰색과의 대비 보장
        val cMainLuma = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain
        val onPulseSets = listOf(
            ColorSet(fg = white,  bg = patternABg),  // A: EFX 패턴 1 — 흰색 on 메인컬러 배경
            ColorSet(fg = cMain,  bg = black)         // B: placeholder
        )

        // BLINK: colorsForEngine에서 beatIndex별 colorGroup 사용 (placeholder)
        val blinkSets = listOf(
            ColorSet(fg = cMain,  bg = black),
            ColorSet(fg = cStep1, bg = black)
        )

        // STROBE: 항상 white/black 고정
        val strokeSets = listOf(
            ColorSet(fg = white, bg = black),
            ColorSet(fg = white, bg = black)
        )

        // BREATH: BG=patternABg와 동일 로직 (이미 위에서 계산됨)
        val breathBg = patternABg
        val breathSet = ColorSet(fg = white, bg = breathBg)

        // BRIDGE: placeholder — colorsForEngine에서 colorGroup 사용
        val bridgeSets = listOf(
            ColorSet(fg = cStep2, bg = black),
            ColorSet(fg = cMain,  bg = black)
        )

        return Palette(
            black       = black,
            white       = white,
            onPulseSets = onPulseSets,
            blinkSets   = blinkSets,
            strokeSets  = strokeSets,
            breathSet   = breathSet,
            bridgeSets  = bridgeSets,
            chorusBg    = cDeep,
            colorGroup  = colorGroup
        )
    }

    /**
     * 엔진과 섹션 인덱스 기반으로 고정 컬러 세트 반환
     *
     * 세트 선택 규칙:
     *  - sectionIndex % sets.size → 섹션 단위로만 A/B 전환
     *  - 비트마다 색이 바뀌지 않음 → 통일감 유지
     *
     * FG/BG 대비는 buildPalette 단계에서 이미 보장됨
     */

    /**
     * 엔진별로 직접 컬러 세트 조회 (BLINK/BREATH 등 섹션 타입과 독립적으로 필요할 때)
     */
    /**
     * EFX 두 가지 패턴 기반 beat별 색상 결정
     *
     * 패턴 A (sectionIndex 짝수): FG=white, BG=cMain — EFX 패턴 1
     *   응원봉 전체가 메인컬러로 켜진 채로 흰빛이 비트에 맞춰 번쩍
     *
     * EFX P2-3/P2-4: 섹션 타입별 색수 가변 + CHORUS 흰색 시작
     *   VERSE  : 3색 순환 (colorGroup[0..2]) — EFX VERSE1: 보라/흰/마젠타
     *   CHORUS : 흰색 시작 후 4색 — EFX CHORUS: 흰→보라→파→청록→코발트→마젠타
     *   BRIDGE : 코발트블루 강조 3색 — EFX DOUBLE CHORUS cStep2(코발트) 비중 상향
     *   기타   : colorGroup 전체 순환
     */
    private fun colorsForEngine(
        palette: Palette,
        engine: FgEngine,
        sectionIndex: Int,
        beatIndex: Int = 0,
        sectionType: SectionType = SectionType.VERSE  // EFX P2-3: 섹션별 색수 가변
    ): Pair<LSColor, LSColor> {
        val isPatternA = (sectionIndex % 2 == 0)

        // EFX P2-3: 섹션 타입별 효과 색상 목록
        //   CHORUS: 흰색 시작 후 colorGroup 3색 (총 4색) — EFX: 흰→colorGroup 방향
        //   VERSE:  colorGroup 앞 3색 순환 — EFX: 보라/흰/마젠타 3색
        //   BRIDGE: 코발트(cStep2) 강조 → [cStep2, cMain, white] — DOUBLE CHORUS 느낌
        //   기타:   colorGroup 전체 4색
        val effectiveColors: List<LSColor> = when (sectionType) {
            SectionType.CHORUS -> listOf(palette.white) + palette.colorGroup.take(3)
            SectionType.VERSE  -> palette.colorGroup.take(3)
            SectionType.BRIDGE -> listOf(
                palette.colorGroup.getOrElse(2) { palette.colorGroup[0] },  // cStep2(코발트)
                palette.colorGroup[0],                                        // cMain
                palette.white                                                 // white
            )
            else -> palette.colorGroup
        }
        val groupColor = effectiveColors[beatIndex % effectiveColors.size]

        return when (engine) {
            FgEngine.ON_PULSE -> if (isPatternA)
                palette.white to palette.onPulseSets[0].bg   // 패턴 A: white on cMain
            else
                groupColor to palette.black                   // 패턴 B: sectionType별 색 순환

            FgEngine.BLINK -> if (isPatternA)
                palette.white to palette.onPulseSets[0].bg   // 패턴 A: white on cMain (통일감)
            else
                groupColor to palette.black                   // 패턴 B: sectionType별 색 순환

            FgEngine.STROBE ->
                palette.white to palette.black                // 항상 white/black

            FgEngine.BREATH ->
                palette.breathSet.fg to palette.breathSet.bg  // 항상 고정

            else -> if (isPatternA)
                palette.bridgeSets[0].fg to palette.black     // BRIDGE 패턴 A: cStep2
            else
                groupColor to palette.black                   // BRIDGE: sectionType별 색 순환
        }
    }

    // =========================================================================
    // Logging helpers
    // =========================================================================

    private fun colorToString(c: LSColor): String {
        return "(${c.r},${c.g},${c.b})"
    }

    private fun logTimelineFrame(
        t: Long,
        section: Section,
        frameType: String,
        engine: FgEngine,
        fg: LSColor? = null,
        bg: LSColor? = null,
        transit: Int? = null,
        period: Int? = null,
        randomDelay: Int? = null,
        note: String? = null
    ) {
        val extra = buildString {
            fg?.let { append(" fg=${colorToString(it)}") }
            bg?.let { append(" bg=${colorToString(it)}") }
            transit?.let { append(" transit=$it") }
            period?.let { append(" period=$it") }
            randomDelay?.let { append(" randomDelay=$it") }
            note?.let { append(" note=$it") }
        }

        Log.d(
            TAG,
            "timeline add t=${t}ms type=$frameType " +
                    "section=${section.type} engine=$engine source=${section.source} beats=${section.beats}$extra"
        )
    }

    // =========================================================================
    // Math / analysis helpers
    // =========================================================================

    private fun estimateBeatCount(startMs: Long, endMs: Long, beatMs: Long): Int {
        if (endMs <= startMs || beatMs <= 0L) return 0
        return max(1, ((endMs - startMs) / beatMs).toInt())
    }

    private fun detectFirstMusicStartMs(
        energyFrames: FloatArray,
        hopMs: Long
    ): Long {
        if (energyFrames.isEmpty()) return 0L

        val smooth = FloatArray(energyFrames.size)
        for (i in energyFrames.indices) {
            var sum = 0f
            var count = 0
            for (k in -2..2) {
                val j = i + k
                if (j in energyFrames.indices) {
                    sum += energyFrames[j]
                    count++
                }
            }
            smooth[i] = if (count > 0) sum / count else energyFrames[i]
        }

        val noiseWindow = ((1000L / hopMs).toInt())
            .coerceAtLeast(1)
            .coerceAtMost(smooth.size)

        var noiseSum = 0f
        for (i in 0 until noiseWindow) {
            noiseSum += smooth[i]
        }
        val noiseFloor = noiseSum / noiseWindow.toFloat()

        val threshold = max(noiseFloor * 2.2f, 0.015f)
        val needRun = ((250L / hopMs).toInt()).coerceAtLeast(2)

        var run = 0
        for (i in smooth.indices) {
            if (smooth[i] >= threshold) {
                run++
                if (run >= needRun) {
                    val startFrame = (i - run + 1).coerceAtLeast(0)
                    return startFrame * hopMs
                }
            } else {
                run = 0
            }
        }

        return 0L
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = (sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): LSColor {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c

        val (rf, gf, bf) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return LSColor(
            ((rf + m) * 255f).toInt().coerceIn(0, 255),
            ((gf + m) * 255f).toInt().coerceIn(0, 255),
            ((bf + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    // =========================================================================
    // Audio decode / envelope
    // =========================================================================

    private fun decodeEnvelopeInternal(
        musicPath: String,
        hopMs: Int,
        mode: EnvMode
    ): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(musicPath)

            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                extractor.release()
                return emptyList()
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                extractor.release()
                return emptyList()
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples = max(1, sampleRate * hopMs / 1000)

            val out = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            var sawInputEOS = false
            var sawOutputEOS = false
            val pcmWindow = ArrayList<Float>(hopSamples)

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val timeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            val mono = pcm16ToMonoFloat(chunk, channelCount)
                            val filtered = when (mode) {
                                EnvMode.FULL -> mono
                                EnvMode.LOW -> lowBandProxy(mono)
                                EnvMode.MID -> midBandProxy(mono)
                            }

                            for (v in filtered) {
                                pcmWindow += v
                                if (pcmWindow.size >= hopSamples) {
                                    out += rms(pcmWindow)
                                    pcmWindow.clear()
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            normalizeEnvelope(out)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeEnvelopeInternal fail mode=$mode path=$musicPath: ${t.message}")
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            emptyList()
        }
    }

    private fun pcm16ToMonoFloat(bytes: ByteArray, channels: Int): List<Float> {
        if (bytes.isEmpty()) return emptyList()

        val out = ArrayList<Float>(bytes.size / 2 / max(1, channels))
        var i = 0

        while (i + 1 < bytes.size) {
            var sum = 0f
            var count = 0

            for (c in 0 until channels) {
                val idx = i + c * 2
                if (idx + 1 < bytes.size) {
                    val lo = bytes[idx].toInt() and 0xFF
                    val hi = bytes[idx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    val signed = if (sample > 32767) sample - 65536 else sample
                    sum += signed / 32768f
                    count++
                }
            }

            out += if (count == 0) 0f else sum / count.toFloat()
            i += channels * 2
        }

        return out
    }

    private fun lowBandProxy(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val lp = onePoleLowPass(src, 0.12f)
        return lp.map { abs(it) }
    }

    private fun midBandProxy(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val lp1 = onePoleLowPass(src, 0.35f)
        val lp2 = onePoleLowPass(src, 0.08f)
        return List(src.size) { i -> abs(lp1[i] - lp2[i]) }
    }

    private fun onePoleLowPass(src: List<Float>, alpha: Float): List<Float> {
        if (src.isEmpty()) return emptyList()

        val out = ArrayList<Float>(src.size)
        var y = 0f
        for (x in src) {
            y += alpha * (x - y)
            out += y
        }
        return out
    }

    private fun rms(src: List<Float>): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f
        for (x in src) sum += x * x
        return sqrt(sum / src.size.toFloat())
    }

    private fun normalizeEnvelope(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val smooth = movingAverage(src, 5)
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src

        val out = ArrayList<Float>(src.size)
        val half = window / 2

        for (i in src.indices) {
            var sum = 0f
            var count = 0
            val s = max(0, i - half)
            val e = min(src.lastIndex, i + half)

            for (j in s..e) {
                sum += src[j]
                count++
            }

            out += if (count == 0) 0f else sum / count.toFloat()
        }

        return out
    }

    // =========================================================================

    fun getVersion(): Int = VERSION
}