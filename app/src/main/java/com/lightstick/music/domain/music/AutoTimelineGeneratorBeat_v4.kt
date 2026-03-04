package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v4: Beat Grid + Pattern Engine (추천)
 *
 * 핵심:
 * - "비트에 맞춘 패턴 시퀀스"를 구간별로 반복 실행
 * - 이펙트가 골고루 섞이게(BREATH / ON / BLINK / STROBE / OFF)
 * - 리듬감 유지: event는 beat/subBeat/grid에만 정렬(quantize)
 */
class AutoTimelineGeneratorBeat_v4 {

    companion object {
        private const val TAG = "AutoTLBeat_v4"

        private const val BPM_MIN = 85
        private const val BPM_MAX = 175

        private const val SNAP_MS = 60L
        private const val MIN_STROBE_GAP_MS = 700L
        private const val SECTION_WIN_MS = 8000L

        private const val INTRO_MS = 8000L
        private const val BREAK_DROP_TH = 0.22f
        private const val BREAK_COOLDOWN_MS = 2000L
    }

    // 팔레트: black/white 포함 + 3~5색
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

    enum class Section { CALM, GROOVE, BUILD, CLIMAX }

    // 패턴 엔진용 “슬롯 타입”
    enum class SlotType { BEAT, HALF, QUARTER1, QUARTER3 }

    fun generate(musicPath: String, musicId: Int, paletteSize: Int = 4): List<Pair<Long, ByteArray>> {
        val env = decodeEnvelope(musicPath, windowMs = 50)
        if (env.rms.isEmpty()) return fallback(musicId)

        val grid = estimateBeatGrid(env)
        val palette = buildPalette(musicId, paletteSize)
        val climaxMask = detectClimaxMask(env)

        val timePoints = buildTimePoints(grid, climaxMask, env.hopMs) // 8분 + climax 16분 일부

        return buildFrames(timePoints, grid, climaxMask, env, palette)
    }

    // ─────────────────────────────────────────────
    // Beat Grid
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
        for (i in beats.indices) {
            snapped[i] = snapToPeak(env, beats[i], SNAP_MS)
        }

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
    // Climax mask (energy + novelty density)
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

