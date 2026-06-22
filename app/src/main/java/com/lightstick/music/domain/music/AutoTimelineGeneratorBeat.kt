package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AutoTimelineGeneratorBeat — 통합 이펙트 생성기
 *
 * === 개요 ===
 * AutoTimelineConfig의 설정에 따라 동작:
 *  - BEAT_DETECTOR_VERSION: 비트 감지기 선택 (V0/V1/V2/V3)
 *  - SECTION_DETECTOR_VERSION: 섹션 감지기 선택 (USE_SECTION_DETECTOR=true일 때만)
 *  - EFFECT_RULE_VERSION: 이펙트 생성 규칙
 *      0 = v0 (간단한 비트 ON/OFF, 섹션 없음)
 *      1 = v1/v2 (섹션+단순 비트)
 *      3 = v3 (V8 이펙트 규칙)
 *      6 = v6 (V8 확장)
 *  - USE_SECTION_DETECTOR: 섹션 감지 사용 여부
 *
 * === 설계 원칙: "엔진 독립성" ===
 *
 * 이 통합 생성기는 3가지 독립적 엔진의 조합으로 작동한다:
 *
 * 1. BEAT_DETECTOR (BeatDetectorRouter)
 *    - 역할: 비트(1/4박) 위치·강도·신뢰도 감지
 *    - Router 여부: ✓ 있음 (V0/V1/V2/V3 분기)
 *    - 분기 이유: 감지 알고리즘이 완전히 다름
 *      * V0: IIR ODF + Autocorrelation (느림)
 *      * V1: PCM 기반 ODF + log-normal prior (중간)
 *      * V2: SuperFlux ODF + DBN HMM (정확, 느림)
 *      * V3: V2 + 위상 보정 (최정확)
 *    - 선택 기준: 정확도 vs 속도 tradeoff
 *
 * 2. SECTION_DETECTOR (SectionDetectorRouter)
 *    - 역할: 섹션(verse/chorus/climax 등) 변경점 감지
 *    - Router 여부: ✓ 있음 (V0/V1 분기)
 *    - 분기 이유: 윈도우 크기·주기성 계산 방식이 다름
 *      * V0: per-window 자기상관 + 느림
 *      * V1: global periodicity + 빠름
 *    - 선택 기준: 정확도 vs 속도 tradeoff
 *
 * 3. EFFECT_RULE (이펙트 생성 규칙)
 *    - 역할: 비트/섹션 정보 → LED 이펙트 프레임 변환
 *    - Router 여부: ✗ 없음 (단일 구현)
 *    - 분기 안 한 이유:
 *
 *      A. 현재 필요한 규칙이 하나뿐 (V8)
 *         - v0: 단순 비트 ON/OFF (특별한 엔진 불필요, 직렬 처리)
 *         - v1: 단순 비트 ON + 팔레트 (v3의 부분집합)
 *         - v3: V8 완전 규칙 (FgEngine, 섹션별 처리)
 *         - v6: V8 확장 (v3과 동일, 향후 확장)
 *
 *      B. 알고리즘 복잡도
 *         - BeatDetector/SectionDetector: 수식 기반 (선택지 많음)
 *         - EffectRule: 디자인 기반 (선택지 적음, 예술적 판단)
 *         → 이펙트 규칙은 "알고리즘 선택" 보다 "창의적 설계"
 *         → 새 규칙을 만드는 것 = 새 엔진 필요 (Router 필요)
 *         → 하지만 현재는 V8만 사용 중
 *
 *      C. 이펙트 규칙의 계층적 포함 관계
 *         - v0 (ON/OFF) ⊂ v1 (섹션 기반 ON) ⊂ v3 (V8 엔진)
 *         - v0은 "비트마다 ON" 규칙만 필요
 *         - v1은 "비트마다 ON" + "색상 로테이션" 규칙 필요
 *         - v3은 "섹션별 이펙트 엔진 할당" + "복잡 색상/period 계산" 필요
 *         - 따라서 v3 엔진이 v0/v1을 충분히 지원 가능
 *         - 다른 엔진(예: AI 기반, 주파수 기반) 추가 필요시에만 Router화
 *
 *      D. 확장성 고려
 *         - 인터페이스 정의: EffectMatchingEngine 있음 (미래 확장 용이)
 *         - 현재 구현: AutoTimelineGeneratorBeat에 v3 로직 직접 포함
 *         - 향후 추가: EffectMatchingEngineV3 + Router로 리팩토링 가능
 *           (예: EffectMatchingEngineAI, EffectMatchingEngineFrequency 추가)
 *
 * === v0/v1/v3 흐름 비교 ===
 *
 * v0 (EFFECT_RULE_VERSION=0, USE_SECTION_DETECTOR=false):
 *   BeatDetector → beats
 *                ↓
 *           [직렬 처리]
 *           비트마다 ON/OFF 생성
 *           색상 = beatInBar 기반 고정 4색
 *                ↓
 *           frames 반환
 *   특징: 매우 빠름, 단조로움
 *
 * v1 (EFFECT_RULE_VERSION=1, USE_SECTION_DETECTOR=true):
 *   BeatDetector ─┐
 *                ├→ SectionDetector → sections
 *                ↓
 *           [섹션 기반 처리]
 *           각 섹션마다 색상 세트 결정
 *           비트마다 ON 생성 (색상 로테이션)
 *                ↓
 *           frames 반환
 *   특징: 중간 속도, 팔레트 활용으로 조화로운 색상
 *
 * v3 (EFFECT_RULE_VERSION=3, USE_SECTION_DETECTOR=true):
 *   BeatDetector ─┐
 *                ├→ SectionDetector → sections
 *                ├→ MusicStyleClassifier (발라드/팝 판정)
 *                ↓
 *           [V8 이펙트 엔진 처리]
 *           1. 섹션별 에너지 계산
 *           2. 섹션 타입별 FgEngine 할당 (ON_PULSE/STROBE/BREATH 등)
 *           3. downbeat 기반 색상 선택
 *           4. beatInBar 기반 페이드 값 계산
 *           5. 이펙트 종류별 period/randomDelay 계산
 *           6. 연속 프레임 최적화 (중복 제거)
 *           7. 음악 종료 감지 (마지막 묵음)
 *                ↓
 *           frames + sectionMeta 반환
 *   특징: 느림, 화려함, 영상미 우수
 *
 * === AutoTimelineConfig 조합 예시 ===
 *
 * 시나리오 1: "가장 빠른 처리"
 *   BEAT_DETECTOR_VERSION = 0 (가장 빠른 감지기)
 *   EFFECT_RULE_VERSION = 0 (단순 ON/OFF)
 *   USE_SECTION_DETECTOR = false
 *   → v0과 동일, 처리 시간 최단
 *
 * 시나리오 2: "최고 정확도 + 화려한 이펙트"
 *   BEAT_DETECTOR_VERSION = 3 (가장 정확한 감지기)
 *   SECTION_DETECTOR_VERSION = 1 (빠른 최적화)
 *   EFFECT_RULE_VERSION = 3 (V8 복잡 규칙)
 *   USE_SECTION_DETECTOR = true
 *   → v3과 동일, 정확도·품질 최고
 *
 * 시나리오 3: "비트 감지기 실험"
 *   BEAT_DETECTOR_VERSION = 1 (이전 감지기)
 *   SECTION_DETECTOR_VERSION = 1
 *   EFFECT_RULE_VERSION = 3
 *   USE_SECTION_DETECTOR = true
 *   → 감지기만 v1로 변경, 이펙트는 v3 품질 유지
 *   → "v1 감지기가 v3 이펙트와 어떻게 작동하는지" 테스트 가능
 *
 * === 파일 구조 개선 ===
 *
 * 이전: 1745줄 분산
 *   AutoTimelineGeneratorBeat_v0.kt (209줄)
 *   AutoTimelineGeneratorBeat_v1.kt (453줄)
 *   AutoTimelineGeneratorBeat_v2.kt (191줄)
 *   AutoTimelineGeneratorBeat_v3.kt (668줄)
 *
 * 현재: 통합 구현
 *   AutoTimelineGeneratorBeat.kt (단일 파일)
 *   - 코드 중복 제거 (팔레트, 색상, 유틸리티)
 *   - 공통 구조 추출
 *   - 설정 기반 분기로 유연성 증대
 *   - AutoTimelineConfig로 모든 버전 조합 지원
 *
 * === 향후 확장 계획 ===
 *
 * Phase 1 (현재):
 *   - BeatDetectorRouter ✓
 *   - SectionDetectorRouter ✓
 *   - EffectMatchingEngine (단일 v3 구현)
 *
 * Phase 2 (예정):
 *   - EffectMatchingEngineRouter 추가
 *   - EffectMatchingEngineV3 (현재 로직 추출)
 *   - EffectMatchingEngineAI (LLM/ML 기반 이펙트)
 *   - EffectMatchingEngineFrequency (주파수 기반 이펙트)
 *   - AutoTimelineConfig.EFFECT_ENGINE_VERSION 추가
 *
 * Phase 3 (미래):
 *   - 이펙트 설계 UI (사용자가 규칙 커스터마이징)
 *   - 실시간 이펙트 프리뷰
 *   - 커뮤니티 공유 이펙트 규칙
 */
