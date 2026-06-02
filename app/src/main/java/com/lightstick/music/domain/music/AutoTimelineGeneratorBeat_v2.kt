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
 * AutoTimelineGeneratorBeat_v2 — SectionDetectorV1 적용 버전
 *
 * v0 기반. 단일 패스 디코딩 후 BeatDetectorV2 + SectionDetectorV1 을 순차 실행.
 * 섹션 타입별 고정 색상으로 ON 이펙트를 생성한다.
 * SectionAwareGenerator 를 구현해 PrecomputeAutoTimelinesUseCase 에서 섹션 메타를 저장할 수 있다.
 */
class AutoTimelineGeneratorBeat_v2 : AutoTimelineGenerator, SectionAwareGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val VERSION     = 13    // local (not AutoTimelineConfig.VERSION)
        private const val HOP_MS      = 50L
        private const val MIN_BEAT_MS = 320L
        private const val MAX_BEAT_MS = 1200L

        // IIR filter coefficients
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
    }

    // ──────────────────────────────────────────────────────────────
    // AutoTimelineGenerator
    // ──────────────────────────────────────────────────────────────

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> = generateWithSections(musicPath, musicId, paletteSize).first

    // ──────────────────────────────────────────────────────────────
    // SectionAwareGenerator
    // ──────────────────────────────────────────────────────────────

    override fun generateWithSections(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>> {
        val fileName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        Log.d(TAG, "v2 generateWithSections() start file=$fileName musicId=$musicId paletteSize=$paletteSize")

        // 1. Single-pass decode
        val (lowEnv, midEnv, fullEnv) = decodeAllEnvelopes(musicPath, HOP_MS.toInt())

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "v2 env empty -> return empty")
            return Pair(emptyList(), emptyList())
        }

        val durationMs = fullEnv.size.toLong() * HOP_MS

        // 2. BeatDetectorV2 — same params as v0
        Log.d(TAG, "v2 BeatDetect start file=$fileName musicId=$musicId durationMs=$durationMs")
        val v11Result = BeatDetectorV2.detect(
            lowEnv  = lowEnv,
            midEnv  = midEnv,
            fullEnv = fullEnv,
            params  = BeatDetectorV2.Params(
                hopMs             = HOP_MS,
                minBeatMs         = MIN_BEAT_MS,
                maxBeatMs         = 1200L,
                minPeakDistanceMs = 140L,
                onsetSmoothWindow = 3,
                peakThresholdK    = 0.28f,
                minPeakAbs        = 0.07f,
                snapToleranceMs   = 130L,
                chainToleranceMs  = 150L,
                minChainCount     = 3,
                continuityBonus   = 0.08f
            )
        )

        val globalBeatMs = v11Result.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = v11Result.timeSignature.beatsPerBar
        Log.d(TAG, "v2 BeatDetectorV2 beatMs=$globalBeatMs beats=${v11Result.beats.size} " +
            "timeSig=${v11Result.timeSignature.type} beatsPerBar=$beatsPerBar")

        // Q6: 비트 타임스탬프 로그 (처음 12개 + 마지막 4개)
        if (v11Result.beats.isNotEmpty()) {
            val first = v11Result.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = v11Result.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v2 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        // [진단A] V11 출력 비트 품질 분석
        if (v11Result.beats.isNotEmpty()) {
            val synth  = v11Result.beats.count { it.confidence <= 0.20f }
            val real   = v11Result.beats.size - synth
            val sPct   = synth * 100 / v11Result.beats.size
            Log.d(TAG, "v2 [A] V11_quality[$fileName]: " +
                "real=$real synth=$synth(${sPct}%) total=${v11Result.beats.size}")

            val gapTh   = globalBeatMs * 3
            val bigGaps = (1 until v11Result.beats.size).mapNotNull { i ->
                val gap = v11Result.beats[i].timeMs - v11Result.beats[i - 1].timeMs
                if (gap >= gapTh) "${v11Result.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v2 [A] V11_gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v2 [A] V11_gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        // 3. SectionDetectorV1
        val sections = SectionDetectorV1().detect(
            lowEnv   = lowEnv,
            midEnv   = midEnv,
            fullEnv  = fullEnv,
            beats    = v11Result.beats,
            beatMs   = globalBeatMs,
            durationMs = durationMs,
            hopMs    = HOP_MS
        )
        Log.d(TAG, "v2 SectionDetectorV1: sections=${sections.size}")

        // 4. Section-aware timeline
        val frames = buildTimeline(v11Result.beats, sections, beatsPerBar, durationMs)
        Log.d(TAG, "v2 frames(final)=${frames.size}")

        // 5. Convert SectionDetector.Section → SectionMeta
        val sectionMetas = sections.map { s ->
            SectionMeta(
                startMs        = s.startMs,
                endMs          = s.endMs,
                type           = s.type,
                changeStrength = s.changeStrength,
                beatMs         = s.beatMs,
                beatConfidence = s.beatConfidence
            )
        }

        // 6. Return
        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — section-aware colors
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorV2.TimedBeat>,
        sections: List<SectionDetector.Section>,
        beatsPerBar: Int,
        durationMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frames    = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip = 0

        for (beat in beats) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) { rangeSkip++; continue }

            val section = sections.firstOrNull { t >= it.startMs && t < it.endMs }
            val color   = sectionColorFor(section?.type ?: SectionDetector.SectionType.VERSE)
            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0).toByteArray())
        }

        Log.d(TAG, "v2 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size}")
        return frames.sortedBy { it.first }
    }

    private fun sectionColorFor(type: SectionDetector.SectionType): LSColor = when (type) {
        SectionDetector.SectionType.INTRO  -> LSColor(128, 0,   255)   // Purple
        SectionDetector.SectionType.VERSE  -> LSColor(0,   100, 255)   // Blue
        SectionDetector.SectionType.CHORUS -> LSColor(255, 50,  50)    // Red
        SectionDetector.SectionType.BRIDGE -> LSColor(255, 128, 0)     // Orange
        SectionDetector.SectionType.END    -> LSColor(0,   200, 200)   // Teal
    }

    // ──────────────────────────────────────────────────────────────
    // Audio decode / envelope — IDENTICAL to v0
    // ──────────────────────────────────────────────────────────────

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
