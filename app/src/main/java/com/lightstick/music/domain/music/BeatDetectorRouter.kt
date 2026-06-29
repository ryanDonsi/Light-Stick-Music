package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * BeatDetector 버전 라우터.
 *
 * [detect] (filePath 오버로드)를 통해 버전에 관계없이 단일 진입점으로 호출.
 * 내부에서 오디오 디코딩 + BeatDetector 실행 + Envelope 추출을 단일 pass로 처리하며
 * [BeatInfo.envelopes]에 SectionDetector용 4밴드 Envelope을 포함해 반환한다.
 *
 *  V0 : BeatDetectorV0 (IIR 3밴드 ODF + Autocorrelation, hopMs=50ms)
 *  V1 : BeatDetectorV1 (IIR 3밴드 ODF + Autocorrelation + log-normal prior, PCM, hopMs=10ms)
 *  V2 : BeatDetectorV2 (Dual ODF + DP tracking, 스트리밍, hopMs=10ms) — envelope 별도 decode
 */
object BeatDetectorRouter {

    private const val TAG = "AutoTimeline_BeatDetectorRouter"

    // IIR 필터 계수 (SectionDetector/MusicStyleClassifier 공통)
    private const val LOW_ALPHA     = 0.12f
    private const val MID_LP1_ALPHA = 0.35f
    private const val MID_LP2_ALPHA = 0.08f
    private const val HIGH_ALPHA    = 0.40f

    private const val MAX_PCM_SECS = 600L  // 10분 OOM 방지

    // =========================================================================
    // 공개 데이터 클래스
    // =========================================================================

    /** SectionDetector 입력용 4밴드 Envelope */
    data class AudioEnvelopes(
        val low:  List<Float>,
        val mid:  List<Float>,
        val full: List<Float>,
        val high: List<Float>,
        val hopMs:      Long,
        val sampleRate: Int
    )

    /** 생성기에서 공통으로 사용하는 정규화 결과 */
    data class BeatInfo(
        val beats:       List<Beat>,
        val beatMs:      Long,
        val beatsPerBar: Int,
        val downbeatMs:  Long,
        /** SectionDetector / MusicStyleClassifier 용 4밴드 Envelope (항상 포함) */
        val envelopes:   AudioEnvelopes? = null
    ) {
        data class Beat(val timeMs: Long, val confidence: Float)
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }
    }

    // =========================================================================
    // 통합 진입점 — 파일 경로 기반, 버전 자동 분기
    // =========================================================================

    /**
     * 파일 경로를 받아 버전에 맞는 BeatDetector를 실행하고 [BeatInfo]를 반환한다.
     * [BeatInfo.envelopes]에 SectionDetector용 4밴드 Envelope이 포함된다.
     *
     * @param version [AutoTimelineConfig.BEAT_DETECTOR_VERSION] 기본값 사용
     * @param hopMs   Envelope hop 간격 ms ([AutoTimelineConfig.beatDetectorHopMs] 기본값)
     */
    fun detect(
        filePath:  String,
        version:   Int  = AutoTimelineConfig.BEAT_DETECTOR_VERSION,
        hopMs:     Long = AutoTimelineConfig.beatDetectorHopMs(AutoTimelineConfig.BEAT_DETECTOR_VERSION),
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo = when (version) {
        1    -> detectV1(filePath, hopMs, minBeatMs, maxBeatMs)
        2    -> detectV2(filePath, hopMs, minBeatMs, maxBeatMs)
        3    -> detectV3(filePath, hopMs, minBeatMs, maxBeatMs)
        else -> detectV0(filePath, hopMs, minBeatMs, maxBeatMs)
    }

    // =========================================================================
    // 버전별 내부 구현
    // =========================================================================

    /** V1: PCM + 4밴드 Envelope 단일 pass → BeatDetectorV1.detectPcm */
    private fun detectV1(filePath: String, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): BeatInfo {
        val decoded = decodeWithEnvelopes(filePath, hopMs, collectPcm = true)
        if (decoded.full.isEmpty()) return emptyBeatInfo()
        val songTitle = java.io.File(filePath).nameWithoutExtension
        val r = BeatDetectorV1.detectPcm(
            decoded.pcm, decoded.sampleRate,
            BeatDetectorV1.Params(
                hopMs     = hopMs,
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L)
            ),
            songTitle
        )
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs,
            envelopes   = decoded.toAudioEnvelopes(hopMs)
        )
    }

    /** V2: SuperFlux 스트리밍 → BeatDetectorV2.detect + 별도 Envelope decode */
    private fun detectV2(filePath: String, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): BeatInfo {
        val r = BeatDetectorV2.detect(
            filePath,
            BeatDetectorV2.Params(
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L)
            )
        )
        val decoded = decodeWithEnvelopes(filePath, hopMs, collectPcm = false)
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs,
            envelopes   = decoded.toAudioEnvelopes(hopMs)
        )
    }

    /** V3: Tempogram 기반 BPM 탐지 → BeatDetectorV3.detect */
    private fun detectV3(filePath: String, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): BeatInfo {
        val decoded = decodeWithEnvelopes(filePath, hopMs, collectPcm = false)
        if (decoded.full.isEmpty()) return emptyBeatInfo()
        val songTitle = java.io.File(filePath).nameWithoutExtension
        val r = BeatDetectorV3.detect(
            decoded.low, decoded.mid, decoded.full,
            BeatDetectorV3.Params(
                hopMs     = hopMs,
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L),
                useTempogram = true
            ),
            songTitle
        )
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs,
            envelopes   = decoded.toAudioEnvelopes(hopMs)
        )
    }

