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
 * AutoTimelineGeneratorBeat_v1 — ON fade 조절 버전 백업
 *
 * v0 기반 BeatDetector 검증 전용 클래스.
 * 1/4박자마다 ON(fade=100) → 100ms 후 ON(동일 컬러, fade=60) 으로 밝기 감쇄.
 * v0(현행)은 fade 없는 단순 ON만 사용. 비교 테스트 시 GENERATOR_VERSION=7 에 연결하여 사용.
 */
class AutoTimelineGeneratorBeat_v1 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION     = 13
        private const val HOP_MS      = 50L
        private const val MIN_BEAT_MS = 320L
        private const val MAX_BEAT_MS = 1200L

        // IIR filter coefficients (V8 최적화)
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
    }



    // ──────────────────────────────────────────────────────────────
    // generate
    // ──────────────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        val t0Total  = System.currentTimeMillis()
        Log.d(TAG, "v1 [PERF] generate() start file=$fileName musicId=$musicId")

        val detectorVer = AutoTimelineConfig.BEAT_DETECTOR_VERSION

        // ── 1. 오디오 디코딩 ──────────────────────────────────────────
        val t0Decode = System.currentTimeMillis()
        val beatInfo: BeatDetectorRouter.BeatInfo
        val durationMs: Long

        if (detectorVer <= 2) {
            val (monoSamples, sampleRate) = decodeMonoPcm(musicPath)
            if (monoSamples.isEmpty()) { Log.w(TAG, "v1 pcm empty -> return empty"); return emptyList() }
            durationMs = monoSamples.size.toLong() * 1000L / sampleRate
            Log.d(TAG, "v1 [PERF] decodePcm=${System.currentTimeMillis() - t0Decode}ms samples=${monoSamples.size}")

            // ── 2. 비트 감지 (PCM) ────────────────────────────────────
            val t0Beat = System.currentTimeMillis()
            beatInfo = BeatDetectorRouter.detectPcm(detectorVer, monoSamples, sampleRate, MIN_BEAT_MS, 1200L)
            Log.d(TAG, "v1 [PERF] beatDetect(pcm)=${System.currentTimeMillis() - t0Beat}ms")
        } else {
            val (lowEnv, midEnv, fullEnv) = decodeAllEnvelopes(musicPath, HOP_MS.toInt())
            if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
                Log.w(TAG, "v1 env empty -> return empty"); return emptyList()
            }
            durationMs = fullEnv.size.toLong() * HOP_MS
            Log.d(TAG, "v1 [PERF] decode=${System.currentTimeMillis() - t0Decode}ms frames=${fullEnv.size}")

            // ── 2. 비트 감지 (envelope) ───────────────────────────────
            val t0Beat = System.currentTimeMillis()
            beatInfo = BeatDetectorRouter.detect(detectorVer, lowEnv, midEnv, fullEnv, HOP_MS, MIN_BEAT_MS, 1200L)
            Log.d(TAG, "v1 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms")
        }

        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = beatInfo.beatsPerBar
        Log.d(TAG, "v1 beatMs=$globalBeatMs beats=${beatInfo.beats.size} detectorVer=$detectorVer")

        if (beatInfo.beats.isNotEmpty()) {
            val first = beatInfo.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = beatInfo.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v1 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        if (beatInfo.beats.isNotEmpty()) {
            val synth  = beatInfo.beats.count { it.confidence <= 0.20f }
            val real   = beatInfo.beats.size - synth
            val sPct   = synth * 100 / beatInfo.beats.size
            Log.d(TAG, "v1 [A] V11_quality[$fileName]: " +
                "real=$real synth=$synth(${sPct}%) total=${beatInfo.beats.size}")

            val gapTh  = globalBeatMs * 3
            val bigGaps = (1 until beatInfo.beats.size).mapNotNull { i ->
                val gap = beatInfo.beats[i].timeMs - beatInfo.beats[i - 1].timeMs
                if (gap >= gapTh) "${beatInfo.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v1 [A] V11_gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v1 [A] V11_gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        // ── 3. 타임라인 빌드 ──────────────────────────────────────────
        val t0Build = System.currentTimeMillis()
        val frames = buildTimeline(beatInfo.beats, globalBeatMs, beatsPerBar, durationMs)
        Log.d(TAG, "v1 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v1 [PERF] total=${System.currentTimeMillis() - t0Total}ms  file=$fileName durationMs=$durationMs")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — 1/4박자마다 ON(fade=100) + 100ms 후 ON(동일 컬러, fade=60)
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        beatsPerBar: Int,
        durationMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip   = 0

        // 1/4박자마다 ON(fade=100) → 100ms 후 동일 컬러 fade=60 으로 감쇄
        val dimDelayMs = 100L
        for ((beatIndex, beat) in beats.withIndex()) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) { rangeSkip++; continue }

            val color = when (beatIndex % beatsPerBar) {
                0    -> LSColor(255, 255, 255)  // White
                1    -> LSColor(255, 0,   255)  // Purple
                2    -> LSColor(255, 255, 0)    // Yellow
                else -> LSColor(0,   255, 255)  // Cyan
            }
            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0, fade = 100).toByteArray())

            val dimT = t + dimDelayMs
            if (dimT < durationMs) {
                frames.add(dimT to LSEffectPayload.Effects.on(color = color, transit = 0, fade = 60).toByteArray())
            }
        }

        Log.d(TAG, "v1 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Audio decode
    // ──────────────────────────────────────────────────────────────

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
            if (trackIndex < 0 || format == null) { extractor.release(); return Pair(FloatArray(0), 44100) }
            extractor.selectTrack(trackIndex)
            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val stepBytes    = channelCount * 2
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start()
            val out = ArrayList<Float>(sampleRate * 30)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false; var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz  = extractor.readSampleData(buf, 0)
                        if (sz < 0) { codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sawInputEOS = true }
                        else { codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && bufferInfo.size > 0) {
                        buf.position(bufferInfo.offset); buf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size); buf.get(chunk)
                        var byteIdx = 0
                        while (byteIdx + stepBytes <= chunk.size) {
                            var monoSum = 0f
                            for (c in 0 until channelCount) {
                                val lo = chunk[byteIdx + c * 2].toInt() and 0xFF
                                val hi = chunk[byteIdx + c * 2 + 1].toInt()
                                monoSum += (hi shl 8 or lo).toShort().toFloat()
                            }
                            out.add(monoSum / channelCount / 32768f)
                            byteIdx += stepBytes
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                }
            }
            codec.stop(); codec.release(); extractor.release()
            Pair(out.toFloatArray(), sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeMonoPcm fail: ${t.message}")
            try { codec?.stop() } catch (_: Throwable) {}
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
