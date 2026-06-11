package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

/**
 * AutoTimelineGeneratorBeat_v1 — BeatDetectorV1(PCM) + Brightness(fade=100) 2:8 비율
 *
 * BeatDetectorV1(PCM 기반 FFT/IIR) 고정 사용.
 * 비트마다 ON(fade=100) → beatMs×0.2 후 ON(fade=0) 으로 소등 (2:8 비율).
 */
class AutoTimelineGeneratorBeat_v1 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION     = 13
        private const val MIN_BEAT_MS = 320L
        private const val MAX_BEAT_MS = 1200L

        private const val DETECTOR_VER = 1  // BeatDetectorV1 고정
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

        // ── 1. 오디오 디코딩 (PCM) ───────────────────────────────────
        val t0Decode = System.currentTimeMillis()
        val (monoSamples, sampleRate) = decodeMonoPcm(musicPath)
        Log.d(TAG, "v1 [PERF] decode=${System.currentTimeMillis() - t0Decode}ms samples=${monoSamples.size} sr=$sampleRate")
        if (monoSamples.isEmpty()) { Log.w(TAG, "v1 pcm empty -> return empty"); return emptyList() }
        val durationMs = monoSamples.size.toLong() * 1000L / sampleRate

        // ── 2. 비트 감지 ─────────────────────────────────────────────
        val t0Beat = System.currentTimeMillis()
        val beatInfo = BeatDetectorRouter.detectPcm(DETECTOR_VER, monoSamples, sampleRate, MIN_BEAT_MS, 1200L)
        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = beatInfo.beatsPerBar
        Log.d(TAG, "v1 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms  beatMs=$globalBeatMs beats=${beatInfo.beats.size} beatsPerBar=$beatsPerBar detectorVer=$DETECTOR_VER")

        if (beatInfo.beats.isNotEmpty()) {
            val first = beatInfo.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = beatInfo.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v1 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        if (beatInfo.beats.isNotEmpty()) {
            val synth  = beatInfo.beats.count { it.confidence <= 0.20f }
            val real   = beatInfo.beats.size - synth
            val sPct   = synth * 100 / beatInfo.beats.size
            Log.d(TAG, "v1 [A] quality[$fileName]: real=$real synth=$synth(${sPct}%) total=${beatInfo.beats.size}")

            val gapTh   = globalBeatMs * 3
            val bigGaps = (1 until beatInfo.beats.size).mapNotNull { i ->
                val gap = beatInfo.beats[i].timeMs - beatInfo.beats[i - 1].timeMs
                if (gap >= gapTh) "${beatInfo.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v1 [A] gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v1 [A] gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        // ── 3. 타임라인 빌드 ──────────────────────────────────────────
        val t0Build = System.currentTimeMillis()
        val frames = buildTimeline(beatInfo.beats, globalBeatMs, beatsPerBar, durationMs)
        Log.d(TAG, "v1 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v1 [PERF] total=${System.currentTimeMillis() - t0Total}ms  file=$fileName durationMs=$durationMs")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — 1/4박자마다 ON(fade=100) → beatMs×0.2 후 ON(동일 컬러, fade=60) [2:8 비율]
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        beatsPerBar: Int,
        durationMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip = 0

        // ON(fade=100) → beatMs×0.2 후 동일 컬러 fade=60 으로 감쇄 (2:8 비율)
        val dimDelayMs = (beatMs * 0.2).toLong().coerceAtLeast(20L)

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

        Log.d(TAG, "v1 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size} dimDelayMs=$dimDelayMs")
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

    fun getVersion(): Int = VERSION
}
