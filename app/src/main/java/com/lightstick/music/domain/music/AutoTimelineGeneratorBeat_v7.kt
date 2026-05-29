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

/**
 * AutoTimelineGeneratorBeat_v7 — 비트 엔진 검증 모드
 *
 * BeatDetectorV11 → SectionDetectorV(SECTION_DETECTOR_VERSION) 순서로 실행.
 * buildTimeline: 감지된 모든 비트 위치에서 20% ON / 80% OFF 처리.
 * 섹션별 색상 변화로 구간 경계를 시각적으로 확인할 수 있다.
 */
class AutoTimelineGeneratorBeat_v7 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION     = 9   // 파일 내부 버전 (AutoTimelineConfig.VERSION 과 별개)
        private const val HOP_MS      = 50L
        private const val MIN_BEAT_MS = 250L
        private const val MAX_BEAT_MS = 900L

        private const val COLOR_HOLD_MS = 5_000L
    }

    private enum class EnvMode { LOW, MID, FULL }

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

    private data class HoldColors(val fg: LSColor, val bg: LSColor)

    // ──────────────────────────────────────────────────────────────
    // generate
    // ──────────────────────────────────────────────────────────────

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "v7 generate() start musicId=$musicId paletteSize=$paletteSize")

        val pSize   = paletteSize.coerceIn(3, 5)
        val palette = buildPalette(musicId, pSize)

        val lowEnv  = decodeEnvelopeInternal(musicPath, HOP_MS.toInt(), EnvMode.LOW)
        val midEnv  = decodeEnvelopeInternal(musicPath, HOP_MS.toInt(), EnvMode.MID)
        val fullEnv = decodeEnvelopeInternal(musicPath, HOP_MS.toInt(), EnvMode.FULL)

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "v7 env empty -> return empty")
            return emptyList()
        }

        val durationMs = fullEnv.size.toLong() * HOP_MS

        // ① BeatDetectorV11 — 전곡 비트 감지
        val v11Result = BeatDetectorV11.detect(
            lowEnv = lowEnv,
            midEnv = midEnv,
            fullEnv = fullEnv,
            params = BeatDetectorV11.Params(
                hopMs             = HOP_MS,
                minBeatMs         = 290L,
                maxBeatMs         = 1200L,
                minPeakDistanceMs = 140L,
                onsetSmoothWindow = 5,
                peakThresholdK    = 0.55f,
                minPeakAbs        = 0.08f,
                snapToleranceMs   = 80L,
                chainToleranceMs  = 120L,
                minChainCount     = 3,
                continuityBonus   = 0.08f
            )
        )

        val globalBeatMs = v11Result.beatMs
            .let { if (it > 900L) it / 2L else it }
            .coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        Log.d(TAG, "v7 BeatDetectorV11 beatMs=$globalBeatMs beats=${v11Result.beats.size}")

        // ② SectionDetector — 섹션 분할 + 비트 배분
        val sectionDetector: SectionDetector = when (AutoTimelineConfig.SECTION_DETECTOR_VERSION) {
            1    -> SectionDetectorV1()
            else -> throw IllegalArgumentException(
                "Unsupported SECTION_DETECTOR_VERSION: ${AutoTimelineConfig.SECTION_DETECTOR_VERSION}"
            )
        }

        val sections = sectionDetector.detect(
            lowEnv     = lowEnv,
            midEnv     = midEnv,
            fullEnv    = fullEnv,
            beats      = v11Result.beats,
            beatMs     = globalBeatMs,
            durationMs = durationMs,
            hopMs      = HOP_MS
        )

        Log.d(TAG, "v7 sections=${sections.size}")

        val frames = buildTimeline(sections, palette, musicId)
        Log.d(TAG, "v7 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — beat validation mode: 20% ON / 80% OFF
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        sections: List<SectionDetector.Section>,
        palette: Palette,
        musicId: Int
    ): List<Pair<Long, ByteArray>> {
        val frames         = ArrayList<Pair<Long, ByteArray>>()
        val usedTimestamps = HashSet<Long>()

        for ((idx, section) in sections.withIndex()) {
            val hold        = colorsForSection(musicId, palette, section.startMs, section.type)
            val onDurationMs = (section.beatMs * 20L / 100L).coerceAtLeast(1L)

            for (beat in section.beatTimesMs) {
                if (beat < section.startMs || beat >= section.endMs) continue

                if (usedTimestamps.add(beat)) {
                    frames += beat to LSEffectPayload.Effects.on(
                        color   = hold.fg,
                        transit = 0
                    ).toByteArray()
                }

                val offT = beat + onDurationMs
                if (offT < section.endMs && usedTimestamps.add(offT)) {
                    frames += offT to LSEffectPayload.Effects.off().toByteArray()
                }
            }

            Log.d(TAG, "v7 timeline section[$idx] ${section.startMs}~${section.endMs} " +
                "type=${section.type} beats=${section.beatTimesMs.size} " +
                "beatMs=${section.beatMs} onDuration=${onDurationMs}ms")
        }

        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Palette / colors
    // ──────────────────────────────────────────────────────────────

    private fun colorsForSection(
        musicId: Int,
        palette: Palette,
        tMs: Long,
        sectionType: SectionDetector.SectionType
    ): HoldColors {
        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + seg * 97 + sectionType.ordinal * 37)

        val fg = when (sectionType) {
            SectionDetector.SectionType.CHORUS -> listOf(palette.white, palette.c1, palette.c2)[rnd.nextInt(3)]
            SectionDetector.SectionType.BRIDGE -> listOf(palette.white, palette.c3)[rnd.nextInt(2)]
            SectionDetector.SectionType.END    -> palette.black
            else                               -> listOf(palette.c1, palette.c2, palette.c3, palette.white)[rnd.nextInt(4)]
        }

        val bg = when (sectionType) {
            SectionDetector.SectionType.CHORUS -> listOf(palette.c4, palette.black)[rnd.nextInt(2)]
            else                               -> palette.black
        }

        return HoldColors(fg = fg, bg = bg)
    }

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        val rnd = Random(seed)
        return Palette(
            c1    = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f),
            c2    = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f),
            c3    = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f),
            c4    = hsvToColor(rnd.nextFloat() * 360f, 0.75f, 0.9f),
            c5    = hsvToColor(rnd.nextFloat() * 360f, 0.70f, 0.95f),
            white = LSColor(255, 255, 255),
            black = LSColor(0, 0, 0),
            size  = paletteSize
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

    // ──────────────────────────────────────────────────────────────
    // Audio decode / envelope
    // ──────────────────────────────────────────────────────────────

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
                val f    = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format     = f
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

            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = max(1, sampleRate * hopMs / 1000)

            val out        = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmWindow  = ArrayList<Float>(hopSamples)

            var sawInputEOS  = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize  = extractor.readSampleData(inputBuffer!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
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

                            val chunk    = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            val mono     = pcm16ToMonoFloat(chunk, channelCount)
                            val filtered = when (mode) {
                                EnvMode.FULL -> mono
                                EnvMode.LOW  -> lowBandProxy(mono)
                                EnvMode.MID  -> midBandProxy(mono)
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
            Log.e(TAG, "decodeEnvelopeInternal fail mode=$mode: ${t.message}")
            try { codec?.stop()    } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            emptyList()
        }
    }

    private fun pcm16ToMonoFloat(bytes: ByteArray, channels: Int): List<Float> {
        if (bytes.isEmpty()) return emptyList()
        val out = ArrayList<Float>(bytes.size / 2 / max(1, channels))
        var i   = 0
        while (i + 1 < bytes.size) {
            var sum = 0f; var count = 0
            for (c in 0 until channels) {
                val idx = i + c * 2
                if (idx + 1 < bytes.size) {
                    val lo     = bytes[idx].toInt() and 0xFF
                    val hi     = bytes[idx + 1].toInt()
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
        return onePoleLowPass(src, 0.12f).map { abs(it) }
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
        for (x in src) { y += alpha * (x - y); out += y }
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
        val mx     = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src
        val out  = ArrayList<Float>(src.size)
        val half = window / 2
        for (i in src.indices) {
            var sum = 0f; var count = 0
            val s = max(0, i - half); val e = min(src.lastIndex, i + half)
            for (j in s..e) { sum += src[j]; count++ }
            out += if (count == 0) 0f else sum / count.toFloat()
        }
        return out
    }

    fun getVersion(): Int = VERSION
}
