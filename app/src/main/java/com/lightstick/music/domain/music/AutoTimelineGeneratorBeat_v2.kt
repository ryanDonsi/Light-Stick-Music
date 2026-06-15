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
 * AutoTimelineGeneratorBeat_v2 — BeatDetectorV1 + SectionDetectorV1
 *
 * 비트 감지·연출은 AutoTimelineGeneratorBeat_v0(detectorVer=1) 과 완전 동일:
 *   - mono PCM 디코딩(decodeMonoPcm) → BeatDetectorRouter.detectPcm(version=1, hop 10ms)
 *   - buildTimeline: beatInBar 0=White(100) / 1=Purple(35) / 2=Yellow(100) / 3=Cyan(35)
 *
 * 섹션 감지는 유지(SectionAwareGenerator): 디코딩한 PCM 에서 50ms 4밴드 엔벨로프를
 * 추출해 SectionDetector 에 공급하고 SectionMeta 로 변환해 반환한다.
 * 섹션은 메타데이터 저장용이며 비트 연출에는 사용하지 않는다.
 */
class AutoTimelineGeneratorBeat_v2 : AutoTimelineGenerator, SectionAwareGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val VERSION       = 13    // local (not AutoTimelineConfig.VERSION)

        // Beat — v0 (detectorVer=1) 과 동일
        private const val BEAT_HOP_MS   = 10L
        private const val MIN_BEAT_MS   = 320L
        private const val MAX_BEAT_MS   = 1200L
        private const val MAX_DECODE_MS = 600_000L  // 최대 10분 (OOM 방지)

        // Section — 엔벨로프 hop (기존 v2 와 동일)
        private const val SECTION_HOP_MS = 50L

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
        Log.d(TAG, "v2 generateWithSections() start file=$fileName musicId=$musicId paletteSize=$paletteSize")

        // 1. mono PCM 디코딩 — v0(detectorVer=1) 과 동일 경로
        val t0Decode = System.currentTimeMillis()
        val (monoSamples, sampleRate) = decodeMonoPcm(musicPath)
        Log.d(TAG, "v2 [PERF] decode=${System.currentTimeMillis() - t0Decode}ms samples=${monoSamples.size} sr=$sampleRate")
        if (monoSamples.isEmpty()) {
            Log.w(TAG, "v2 pcm empty -> return empty")
            return Pair(emptyList(), emptyList())
        }
        val durationMs = monoSamples.size.toLong() * 1000L / sampleRate

        // 2. BeatDetectorV1 — v0 과 동일 호출 (version=1 고정, hop 10ms)
        val t0Beat = System.currentTimeMillis()
        val beatInfo = BeatDetectorRouter.detectPcm(
            version      = 1,
            monoSamples  = monoSamples,
            sampleRate   = sampleRate,
            minBeatMs    = MIN_BEAT_MS,
            maxBeatMs    = 1200L,
            hopMs        = BEAT_HOP_MS
        )
        val globalBeatMs = beatInfo.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
        val beatsPerBar  = beatInfo.beatsPerBar
        Log.d(TAG, "v2 [PERF] beatDetect=${System.currentTimeMillis() - t0Beat}ms beatMs=$globalBeatMs beats=${beatInfo.beats.size} beatsPerBar=$beatsPerBar detectorVer=1")

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

        // 3. SectionDetector — PCM 에서 50ms 4밴드 엔벨로프 추출 후 실행 (메타데이터 전용)
        val envs = computeEnvelopesFromPcm(monoSamples, sampleRate, SECTION_HOP_MS.toInt())
        val sections = if (envs.full.isEmpty()) {
            Log.w(TAG, "v2 section env empty -> sections skipped")
            emptyList()
        } else {
            SectionDetectorRouter.detect(
                version    = AutoTimelineConfig.SECTION_DETECTOR_VERSION,
                lowEnv     = envs.low,
                midEnv     = envs.mid,
                fullEnv    = envs.full,
                highEnv    = envs.high,
                beats      = beatInfo.beats,
                beatMs     = globalBeatMs,
                durationMs = durationMs,
                hopMs      = SECTION_HOP_MS
            )
        }
        Log.d(TAG, "v2 sections=${sections.size}")

        // 4. Timeline — v0 과 동일한 Beat 연출
        val frames = buildTimeline(beatInfo.beats, globalBeatMs, beatsPerBar, beatInfo.downbeatMs, durationMs)
        Log.d(TAG, "v2 frames(final)=${frames.size}")

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
    // Timeline — v0 과 동일한 Beat 연출 (섹션 미사용)
    // ──────────────────────────────────────────────────────────────

    private fun buildTimeline(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        beatMs: Long,
        beatsPerBar: Int,
        downbeatMs: Long,
        durationMs: Long
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

        Log.d(TAG, "v2 buildTimeline: beats=${beats.size} rangeSkip=$rangeSkip frames=${frames.size} downbeatMs=$downbeatMs beatsPerBar=$beatsPerBar")
        return frames.sortedBy { it.first }
    }

    // ──────────────────────────────────────────────────────────────
    // Audio decode — mono PCM (v0 의 decodeMonoPcm 과 동일)
    // ──────────────────────────────────────────────────────────────

    /**
     * PCM 을 모노 FloatArray 로 디코딩한다.
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

    // ──────────────────────────────────────────────────────────────
    // Section envelopes — 디코딩된 PCM 에서 LOW / MID / FULL / HIGH 추출
    // (기존 decodeAllEnvelopes 와 동일한 IIR·RMS 수식, 디코딩만 재사용)
    // ──────────────────────────────────────────────────────────────

    private fun computeEnvelopesFromPcm(
        monoSamples: FloatArray,
        sampleRate: Int,
        hopMs: Int
    ): Envelopes {
        if (monoSamples.isEmpty() || sampleRate <= 0) {
            return Envelopes(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val hopSamples = max(1, sampleRate * hopMs / 1000)

        val outLow  = ArrayList<Float>(monoSamples.size / hopSamples + 1)
        val outMid  = ArrayList<Float>(monoSamples.size / hopSamples + 1)
        val outFull = ArrayList<Float>(monoSamples.size / hopSamples + 1)
        val outHigh = ArrayList<Float>(monoSamples.size / hopSamples + 1)

        // IIR 상태 변수
        var lowZ   = 0f; var midLP1 = 0f; var midLP2 = 0f; var highLP = 0f
        // 누산기 RMS (hopSamples 마다 flush)
        var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var highSumSq = 0f; var winPos = 0

        for (mono in monoSamples) {
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
        }

        return Envelopes(
            low  = normalizeEnvelope(outLow),
            mid  = normalizeEnvelope(outMid),
            full = normalizeEnvelope(outFull),
            high = normalizeEnvelope(outHigh)
        )
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