        val ratio = mask.count { it }.toFloat() / mask.size.toFloat().coerceAtLeast(1f)
        Log.d(TAG, "CLIMAX ratio=${"%.1f".format(ratio * 100f)}%")
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
                // 16분 일부
                points.add(t + quarter)
                points.add(t + quarter * 3)
            }
        }

        return points.filter { it >= 0L }.distinct().sorted().toLongArray()
    }

    // ─────────────────────────────────────────────
    // Pattern Engine Frames
    // ─────────────────────────────────────────────

    private fun buildFrames(
        timePoints: LongArray,
        grid: BeatGrid,
        climaxMask: BooleanArray,
        env: Envelope,
        palette: ThemePalette
    ): List<Pair<Long, ByteArray>> {

        val frames = ArrayList<Pair<Long, ByteArray>>(timePoints.size)

        val beatMs = grid.beatMs
        val firstBeat = grid.beatTimesMs.firstOrNull() ?: 0L
        val bg = palette.black
        val hopMs = env.hopMs

        // 에너지 임계값 (곡별)
        val eSorted = env.rms.sorted()
        val eLow = eSorted[(eSorted.size * 0.30).toInt().coerceIn(0, eSorted.lastIndex)]
        val eHigh = eSorted[(eSorted.size * 0.70).toInt().coerceIn(0, eSorted.lastIndex)]

        fun energyAt(tMs: Long): Float {
            val idx = (tMs / hopMs).toInt().coerceIn(0, env.rms.lastIndex)
            return env.rms[idx]
        }

        // intro 조건부 breath
        val introUseBreath = run {
            val end = (INTRO_MS / hopMs).toInt().coerceIn(0, env.rms.lastIndex)
            var s = 0.0
            var c = 0
            for (i in 0..end) {
                s += env.rms[i].toDouble()
                c++
            }
            val avg = (s / c.coerceAtLeast(1)).toFloat()
            avg < eLow
        }

        // BREAK 감지(beat 단위)
        var prevBeatEnergy = energyAt(timePoints.firstOrNull() ?: 0L)
        var lastOffMs = Long.MIN_VALUE

        // STROBE guard
        var lastStrobeMs = Long.MIN_VALUE

        // 로그/디버그
        var strobeCount = 0
        var breathCount = 0
        var blinkCount = 0
        var onCount = 0
        var offCount = 0
        var climaxPointCount = 0

        // 팔레트 인덱스(리듬에 맞춰 변화)
        var colorStep = 0

        // BUILD 판정용(간단): climax 직전 8초는 build로 처리(리듬 패턴 다르게)
        fun isBuild(tMs: Long): Boolean {
            if (isClimaxAtMs(climaxMask, tMs, hopMs)) return false
            // climax로 들어가기 직전 구간을 build로(대략)
            val ahead = tMs + 4000L
            return isClimaxAtMs(climaxMask, ahead, hopMs) && !isClimaxAtMs(climaxMask, tMs, hopMs)
        }

        for (t in timePoints) {
            val e = energyAt(t)
            val beatIndex = if (beatMs > 0) ((t - firstBeat) / beatMs).toInt() else 0
            val posInBeat = if (beatMs > 0) ((t - firstBeat) % beatMs) else 0L

            val isBeat = (beatMs > 0) && (posInBeat == 0L)
            val halfBeat = (beatMs / 2).coerceAtLeast(1L)
            val quarter = (beatMs / 4).coerceAtLeast(1L)

            val isHalf = (beatMs > 0) && (abs(posInBeat - halfBeat) <= (hopMs * 2))
            val isQ1 = (beatMs > 0) && (abs(posInBeat - quarter) <= (hopMs * 2))
            val isQ3 = (beatMs > 0) && (abs(posInBeat - quarter * 3) <= (hopMs * 2))

            val slotType = when {
                isBeat -> SlotType.BEAT
                isHalf -> SlotType.HALF
                isQ1 -> SlotType.QUARTER1
                isQ3 -> SlotType.QUARTER3
                else -> SlotType.HALF // fallback
            }

            val inClimax = isClimaxAtMs(climaxMask, t, hopMs)
            if (inClimax) climaxPointCount++

            // Section 결정(단순하지만 비교적 안정적)
            val section: Section = when {
                t < INTRO_MS && introUseBreath && e < eLow -> Section.CALM
                inClimax || e > eHigh -> Section.CLIMAX
                isBuild(t) -> Section.BUILD
                e < eLow -> Section.CALM
                else -> Section.GROOVE
            }

            // BREAK(급락) — beat에서만 체크
            if (isBeat) {
                val drop = prevBeatEnergy - e
                prevBeatEnergy = e
                if (drop > BREAK_DROP_TH && (t - lastOffMs) > BREAK_COOLDOWN_MS) {
                    lastOffMs = t
                    frames.add(t to LSEffectPayload.Effects.off().toByteArray())
                    offCount++
                    continue
                }
            }

            // 색 선택: 리듬 단위로만 교대
            val baseColor = when (section) {
                Section.CALM -> if (beatIndex % 4 == 0) palette.c3 else palette.whiteTint
                Section.BUILD -> if (colorStep % 2 == 0) palette.c2 else palette.c1
                Section.CLIMAX -> if (colorStep % 2 == 0) palette.c1 else palette.c2
                Section.GROOVE -> if (colorStep % 2 == 0) palette.c1 else palette.c2
            }
            colorStep++

            // ─────────────────────────────────────────
            // v4 패턴: 섹션별 “시퀀스”로 이펙트 분배
            // ─────────────────────────────────────────
            val isDownBeat = isBeat && (beatIndex % 4 == 0)

            val payload: ByteArray = when (section) {

                Section.CALM -> {
                    // CALM 패턴: BEAT에서만 breath, subBeat에서는 on으로 살짝 리듬감
                    when (slotType) {
                        SlotType.BEAT -> {
                            breathCount++
                            LSEffectPayload.Effects.breath(
                                period = breathPeriod(e),
                                color = baseColor,
                                backgroundColor = bg
                            ).toByteArray()
                        }
                        else -> {
                            // 너무 과하지 않게, 부드러운 ON
                            onCount++
                            LSEffectPayload.Effects.on(
                                color = baseColor,
                                transit = onTransit(e).coerceIn(30, 60)
                            ).toByteArray()
                        }
                    }
                }

                Section.GROOVE -> {
                    // GROOVE 패턴(4박 반복):
                    // BEAT: BLINK
                    // HALF: ON
                    // (16분 포인트는 ON으로만 처리)
                    when (slotType) {
                        SlotType.BEAT -> {
                            blinkCount++
                            LSEffectPayload.Effects.blink(
                                period = blinkPeriod(grid.bpm, climax = false).coerceIn(10, 16),
                                color = baseColor,
                                backgroundColor = bg
                            ).toByteArray()
                        }
                        else -> {
                            onCount++
                            LSEffectPayload.Effects.on(
                                color = baseColor,
                                transit = onTransit(e).coerceIn(15, 35)
                            ).toByteArray()
                        }
                    }
                }

                Section.BUILD -> {
                    // BUILD 패턴: 점점 긴장감 (ON 빠르게 + 가끔 blink)
                    // - downbeat에서는 ON을 더 빠르게
                    when (slotType) {
                        SlotType.BEAT -> {
                            onCount++
                            LSEffectPayload.Effects.on(
                                color = baseColor,
                                transit = if (isDownBeat) 15 else 25
                            ).toByteArray()
                        }
                        SlotType.HALF -> {
                            // build에서는 half에서 blink를 섞어 상승감
                            blinkCount++
                            LSEffectPayload.Effects.blink(
                                period = (blinkPeriod(grid.bpm, climax = true) + 2).coerceIn(6, 12),
                                color = baseColor,
                                backgroundColor = bg
                            ).toByteArray()
                        }
                        else -> {
                            onCount++
                            LSEffectPayload.Effects.on(
                                color = baseColor,
                                transit = 25
                            ).toByteArray()
                        }
                    }
                }

                Section.CLIMAX -> {
                    // CLIMAX 패턴(가장 중요):
                    // - downbeat(4박 시작) STROBE 확정
                    // - BEAT: BLINK(빠르게)
                    // - HALF/16th: ON(빠른 transit)으로 박을 타게
                    if (isDownBeat && (t - lastStrobeMs) >= MIN_STROBE_GAP_MS) {
                        lastStrobeMs = t
                        strobeCount++
                        LSEffectPayload.Effects.strobe(
                            period = strobePeriod(grid.bpm),
                            color = palette.white,
                            backgroundColor = bg
                        ).toByteArray()
                    } else {
                        when (slotType) {
                            SlotType.BEAT -> {
                                blinkCount++
                                LSEffectPayload.Effects.blink(
                                    period = blinkPeriod(grid.bpm, climax = true).coerceIn(5, 10),
                                    color = baseColor,
                                    backgroundColor = bg
                                ).toByteArray()
                            }
                            else -> {
                                onCount++
                                LSEffectPayload.Effects.on(
                                    color = baseColor,
                                    transit = onTransit(e).coerceIn(10, 25)
                                ).toByteArray()
                            }
                        }
                    }
                }
            }

            frames.add(t to payload)
        }

        val climaxRatio = if (climaxMask.isNotEmpty()) {
            climaxMask.count { it }.toFloat() / climaxMask.size.toFloat().coerceAtLeast(1f)
        } else 0f

        Log.d(TAG, "points=${timePoints.size} BPM=${grid.bpm} beatMs=${grid.beatMs}")
        Log.d(TAG, "CLIMAX ratio=${"%.1f".format(climaxRatio * 100f)}%  climaxPoints=$climaxPointCount")
        Log.d(TAG, "mix: breath=$breathCount blink=$blinkCount on=$onCount strobe=$strobeCount off=$offCount")

        return frames
    }

    // ─────────────────────────────────────────────
    // Period / Transit
    // ─────────────────────────────────────────────

    private fun breathPeriod(e: Float): Int =
        (80 - 30 * e).toInt().coerceIn(45, 85)

    private fun onTransit(e: Float): Int =
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