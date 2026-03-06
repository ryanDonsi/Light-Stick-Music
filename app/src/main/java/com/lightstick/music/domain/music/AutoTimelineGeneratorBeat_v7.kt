package com.lightstick.music.domain.music

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

class AutoTimelineGeneratorBeat_v7 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION = 7
        private const val HOP_MS = 50L

        private const val MIN_BEAT_MS = 350L
        private const val MAX_BEAT_MS = 900L

        private const val COLOR_HOLD_MS = 2_000L
        private const val TOTAL_UNITS = 10L
        private const val FG_UNITS = 3L
        private const val ON_TRANSIT = 2
    }

    private enum class EnvMode {
        LOW, MID, FULL
    }

    private enum class FgMode {
        ON_PULSE,
        BLNK,
        STROBE
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

    fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int = 4
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "generate() start file=$musicPath musicId=$musicId paletteSize=$paletteSize")

        val pSize = paletteSize.coerceIn(3, 5)
        val palette = buildPalette(musicId, pSize)

        val lowEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.LOW)
        val midEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.MID)
        val fullEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.FULL)

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "env empty -> return empty")
            return emptyList()
        }

        val detect = KPopBeatDetectorV7.detect(
            lowEnv = lowEnv,
            midEnv = midEnv,
            fullEnv = fullEnv,
            params = KPopBeatDetectorV7.Params(
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = MAX_BEAT_MS,
                minPeakDistanceMs = 180L,
                onsetSmoothWindow = 3,
                segmentMs = 20_000L,
                peakThresholdK = 0.55f,
                minPeakAbs = 0.08f,
                snapToleranceMs = 120L,
                chainToleranceMs = 140L,
                minChainCount = 3
            )
        )

        val beatTimes = detect.beatTimesMs
        if (beatTimes.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return emptyList()
        }

        val beatIntervalMs = detect.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val unitMs = (beatIntervalMs / TOTAL_UNITS).coerceAtLeast(1L)
        val fgMs = unitMs * FG_UNITS

        val frames = ArrayList<Pair<Long, ByteArray>>(beatTimes.size * 2)

        for (t in beatTimes) {
            val seg = (t / COLOR_HOLD_MS).toInt()
            val rnd = Random(musicId * 1_000_003 + seg * 97)

            val fg = pickFgColor(palette, pSize, rnd)
            val bg = pickBgColor(palette, pSize, rnd)
            val fgMode = pickFgMode(rnd)

            val fgPayload = buildFgPayload(
                mode = fgMode,
                fg = fg,
                bg = bg,
                unitMs = unitMs
            )
            val bgPayload = LSEffectPayload.Effects.on(
                color = bg,
                transit = ON_TRANSIT
            ).toByteArray()

            frames += t to fgPayload
            frames += (t + fgMs) to bgPayload
        }

        Log.d(
            TAG,
            "frames(final)=${frames.size} beats=${beatTimes.size} beatIntervalMs=$beatIntervalMs " +
                    "unitMs=$unitMs fgMs=$fgMs source=${detect.source}"
        )

        return frames.sortedBy { it.first }
    }

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        val rnd = Random(seed)

        val c1 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c2 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c3 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c4 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c5 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)

        return Palette(
            c1 = c1,
            c2 = c2,
            c3 = c3,
            c4 = c4,
            c5 = c5,
            white = LSColor(255, 255, 255),
            black = LSColor(0, 0, 0),
            size = paletteSize
        )
    }

    private fun pickBgColor(palette: Palette, paletteSize: Int, rnd: Random): LSColor {
        val list = when {
            paletteSize >= 4 -> listOf(palette.c4, palette.black)
            else -> listOf(palette.black)
        }
        return list[rnd.nextInt(list.size)]
    }

    private fun pickFgColor(palette: Palette, paletteSize: Int, rnd: Random): LSColor {
        val list = when {
            paletteSize >= 5 -> listOf(palette.c1, palette.c2, palette.c3, palette.c5, palette.white)
            else -> listOf(palette.c1, palette.c2, palette.c3, palette.white)
        }
        return list[rnd.nextInt(list.size)]
    }

    private fun pickFgMode(rnd: Random): FgMode {
        val roll = rnd.nextInt(100)
        return when {
            roll < 45 -> FgMode.ON_PULSE
            roll < 80 -> FgMode.BLNK
            else -> FgMode.STROBE
        }
    }

    private fun buildFgPayload(
        mode: FgMode,
        fg: LSColor,
        bg: LSColor,
        unitMs: Long
    ): ByteArray {
        return when (mode) {
            FgMode.ON_PULSE -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgMode.BLNK -> {
                LSEffectPayload.Effects.blink(
                    period = unitMs.toInt().coerceAtLeast(1),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgMode.STROBE -> {
                LSEffectPayload.Effects.strobe(
                    period = max(1, (unitMs / 2L).toInt()),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }
        }
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

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // no-op
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            normalizeEnvelope(out)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeEnvelopeInternal fail mode=$mode path=$musicPath: ${t.message}")
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
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

    fun getVersion(): Int = VERSION
}