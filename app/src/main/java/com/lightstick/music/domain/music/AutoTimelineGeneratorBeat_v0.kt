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
        private const val HOP_MS      = 20L
        private const val MIN_BEAT_MS   = 320L
        private const val MAX_BEAT_MS   = 1200L
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

        // ── 1. 오디오 디코딩 ──────────────────────────────────────────
        val t0Decode = System.currentTimeMillis()
        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION

        val beatInfo: BeatDetectorRouter.BeatInfo
        val durationMs: Long

        if (detectorVer == 1) {
            // V1: PCM 디코딩 → BeatDetectorRouter.detectPcm
            val (monoSamples, sampleRate) = decodeMonoPcm(musicPath)
            Log.d(TAG, "v0 [PERF] decode=${System.currentTimeMillis() - t0Decode}ms samples=${monoSamples.size} sr=$sampleRate")
            if (monoSamples.isEmpty()) {
                Log.w(TAG, "v0 pcm empty -> return empty")
                return emptyList()
            }
            durationMs = monoSamples.size.toLong() * 1000L / sampleRate

            // ── 2. 비트 감지 ──────────────────────────────────────────────
            val t0Beat = System.currentTimeMillis()
            beatInfo = BeatDetectorRouter.detectPcm(
                version      = detectorVer,
                monoSamples  = monoSamples,
                sampleRate   = sampleRate,
                minBeatMs    = MIN_BEAT_MS,
                maxBeatMs    = 1200L
            )
            val globalBeatMs0 = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            Log.d(TAG, "v0 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms  beatMs=$globalBeatMs0 beats=${beatInfo.beats.size} beatsPerBar=${beatInfo.beatsPerBar} detectorVer=$detectorVer")
        } else if (detectorVer == 2) {
            // V2: 스트리밍 ODF (PCM 불필요) → BeatDetectorRouter.detectFile
            Log.d(TAG, "v0 [PERF] decode=0ms (streaming)")
            val t0Beat = System.currentTimeMillis()
            beatInfo = BeatDetectorRouter.detectFile(
                musicPath = musicPath,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = 1200L
            )
            durationMs = beatInfo.beats.lastOrNull()?.timeMs?.plus(beatInfo.beatMs) ?: 0L
            val globalBeatMs0 = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            Log.d(TAG, "v0 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms  beatMs=$globalBeatMs0 beats=${beatInfo.beats.size} beatsPerBar=${beatInfo.beatsPerBar} detectorVer=$detectorVer")
        } else {
            // V3/V4/V5: 엔벨로프 디코딩 → BeatDetectorRouter.detect
            val (lowEnv, midEnv, fullEnv) = decodeAllEnvelopes(musicPath, HOP_MS.toInt())
            Log.d(TAG, "v0 [PERF] decode=${System.currentTimeMillis() - t0Decode}ms frames=${fullEnv.size}")
            if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
                Log.w(TAG, "v0 env empty -> return empty")
                return emptyList()
            }
            durationMs = fullEnv.size.toLong() * HOP_MS

            // ── 2. 비트 감지 ──────────────────────────────────────────────
            val t0Beat = System.currentTimeMillis()
            beatInfo = BeatDetectorRouter.detect(
                version   = detectorVer,
                lowEnv    = lowEnv,
                midEnv    = midEnv,
                fullEnv   = fullEnv,
                hopMs     = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = 1200L
            )
            val globalBeatMs0 = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            Log.d(TAG, "v0 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms  beatMs=$globalBeatMs0 beats=${beatInfo.beats.size} beatsPerBar=${beatInfo.beatsPerBar} detectorVer=$detectorVer")
        }
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

    // ──────────────────────────────────────────────────────────────
    // Audio decode / envelope
    // ──────────────────────────────────────────────────────────────

    /**
     * PCM 을 모노 FloatArray 로 디코딩한다. (V1/V2 전용)
     * 스테레오 이상이면 채널 평균으로 다운믹스, -1.0..1.0 정규화.
     */
    private fun decodeMonoPcm(musicPath: String): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(musicPath)
            var trackIndex = -1; var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release(); return Pair(FloatArray(0), 44100)
            }
            extractor.selectTrack(trackIndex)
            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val stepBytes    = channelCount * 2
            val maxSamples   = (sampleRate * MAX_DECODE_MS / 1000).toInt()
            // MediaFormat duration으로 pre-allocate → boxing 없이 4바이트/샘플, grow() OOM 방지
            val durationUs   = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            val allocSamples = if (durationUs > 0) (sampleRate * durationUs / 1_000_000L).toInt().coerceAtMost(maxSamples) else maxSamples
            val out          = FloatArray(allocSamples)
            var outPos       = 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo   = MediaCodec.BufferInfo()
            var sawInputEOS  = false; var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz  = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && bufferInfo.size > 0) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size); buf.get(chunk)
                        var byteIdx = 0
                        while (byteIdx + stepBytes <= chunk.size) {
                            if (outPos >= maxSamples) { sawInputEOS = true; sawOutputEOS = true; break }
                            var monoSum = 0f
                            for (c in 0 until channelCount) {
                                val lo = chunk[byteIdx + c * 2].toInt() and 0xFF
                                val hi = chunk[byteIdx + c * 2 + 1].toInt()
                                monoSum += (hi shl 8 or lo).toShort().toFloat()
                            }
                            if (outPos < out.size) out[outPos] = monoSum / channelCount / 32768f
                            outPos++
                            byteIdx += stepBytes
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                }
            }
            codec.stop(); codec.release(); extractor.release()
            Pair(if (outPos < out.size) out.copyOf(outPos) else out, sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeMonoPcm fail: ${t.message}")
            try { codec?.stop() }    catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            Pair(FloatArray(0), 44100)
        }
    }

    /** 단일 패스로 LOW / MID / FULL 엔벨로프를 동시에 추출한다. */
    private fun decodeAllEnvelopes(
        musicPath: String,
        hopMs: Int
    ): Triple<List<Float>, List<Float>, List<Float>> {
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
            if (trackIndex < 0 || format == null) { extractor.release(); return Triple(emptyList(), emptyList(), emptyList()) }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: run { extractor.release(); return Triple(emptyList(), emptyList(), emptyList()) }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = max(1, sampleRate * hopMs / 1000)

            val outLow  = ArrayList<Float>()
            val outMid  = ArrayList<Float>()
            val outFull = ArrayList<Float>()

            // IIR 상태 변수 (청크 간 유지)
            var lowZ   = 0f; var midLP1 = 0f; var midLP2 = 0f
            // 누산기 RMS (hopSamples 마다 flush)
            var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var winPos = 0
            val stepBytes = channelCount * 2

            val bufferInfo   = MediaCodec.BufferInfo()
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
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            var byteIdx = 0
                            while (byteIdx + stepBytes <= chunk.size) {
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    val lo = chunk[byteIdx + c * 2].toInt() and 0xFF
                                    val hi = chunk[byteIdx + c * 2 + 1].toInt()
                                    monoSum += (hi shl 8 or lo).toShort().toFloat()
                                }
                                val mono = monoSum / channelCount / 32768f
                                lowZ   += LOW_ALPHA     * (mono - lowZ)
                                midLP1 += MID_LP1_ALPHA * (mono - midLP1)
                                midLP2 += MID_LP2_ALPHA * (mono - midLP2)
                                val lowVal = abs(lowZ)
                                val midVal = abs(midLP1 - midLP2)
                                lowSumSq  += lowVal * lowVal
                                midSumSq  += midVal * midVal
                                fullSumSq += mono    * mono
                                winPos++
                                if (winPos >= hopSamples) {
                                    outLow  += sqrt(lowSumSq  / winPos)
                                    outMid  += sqrt(midSumSq  / winPos)
                                    outFull += sqrt(fullSumSq / winPos)
                                    lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; winPos = 0
                                }
                                byteIdx += stepBytes
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop(); codec.release(); extractor.release()
            Triple(normalizeEnvelope(outLow), normalizeEnvelope(outMid), normalizeEnvelope(outFull))
        } catch (t: Throwable) {
            Log.e(TAG, "decodeAllEnvelopes fail: ${t.message}")
            try { codec?.stop() }    catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            Triple(emptyList(), emptyList(), emptyList())
        }
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
