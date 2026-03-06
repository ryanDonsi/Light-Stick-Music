package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ✅ Beat 기반 Auto Timeline 생성기
 *
 * - 8분음표(beat/2) 기본
 * - 클라이맥스(에너지+온셋밀도 상위 구간)에서만 16분(beat/4) 일부 사용
 * - Downbeat(4박 단위 시작)에는 STROBE를 "확정"으로 넣어 리듬감/가시성 보장
 * - 나머지는 BLINK를 subBeat마다 출력 (색은 팔레트 내에서만 교대)
 */
class AutoTimelineGeneratorBeat_v1 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val BPM_MIN = 85
        private const val BPM_MAX = 175

        private const val SNAP_MS = 60L          // beat -> nearest onset snap range
        private const val MIN_STROBE_GAP_MS = 800L
        private const val SECTION_WIN_MS = 8000L // climax scoring window
    }

    data class ThemePalette(
        val black: LSColor,
        val white: LSColor,
        val whiteTint: LSColor,
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor? = null
    )

    data class Envelope(
        val hopMs: Long,
        val rms: FloatArray,       // 0..1
        val novelty: FloatArray    // 0..1 (onset-ish)
    ) {
        val durationMs: Long get() = rms.size.toLong() * hopMs
    }

    data class BeatGrid(
        val bpm: Int,
        val beatMs: Long,
        val beatTimesMs: LongArray
    )

    fun generate(musicPath: String, musicId: Int, paletteSize: Int = 4): List<Pair<Long, ByteArray>> {
        // 1) envelope 추출 (리듬용)
        val env = decodeEnvelope(musicPath, windowMs = 50)
        if (env.rms.isEmpty()) return fallback(musicId)

        // 2) BPM/BeatGrid 추정
        val grid = estimateBeatGrid(env)

        // 3) 팔레트(3~5색 + white/black 포함)
        val palette = buildPalette(musicId, paletteSize)

        // 4) climax 구간 마스크(리듬 밀도 높은 구간에서 16분 적용)
        val isClimax = detectClimaxMask(env, grid)

        // 5) 비트 기반 타임포인트 생성 (8분 기본, climax에서 16분 일부)
        val timePoints = buildTimePoints(grid, isClimax)

        // 6) 타임포인트 -> payload 생성 (downbeat strobe + subbeat blink)
        return buildFrames(timePoints, grid, isClimax, palette)
    }

    // ─────────────────────────────────────────────────────────────
    // Beat grid estimation (autocorr + snap)
    // ─────────────────────────────────────────────────────────────

    private fun estimateBeatGrid(env: Envelope): BeatGrid {
        // novelty 기반 autocorrelation으로 주기 추정
        val bpm = estimateBpmFromNovelty(env.novelty, env.hopMs)

        val beatMs = (60_000.0 / bpm.toDouble()).toLong().coerceAtLeast(200L)

        // 시작점은 초반 6초 내 가장 큰 novelty peak 근처
        val startMs = findFirstStrongPeakMs(env, searchMs = 6000L)
        val durationMs = env.durationMs

        // 1차 grid 생성
        val beats = mutableListOf<Long>()
        var t = startMs
        while (t < durationMs) {
            beats.add(t)
            t += beatMs
        }

        // snap: 각 beat를 가장 가까운 novelty peak로 ±SNAP_MS 스냅
        val snapped = LongArray(beats.size)
        for (i in beats.indices) {
            snapped[i] = snapToPeak(env, beats[i], SNAP_MS)
        }

        return BeatGrid(bpm = bpm, beatMs = beatMs, beatTimesMs = snapped)
    }

    private fun estimateBpmFromNovelty(novelty: FloatArray, hopMs: Long): Int {
        // BPM 범위에 해당하는 lag 범위(프레임)
        val minLag = ((60_000.0 / BPM_MAX) / hopMs).toInt().coerceAtLeast(1)
        val maxLag = ((60_000.0 / BPM_MIN) / hopMs).toInt().coerceAtLeast(minLag + 1)

        // novelty를 평균 제거(DC 제거)
        var mean = 0.0
        for (v in novelty) mean += v.toDouble()
        mean /= max(1, novelty.size).toDouble()

        val x = DoubleArray(novelty.size) { (novelty[it].toDouble() - mean) }

        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY

        // normalized autocorr (간단)
        for (lag in minLag..maxLag) {
            var num = 0.0
            var den1 = 0.0
            var den2 = 0.0
            val n = x.size - lag
            if (n <= 10) break

            for (i in 0 until n) {
                val a = x[i]
                val b = x[i + lag]
                num += a * b
                den1 += a * a
                den2 += b * b
            }
            val denom = (sqrt(den1) * sqrt(den2)).coerceAtLeast(1e-9)
            val score = num / denom
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        val beatMs = bestLag.toDouble() * hopMs.toDouble()
        val bpm = (60_000.0 / beatMs).toInt().coerceIn(BPM_MIN, BPM_MAX)
        return bpm
    }

    private fun findFirstStrongPeakMs(env: Envelope, searchMs: Long): Long {
        val maxIdx = (searchMs / env.hopMs).toInt().coerceIn(0, env.novelty.lastIndex)
        var bestI = 0
        var bestV = -1f
        for (i in 1..maxIdx) {
            val v = env.novelty[i]
            if (v > bestV) {
                bestV = v
                bestI = i
            }
        }
        return bestI.toLong() * env.hopMs
    }

    private fun snapToPeak(env: Envelope, tMs: Long, rangeMs: Long): Long {
        val center = (tMs / env.hopMs).toInt().coerceIn(0, env.novelty.lastIndex)
        val r = (rangeMs / env.hopMs).toInt().coerceAtLeast(1)

        var bestI = center
        var bestV = env.novelty[center]

        val start = (center - r).coerceAtLeast(0)
        val end = (center + r).coerceAtMost(env.novelty.lastIndex)

        for (i in start..end) {
            val v = env.novelty[i]
            if (v > bestV) {
                bestV = v
                bestI = i
            }
        }

        return bestI.toLong() * env.hopMs
    }

    // ─────────────────────────────────────────────────────────────
    // Climax detection (energy + onset density)
    // ─────────────────────────────────────────────────────────────

    /**
     * 8초 윈도우로 (평균 RMS + novelty 밀도) 스코어를 계산해서 상위 25%를 climax로 표시.
     * -> 그 구간에서만 16분(beat/4)을 섞음.
     */
    private fun detectClimaxMask(env: Envelope, grid: BeatGrid): BooleanArray {
        val n = env.rms.size
        val mask = BooleanArray(n)

        val winFrames = (SECTION_WIN_MS / env.hopMs).toInt().coerceAtLeast(10)
        if (n < winFrames + 10) {
            // 너무 짧으면 전체를 false
            return mask
        }

        val scores = DoubleArray(n) { 0.0 }

        // 간단 스코어: avgRms + k*onsetCount
        for (i in 0 until n - winFrames) {
            var eSum = 0.0
            var oSum = 0.0
            for (j in i until i + winFrames) {
                eSum += env.rms[j].toDouble()
                oSum += env.novelty[j].toDouble()
            }
            val eAvg = eSum / winFrames
            val oAvg = oSum / winFrames
            scores[i] = eAvg + 0.8 * oAvg
        }

        // 상위 25% threshold
        val sorted = scores.copyOf().sorted()
        val th = sorted[(sorted.size * 0.75).toInt().coerceIn(0, sorted.lastIndex)]

        for (i in 0 until n - winFrames) {
            if (scores[i] >= th) {
                // 윈도우 영역을 climax로
                val end = (i + winFrames).coerceAtMost(n - 1)
                for (j in i..end) mask[j] = true
            }
        }

        return mask
    }

    // ─────────────────────────────────────────────────────────────
    // Time points from beat grid
    // ─────────────────────────────────────────────────────────────

    /**
     * 기본: 8분(subBeat=beat/2)
     * climax: 16분(beat/4) 일부 섞기
     */
    private fun buildTimePoints(grid: BeatGrid, isClimaxFrames: BooleanArray): LongArray {
        val beat = grid.beatMs
        val half = (beat / 2).coerceAtLeast(100L)
        val quarter = (beat / 4).coerceAtLeast(60L)

        val points = mutableListOf<Long>()
        for (t in grid.beatTimesMs) {
            points.add(t)
            points.add(t + half)

            // 해당 beat 시점이 climax면 16분 일부 추가
            if (isClimaxAtMs(isClimaxFrames, t, hopMs = 50L)) {
                points.add(t + quarter)
                points.add(t + quarter * 3)
            }
        }

        // 정렬 + 중복 제거 + 음수 제거
        return points
            .filter { it >= 0L }
            .distinct()
            .sorted()
            .toLongArray()
    }

    private fun isClimaxAtMs(mask: BooleanArray, tMs: Long, hopMs: Long): Boolean {
        val idx = (tMs / hopMs).toInt().coerceIn(0, mask.lastIndex)
        return mask[idx]
    }

    // ─────────────────────────────────────────────────────────────
    // Frames: beat-aligned effect placement
    // ─────────────────────────────────────────────────────────────

    private fun buildFrames(
        timePoints: LongArray,
        grid: BeatGrid,
        isClimaxFrames: BooleanArray,
        palette: ThemePalette
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>(timePoints.size)

        val beatMs = grid.beatMs
        val bg = palette.black

        var lastStrobeMs = Long.MIN_VALUE
        var colorIndex = 0

        for (t in timePoints) {
            val beatIndex = if (beatMs > 0) ((t - grid.beatTimesMs.first()) / beatMs).toInt() else 0
            val isDownBeat = (beatIndex % 4 == 0) // 4박 기준 bar start

            val inClimax = isClimaxAtMs(isClimaxFrames, t, hopMs = 50L)

            // 색은 리듬 단위로만 교대
            val blinkColor = pickPaletteColor(palette, beatIndex, colorIndex).also { colorIndex++ }

            val payload = if (inClimax && isDownBeat && (t - lastStrobeMs) >= MIN_STROBE_GAP_MS) {
                lastStrobeMs = t
                // ✅ STROBE는 확실히 보이게 WHITE 추천
                LSEffectPayload.Effects.strobe(
                    period = strobePeriod(grid.bpm),
                    color = palette.white,
                    backgroundColor = bg
                ).toByteArray()
            } else {
                // ✅ BLINK: subBeat마다
                LSEffectPayload.Effects.blink(
                    period = blinkPeriod(grid.bpm, inClimax),
                    color = blinkColor,
                    backgroundColor = bg
                ).toByteArray()
            }

            frames.add(t to payload)
        }

        return frames
    }

    private fun pickPaletteColor(p: ThemePalette, beatIndex: Int, stepIndex: Int): LSColor {
        // 기본: C1 <-> C2 교대
        // bar start(다운비트)에는 C3로 포인트
        if (beatIndex % 4 == 0) return p.c3
        return if (stepIndex % 2 == 0) p.c1 else p.c2
    }

    private fun blinkPeriod(bpm: Int, climax: Boolean): Int {
        // bpm 빠를수록 더 빠르게(작은 값)
        val base = when {
            bpm < 95 -> 12
            bpm < 115 -> 10
            bpm < 135 -> 9
            bpm < 155 -> 8
            else -> 7
        }
        return (if (climax) base - 2 else base).coerceIn(4, 14)
    }

    private fun strobePeriod(bpm: Int): Int {
        return when {
            bpm < 110 -> 5
            bpm < 140 -> 4
            else -> 3
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Palette (3~5 colors + white/black always included)
    // ─────────────────────────────────────────────────────────────

    private fun buildPalette(musicId: Int, paletteSize: Int): ThemePalette {
        val baseHue = ((musicId * 53) % 360).toFloat()

        val c1 = hsvToRgb(baseHue, 0.85f, 0.95f)
        val c2 = hsvToRgb(wrap360(baseHue + 18f), 0.60f, 1.00f)
        val c3 = hsvToRgb(wrap360(baseHue - 18f), 0.85f, 0.80f)
        val c4 = if (paletteSize >= 4) hsvToRgb(wrap360(baseHue + 30f), 0.75f, 0.90f) else null
        val whiteTint = hsvToRgb(baseHue, 0.15f, 1.00f)

        return ThemePalette(
            black = Colors.BLACK,
            white = Colors.WHITE,
            whiteTint = whiteTint,
            c1 = c1,
            c2 = c2,
            c3 = c3,
            c4 = c4
        )
    }

    private fun wrap360(h: Float): Float = (h % 360 + 360) % 360

    private fun hsvToRgb(h: Float, s: Float, v: Float): LSColor {
        val hh = wrap360(h)
        val c = v * s
        val x = c * (1 - abs((hh / 60f) % 2 - 1))
        val m = v - c

        val (r1, g1, b1) = when {
            hh < 60f  -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else      -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return LSColor(r, g, b)
    }

    // ─────────────────────────────────────────────────────────────
    // Envelope decode: RMS + novelty
    // ─────────────────────────────────────────────────────────────

    private fun decodeEnvelope(path: String, windowMs: Int = 50): Envelope {
        val rms = decodeRmsSeries(path, windowMs)
        if (rms.isEmpty()) return Envelope(windowMs.toLong(), FloatArray(0), FloatArray(0))

        val rmsArr = rms.toFloatArray()
        val noveltyArr = FloatArray(rmsArr.size)
        for (i in 1 until rmsArr.size) {
            val d = rmsArr[i] - rmsArr[i - 1]
            noveltyArr[i] = max(0f, d)
        }

        // smoothing (짧은 moving average)
        smoothInPlace(noveltyArr, win = 3)

        // normalize 0..1
        normalize01InPlace(noveltyArr)

        return Envelope(hopMs = windowMs.toLong(), rms = rmsArr, novelty = noveltyArr)
    }

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.size < win + 2) return
        val copy = x.copyOf()
        for (i in x.indices) {
            var s = 0f
            var c = 0
            val a = (i - win).coerceAtLeast(0)
            val b = (i + win).coerceAtMost(x.lastIndex)
            for (j in a..b) {
                s += copy[j]
                c++
            }
            x[i] = s / max(1, c)
        }
    }

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    /**
     * RMS 시계열 (windowMs 간격으로 0..1)
     */
    private fun decodeRmsSeries(path: String, windowMs: Int): List<Float> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return emptyList()
            extractor.selectTrack(audioTrack)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val windowSamplesTarget = max(1, sampleRate * windowMs / 1000)

            val rmsList = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            var windowEnergy = 0.0
            var windowSamples = 0

            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                            continue
                        }

                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer == null) {
                            codec.releaseOutputBuffer(outIndex, false)
                            continue
                        }

                        val bytes = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outBuffer.get(bytes)

                        // PCM 16bit LE 가정
                        var i = 0
                        while (i + 1 < bytes.size) {
                            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                            val s = sample / 32768.0
                            windowEnergy += s * s
                            windowSamples++

                            if (windowSamples >= windowSamplesTarget) {
                                val rms = sqrt(windowEnergy / max(1, windowSamples)).toFloat().coerceIn(0f, 1f)
                                rmsList.add(rms)
                                windowEnergy = 0.0
                                windowSamples = 0
                            }
                            i += 2
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            return rmsList

        } catch (t: Throwable) {
            runCatching { extractor.release() }
            Log.e(TAG, "decodeRmsSeries failed: ${t.message}")
            return emptyList()
        }
    }

    private fun fallback(musicId: Int, durationMs: Long = 180_000L): List<Pair<Long, ByteArray>> {
        val palette = buildPalette(musicId, 3)
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        var t = 0L
        while (t < durationMs) {
            frames.add(t to LSEffectPayload.Effects.breath(70, palette.c1, palette.black).toByteArray())
            t += 500L
        }
        return frames
    }
}