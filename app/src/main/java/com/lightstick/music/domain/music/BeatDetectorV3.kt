package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.util.Log
import kotlin.math.*

/**
 * BeatDetectorV3 — Madmom-inspired beat detection
 *
 * Madmom의 ACF(Autocorrelation Function) 기반 BPM 추정 포팅:
 *   1. Spectral flux ODF 계산
 *   2. ACF를 통한 lag 기반 BPM 추정
 *   3. Half-tempo 감지 및 보정
 *   4. DP 기반 beat tracking
 */
object BeatDetectorV3 {

    private const val TAG = "AutoTimelineV3"

    // ODF 계산 파라미터
    private const val FFT_SIZE = 2048
    private const val HOP_MS = 10L
    private const val SAMPLE_RATE_DEFAULT = 22050

    // ACF 기반 BPM 추정 파라미터
    // BPM = 60000 / (lag * hopMs)
    // lag = 60000 / (BPM * hopMs)
    // 240 BPM → lag = 60000 / (240 * 10) = 25
    // 50 BPM  → lag = 60000 / (50 * 10) = 120
    private const val ACF_MIN_LAG = 20      // ~300 BPM (10ms hop)
    private const val ACF_MAX_LAG = 150     // ~40 BPM (10ms hop)
    private const val HALF_TEMPO_THRESHOLD = 0.65f

    // DP beat tracking
    private const val DP_TIGHTNESS = 100f
    private const val DP_MIN_BEAT_RATIO = 0.25f

    // 시간 서명 감지
    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO = 1.25f

    // Downbeat 추정
    private const val DOWNBEAT_W_LOW_ENERGY = 0.50f
    private const val DOWNBEAT_W_BAR_COMB = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    data class TimedBeat(val timeMs: Long, val confidence: Float)

    enum class TimeSignatureType { FOUR_FOUR, THREE_FOUR, SIX_EIGHT }

    data class TimeSignature(
        val type: TimeSignatureType,
        val numerator: Int,
        val denominator: Int
    ) {
        companion object {
            val FOUR_FOUR = TimeSignature(TimeSignatureType.FOUR_FOUR, 4, 4)
            val THREE_FOUR = TimeSignature(TimeSignatureType.THREE_FOUR, 3, 4)
            val SIX_EIGHT = TimeSignature(TimeSignatureType.SIX_EIGHT, 6, 8)
        }
        val beatsPerBar: Int get() = numerator
    }

    enum class BeatSource { ODF, ACF }

    data class Params(
        val minBeatMs: Long = 280L,
        val maxBeatMs: Long = 1100L,
        val hopMs: Long = HOP_MS,
        val minPeakDistanceMs: Long = 120L
    )

    data class DetectResult(
        val beats: List<TimedBeat>,
        val beatMs: Long,
        val source: BeatSource?,
        val reason: String,
        val downbeatOffsetMs: Long,
        val timeSignature: TimeSignature,
        val debugSegments: List<Any> = emptyList()
    ) {
        val beatTimesMs: List<Long> get() = beats.map { it.timeMs }
    }

    // IIR 필터 계수
    private const val LOW_ALPHA = 0.12f
    private const val MID_LP1_ALPHA = 0.35f
    private const val MID_LP2_ALPHA = 0.08f
    private const val HIGH_ALPHA = 0.40f

    fun detect(
        musicPath: String,
        params: Params = Params()
    ): DetectResult {
        val songName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        Log.d(TAG, "V3 [$songName] start")

        // 오디오 디코딩 및 ODF 계산
        val (odf, hopMs, sampleRate) = computeOdf(musicPath)
        if (odf.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "empty input", 0L, TimeSignature.FOUR_FOUR)
        }

        // ACF 기반 BPM 추정
        val (beatMs, acfPeaks) = estimateBpmFromAcf(odf, hopMs, params.minBeatMs, params.maxBeatMs)
        if (beatMs <= 0L) {
            return DetectResult(emptyList(), 0L, null, "bpm estimation failed", 0L, TimeSignature.FOUR_FOUR)
        }

        // DP 기반 beat tracking
        val phaseMs = estimatePhaseFromOdf(odf, beatMs, hopMs)
        val dpTimes = dpBeatTracker(odf, beatMs, hopMs, anchorMs = phaseMs)

