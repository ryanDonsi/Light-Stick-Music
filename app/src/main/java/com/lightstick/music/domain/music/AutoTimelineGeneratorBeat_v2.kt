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
        private const val HOP_MS        = 50L
        private const val MIN_BEAT_MS   = 320L
        private const val MAX_BEAT_MS   = 1200L
        private const val MAX_DECODE_MS = 600_000L  // 최대 10분 (OOM 방지)

        // IIR filter coefficients
        private const val LOW_ALPHA     = 0.12f  // ~897 Hz 저역 차단
        private const val MID_LP1_ALPHA = 0.35f  // ~3020 Hz
        private const val MID_LP2_ALPHA = 0.08f  // ~585 Hz  → MID = LP1-LP2 (585~3020 Hz)
        private const val HIGH_ALPHA    = 0.40f  // ~3585 Hz  → HIGH = mono-LP (3.6 kHz↑, 여성보컬 존재감)
    }

    private data class Envelopes(
        val low: List<Float>,
        val mid: List<Float>,
        val full: List<Float>,
        val high: List<Float>
    )

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
        val t0Total  = System.currentTimeMillis()
        Log.d(TAG, "v2 [PERF] start file=$fileName musicId=$musicId paletteSize=$paletteSize")

        // BeatDetector 버전 및 hopMs 결정
        val detectorVer    = AutoTimelineConfig.BEAT_DETECTOR_VERSION
        val effectiveHopMs = AutoTimelineConfig.beatDetectorHopMs(detectorVer)

        // 1. Single-pass decode (LOW / MID / FULL / HIGH 4밴드) — effectiveHopMs 적용
        val t0Decode = System.currentTimeMillis()
        val envs = decodeAllEnvelopes(musicPath, effectiveHopMs.toInt())
        val (lowEnv, midEnv, fullEnv, highEnv) = envs
        Log.d(TAG, "v2 [PERF] decode=${System.currentTimeMillis() - t0Decode}ms frames=${fullEnv.size} hopMs=$effectiveHopMs")

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "v2 env empty -> return empty")
            return Pair(emptyList(), emptyList())
        }

        val durationMs = fullEnv.size.toLong() * effectiveHopMs

        // 2. BeatDetector — 버전별 원래 입력 방식으로 dispatch
        val t0Beat = System.currentTimeMillis()
        Log.d(TAG, "v2 [PERF] beatDetect start file=$fileName durationMs=$durationMs beatDetectorVer=$detectorVer hopMs=$effectiveHopMs")
        val beatInfo = when (detectorVer) {
            1 -> {
                // V1 원래 방식: PCM FloatArray 입력 → IIR 엔벨로프 내부 변환
                val (monoSamples, sampleRate) = decodeMonoPcm(musicPath)
                BeatDetectorRouter.detectPcm(
                    monoSamples = monoSamples,
                    sampleRate  = sampleRate,
                    minBeatMs   = MIN_BEAT_MS,
                    maxBeatMs   = 1200L,
                    hopMs       = effectiveHopMs
                )
            }
            2 -> {
                // V2 원래 방식: 스트리밍 파일 입력 → SuperFlux ODF + DBN HMM
                BeatDetectorRouter.detectFile(musicPath, MIN_BEAT_MS, 1200L)
            }
            else -> {
                // V3/4/5: 외부 엔벨로프 입력 — Config hopMs 적용
                BeatDetectorRouter.detect(
                    version   = detectorVer,
                    lowEnv    = lowEnv,
                    midEnv    = midEnv,
                    fullEnv   = fullEnv,
                    hopMs     = effectiveHopMs,
                    minBeatMs = MIN_BEAT_MS,
                    maxBeatMs = 1200L
                )
            }
        }

        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = beatInfo.beatsPerBar
        Log.d(TAG, "v2 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms beatMs=$globalBeatMs beats=${beatInfo.beats.size} beatsPerBar=$beatsPerBar")

        // 비트 타임스탬프 로그 (처음 12개 + 마지막 4개)
        if (beatInfo.beats.isNotEmpty()) {
            val first = beatInfo.beats.take(12).joinToString(" ") { "${it.timeMs}" }
            val last  = beatInfo.beats.takeLast(4).joinToString(" ") { "${it.timeMs}" }
            Log.d(TAG, "v2 beatTimes[$fileName] first=[$first] last=[$last]")
        }

        // 비트 품질 분석 (confidence ≤ 0.20 = 합성 비트)
        if (beatInfo.beats.isNotEmpty()) {
            val synth  = beatInfo.beats.count { it.confidence <= 0.20f }
            val real   = beatInfo.beats.size - synth
            val sPct   = synth * 100 / beatInfo.beats.size
            Log.d(TAG, "v2 [A] quality[$fileName]: real=$real synth=$synth(${sPct}%) total=${beatInfo.beats.size}")

            val gapTh   = globalBeatMs * 3
            val bigGaps = (1 until beatInfo.beats.size).mapNotNull { i ->
                val gap = beatInfo.beats[i].timeMs - beatInfo.beats[i - 1].timeMs
                if (gap >= gapTh) "${beatInfo.beats[i - 1].timeMs / 1000}s+${gap}ms" else null
            }
            if (bigGaps.isEmpty())
                Log.d(TAG, "v2 [A] gaps[$fileName]: 없음 (최대 < ${gapTh}ms) ✓")
            else
                Log.w(TAG, "v2 [A] gaps[$fileName](≥${gapTh}ms): ${bigGaps.take(5).joinToString(" | ")}")
        }

        // 3. SectionDetector (버전은 AutoTimelineConfig.SECTION_DETECTOR_VERSION)
        val t0Section = System.currentTimeMillis()
        val sections = SectionDetectorRouter.detect(
            version    = AutoTimelineConfig.SECTION_DETECTOR_VERSION,
            lowEnv     = lowEnv,
            midEnv     = midEnv,
            fullEnv    = fullEnv,
            highEnv    = highEnv,
            beats      = beatInfo.beats,
            beatMs     = globalBeatMs,
            durationMs = durationMs,
            hopMs      = effectiveHopMs
        )
        Log.d(TAG, "v2 [PERF] sectionDetect=${System.currentTimeMillis() - t0Section}ms sections=${sections.size}")

        // 4. Section-aware timeline
        val t0Build = System.currentTimeMillis()
        val frames = buildTimeline(beatInfo.beats, sections, beatsPerBar, globalBeatMs, beatInfo.downbeatMs, durationMs)
        Log.d(TAG, "v2 [PERF] build=${System.currentTimeMillis() - t0Build}ms frames=${frames.size}")
        Log.d(TAG, "v2 [PERF] total=${System.currentTimeMillis() - t0Total}ms file=$fileName durationMs=$durationMs")

        // 5. Convert SectionDetector.Section → SectionMeta
        val sectionMetas = sections.map { s ->
            val sBeats     = beatInfo.beats.filter { it.timeMs >= s.startMs && it.timeMs < s.endMs }
            val confidence = if (sBeats.isNotEmpty()) sBeats.map { it.confidence }.average().toFloat() else 0.20f
            SectionMeta(
                startMs        = s.startMs,
                endMs          = s.endMs,
                type           = s.type,
                changeStrength = s.changeStrength,
                beatMs         = globalBeatMs,
                beatConfidence = confidence,
                energy         = s.energy,
                peakEnergy     = s.peakEnergy,
                lowRatio       = s.lowRatio,
                midRatio       = s.midRatio,
                highRatio      = s.highRatio,
                onsetDensity   = s.onsetDensity,
                periodicity    = s.periodicity
            )
        }

        // 6. Return
        return Pair(frames.sortedBy { it.first }, sectionMetas)
    }

    // ──────────────────────────────────────────────────────────────
    // Timeline — section-aware colors
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        sections: List<SectionDetector.Section>,
        beatsPerBar: Int,
        beatMs: Long,
        downbeatMs: Long,
        durationMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frames    = ArrayList<Pair<Long, ByteArray>>()
        var rangeSkip = 0

        for (beat in beats) {
            val t = beat.timeMs
            if (t < 0 || t >= durationMs) { rangeSkip++; continue }

            val section   = sections.firstOrNull { t >= it.startMs && t < it.endMs }
            val baseColor = sectionColorFor(section?.type ?: SectionDetector.SectionType.VERSE)

            val beatInBar = if (beatMs > 0L) {
                val steps = Math.round((t - downbeatMs).toDouble() / beatMs.toDouble())
                (((steps % beatsPerBar) + beatsPerBar) % beatsPerBar).toInt()
            } else 0

            // 1박(0)=White, 3박(2)=섹션색상, 약박=섹션색상 — 밝기는 fade로 조정
            val color = if (beatInBar == 0) LSColor(255, 255, 255) else baseColor
            val fade  = beatFade(beatInBar, beatsPerBar)

            frames.add(t to LSEffectPayload.Effects.on(color = color, transit = 0, fade = fade).toByteArray())
        }

        Log.d(TAG, "v2 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size} downbeatMs=$downbeatMs beatsPerBar=$beatsPerBar")
        return frames.sortedBy { it.first }
    }

    /** fade 값 반환 (0~100). 강박(0)은 White + fade=100 고정 */
    private fun beatFade(beatInBar: Int, beatsPerBar: Int): Int = when (beatsPerBar) {
        4 -> when (beatInBar) { 0, 2 -> 100; else -> 35 }
        3 -> when (beatInBar) { 0    -> 100; else -> 45 }
        6 -> when (beatInBar) { 0, 3 -> 100; else -> 35 }
        else -> 100
    }

    private fun sectionColorFor(type: SectionDetector.SectionType): LSColor = when (type) {
        SectionDetector.SectionType.INTRO  -> LSColor(128, 0,   255)   // Purple
        SectionDetector.SectionType.VERSE  -> LSColor(0,   100, 255)   // Blue
        SectionDetector.SectionType.CHORUS -> LSColor(255, 50,  50)    // Red
        SectionDetector.SectionType.BRIDGE -> LSColor(255, 128, 0)     // Orange
        SectionDetector.SectionType.END    -> LSColor(0,   200, 200)   // Teal
        else                               -> LSColor(0,   100, 255)   // V2 타입 fallback → Blue
    }

    // ──────────────────────────────────────────────────────────────
    // Audio decode / envelope — IDENTICAL to v0
    // ──────────────────────────────────────────────────────────────

    /** 단일 패스로 LOW / MID / FULL / HIGH 엔벨로프를 동시에 추출한다. */
    private fun decodeAllEnvelopes(
        musicPath: String,
        hopMs: Int
    ): Envelopes {
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
            if (trackIndex < 0 || format == null) { extractor.release(); return Envelopes(emptyList(), emptyList(), emptyList(), emptyList()) }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: run { extractor.release(); return Envelopes(emptyList(), emptyList(), emptyList(), emptyList()) }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = max(1, sampleRate * hopMs / 1000)

            val outLow  = ArrayList<Float>()
            val outMid  = ArrayList<Float>()
            val outFull = ArrayList<Float>()
            val outHigh = ArrayList<Float>()

            // IIR 상태 변수 (청크 간 유지)
            var lowZ   = 0f; var midLP1 = 0f; var midLP2 = 0f; var highLP = 0f
            // 누산기 RMS (hopSamples 마다 flush)
            var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var highSumSq = 0f; var winPos = 0
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
                                highLP += HIGH_ALPHA    * (mono - highLP)
                                val lowVal  = abs(lowZ)
                                val midVal  = abs(midLP1 - midLP2)
                                val highVal = abs(mono - highLP)   // high-pass: 3.6kHz↑
                                lowSumSq  += lowVal  * lowVal
                                midSumSq  += midVal  * midVal
                                fullSumSq += mono    * mono
                                highSumSq += highVal * highVal
                                winPos++
                                if (winPos >= hopSamples) {
                                    outLow  += sqrt(lowSumSq  / winPos)
                                    outMid  += sqrt(midSumSq  / winPos)
                                    outFull += sqrt(fullSumSq / winPos)
                                    outHigh += sqrt(highSumSq / winPos)
                                    lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; highSumSq = 0f; winPos = 0
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
            Envelopes(
                low  = normalizeEnvelope(outLow),
                mid  = normalizeEnvelope(outMid),
                full = normalizeEnvelope(outFull),
                high = normalizeEnvelope(outHigh)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "decodeAllEnvelopes fail: ${t.message}")
            try { codec?.stop() }    catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            Envelopes(emptyList(), emptyList(), emptyList(), emptyList())
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

    /** V1 beatDetector 전용: PCM FloatArray 디코딩 (최대 MAX_DECODE_MS) */
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
            val durationUs   = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            val allocSamples = if (durationUs > 0) (sampleRate * durationUs / 1_000_000L).toInt().coerceAtMost(maxSamples) else maxSamples
            val out          = FloatArray(allocSamples)
            var outPos       = 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo  = MediaCodec.BufferInfo()
            var sawInputEOS = false; var sawOutputEOS = false

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

    fun getVersion(): Int = VERSION
}
