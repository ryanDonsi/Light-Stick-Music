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
 * v3: Beat Grid 기반 + "비트 탄다" 체감 강화
 * - subBeat(8분)에서는 BLINK 대신 ON+transit로 리듬 표현(깜빡거림 방지)
 * - beat(4분)에는 BLINK로 패턴감 보조
 * - CLIMAX downbeat STROBE 확정 + 로그(클라이맥스/스토브 카운트)
 * - intro는 조건부 BREATH(무조건 breath 아님)
 */
class AutoTimelineGeneratorBeat_v3 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val BPM_MIN = 85
        private const val BPM_MAX = 175

        private const val SNAP_MS = 60L
        private const val MIN_STROBE_GAP_MS = 800L
        private const val SECTION_WIN_MS = 8000L
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
        val rms: FloatArray,
        val novelty: FloatArray
    ) {
        val durationMs: Long get() = rms.size.toLong() * hopMs
    }

    data class BeatGrid(
        val bpm: Int,
        val beatMs: Long,
        val beatTimesMs: LongArray
    )

    fun generate(musicPath: String, musicId: Int, paletteSize: Int = 4): List<Pair<Long, ByteArray>> {
        val env = decodeEnvelope(musicPath, windowMs = 50)
        if (env.rms.isEmpty()) return fallback(musicId)

        val grid = estimateBeatGrid(env)
        val palette = buildPalette(musicId, paletteSize)
        val isClimax = detectClimaxMask(env)
        val timePoints = buildTimePoints(grid, isClimax, env.hopMs)

        return buildFrames(timePoints, grid, isClimax, env, palette)
    }

    // ─────────────────────────────────────────────
    // Beat grid estimation
    // ─────────────────────────────────────────────

    private fun estimateBeatGrid(env: Envelope): BeatGrid {
        val bpm = estimateBpmFromNovelty(env.novelty, env.hopMs)
        val beatMs = (60_000.0 / bpm.toDouble()).toLong().coerceAtLeast(200L)

        val startMs = findFirstStrongPeakMs(env, 6000L)
        val beats = mutableListOf<Long>()
        var t = startMs
        while (t < env.durationMs) {
            beats.add(t)
            t += beatMs
        }

        val snapped = LongArray(beats.size)
        for (i in beats.indices) snapped[i] = snapToPeak(env, beats[i], SNAP_MS)

        Log.d(TAG, "BPM=$bpm beatMs=$beatMs beats=${snapped.size}")
        return BeatGrid(bpm, beatMs, snapped)
    }

    private fun estimateBpmFromNovelty(novelty: FloatArray, hopMs: Long): Int {
        val minLag = ((60_000.0 / BPM_MAX) / hopMs).toInt().coerceAtLeast(1)
        val maxLag = ((60_000.0 / BPM_MIN) / hopMs).toInt().coerceAtLeast(minLag + 1)

        var mean = 0.0
        for (v in novelty) mean += v.toDouble()
        mean /= max(1, novelty.size).toDouble()

        val x = DoubleArray(novelty.size) { novelty[it].toDouble() - mean }

        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            val n = x.size - lag
            if (n <= 10) break
            var num = 0.0
            var den1 = 0.0
            var den2 = 0.0
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
        return (60_000.0 / beatMs).toInt().coerceIn(BPM_MIN, BPM_MAX)
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

    // ─────────────────────────────────────────────
    // Climax mask
    // ─────────────────────────────────────────────

    private fun detectClimaxMask(env: Envelope): BooleanArray {
        val n = env.rms.size
        val mask = BooleanArray(n)
        val win = (SECTION_WIN_MS / env.hopMs).toInt().coerceAtLeast(10)
        if (n < win + 10) return mask

        val scores = DoubleArray(n) { 0.0 }
        for (i in 0 until n - win) {
            var eSum = 0.0
            var oSum = 0.0
            for (j in i until i + win) {
                eSum += env.rms[j].toDouble()
                oSum += env.novelty[j].toDouble()
            }
            val eAvg = eSum / win
            val oAvg = oSum / win
            scores[i] = eAvg + 0.8 * oAvg
        }

        val sorted = scores.copyOf().sorted()
        val th = sorted[(sorted.size * 0.75).toInt().coerceIn(0, sorted.lastIndex)]

        for (i in 0 until n - win) {
            if (scores[i] >= th) {
                val end = (i + win).coerceAtMost(n - 1)
                for (j in i..end) mask[j] = true
            }
        }
        return mask
    }

    private fun isClimaxAtMs(mask: BooleanArray, tMs: Long, hopMs: Long): Boolean {
        val idx = (tMs / hopMs).toInt().coerceIn(0, mask.lastIndex)
        return mask[idx]
    }

    // ─────────────────────────────────────────────
    // Time points: 8분 기본 + climax에서 16분 일부
    // ─────────────────────────────────────────────

    private fun buildTimePoints(grid: BeatGrid, mask: BooleanArray, hopMs: Long): LongArray {
        val beat = grid.beatMs
        val half = (beat / 2).coerceAtLeast(100L)
        val quarter = (beat / 4).coerceAtLeast(60L)

        val points = mutableListOf<Long>()
        for (t in grid.beatTimesMs) {
            points.add(t)
            points.add(t + half)
            if (isClimaxAtMs(mask, t, hopMs)) {
                points.add(t + quarter)
                points.add(t + quarter * 3)
            }
        }

        return points.filter { it >= 0L }.distinct().sorted().toLongArray()
    }

    // ─────────────────────────────────────────────
    // Frames: v3 최신 전략
    // ─────────────────────────────────────────────

    private fun buildFrames(
        timePoints: LongArray,
        grid: BeatGrid,
        isClimaxFrames: BooleanArray,
        env: Envelope,
        palette: ThemePalette
    ): List<Pair<Long, ByteArray>> {

        val frames = ArrayList<Pair<Long, ByteArray>>(timePoints.size)
        val beatMs = grid.beatMs
        val bg = palette.black
        val firstBeat = grid.beatTimesMs.firstOrNull() ?: 0L

        // 로그용
        var strobeCount = 0
        var climaxPointCount = 0

        // 에너지 임계값
        val eSorted = env.rms.sorted()
        val eLow  = eSorted[(eSorted.size * 0.30).toInt().coerceIn(0, eSorted.lastIndex)]
        val eHigh = eSorted[(eSorted.size * 0.70).toInt().coerceIn(0, eSorted.lastIndex)]

        fun energyAt(tMs: Long): Float {
            val idx = (tMs / env.hopMs).toInt().coerceIn(0, env.rms.lastIndex)
            return env.rms[idx]
        }

        fun inClimax(tMs: Long): Boolean = isClimaxAtMs(isClimaxFrames, tMs, hopMs = env.hopMs)

        // intro 조건부 breath
        val introMs = 8000L
        var introSum = 0.0
        var introCnt = 0
        run {
            val end = (introMs / env.hopMs).toInt().coerceIn(0, env.rms.lastIndex)
            for (i in 0..end) {
                introSum += env.rms[i].toDouble()
                introCnt++
            }
        }
        val introAvg = (introSum / introCnt.coerceAtLeast(1)).toFloat()
        val introUseBreath = introAvg < eLow

        // BREAK 감지(beat 단위)
        var prevBeatEnergy = energyAt(timePoints.firstOrNull() ?: 0L)
        var lastOffMs = Long.MIN_VALUE

        // STROBE guard
        var lastStrobeMs = Long.MIN_VALUE

        // 색 교대
        var subStep = 0

        for (t in timePoints) {
            val e = energyAt(t)
            val beatIndex = if (beatMs > 0) ((t - firstBeat) / beatMs).toInt() else 0
            val posInBeat = if (beatMs > 0) ((t - firstBeat) % beatMs) else 0L

            val isBeat = (beatMs > 0) && (posInBeat == 0L)
            val halfBeat = (beatMs / 2).coerceAtLeast(1L)
            val isHalfBeat = (beatMs > 0) && (abs(posInBeat - halfBeat) <= (env.hopMs * 2))
            val isDownBeat = isBeat && (beatIndex % 4 == 0)

            val climax = inClimax(t)
            if (climax) climaxPointCount++

            val role = when {
                t < introMs && introUseBreath && e < eLow -> "CALM"
                climax || e > eHigh -> "CLIMAX"
                e < eLow -> "CALM"
                else -> "GROOVE"
            }

            val baseColor = when (role) {
                "CALM" -> if ((beatIndex % 4) == 0) palette.c3 else palette.whiteTint
                else -> if (subStep % 2 == 0) palette.c1 else palette.c2
            }
            subStep++

            // BREAK(급락) 감지
            if (isBeat) {
                val drop = prevBeatEnergy - e
                prevBeatEnergy = e
                if (drop > 0.22f && (t - lastOffMs) > 2000L) {
                    lastOffMs = t
                    frames.add(t to LSEffectPayload.Effects.off().toByteArray())
                    continue
                }
            }

            val payload = when (role) {
                "CALM" -> {
                    LSEffectPayload.Effects.breath(
                        period = breathPeriodByEnergy(e),
                        color = baseColor,
                        backgroundColor = bg
                    ).toByteArray()
                }

                "CLIMAX" -> {
                    val canStrobe = isDownBeat && (t - lastStrobeMs) >= MIN_STROBE_GAP_MS
                    if (canStrobe) {
                        lastStrobeMs = t
                        strobeCount++
                        LSEffectPayload.Effects.strobe(
                            period = strobePeriod(grid.bpm),
                            color = palette.white,
                            backgroundColor = bg
                        ).toByteArray()
                    } else {
                        // 핵심: subBeat는 ON+transit로 박을 탐(껌뻑거림 방지)
                        LSEffectPayload.Effects.on(
                            color = baseColor,
                            transit = onTransitByEnergy(e).coerceIn(10, 25)
                        ).toByteArray()
                    }
                }

                else -> {
                    if (isBeat) {
                        LSEffectPayload.Effects.blink(
                            period = blinkPeriod(grid.bpm, climax = false).coerceIn(9, 14),
                            color = baseColor,
                            backgroundColor = bg
                        ).toByteArray()
                    } else if (isHalfBeat) {
                        LSEffectPayload.Effects.on(
                            color = baseColor,
                            transit = onTransitByEnergy(e).coerceIn(15, 35)
                        ).toByteArray()
                    } else {
                        // 16분 포인트도 ON으로 처리(산만해지지 않게)
                        LSEffectPayload.Effects.on(
                            color = baseColor,
                            transit = onTransitByEnergy(e).coerceIn(15, 35)
                        ).toByteArray()
                    }
                }
            }

            frames.add(t to payload)
        }

        val climaxRatio = if (isClimaxFrames.isNotEmpty()) {
            isClimaxFrames.count { it }.toFloat() / isClimaxFrames.size.toFloat().coerceAtLeast(1f)
        } else 0f

        Log.d(TAG, "BPM=${grid.bpm}, beatMs=${grid.beatMs}, points=${timePoints.size}")
        Log.d(TAG, "CLIMAX ratio=${"%.1f".format(climaxRatio * 100f)}%  climaxPoints=$climaxPointCount")
        Log.d(TAG, "STROBE count=$strobeCount  OFF used=${if (lastOffMs != Long.MIN_VALUE) "YES" else "NO"}")

        return frames
    }

    private fun breathPeriodByEnergy(e: Float): Int =
        (80 - 30 * e).toInt().coerceIn(45, 85)

    private fun onTransitByEnergy(e: Float): Int =
        (60 - 40 * e).toInt().coerceIn(15, 60)

    private fun blinkPeriod(bpm: Int, climax: Boolean): Int {
        val base = when {
            bpm < 95 -> 12
            bpm < 115 -> 10
            bpm < 135 -> 9
            bpm < 155 -> 8
            else -> 7
        }
        return (if (climax) base - 2 else base).coerceIn(4, 14)
    }

    private fun strobePeriod(bpm: Int): Int =
        when {
            bpm < 110 -> 5
            bpm < 140 -> 4
            else -> 3
        }

    // ─────────────────────────────────────────────
    // Palette
    // ─────────────────────────────────────────────

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

    // ─────────────────────────────────────────────
    // Envelope (RMS + novelty)
    // ─────────────────────────────────────────────

    private fun decodeEnvelope(path: String, windowMs: Int = 50): Envelope {
        val rms = decodeRmsSeries(path, windowMs)
        if (rms.isEmpty()) return Envelope(windowMs.toLong(), FloatArray(0), FloatArray(0))

        val rmsArr = rms.toFloatArray()
        val novelty = FloatArray(rmsArr.size)
        for (i in 1 until rmsArr.size) novelty[i] = max(0f, rmsArr[i] - rmsArr[i - 1])
        normalize01InPlace(novelty)

        return Envelope(windowMs.toLong(), rmsArr, novelty)
    }

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

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

                        var i = 0
                        while (i + 1 < bytes.size) {
                            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                            val s = sample / 32768.0
                            windowEnergy += s * s
                            windowSamples++

                            if (windowSamples >= windowSamplesTarget) {
                                val rmsVal = sqrt(windowEnergy / max(1, windowSamples)).toFloat().coerceIn(0f, 1f)
                                rmsList.add(rmsVal)
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