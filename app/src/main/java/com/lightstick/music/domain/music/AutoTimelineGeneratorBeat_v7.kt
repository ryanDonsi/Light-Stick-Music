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

/**
 * AutoTimelineGeneratorBeat_v7 — BeatDetector 검증 전용
 *
 * 섹션 분석 없음. BeatDetectorV11이 감지한 모든 비트 시각에서
 * 20% ON / 80% OFF 처리만 수행한다.
 * 5초 단위로 팔레트 색상을 바꿔 비트 연속성을 육안으로 확인한다.
 */
class AutoTimelineGeneratorBeat_v7 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION     = 13
        private const val HOP_MS      = 50L
        private const val MIN_BEAT_MS = 320L
        private const val MAX_BEAT_MS = 1200L

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

    // ──────────────────────────────────────────────────────────────
    // generate
    // ──────────────────────────────────────────────────────────────

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        Log.d(TAG, "v7 generate() start file=$fileName musicId=$musicId paletteSize=$paletteSize")

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

        Log.d(TAG, "v7 BeatDetect start file=$fileName musicId=$musicId durationMs=$durationMs")
        val v11Result = BeatDetectorV11.detect(
            lowEnv  = lowEnv,
            midEnv  = midEnv,
            fullEnv = fullEnv,
            params  = BeatDetectorV11.Params(
                hopMs             = HOP_MS,
                minBeatMs         = MIN_BEAT_MS,
                maxBeatMs         = 1200L,
                minPeakDistanceMs = 140L,
                onsetSmoothWindow = 3,     // 5→3: 좁은 스무딩으로 비트 피크 선명하게 유지
                peakThresholdK    = 0.28f, // 0.55→0.28: 임계값 완화, 약한 비트도 검출
                minPeakAbs        = 0.05f, // 0.08→0.05: 절대 임계값 완화
                snapToleranceMs   = 130L,  // 80→130: 그리드 스냅 허용범위 확대 (2frame)
                chainToleranceMs  = 150L,  // 120→150: 체인 허용 오차 확대
                minChainCount     = 3,
                continuityBonus   = 0.08f
            )
        )

        val globalBeatMs = v11Result.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        val beatsPerBar = v11Result.timeSignature.beatsPerBar
        Log.d(TAG, "v7 BeatDetectorV11 beatMs=$globalBeatMs beats=${v11Result.beats.size} " +
            "timeSig=${v11Result.timeSignature.type} beatsPerBar=$beatsPerBar")

        // Q6: 비트 타임스탬프 로그 (처음 12개 + 마지막 4개)
        if (v11Result.beats.isNotEmpty()) {
            val first = v11Result.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = v11Result.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v7 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        // ── [진단A] V11 출력 비트 품질 분석 ──────────────────────────────
        // confidence ≤ 0.20 = normalizeBeats fill 합성 비트
        // confidence  > 0.20 = 실제 ODF 피크에서 감지된 비트
        if (v11Result.beats.isNotEmpty()) {
            val synth  = v11Result.beats.count { it.confidence <= 0.20f }
            val real   = v11Result.beats.size - synth
            val sPct   = synth * 100 / v11Result.beats.size
            Log.d(TAG, "v7 [A] V11_quality[$fileName]: " +
                "real=$real synth=$synth(${sPct}%) total=${v11Result.beats.size}")

            // 3×beatMs 이상의 갭 → normalizeBeats fill 이 동작하지 않은 구간
            val gapTh  = globalBeatMs * 3
            val bigGaps = (1 until v11Result.beats.size).mapNotNull { i ->
                val gap = v11Result.beats[i].timeMs - v11Result.beats[i - 1].timeMs
                if (gap >= gapTh) "${v11Result.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v7 [A] V11_gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v7 [A] V11_gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        val frames = buildTimeline(v11Result.beats, globalBeatMs, beatsPerBar, durationMs, palette, musicId)
        Log.d(TAG, "v7 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — 20% ON / 80% OFF, 섹션 없음
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorV11.TimedBeat>,
        beatMs: Long,
        beatsPerBar: Int,
        durationMs: Long,
        palette: Palette,
        musicId: Int
    ): List<Pair<Long, ByteArray>> {
        val frames      = ArrayList<Pair<Long, ByteArray>>()
        val firstBeatMs = beats.firstOrNull()?.timeMs ?: 0L
        val barMs       = beatMs * beatsPerBar.coerceAtLeast(1)
        var rangeSkip   = 0

        // 박자(beat)마다 ON(color) 1개 — R→G→B→W 순환
        for ((beatIndex, beat) in beats.withIndex()) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) { rangeSkip++; continue }

            val color = when (beatIndex % beatsPerBar) {
                0    -> LSColor(255, 0,   0)    // Red
                1    -> LSColor(0,   255, 0)    // Green
                2    -> LSColor(0,   0,   255)  // Blue
                else -> LSColor(255, 255, 255)  // White
            }
            Log.d(TAG, "frame ON[beat${beatIndex + 1}] t=${t}ms color=$color")
            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0).toByteArray())
        }

        Log.d(TAG, "v7 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size}")
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
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }

            if (trackIndex < 0 || format == null) { extractor.release(); return emptyList() }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return emptyList() }

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
                                if (pcmWindow.size >= hopSamples) { out += rms(pcmWindow); pcmWindow.clear() }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop(); codec.release(); extractor.release()
            normalizeEnvelope(out)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeEnvelopeInternal fail mode=$mode: ${t.message}")
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
                    val lo = bytes[idx].toInt() and 0xFF
                    val hi = bytes[idx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    val signed = if (sample > 32767) sample - 65536 else sample
                    sum += signed / 32768f; count++
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
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src
        val out = ArrayList<Float>(src.size)
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
