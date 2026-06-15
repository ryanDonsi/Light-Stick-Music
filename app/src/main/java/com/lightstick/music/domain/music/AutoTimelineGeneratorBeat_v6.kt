package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * AutoTimelineGeneratorBeat_v6
 *
 * 비트 검증 모드 (globalBeatMs 균등 그리드 기반):
 * - BeatDetectorV2.detect()의 beatMs 사용 (하모닉 폴딩 보정 포함, 정확도 높음)
 * - novelty 위상 추정으로 첫 비트 시작점 결정
 * - 첫 비트부터 끝까지 globalBeatMs 간격의 균등 그리드 생성
 * - 각 그리드 타임에: ON 20% → OFF 80%
 *
 * v7과의 차이:
 * - v7: BeatDetectorV11의 TimedBeat(실제 감지 시각) 사용 → 조용한 구간에 비트 누락 가능
 * - v6: BeatDetectorV11의 beatMs + 균등 그리드 → 전 구간 빈틈없이 균일한 박자 출력
 */
class AutoTimelineGeneratorBeat_v6 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val HOP_MS = 50L

        // 색상 구간 길이 (ms) — 같은 구간 내 비트는 같은 fg 색상 사용
        private const val COLOR_SEGMENT_MS = 5_000L
    }

    private data class Palette(
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor,
        val c5: LSColor,
        val white: LSColor
    )

    // -------------------------------------------------------------------------
    // AutoTimelineGenerator 인터페이스 구현
    // -------------------------------------------------------------------------

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "v6 generate() start file=$musicPath musicId=$musicId paletteSize=$paletteSize")

        val beatInfo = BeatDetectorRouter.detect(
            filePath  = musicPath,
            version   = AutoTimelineConfig.BEAT_DETECTOR_VERSION,
            hopMs     = HOP_MS,
            minBeatMs = 290L,
            maxBeatMs = 1200L
        )
        val envelopes = beatInfo.envelopes
        if (envelopes == null || envelopes.full.isEmpty()) {
            Log.w(TAG, "v6 env empty -> return empty"); return emptyList()
        }
        val lowEnv  = envelopes.low
        val midEnv  = envelopes.mid
        val fullEnv = envelopes.full
        val durationMs = fullEnv.size.toLong() * HOP_MS
        val globalBeatMs = if (beatInfo.beatMs > 0L) beatInfo.beatMs else 500L

        // 첫 비트 위상 추정 — novelty 에너지 분포 기반 (V11 beatMs 기준으로 계산)
        val novelty = computeNovelty(lowEnv, midEnv, fullEnv)
        val phaseMs = estimatePhaseOffsetMs(novelty, HOP_MS, globalBeatMs)

        Log.d(TAG, "v6 globalBeatMs=$globalBeatMs phaseMs=$phaseMs durationMs=$durationMs")

        val onDurationMs = (globalBeatMs * 20L / 100L).coerceAtLeast(1L)
        val palette = buildPalette(musicId, paletteSize.coerceIn(3, 5))

        val frames = ArrayList<Pair<Long, ByteArray>>()
        val usedTimestamps = HashSet<Long>()

        var t = phaseMs
        while (t < durationMs) {
            val fg = colorForTime(musicId, palette, t)

            if (usedTimestamps.add(t)) {
                frames += t to LSEffectPayload.Effects.on(
                    color   = fg,
                    transit = 0
                ).toByteArray()
            }

            val offT = t + onDurationMs
            if (offT < durationMs && usedTimestamps.add(offT)) {
                frames += offT to LSEffectPayload.Effects.off().toByteArray()
            }

            t += globalBeatMs
        }

        Log.d(TAG, "v6 frames=${frames.size} beats=${frames.size / 2}")
        return frames.sortedBy { it.first }
    }

    // -------------------------------------------------------------------------
    // 색상 — COLOR_SEGMENT_MS 구간마다 고정 색상 (musicId × 구간번호로 결정)
    // -------------------------------------------------------------------------

    private fun colorForTime(musicId: Int, palette: Palette, tMs: Long): LSColor {
        val seg = (tMs / COLOR_SEGMENT_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + seg * 97)
        return listOf(palette.c1, palette.c2, palette.c3, palette.c4, palette.c5, palette.white)
            .let { it[rnd.nextInt(it.size)] }
    }

    // -------------------------------------------------------------------------
    // 위상 추정
    // -------------------------------------------------------------------------

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

    /**
     * 전곡 novelty에서 첫 비트의 위상(시작점 ms)을 추정한다.
     *
     * beatMs 간격마다 novelty 에너지 합이 가장 큰 위상 오프셋 선택.
     * 이 값이 첫 비트의 절대 시각이 된다.
     */
    private fun estimatePhaseOffsetMs(
        novelty: FloatArray,
        hopMs: Long,
        beatMs: Long
    ): Long {
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag <= 1 || novelty.isEmpty()) return 0L

        var bestOffset = 0
        var bestScore  = Double.NEGATIVE_INFINITY

        for (offset in 0 until lag) {
            var s = 0.0
            var i = offset
            while (i < novelty.size) {
                s += novelty[i]
                i += lag
            }
            if (s > bestScore) {
                bestScore  = s
                bestOffset = offset
            }
        }

        return bestOffset.toLong() * hopMs
    }

    // -------------------------------------------------------------------------
    // 팔레트
    // -------------------------------------------------------------------------

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        val rnd = Random(seed)
        return Palette(
            c1    = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f),
            c2    = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f),
            c3    = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f),
            c4    = hsvToColor(rnd.nextFloat() * 360f, 0.75f, 0.9f),
            c5    = hsvToColor(rnd.nextFloat() * 360f, 0.70f, 0.95f),
            white = LSColor(255, 255, 255)
        )
    }

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
            for (j in (i - win).coerceAtLeast(0)..(i + win).coerceAtMost(x.lastIndex)) { s += copy[j]; c++ }
            x[i] = s / max(1, c)
        }
    }
}