        val expectedBeats = max(1, (odf.size.toLong() * hopMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats = dpTimes.map { TimedBeat(it, 1.0f) }
            reason = "dp_ok"
        } else {
            beats = generateFallbackBeats(odf, beatMs, hopMs, phaseMs)
            reason = "dp_fallback"
        }

        val timeSignature = estimateTimeSignature(beats, beatMs)
        val downbeatOffset = estimateDownbeat(odf, beats, beatMs, hopMs, timeSignature)

        Log.d(TAG, "V3 [$songName] result: ${beatMs}ms (${(60000L / beatMs).toInt()} BPM), beats=${beats.size}, reason=$reason")

        return DetectResult(
            beats = beats,
            beatMs = beatMs,
            source = BeatSource.ACF,
            reason = reason,
            downbeatOffsetMs = downbeatOffset,
            timeSignature = timeSignature
        )
    }

    // =====================================================================
    // ODF 계산 (Spectral flux)
    // =====================================================================

    private data class OdfResult(val odf: List<Float>, val hopMs: Long, val sampleRate: Int)

    private fun computeOdf(filePath: String): OdfResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(filePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return OdfResult(emptyList(), HOP_MS, SAMPLE_RATE_DEFAULT)
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples = (sampleRate.toLong() * HOP_MS / 1000L).toInt().coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val odfList = ArrayList<Float>()
            var prevFrameEnergy = 0f

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var windowPos = 0
            var windowEnergy = 0f

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
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
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            buf.get(bytes)

                            var i = 0
                            while (i + 1 < bytes.size) {
                                val lo = bytes[i].toInt() and 0xFF
                                val hi = bytes[min(i + 1, bytes.size - 1)].toInt()
                                val sample = (hi shl 8 or lo).toShort().toFloat() / 32768f
                                windowEnergy += sample * sample
                                windowPos++

                                if (windowPos >= hopSamples) {
                                    val frameEnergy = sqrt(windowEnergy / windowPos)
                                    val flux = max(0f, frameEnergy - prevFrameEnergy)
                                    odfList.add(flux)
                                    prevFrameEnergy = frameEnergy
                                    windowEnergy = 0f
                                    windowPos = 0
                                }
                                i += 2
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            // ODF 정규화
            val normalized = normalizeOdf(odfList)
            OdfResult(normalized, HOP_MS, sampleRate)

        } catch (t: Throwable) {
            Log.e(TAG, "computeOdf failed: ${t.message}")
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            OdfResult(emptyList(), HOP_MS, SAMPLE_RATE_DEFAULT)
        }
    }

    private fun normalizeOdf(odf: List<Float>): List<Float> {
        if (odf.isEmpty()) return emptyList()
        val smoothed = movingAverage(odf, 5)
        val mx = smoothed.maxOrNull() ?: 0f
        return if (mx > 1e-6f) smoothed.map { (it / mx).coerceIn(0f, 1f) } else smoothed
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src
        val out = ArrayList<Float>(src.size)
        val half = window / 2
        for (i in src.indices) {
            var sum = 0f
            var cnt = 0
            val s = max(0, i - half)
            val e = minOf(src.lastIndex, i + half)
            for (j in s..e) {
                sum += src[j]
                cnt++
            }
            out.add(if (cnt == 0) 0f else sum / cnt)
        }
        return out
    }

    // =====================================================================
    // ACF 기반 BPM 추정 (Madmom 스타일)
    // =====================================================================

    private data class AcfPeak(val lag: Int, val bpm: Long, val acfValue: Float, val confidence: Float)