/** V0: 4밴드 Envelope decode → BeatDetectorV0.detect */
    private fun detectV0(filePath: String, hopMs: Long, minBeatMs: Long, maxBeatMs: Long): BeatInfo {
        val decoded = decodeWithEnvelopes(filePath, hopMs, collectPcm = false)
        if (decoded.full.isEmpty()) return emptyBeatInfo()
        val r = BeatDetectorV0.detect(
            decoded.low, decoded.mid, decoded.full,
            BeatDetectorV0.Params(
                hopMs             = hopMs,
                minBeatMs         = minBeatMs.coerceAtLeast(375L),
                maxBeatMs         = maxBeatMs.coerceAtMost(1000L),
                minPeakDistanceMs = 120L,
                onsetSmoothWindow = 3
            )
        )
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs,
            envelopes   = decoded.toAudioEnvelopes(hopMs)
        )
    }

    // =========================================================================
    // 오디오 디코딩 — 단일 pass로 4밴드 Envelope + PCM 동시 추출
    // =========================================================================

    private data class DecodeResult(
        val low:  List<Float>,
        val mid:  List<Float>,
        val full: List<Float>,
        val high: List<Float>,
        val pcm:        FloatArray,
        val sampleRate: Int
    ) {
        fun toAudioEnvelopes(hopMs: Long) =
            AudioEnvelopes(low, mid, full, high, hopMs, sampleRate)
    }

    private fun decodeWithEnvelopes(
        filePath:   String,
        hopMs:      Long,
        collectPcm: Boolean
    ): DecodeResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(filePath)
            var trackIndex = -1; var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return DecodeResult(emptyList(), emptyList(), emptyList(), emptyList(), FloatArray(0), 44100)
            }
            extractor.selectTrack(trackIndex)
            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = (sampleRate.toLong() * hopMs / 1000L).toInt().coerceAtLeast(1)
            val maxPcmSamp   = if (collectPcm) (sampleRate * MAX_PCM_SECS).toInt() else 0
            val stepBytes    = channelCount * 2

            // Pre-allocate PCM as primitive FloatArray to avoid boxing overhead (4 bytes vs 16 bytes per sample)
            val durationUs   = if (collectPcm && format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else -1L
            val estPcmSamp   = if (collectPcm) {
                if (durationUs > 0) (sampleRate * durationUs / 1_000_000L).toInt().coerceAtMost(maxPcmSamp)
                else maxPcmSamp
            } else 0
            val pcmArray     = if (collectPcm) FloatArray(estPcmSamp) else null
            var pcmPos       = 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start()

            val est     = (sampleRate.toLong() * 300L / hopSamples).toInt()
            val outLow  = ArrayList<Float>(est); val outMid  = ArrayList<Float>(est)
            val outFull = ArrayList<Float>(est); val outHigh = ArrayList<Float>(est)

            var lowZ = 0f; var midLP1 = 0f; var midLP2 = 0f; var highLP = 0f
            var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var highSumSq = 0f
            var winPos = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false; var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!; buf.clear()
                        val sz = extractor.readSampleData(buf, 0)
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
                when {
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && bufferInfo.size > 0) {
                            buf.position(bufferInfo.offset); buf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size); buf.get(bytes)
                            var i = 0
                            while (i + stepBytes <= bytes.size) {
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    val lo = bytes[i + c * 2].toInt() and 0xFF
                                    val hi = bytes[i + c * 2 + 1].toInt()
                                    monoSum += (hi shl 8 or lo).toShort().toFloat()
                                }
                                val mono = monoSum / channelCount / 32768f
                                if (pcmArray != null && pcmPos < pcmArray.size) pcmArray[pcmPos++] = mono
                                lowZ   += LOW_ALPHA     * (mono - lowZ)
                                midLP1 += MID_LP1_ALPHA * (mono - midLP1)
                                midLP2 += MID_LP2_ALPHA * (mono - midLP2)
                                highLP += HIGH_ALPHA    * (mono - highLP)
                                val lv = abs(lowZ); val mv = abs(midLP1 - midLP2)
                                val fv = abs(mono); val hv = abs(mono - highLP)
                                lowSumSq += lv*lv; midSumSq += mv*mv
                                fullSumSq += fv*fv; highSumSq += hv*hv
                                winPos++
                                if (winPos >= hopSamples) {
                                    val n = hopSamples.toFloat()
                                    outLow  += sqrt(lowSumSq  / n); outMid  += sqrt(midSumSq  / n)
                                    outFull += sqrt(fullSumSq / n); outHigh += sqrt(highSumSq / n)
                                    lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; highSumSq = 0f; winPos = 0
                                }
                                i += stepBytes
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
            codec.stop(); codec.release(); extractor.release()

            if (winPos > 0) {
                val n = winPos.toFloat()
                outLow += sqrt(lowSumSq / n); outMid += sqrt(midSumSq / n)
                outFull += sqrt(fullSumSq / n); outHigh += sqrt(highSumSq / n)
            }
            DecodeResult(
                low  = normalize(outLow),  mid  = normalize(outMid),
                full = normalize(outFull), high = normalize(outHigh),
                pcm        = if (pcmArray != null) { if (pcmPos < pcmArray.size) pcmArray.copyOf(pcmPos) else pcmArray } else FloatArray(0),
                sampleRate = sampleRate
            )
        } catch (t: Throwable) {
            Log.e(TAG, "decodeWithEnvelopes fail: ${t.message}")
            try { codec?.stop()    } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            DecodeResult(emptyList(), emptyList(), emptyList(), emptyList(), FloatArray(0), 44100)
        }
    }

    // =========================================================================
    // 유틸리티
    // =========================================================================

    private fun normalize(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val smooth = movingAverage(src, 5)
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src
        val out = ArrayList<Float>(src.size); val half = window / 2
        for (i in src.indices) {
            var sum = 0f; var cnt = 0
            val s = max(0, i - half); val e = minOf(src.lastIndex, i + half)
            for (j in s..e) { sum += src[j]; cnt++ }
            out += if (cnt == 0) 0f else sum / cnt.toFloat()
        }
        return out
    }

    private fun emptyBeatInfo() = BeatInfo(emptyList(), 500L, 4, 0L, null)

    // =========================================================================
    // 하위 호환 오버로드 (기존 코드에서 직접 호출하는 경우를 위해 유지)
    // =========================================================================

    fun detectPcm(
        monoSamples: FloatArray,
        sampleRate:  Int,
        minBeatMs:   Long,
        maxBeatMs:   Long,
        hopMs:       Long = 50L
    ): BeatInfo {
        val r = BeatDetectorV1.detectPcm(monoSamples, sampleRate,
            BeatDetectorV1.Params(hopMs = hopMs,
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L)))
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
        )
    }

    fun detectFile(
        musicPath: String,
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo {
        val r = BeatDetectorV2.detect(musicPath,
            BeatDetectorV2.Params(minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L)))
        return BeatInfo(
            beats       = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs      = r.beatMs,
            beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs  = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs
        )
    }

    fun detect(
        version:  Int,
        lowEnv:   List<Float>,
        midEnv:   List<Float>,
        fullEnv:  List<Float>,
        hopMs:    Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): BeatInfo = if (version == 1) {
        val r = BeatDetectorV1.detect(lowEnv, midEnv, fullEnv,
            BeatDetectorV1.Params(hopMs = hopMs,
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L),
                minPeakDistanceMs = 120L, onsetSmoothWindow = 3))
        BeatInfo(beats = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs = r.beatMs, beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs)
    } else {
        val r = BeatDetectorV0.detect(lowEnv, midEnv, fullEnv,
            BeatDetectorV0.Params(hopMs = hopMs,
                minBeatMs = minBeatMs.coerceAtLeast(375L),
                maxBeatMs = maxBeatMs.coerceAtMost(1000L),
                minPeakDistanceMs = 120L, onsetSmoothWindow = 3))
        BeatInfo(beats = r.beats.map { BeatInfo.Beat(it.timeMs, it.confidence) },
            beatMs = r.beatMs, beatsPerBar = r.timeSignature.beatsPerBar,
            downbeatMs = (r.beats.firstOrNull()?.timeMs ?: 0L) + r.downbeatOffsetMs)
    }
}
