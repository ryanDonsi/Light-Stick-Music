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

    private enum class EnvMode { LOW, MID, FULL }

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

        val lowEnv  = decodeEnvelopeInternal(musicPath, HOP_MS.toInt(), EnvMode.LOW)
        val midEnv  = decodeEnvelopeInternal(musicPath, HOP_MS.toInt(), EnvMode.MID)
        val fullEnv = decodeEnvelopeInternal(musicPath, HOP_MS.toInt(), EnvMode.FULL)

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "v6 env empty -> return empty")
            return emptyList()
        }

        val durationMs = fullEnv.size.toLong() * HOP_MS

        // BeatDetectorRouter version=3 으로 globalBeatMs 계산
        val beatInfo     = BeatDetectorRouter.detect(3, lowEnv, midEnv, fullEnv, HOP_MS, 290L, 1200L)
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

    // -------------------------------------------------------------------------
    // PCM 디코딩 / envelope 추출
    // -------------------------------------------------------------------------

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
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) { extractor.release(); return emptyList() }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: run { extractor.release(); return emptyList() }

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
            Log.e(TAG, "v6 decodeEnvelope fail mode=$mode: ${t.message}")
            try { codec?.stop() }    catch (_: Throwable) {}
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
            var sum = 0f; var count = 0
            for (c in 0 until channels) {
                val idx = i + c * 2
                if (idx + 1 < bytes.size) {
                    val lo  = bytes[idx].toInt() and 0xFF
                    val hi  = bytes[idx + 1].toInt()
                    val raw = (hi shl 8) or lo
                    sum += (if (raw > 32767) raw - 65536 else raw) / 32768f
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
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src
        val out  = ArrayList<Float>(src.size)
        val half = window / 2
        for (i in src.indices) {
            var sum = 0f; var count = 0
            for (j in max(0, i - half)..min(src.lastIndex, i + half)) { sum += src[j]; count++ }
            out += if (count == 0) 0f else sum / count.toFloat()
        }
        return out
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
