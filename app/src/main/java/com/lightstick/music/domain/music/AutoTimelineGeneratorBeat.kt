package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.domain.music.beatdetector.BeatDetectorRouter
import com.lightstick.music.domain.music.sectiondetector.SectionDetector
import com.lightstick.music.domain.music.sectiondetector.SectionDetectorRouter
import com.lightstick.music.domain.music.sectiondetector.meta.SectionMeta
import com.lightstick.music.domain.music.genredetector.GenreDetector
import com.lightstick.music.domain.music.effectmatchingengine.EffectMatchingEngine
import com.lightstick.music.domain.music.effectmatchingengine.EffectMatchingEngineRouter
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AutoTimelineGeneratorBeat — 비트/섹션 감지 결과로부터 자동 이펙트 타임라인을 생성하는 통합 생성기.
 * AutoTimelineConfig 설정에 따라 비트 감지기(BeatDetectorRouter)·섹션 감지기
 * (SectionDetectorRouter)·이펙트 매칭 엔진(EffectMatchingEngineRouter)을 조합해 동작한다.
 */
class AutoTimelineGeneratorBeat : AutoTimelineGenerator, AutoTimelineGeneratorWithSections {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val MIN_BEAT_MS = AutoTimelineConfig.MIN_BEAT_MS
        private const val MAX_BEAT_MS = AutoTimelineConfig.MAX_BEAT_MS
        private const val MIN_TRAILING_SILENCE_MS = 1_500L
    }

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

        Log.d(TAG, "generate() start effectRule=$effectRuleVersion file=$fileName musicId=$musicId")

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        // 비트 감지
        val t0Beat = System.currentTimeMillis()
        // V3 analysis data save를 위해 application context 획득
        val appContext = try {
            android.app.ActivityManager::class.java.classLoader?.let {
                Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.app.Application
            }
        } catch (e: Exception) {
            null
        }

        val beatInfo = BeatDetectorRouter.detect(
            filePath = musicPath,
            version = detectorVer,
            hopMs = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS,
            context = appContext
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

        // 섹션 감지 (항상 활성화)
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
                ab.copy(sectionType = SectionDetector.SectionType.END)
            else ab
        }
        val tSection = System.currentTimeMillis() - t0Section
        val sectionGroups = groupAnnotatedBeats(detectedAnnotated, durationMs)

        // 음악 스타일 분류
        val musicStyle = GenreDetector.classify(
            lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
            beatMs = globalBeatMs, beats = beatInfo.beats, hopMs = effectiveHopMs
        ).style

        // 이펙트 엔진 선택 및 실행
        val engine = EffectMatchingEngineRouter.createEngine()
        val palette = engine.buildPalette(musicId, musicStyle)

        val isBalladMode = musicStyle == GenreDetector.MusicStyle.BALLAD
                        || musicStyle == GenreDetector.MusicStyle.HIPHOP_RNB

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
            beatsPerBar = beatsPerBar,
            fullEnv = fullEnv,
            hopMs = effectiveHopMs
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
            // 구간 특성값은 AnnotatedBeat.section* 필드에서 가져온다 (대표값: 첫 비트)
            val rep = g.annotatedBeats.firstOrNull()
            SectionMeta(
                startMs = g.startMs, endMs = g.endMs,
                type = g.type, changeStrength = changeStrength,
                beatMs = globalBeatMs, beatConfidence = confidence,
                energy       = rep?.sectionEnergy ?: 0f,
                peakEnergy   = rep?.sectionPeakEnergy ?: 0f,
                lowRatio     = rep?.sectionLowRatio ?: 0f,
                midRatio     = rep?.sectionMidRatio ?: 0f,
                highRatio    = rep?.sectionHighRatio ?: 0f,
                onsetDensity = rep?.sectionOnsetDensity ?: 0f,
                periodicity  = rep?.sectionPeriodicity ?: 0f,
                musicStyle = if (idx == 0) musicStyle else null,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }

        val tTotal = System.currentTimeMillis() - t0Total
        val tOverhead = tTotal - tBeat - tSection - tEffect

        logTimelineStats(fileName, musicId, durationMs, tTotal, tBeat, detectorVer, tSection,
            sectionDetectorVer, true, tEffect, effectRuleVersion, tOverhead)
        Log.d(TAG, "[PERF] total=${tTotal}ms frames=${frames.size}")

        return Pair(frames.sortedBy { it.first }, sectionMetas)
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
}
