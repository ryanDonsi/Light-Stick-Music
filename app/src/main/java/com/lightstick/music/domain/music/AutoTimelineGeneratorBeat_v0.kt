package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs

/**
 * AutoTimelineGeneratorBeat_v0 — BeatDetector 검증 전용
 *
 * 섹션 분석 없음. BeatDetectorV1 / V2 알고리즘 비교 테스트 전용 (BeatDetector Test)
 * 20% ON / 80% OFF 처리만 수행한다.
 * 5초 단위로 팔레트 색상을 바꿔 비트 연속성을 육안으로 확인한다.
 */
class AutoTimelineGeneratorBeat_v0 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION     = 13
        private const val HOP_MS      = 10L
        private val MIN_BEAT_MS = AutoTimelineConfig.MIN_BEAT_MS
        private val MAX_BEAT_MS = AutoTimelineConfig.MAX_BEAT_MS
        private const val MAX_DECODE_MS = 600_000L  // 최대 10분 (OOM 방지)

        // IIR filter coefficients (V8 최적화)
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
    }


    data class Palette(
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor,
        val c5: LSColor,
        val white: LSColor,
        val black: LSColor,
        val size: Int
    )

    // ──────────────────────────────────────────────────────────────
    // generate
    // ──────────────────────────────────────────────────────────────

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        val fileName  = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total   = System.currentTimeMillis()
        Log.d(TAG, "v0 [PERF] generate() start file=$fileName musicId=$musicId")

        val pSize   = paletteSize.coerceIn(3, 5)
        val palette = buildPalette(musicId, pSize)

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION

        // ── 1+2. BeatDetector + Envelope 단일 decode ──────────────────
        val t0Decode = System.currentTimeMillis()
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)
        val beatInfo = BeatDetectorRouter.detect(
            filePath  = musicPath,
            version   = detectorVer,
            hopMs     = effectiveHopMs,
            minBeatMs = MIN_BEAT_MS,
            maxBeatMs = MAX_BEAT_MS
        )
        val durationMs = beatInfo.envelopes?.let { it.full.size.toLong() * effectiveHopMs }
            ?: (beatInfo.beats.lastOrNull()?.timeMs?.plus(beatInfo.beatMs) ?: 0L)
        Log.d(TAG, "v0 [PERF] decode+beat=${System.currentTimeMillis() - t0Decode}ms beatMs=${beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)} beats=${beatInfo.beats.size} beatsPerBar=${beatInfo.beatsPerBar} detectorVer=$detectorVer")

        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = beatInfo.beatsPerBar

        // 비트 타임스탬프 로그 (처음 12개 + 마지막 4개)
        if (beatInfo.beats.isNotEmpty()) {
            val first = beatInfo.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = beatInfo.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v0 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        // 비트 품질 분석 (confidence ≤ 0.20 = 합성 비트)
        if (beatInfo.beats.isNotEmpty()) {
            val synth  = beatInfo.beats.count { it.confidence <= 0.20f }
            val real   = beatInfo.beats.size - synth
            val sPct   = synth * 100 / beatInfo.beats.size
            Log.d(TAG, "v0 [A] quality[$fileName]: real=$real synth=$synth(${sPct}%) total=${beatInfo.beats.size}")

            val gapTh   = globalBeatMs * 3
            val bigGaps = (1 until beatInfo.beats.size).mapNotNull { i ->
                val gap = beatInfo.beats[i].timeMs - beatInfo.beats[i - 1].timeMs
                if (gap >= gapTh) "${beatInfo.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v0 [A] gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v0 [A] gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        // ── 3. 타임라인 빌드 ──────────────────────────────────────────
        val t0Build = System.currentTimeMillis()
        val frames = buildTimeline(beatInfo.beats, globalBeatMs, beatsPerBar, beatInfo.downbeatMs, durationMs, palette, musicId)
        Log.d(TAG, "v0 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v0 [PERF] total=${System.currentTimeMillis() - t0Total}ms  file=$fileName durationMs=$durationMs")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — 20% ON / 80% OFF, 섹션 없음
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        beatsPerBar: Int,
        downbeatMs: Long,
        durationMs: Long,
        palette: Palette,
        musicId: Int
    ): List<Pair<Long, ByteArray>> {
        val frames    = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip = 0

        // 1/4박자마다 ON — downbeat 기준 마디 내 위치로 색상 결정
        // beatInBar 0(강박)=White, 1=Purple, 2=Yellow, 3=Cyan
        for (beat in beats) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) { rangeSkip++; continue }

            val beatInBar = if (beatMs > 0L) {
                val steps = Math.round((t - downbeatMs).toDouble() / beatMs.toDouble())
                (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
            } else 0

            val color = when (beatInBar) {
                0    -> LSColor(255, 255, 255)      // White  — 강박
                1    -> LSColor(255, 0,   255)      // Purple — 약박
                2    -> LSColor(255, 255, 0)        // Yellow — 중간박
                else -> LSColor(0,   255, 255)      // Cyan   — 약박
            }
            val fade = when (beatInBar) {
                0    -> 100   // 강박 — 최대 밝기
                2    -> 100   // 중간박
                else -> 35    // 약박
            }
            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
        }

        Log.d(TAG, "v0 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size} downbeatMs=$downbeatMs")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Color — barIndex 기반 배열 순환 (musicId로 시작 오프셋 결정)
    // ──────────────────────────────────────────────────────────────

    // barMs = beatMs × beatsPerBar: 4/4 → 4박, 3/4 → 3박마다 색상 변경
    // 색상 배열 [c1, c2, c3, c4, c5, white] 를 barIndex 순서로 Rotate
    // musicId 기반으로 시작 오프셋(0~5)을 고정 → 같은 곡은 항상 같은 색상 순서
    private fun colorForBar(
        musicId: Int,
        palette: Palette,
        barMs: Long,
        firstBeatMs: Long,
        tMs: Long
    ): LSColor {
        val colorArray = arrayOf(palette.c1, palette.c2, palette.c3, palette.c4, palette.c5, palette.white)
        val startOffset = ((musicId and 0x7FFFFFFF) % colorArray.size)
        val elapsed     = (tMs - firstBeatMs).coerceAtLeast(0L)
        val barIndex    = if (barMs > 0L) (elapsed / barMs).toInt() else 0
        return colorArray[(startOffset + barIndex) % colorArray.size]
    }

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        // v8과 동일한 Knuth 해시 — musicId → baseHue 결정, Random 불필요
        val rawHue  = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val baseHue = (((rawHue % 360) + 360) % 360).toFloat()
        return Palette(
            c1    = hsvToColor(baseHue,                     1.00f, 1.00f),
            c2    = hsvToColor(wrap360(baseHue +  60f),     1.00f, 1.00f),
            c3    = hsvToColor(wrap360(baseHue -  60f),     0.85f, 0.95f),
            c4    = hsvToColor(wrap360(baseHue - 120f),     1.00f, 1.00f),
            c5    = hsvToColor(wrap360(baseHue + 120f),     0.90f, 0.95f),
            white = LSColor(255, 255, 255),
            black = LSColor(0, 0, 0),
            size  = paletteSize
        )
    }

    private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f

    private fun hsvToColor(h: Float, s: Float, v: Float): LSColor {
        val hh = ((h % 360f) + 360f) % 360f
        val c  = v * s
        val x  = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m  = v - c
        val (rf, gf, bf) = when {
            hh < 60f  -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else      -> Triple(c, 0f, x)
        }
        return LSColor(
            ((rf + m) * 255f).toInt().coerceIn(0, 255),
            ((gf + m) * 255f).toInt().coerceIn(0, 255),
            ((bf + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    fun getVersion(): Int = VERSION
}