class AutoTimelineGeneratorBeat : AutoTimelineGenerator, SectionAwareGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val MIN_BEAT_MS = AutoTimelineConfig.MIN_BEAT_MS
        private const val MAX_BEAT_MS = AutoTimelineConfig.MAX_BEAT_MS
        private const val MIN_TRAILING_SILENCE_MS = 1_500L

        private const val ON_TRANSIT = 2
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT
    }

    // ──────────────────────────────────────────────────────────────
    // V8 이펙트 타입 (v3/v6용)
    // ──────────────────────────────────────────────────────────────

    enum class FgEngine { ON_PULSE, BLINK, STROBE, BREATH, ON_TRANSIT_ROTATE, OFF_TRANSIT }

    enum class ChangeLevel { MEDIUM, STRONG }

    data class ColorSet(val fg: LSColor, val bg: LSColor)

    data class Palette(
        val black: LSColor, val white: LSColor,
        val onPulseSets: List<ColorSet>,
        val blinkSets:   List<ColorSet>,
        val strokeSets:  List<ColorSet>,
        val breathSet:   ColorSet,
        val bridgeSets:  List<ColorSet>,
        val chorusBg:    LSColor,
        val colorGroup:  List<LSColor>
    )

    data class V8Section(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val engine: FgEngine,
        val beatMs: Long, val beats: Int,
        val source: String, val change: ChangeLevel,
        val energyScore: Float = 0f, val relScore: Float = 0f,
        val beatTimesMs: List<Long> = emptyList()
    )

    private data class SectionGroup(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val annotatedBeats: List<SectionDetector.AnnotatedBeat>
    )

    // ──────────────────────────────────────────────────────────────
    // 진입점
    // ──────────────────────────────────────────────────────────────

    override fun generate(musicPath: String, musicId: Int, paletteSize: Int): List<Pair<Long, ByteArray>> {
        return generateWithSections(musicPath, musicId, paletteSize).first
    }

    override fun generateWithSections(
        musicPath: String, musicId: Int, paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total = System.currentTimeMillis()
        val effectRuleVersion = AutoTimelineConfig.EFFECT_RULE_VERSION
        val useSectionDetector = AutoTimelineConfig.USE_SECTION_DETECTOR

        Log.d(TAG, "generate() start effectRule=$effectRuleVersion useSection=$useSectionDetector file=$fileName musicId=$musicId")

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        // 비트 감지
        val t0Beat = System.currentTimeMillis()
        val beatInfo = BeatDetectorRouter.detect(
            filePath = musicPath,
            version = detectorVer,
            hopMs = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )
        val tBeat = System.currentTimeMillis() - t0Beat

        val envelopes = beatInfo.envelopes
        if (envelopes == null || envelopes.full.isEmpty()) {
            Log.w(TAG, "env empty")
            return Pair(emptyList(), emptyList())
        }

        val lowEnv = envelopes.low
        val midEnv = envelopes.mid
        val fullEnv = envelopes.full
        val highEnv = envelopes.high

        val durationMs = fullEnv.size.toLong() * effectiveHopMs
        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar = beatInfo.beatsPerBar
        val downbeatMs = beatInfo.downbeatMs
        val beatTimesMs = beatInfo.beats.map { it.timeMs }.filter { it in 0..durationMs }

        if (beatTimesMs.isEmpty()) {
            Log.w(TAG, "beat detect FAIL")
            return Pair(emptyList(), emptyList())
        }

        // 음악 종료 위치 감지
        val finalOffMs = detectLastMusicEndMs(fullEnv.toFloatArray(), effectiveHopMs, MIN_TRAILING_SILENCE_MS)
            .coerceIn(0L, durationMs)

        // 섹션 감지
        var sectionGroups: List<SectionGroup> = emptyList()
        var musicStyle: MusicStyleClassifier.MusicStyle = MusicStyleClassifier.MusicStyle.POP
        var tSection = 0L

        if (useSectionDetector) {
            val sectionDetectorVer = AutoTimelineConfig.SECTION_DETECTOR_VERSION
            val t0Section = System.currentTimeMillis()
            val detectedAnnotated = SectionDetectorRouter.detect(
                version = sectionDetectorVer,
                lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
                beats = beatInfo.beats,
                beatMs = globalBeatMs, durationMs = durationMs, hopMs = effectiveHopMs,
                beatsPerBar = beatsPerBar, downbeatMs = downbeatMs
            ).map { ab ->
                if (ab.timeMs >= finalOffMs)
                    SectionDetector.AnnotatedBeat(ab.timeMs, ab.confidence, SectionDetector.SectionType.END)
                else ab
            }
            tSection = System.currentTimeMillis() - t0Section
            sectionGroups = groupAnnotatedBeats(detectedAnnotated, durationMs)

            // 음악 스타일 분류
            musicStyle = MusicStyleClassifier.classify(
                lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
                beatMs = globalBeatMs, beats = beatInfo.beats, hopMs = effectiveHopMs
            ).style
        }

        // 이펙트 엔진 선택 및 실행
        val engine = EffectMatchingEngineRouter.createEngine()
        val palette = engine.buildPalette(musicId)

        val isBalladMode = musicStyle == MusicStyleClassifier.MusicStyle.BALLAD
                        || musicStyle == MusicStyleClassifier.MusicStyle.HIPHOP_RNB

        val t0Effect = System.currentTimeMillis()
        val frames = engine.buildFrames(
            palette = palette,
            sectionGroups = sectionGroups.map { g ->
                EffectMatchingEngine.SectionGroup(
                    startMs = g.startMs, endMs = g.endMs,
                    type = g.type, annotatedBeats = g.annotatedBeats
                )
            },
            beatTimesMs = beatTimesMs,
            durationMs = durationMs,
            isBalladMode = isBalladMode,
            finalOffMs = finalOffMs,
            downbeatMs = downbeatMs,
            beatsPerBar = beatsPerBar
        )
        val tEffect = System.currentTimeMillis() - t0Effect

        // 섹션 메타 생성
        val sectionMetas = sectionGroups.mapIndexed { idx, g ->
            val confidence = if (g.annotatedBeats.isNotEmpty())
                g.annotatedBeats.map { it.confidence }.average().toFloat() else 0.20f
            val changeStrength = when {
                g.annotatedBeats.size < 8 -> SectionDetector.ChangeStrength.MEDIUM
                else -> SectionDetector.ChangeStrength.STRONG
            }
            SectionMeta(
                startMs = g.startMs, endMs = g.endMs,
                type = g.type, changeStrength = changeStrength,
                beatMs = globalBeatMs, beatConfidence = confidence,
                musicStyle = if (idx == 0) musicStyle else null,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }

        val tTotal = System.currentTimeMillis() - t0Total
        val tOverhead = tTotal - tBeat - tSection - tEffect

        logTimelineStats(fileName, musicId, durationMs, tTotal, tBeat, detectorVer, tSection,
            if (useSectionDetector) AutoTimelineConfig.SECTION_DETECTOR_VERSION else 0,
            useSectionDetector, tEffect, effectRuleVersion, tOverhead)
        Log.d(TAG, "[PERF] total=${tTotal}ms frames=${frames.size}")

        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }


    // ──────────────────────────────────────────────────────────────
    // V8 섹션 변환 (v3/v6 공용)
    // ──────────────────────────────────────────────────────────────

    private fun convertToV8Sections(
        groups: List<SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<V8Section> {
        if (groups.isEmpty()) return emptyList()

        val energies = groups.map { g -> computeGroupEnergy(g.startMs, g.endMs, fullEnv, durationMs, hopMs) }
        val lowTh = percentile(energies, 0.35f)
        val highTh = percentile(energies, 0.70f)
        val range = (highTh - lowTh).coerceAtLeast(1e-6f)

        return groups.mapIndexed { i, g ->
            val energy = energies[i]
            val beats = g.annotatedBeats.size
            val relScore = ((energy - lowTh) / range).coerceIn(0f, 1f)

            val normalizedType = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 6 ->
                    SectionDetector.SectionType.VERSE
                else -> g.type
            }

            val engine = assignFgEngine(normalizedType, relScore, beats, globalBeatMs = beatMs,
                isBalladMode = isBalladMode)

            val source = buildSourceName(normalizedType, engine, beats)

            val change = when {
                normalizedType == SectionDetector.SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
                beats < 8 -> ChangeLevel.MEDIUM
                else -> ChangeLevel.STRONG
            }

            V8Section(
                startMs = g.startMs, endMs = g.endMs,
                type = normalizedType, engine = engine,
                beatMs = beatMs, beats = beats,
                source = source, change = change,
                energyScore = energy, relScore = relScore,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }
    }

    private fun computeGroupEnergy(startMs: Long, endMs: Long, fullEnv: List<Float>, durationMs: Long, hopMs: Long): Float {
        if (fullEnv.isEmpty()) return 0f
        val startIdx = (startMs / hopMs).toInt().coerceIn(0, fullEnv.lastIndex)
        val endIdx = (endMs / hopMs).toInt().coerceAtMost(fullEnv.lastIndex)
        if (endIdx <= startIdx) return fullEnv.getOrElse(startIdx) { 0f }
        return fullEnv.subList(startIdx, endIdx + 1).average().toFloat()
    }

    private fun assignFgEngine(
        type: SectionDetector.SectionType,
        rel: Float, beats: Int, globalBeatMs: Long,
        isBalladMode: Boolean
    ): FgEngine = when (type) {
        SectionDetector.SectionType.INTRO -> FgEngine.BREATH
        SectionDetector.SectionType.OUTRO -> FgEngine.OFF_TRANSIT
        SectionDetector.SectionType.BREAK -> FgEngine.BREATH

        SectionDetector.SectionType.CLIMAX -> when {
            globalBeatMs <= 300L -> FgEngine.STROBE
            rel >= 0.60f -> FgEngine.STROBE
            else -> FgEngine.ON_TRANSIT_ROTATE
        }
        SectionDetector.SectionType.BUILD -> FgEngine.ON_TRANSIT_ROTATE

        SectionDetector.SectionType.BEAT -> when {
            isBalladMode -> FgEngine.BREATH
            globalBeatMs <= 350L -> FgEngine.BLINK
            else -> FgEngine.ON_PULSE
        }
        SectionDetector.SectionType.VOCAL -> when {
            isBalladMode -> FgEngine.BREATH
            rel >= 0.55f -> FgEngine.ON_PULSE
            else -> FgEngine.BREATH
        }

        SectionDetector.SectionType.VERSE -> if (isBalladMode) FgEngine.BREATH else FgEngine.ON_PULSE
        SectionDetector.SectionType.CHORUS -> FgEngine.ON_TRANSIT_ROTATE
        SectionDetector.SectionType.BRIDGE -> FgEngine.BREATH
        SectionDetector.SectionType.END -> FgEngine.OFF_TRANSIT
    }

    private fun buildSourceName(type: SectionDetector.SectionType, engine: FgEngine, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.INTRO -> "intro-breath"
            SectionDetector.SectionType.OUTRO -> "outro-off"
            SectionDetector.SectionType.BREAK -> "break-breath"
            SectionDetector.SectionType.CLIMAX -> if (engine == FgEngine.STROBE) "climax-strobe" else "climax-rotate"
            SectionDetector.SectionType.BUILD -> "build-rotate"
            SectionDetector.SectionType.BEAT -> when (engine) {
                FgEngine.BLINK -> "beat-blink"
                else -> "beat-pulse"
            }
            SectionDetector.SectionType.VOCAL -> if (engine == FgEngine.BREATH) "vocal-breath" else "vocal-pulse"
            SectionDetector.SectionType.VERSE -> "verse-on-pulse"
            SectionDetector.SectionType.CHORUS -> "chorus-rotate"
            SectionDetector.SectionType.BRIDGE -> "bridge-breath"
            SectionDetector.SectionType.END -> "end-off"
        }

    // ──────────────────────────────────────────────────────────────
    // 프레임 빌딩 (v3/v6 공용)
    // ──────────────────────────────────────────────────────────────

    private fun buildFramesFromSections(
        palette: Palette,
        sections: List<V8Section>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long = 0L,
        beatsPerBar: Int = 4
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        data class RepeatKey(
            val engine: FgEngine,
            val fgR: Int, val fgG: Int, val fgB: Int,
            val bgR: Int, val bgG: Int, val bgB: Int,
            val period: Int, val randomDelay: Int
        )
        var lastRepeatKey: RepeatKey? = null

        val sameTypeCountMap = mutableMapOf<SectionDetector.SectionType, Int>()

        for ((index, section) in sections.withIndex()) {
            val sameTypeIdx = sameTypeCountMap.getOrDefault(section.type, 0)
            sameTypeCountMap[section.type] = sameTypeIdx + 1
            lastRepeatKey = null

            val effectiveBeats = section.beatTimesMs

            if (section.type == SectionDetector.SectionType.BEAT) {
                for (t in effectiveBeats) {
                    val beatInBar = beatInBar(t, downbeatMs, globalBeatMs = section.beatMs, beatsPerBar)
                    val (color, fade) = beatSectionColorAndFade(beatInBar, palette)
                    put(t, LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
                }
                continue
            }

            if (section.engine == FgEngine.OFF_TRANSIT) continue

            for ((beatIndex, t) in effectiveBeats.withIndex()) {
                val beatEngine = if (section.type == SectionDetector.SectionType.BRIDGE)
                    bridgePhaseEngine(beatIndex, effectiveBeats.size, section.beatMs, section.relScore, isBalladMode)
                else section.engine

                val effectiveEngine = beatEngine

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, sameTypeIdx, beatIndex, section.type)
                val bgNonNull = bg ?: LSColor(0, 0, 0)

                val beatPeriod = when (effectiveEngine) {
                    FgEngine.STROBE -> 1
                    FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == FgEngine.STROBE -> 1
                    effectiveEngine == FgEngine.ON_TRANSIT_ROTATE -> null
                    effectiveEngine == FgEngine.ON_PULSE -> null
                    effectiveEngine == FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.VERSE -> 0
                    effectiveEngine == FgEngine.BREATH -> msToBreathRandomDelay(section.beatMs)
                    else -> null
                }
                val beatRotateTransit = if (effectiveEngine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0

                val skipOnPulseOdd = (beatEngine == FgEngine.ON_PULSE && beatIndex % 2 != 0)

                val skipRepeat = if (skipOnPulseOdd) {
                    true
                } else if (effectiveEngine == FgEngine.ON_TRANSIT_ROTATE
                    || effectiveEngine == FgEngine.STROBE
                    || effectiveEngine == FgEngine.BREATH) {
                    val key = RepeatKey(effectiveEngine,
                        fg.r, fg.g, fg.b, bgNonNull.r, bgNonNull.g, bgNonNull.b,
                        beatPeriod ?: 0, beatRandomDelay ?: 0)
                    val dup = (key == lastRepeatKey); lastRepeatKey = key; dup
                } else {
                    lastRepeatKey = null; false
                }

                if (!skipRepeat) {
                    put(t, buildPayload(effectiveEngine, fg, bg, section.beatMs, beatPeriod,
                        beatRandomDelay ?: 0, rotateTransit = beatRotateTransit))
                }

                if (beatEngine == FgEngine.ON_PULSE && !skipOnPulseOdd) {
                    val holdMs = minOf(ON_PULSE_ACCENT_HOLD_MS * 2L, section.beatMs * 44L / 100L).coerceAtLeast(60L)
                    val offT = minOf(section.endMs - 1L, t + holdMs)
                    if (offT > t)
                        put(offT, LSEffectPayload.Effects.off(transit = 3).toByteArray())
                }
            }
        }

        frameMap.keys.filter { it >= finalOffMs }.forEach { frameMap.remove(it) }
        frameMap[finalOffMs] = buildOffPayload()

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun beatInBar(tMs: Long, downbeatMs: Long, globalBeatMs: Long, beatsPerBar: Int): Int {
        if (globalBeatMs <= 0L || beatsPerBar <= 0) return 0
        val steps = Math.round((tMs - downbeatMs).toDouble() / globalBeatMs.toDouble())
        return (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
    }

    private fun beatSectionColorAndFade(beatInBar: Int, palette: Palette): Pair<LSColor, Int> {
        if (beatInBar == 0) return palette.white to 100
        val paletteColor = palette.colorGroup.getOrElse(beatInBar - 1) { palette.colorGroup.first() }
        val fade = when (beatInBar) { 2 -> 100; else -> 35 }
        return paletteColor to fade
    }

    private fun buildPayload(
        engine: FgEngine, fg: LSColor, bg: LSColor?, beatMs: Long,
        period: Int? = null, randomDelay: Int = 0, rotateTransit: Int = 0
    ): ByteArray {
        val bgColor = bg ?: LSColor(0, 0, 0)
        return when (engine) {
            FgEngine.ON_PULSE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            FgEngine.BLINK ->
                LSEffectPayload.Effects.blink(period = period ?: msToBlinkPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay).toByteArray()
            FgEngine.STROBE ->
                LSEffectPayload.Effects.strobe(period = period ?: msToStrobePeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay).toByteArray()
            FgEngine.BREATH ->
                LSEffectPayload.Effects.breath(period = period ?: msToBreathPeriod(beatMs),
                    color = fg, backgroundColor = bgColor,
                    randomDelay = randomDelay.takeIf { it > 0 } ?: 5).toByteArray()
            FgEngine.ON_TRANSIT_ROTATE ->
                LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
            FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(): ByteArray = LSEffectPayload.Effects.off(transit = ON_TRANSIT).toByteArray()

    private fun bridgePhaseEngine(
        beatIndex: Int, totalBeats: Int, beatMs: Long, relScore: Float, isBalladMode: Boolean
    ): FgEngine {
        if (isBalladMode || relScore < 0.1f) return FgEngine.BREATH
        if (totalBeats <= 0) return FgEngine.STROBE
        val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)
        return when {
            totalBeats < 8 -> FgEngine.STROBE
            totalBeats < 16 -> {
                val phase = beatIndex.toFloat() / totalBeats
                if (phase < strobeEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
            else -> {
                val phase = beatIndex.toFloat() / totalBeats
                val rotateEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
                if (phase < rotateEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 팔레트 및 색상
    // ──────────────────────────────────────────────────────────────

    private fun buildPalette(seed: Int): Palette {
        val rawHue = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val baseHue = (((rawHue % 360) + 360) % 360).toFloat()
        val cMain = hsvToColor(baseHue, 1.00f, 1.00f)
        val cStep1 = hsvToColor(wrap360(baseHue + 60f), 1.00f, 1.00f)
        val cStep2 = hsvToColor(wrap360(baseHue - 60f), 0.85f, 0.95f)
        val cStep3 = hsvToColor(wrap360(baseHue - 120f), 1.00f, 1.00f)
        val cDeep = hsvToColor(baseHue, 1.00f, 0.48f)
        val black = LSColor(0, 0, 0)
        val white = LSColor(255, 255, 255)
        val colorGroup = listOf(cMain, cStep1, cStep2, cStep3)
        val cMainLuma = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain
        return Palette(
            black = black, white = white,
            onPulseSets = listOf(ColorSet(white, patternABg), ColorSet(cMain, black)),
            blinkSets = listOf(ColorSet(cMain, black), ColorSet(cStep1, black)),
            strokeSets = listOf(ColorSet(white, black)),
            breathSet = ColorSet(white, patternABg),
            bridgeSets = listOf(ColorSet(cStep2, black), ColorSet(cMain, black)),
            chorusBg = cDeep, colorGroup = colorGroup
        )
    }

    private fun colorsForEngine(
        palette: Palette, engine: FgEngine, sectionIndex: Int,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE
    ): Pair<LSColor, LSColor> {
        val isPatternA = (sectionIndex % 2 == 0)
        val effectiveColors: List<LSColor> = when (sectionType) {
            SectionDetector.SectionType.CHORUS -> listOf(palette.white) + palette.colorGroup.take(3)
            SectionDetector.SectionType.VERSE -> palette.colorGroup.take(3)
            SectionDetector.SectionType.BRIDGE -> listOf(
                palette.colorGroup.getOrElse(2) { palette.colorGroup[0] },
                palette.colorGroup[0], palette.white
            )
            else -> palette.colorGroup
        }
        val groupColor = effectiveColors[beatIndex % effectiveColors.size]
        val sectionColor = effectiveColors[sectionIndex % effectiveColors.size]
        return when (engine) {
            FgEngine.ON_PULSE ->
                if (isPatternA) palette.white to palette.onPulseSets[0].bg
                else sectionColor to palette.black
            FgEngine.BLINK, FgEngine.ON_TRANSIT_ROTATE -> groupColor to palette.black
            FgEngine.STROBE -> palette.white to palette.black
            FgEngine.BREATH -> palette.breathSet.fg to palette.breathSet.bg
            else ->
                if (isPatternA) palette.bridgeSets[0].fg to palette.black
                else groupColor to palette.black
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 유틸리티
    // ──────────────────────────────────────────────────────────────

    private fun detectLastMusicEndMs(frames: FloatArray, hopMs: Long, minTrailingSilenceMs: Long): Long {
        if (frames.isEmpty()) return 0L
        val totalMs = frames.size * hopMs
        val smooth = FloatArray(frames.size)
        for (i in frames.indices) {
            var sum = 0f; var cnt = 0
            for (k in -4..4) { val j = i + k; if (j in frames.indices) { sum += frames[j]; cnt++ } }
            smooth[i] = if (cnt > 0) sum / cnt else frames[i]
        }
        val threshold = max((smooth.maxOrNull() ?: 0f) * 0.03f, 0.01f)
        for (i in smooth.indices.reversed()) {
            if (smooth[i] >= threshold) {
                val lastActiveMs = (i + 1) * hopMs
                return if (totalMs - lastActiveMs >= minTrailingSilenceMs) lastActiveMs else totalMs
            }
        }
        return totalMs
    }

    private fun groupAnnotatedBeats(
        annotated: List<SectionDetector.AnnotatedBeat>,
        durationMs: Long
    ): List<SectionGroup> {
        if (annotated.isEmpty()) return emptyList()
        val groups = mutableListOf<SectionGroup>()
        var groupStart = 0
        for (i in 1..annotated.size) {
            val isLast = (i == annotated.size)
            if (isLast || annotated[i].sectionType != annotated[groupStart].sectionType) {
                val beats = annotated.subList(groupStart, i)
                val startMs = beats.first().timeMs
                val endMs = if (isLast) durationMs else annotated[i].timeMs
                groups += SectionGroup(startMs, endMs, beats.first().sectionType, beats)
                groupStart = i
            }
        }
        return groups
    }

    private fun msToBlinkPeriod(beatMs: Long) = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToStrobePeriod(beatMs: Long) = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long) = (beatMs / 20L).toInt().coerceIn(1, 255)

    // ──────────────────────────────────────────────────────────────
    // 성능 통계 로깅
    // ──────────────────────────────────────────────────────────────

    private fun logTimelineStats(
        fileName: String,
        musicId: Int,
        durationMs: Long,
        totalMs: Long,
        beatMs: Long,
        beatDetectorVer: Int,
        sectionMs: Long,
        sectionDetectorVer: Int,
        sectionDetectorEnabled: Boolean,
        effectMs: Long,
        effectMatchingVer: Int,
        overheadMs: Long
    ) {
        val logLine = buildString {
            appendLine("[TIMELINE_STATS] file=$fileName musicId=$musicId duration=$durationMs")
            appendLine("  [TOTAL] ${totalMs}ms")
            appendLine("  [BEAT_DETECT] ${beatMs}ms version=$beatDetectorVer")
            appendLine("  [SECTION_DETECT] ${sectionMs}ms version=$sectionDetectorVer enabled=$sectionDetectorEnabled")
            appendLine("  [EFFECT_MATCHING] ${effectMs}ms version=$effectMatchingVer")
            appendLine("  [OVERHEAD] ${overheadMs}ms")
        }
        Log.i(TAG, logLine.trim())
    }

    private fun msToBreathRandomDelay(beatMs: Long) = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f

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
}