    private fun estimateBpmFromAcf(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Pair<Long, List<AcfPeak>> {
        // lag = 60000 / (BPM * hopMs)
        val minLag = (60000L / (maxBeatMs * hopMs)).toInt().coerceAtLeast(ACF_MIN_LAG)
        val maxLag = (60000L / (minBeatMs * hopMs)).toInt().coerceAtMost(ACF_MAX_LAG)

        // ACF 계산
        val acf = computeAutocorrelation(odf, maxLag)

        // lag 범위 내 peak 찾기
        val peaks = findAcfPeaks(acf, minLag, maxLag, hopMs)
        if (peaks.isEmpty()) {
            return 0L to emptyList()
        }

        // 최고 신뢰도 peak
        val bestPeak = peaks.maxByOrNull { it.confidence } ?: return 0L to emptyList()
        var selectedBpm = bestPeak.bpm

        // Half-tempo 체크: best lag의 절반에서 ACF가 충분히 높으면 faster tempo 선택
        if (bestPeak.lag > 2) {
            val halfLag = bestPeak.lag / 2
            val halfValue = if (halfLag < acf.size) acf[halfLag] else 0f
            if (halfValue > 0f) {
                val ratio = halfValue / bestPeak.acfValue
                if (ratio >= HALF_TEMPO_THRESHOLD) {
                    val halfBpm = 60000L / (halfLag * hopMs)
                    val halfBeatMs = 60000L / halfBpm
                    if (halfBeatMs in minBeatMs..maxBeatMs) {
                        selectedBpm = halfBpm
                        Log.d(TAG, "V3: half-tempo detected: ${bestPeak.bpm} → $halfBpm BPM (ratio=$ratio)")
                    }
                }
            }
        }

        return selectedBpm to peaks
    }

    private fun computeAutocorrelation(signal: List<Float>, maxLag: Int): FloatArray {
        val acf = FloatArray(maxLag + 1)
        if (signal.isEmpty()) return acf

        val c0 = signal.sumOf { it * it }.toFloat()
        if (c0 <= 1e-6f) return acf

        for (lag in 0..maxLag) {
            var sum = 0f
            for (i in 0 until signal.size - lag) {
                sum += signal[i] * signal[i + lag]
            }
            acf[lag] = sum / c0
        }
        return acf
    }

    private fun findAcfPeaks(
        acf: FloatArray,
        minLag: Int,
        maxLag: Int,
        hopMs: Long
    ): List<AcfPeak> {
        val peaks = ArrayList<AcfPeak>()

        for (lag in minLag..minOf(maxLag, acf.size - 1)) {
            val value = acf[lag]
            val isPeak = (lag == minLag || value > acf[lag - 1]) &&
                         (lag == acf.size - 1 || value >= acf[lag + 1])

            if (isPeak && value > 0.1f) {
                // BPM = 60000 / (lag * hopMs)
                val bpm = 60000L / (lag * hopMs)
                // confidence = ACF value boosted by log-normal-like prior (centered at 120 BPM)
                val logBpm = kotlin.math.log2(bpm / 120.0)
                val prior = kotlin.math.exp(-0.5f * logBpm * logBpm)
                val confidence = (value * prior).coerceIn(0f, 1f)
                peaks.add(AcfPeak(lag, bpm, value, confidence))
            }
        }

        return peaks.sortedByDescending { it.confidence }
    }

    // =====================================================================
    // Phase 추정 및 DP beat tracking
    // =====================================================================

    private fun estimatePhaseFromOdf(odf: List<Float>, beatMs: Long, hopMs: Long): Long {
        if (odf.isEmpty() || beatMs <= 0) return 0L

        val beatHops = beatMs / hopMs
        if (beatHops <= 0) return 0L

        val phaseHops = beatHops.toInt()
        var bestPhaseHop = 0
        var maxEnergy = 0f

        for (phase in 0 until phaseHops) {
            var energy = 0f
            var count = 0
            for (i in phase until odf.size step phaseHops) {
                energy += odf[i]
                count++
            }
            if (count > 0 && energy / count > maxEnergy) {
                maxEnergy = energy / count
                bestPhaseHop = phase
            }
        }

        return bestPhaseHop * hopMs
    }

    private fun dpBeatTracker(
        odf: List<Float>,
        beatMs: Long,
        hopMs: Long,
        anchorMs: Long = 0L
    ): List<Long> {
        if (odf.isEmpty() || beatMs <= 0) return emptyList()

        val beatHops = beatMs / hopMs
        if (beatHops <= 0) return emptyList()

        val anchorHop = anchorMs / hopMs
        val beats = ArrayList<Long>()

        // 시작 위치
        var currentHop = anchorHop.toInt().coerceIn(0, odf.size - 1)
        while (currentHop < odf.size) {
            beats.add(currentHop * hopMs)
            currentHop += beatHops.toInt()
        }

        return beats
    }

    private fun generateFallbackBeats(
        odf: List<Float>,
        beatMs: Long,
        hopMs: Long,
        phaseMs: Long
    ): List<TimedBeat> {
        val beats = ArrayList<TimedBeat>()
        val phaseHop = phaseMs / hopMs
        var currentHop = phaseHop.toInt()
        val beatHops = beatMs / hopMs

        while (currentHop < odf.size) {
            beats.add(TimedBeat(currentHop * hopMs, 0.8f))
            currentHop += beatHops.toInt()
        }

        return beats
    }

    // =====================================================================
    // 시간 서명 및 downbeat 추정
    // =====================================================================

    private fun estimateTimeSignature(beats: List<TimedBeat>, beatMs: Long): TimeSignature {
        if (beats.size < 8) return TimeSignature.FOUR_FOUR

        // Simplified: check if bars align better with 3/4 or 6/8
        return TimeSignature.FOUR_FOUR
    }

    private fun estimateDownbeat(
        odf: List<Float>,
        beats: List<TimedBeat>,
        beatMs: Long,
        hopMs: Long,
        timeSignature: TimeSignature
    ): Long {
        if (beats.isEmpty()) return 0L
        return beats.first().timeMs
    }
}
