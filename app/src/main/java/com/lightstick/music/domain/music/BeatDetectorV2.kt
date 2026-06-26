package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

object BeatDetectorV2 {

    private const val TAG = "AutoTimelineV2"

    private const val FFT_SIZE  = 2048
    private const val HOP_MS    = 10L
    private const val LOG_LAMBDA = 1000f

    private const val NUM_BANDS = 24
    private const val FB_FMIN   = 27.5f
    private const val FB_FMAX   = 16000f

    private val cachedHannWindow: FloatArray = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    private const val BPM_PRIOR_CENTER_MS   = 500L
    private const val BPM_PRIOR_STD_OCTAVE  = 1.0f
    private const val BPM_HALF_TEMPO_RATIO  = 0.70f
    private const val BPM_DOUBLE_TEMPO_RATIO = 1.00f
    private const val BPM_SUBBEAT_RATIO_MAX = 0.65f

    private const val FILL_CONFIDENCE   = 0.20f
    private const val DP_MIN_BEAT_RATIO = 0.25f
    private const val DP_TIGHTNESS      = 100.0f

    private const val TIME_SIG_THREE_RATIO   = 1.20f
    private const val TIME_SIG_SIX_RATIO     = 1.25f
    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB    = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    data class TimedBeat(val timeMs: Long, val confidence: Float)

    enum class TimeSignatureType { FOUR_FOUR, THREE_FOUR, SIX_EIGHT }

    data class TimeSignature(
        val type: TimeSignatureType,
        val numerator: Int,
        val denominator: Int
    ) {
        companion object {
            val FOUR_FOUR  = TimeSignature(TimeSignatureType.FOUR_FOUR,  4, 4)
            val THREE_FOUR = TimeSignature(TimeSignatureType.THREE_FOUR, 3, 4)
            val SIX_EIGHT  = TimeSignature(TimeSignatureType.SIX_EIGHT,  6, 8)
        }
        val beatsPerBar: Int get() = numerator
    }

    enum class BeatSource { FULL }

    data class Params(
        val minBeatMs: Long = 280L,
        val maxBeatMs: Long = 1100L,
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

    fun detect(
        musicPath: String,
        params: Params = Params()
    ): DetectResult {
        val songName = musicPath.substringAfterLast("/").substringBeforeLast(".")
        Log.d(TAG, "V2 [$songName] start")

        val (odfTempo, odfTrack, hopMs) = streamingOdf(musicPath)
        if (odfTempo.isEmpty() || odfTrack.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "empty input", 0L, TimeSignature.FOUR_FOUR)
        }

        val beatMs = estimateBpm(odfTempo, hopMs, params.minBeatMs, params.maxBeatMs, songName)
        val phaseMs = estimatePhaseFromOdf(odfTrack, beatMs, hopMs)
        val dpTimes = dpBeatTracker(odfTrack, beatMs, hopMs, anchorMs = phaseMs)

        val expectedBeats = max(1, (odfTrack.size.toLong() * hopMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            beats  = fallbackSegmentBeats(odfTrack, hopMs, beatMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        val clippedTimes  = clipToAudioContent(beats.map { it.timeMs }.toLongArray(), odfTempo, hopMs, beatMs)
        val clippedBeats  = if (clippedTimes.size < beats.size) {
            val timeSet = clippedTimes.toHashSet()
            beats.filter { it.timeMs in timeSet }
        } else beats

        val timeSignature = detectTimeSignature(odfTempo, beatMs, hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            clippedBeats.map { it.timeMs }, odfTrack, beatMs, timeSignature.beatsPerBar, hopMs)
        val downbeatOffsetMs = (downbeatMs - (clippedBeats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V2 complete")

        return DetectResult(
            beats            = clippedBeats,
            beatMs           = beatMs,
            source           = BeatSource.FULL,
            reason           = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature
        )
    }

    private data class FilterBand(val startBin: Int, val weights: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FilterBand) return false
            return startBin == other.startBin && weights.contentEquals(other.weights)
        }

        override fun hashCode(): Int {
            return 31 * startBin + weights.contentHashCode()
        }
    }

    private fun buildLogFilterbank(sampleRate: Int): Array<FilterBand> {
        val numBins  = FFT_SIZE / 2 + 1
        val freqBin  = sampleRate.toFloat() / FFT_SIZE
        val fmax     = minOf(FB_FMAX, sampleRate / 2f)
        val logMin   = ln(FB_FMIN.toDouble())
        val logMax   = ln(fmax.toDouble())
        val centers  = DoubleArray(NUM_BANDS + 2) { k ->
            exp(logMin + k.toDouble() * (logMax - logMin) / (NUM_BANDS + 1))
        }
        return Array(NUM_BANDS) { b ->
            val lo     = centers[b].toFloat()
            val center = centers[b + 1].toFloat()
            val hi     = centers[b + 2].toFloat()
            val sIdx   = ((lo / freqBin).toInt()).coerceAtLeast(0)
            val eIdx   = minOf(numBins - 1, (hi / freqBin).toInt() + 1)
            val w      = FloatArray(eIdx - sIdx + 1) { i ->
                val freq = (sIdx + i) * freqBin
                when {
                    freq <= lo || freq >= hi -> 0f
                    freq <= center           -> (freq - lo) / (center - lo)
                    else                     -> (hi - freq) / (hi - center)
                }
            }
            val norm = w.sum()
            if (norm > 1e-8f) w.forEachIndexed { i, v -> w[i] = v / norm }
            FilterBand(sIdx, w)
        }
    }

    private data class OdfResult(val odfTempo: List<Float>, val odfTrack: List<Float>, val hopMs: Long)

    private fun streamingOdf(musicPath: String): OdfResult {
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
                extractor.release()
                return OdfResult(emptyList(), emptyList(), HOP_MS)
            }
            extractor.selectTrack(trackIndex)

            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = max(1, (sampleRate * HOP_MS / 1000).toInt())
            val hopMs        = hopSamples * 1000L / sampleRate
            val stepBytes    = channelCount * 2

            val fft        = FloatFFT_1D(FFT_SIZE.toLong())
            val hannWindow = cachedHannWindow
            val numBins    = FFT_SIZE / 2 + 1
            val fftBuf     = FloatArray(FFT_SIZE)
            val curMag     = FloatArray(numBins)
            val filterbank = buildLogFilterbank(sampleRate)

            val curBand    = FloatArray(NUM_BANDS)
            val prevBand   = FloatArray(NUM_BANDS)

            val odfTempo   = ArrayList<Float>()
            val odfTrack   = ArrayList<Float>()

            val ringBuf    = FloatArray(FFT_SIZE)
            var ringHead   = 0
            var totalSamples         = 0L
            var samplesUntilNextFrame = FFT_SIZE

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start()
            val bufferInfo  = MediaCodec.BufferInfo()
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
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size); buf.get(chunk)

                            var byteIdx = 0
                            while (byteIdx + stepBytes <= chunk.size) {
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    val lo = chunk[byteIdx + c * 2].toInt() and 0xFF
                                    val hi = chunk[byteIdx + c * 2 + 1].toInt()
                                    monoSum += (hi shl 8 or lo).toShort().toFloat()
                                }
                                val mono = monoSum / channelCount / 32768f

                                ringBuf[ringHead] = mono
                                ringHead = (ringHead + 1) % FFT_SIZE
                                totalSamples++
                                samplesUntilNextFrame--

                                if (samplesUntilNextFrame <= 0) {
                                    samplesUntilNextFrame = hopSamples
                                    val oldest = ringHead
                                    for (i in 0 until FFT_SIZE) {
                                        fftBuf[i] = ringBuf[(oldest + i) % FFT_SIZE] * hannWindow[i]
                                    }
                                    fft.realForward(fftBuf)

                                    curMag[0]           = abs(fftBuf[0])
                                    curMag[numBins - 1] = abs(fftBuf[1])
                                    for (k in 1 until numBins - 1) {
                                        val re = fftBuf[2 * k]; val im = fftBuf[2 * k + 1]
                                        curMag[k] = sqrt(re * re + im * im)
                                    }

                                    for (b in 0 until NUM_BANDS) {
                                        val fb = filterbank[b]
                                        var sum = 0f
                                        for (i in fb.weights.indices) sum += fb.weights[i] * curMag[fb.startBin + i]
                                        curBand[b] = ln(1f + LOG_LAMBDA * sum)
                                    }

                                    if (odfTempo.isEmpty()) {
                                        odfTempo.add(0f)
                                        odfTrack.add(0f)
                                    } else {
                                        var fluxTempo = 0f
                                        var fluxTrack = 0f
                                        for (b in 0 until NUM_BANDS) {
                                            var prevMax = prevBand[b]
                                            if (b > 0 && prevBand[b - 1] > prevMax) prevMax = prevBand[b - 1]
                                            if (b < NUM_BANDS - 1 && prevBand[b + 1] > prevMax) prevMax = prevBand[b + 1]

                                            val diff = curBand[b] - prevMax
                                            if (diff > 0f) {
                                                fluxTempo += diff

                                                val weight = when {
                                                    b <= 5 -> 1.5f   // Kick 대역
                                                    b >= 16 -> 0.5f  // Hi-hat 대역
                                                    else -> 1.0f
                                                }
                                                fluxTrack += diff * weight
                                            }
                                        }
                                        odfTempo.add(fluxTempo)
                                        odfTrack.add(fluxTrack)
                                    }
                                    curBand.copyInto(prevBand)
                                }
                                byteIdx += stepBytes
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop(); codec.release(); extractor.release()

            val maxTempo = odfTempo.maxOrNull() ?: 1f
            val normTempo = if (maxTempo > 1e-6f) odfTempo.map { it / maxTempo } else odfTempo

            val maxTrack = odfTrack.maxOrNull() ?: 1f
            val normTrack = if (maxTrack > 1e-6f) odfTrack.map { it / maxTrack } else odfTrack

            OdfResult(normTempo, normTrack, hopMs)
        } catch (t: Throwable) {
            Log.e(TAG, "V2 streamingOdf fail: ${t.message}")
            try { codec?.stop() }     catch (_: Throwable) {}
            try { codec?.release() }  catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            OdfResult(emptyList(), emptyList(), HOP_MS)
        }
    }


    private fun estimateBpm(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long,
        songName: String = ""
    ): Long {
        val t = if (songName.isNotEmpty()) "[$songName]" else ""
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt())
        if (odf.size <= maxLag + 2) return minBeatMs

        val acVals     = FloatArray(maxLag + 1)
        val priorVals  = FloatArray(maxLag + 1)
        val scoreVals  = FloatArray(maxLag + 1)
        var bestScore  = Float.NEGATIVE_INFINITY
        var bestLag    = -1

        for (lag in minLag..maxLag) {
            var sum = 0f; var count = 0
            for (i in 0 until odf.size - lag) { sum += odf[i] * odf[i + lag]; count++ }
            if (count == 0) continue
            val acVal    = sum / count
            val lagMs    = lag * hopMs
            val logRatio = ln(lagMs.toFloat() / BPM_PRIOR_CENTER_MS) / ln(2f)
            val prior    = exp(-0.5f * (logRatio / BPM_PRIOR_STD_OCTAVE) * (logRatio / BPM_PRIOR_STD_OCTAVE))
            val score    = acVal * prior
            acVals[lag]    = acVal
            priorVals[lag] = prior
            scoreVals[lag] = score
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }

        if (bestLag <= 0) return minBeatMs

        val bestMs  = bestLag * hopMs
        val bestBpm = 60_000L / bestMs
        val bestAc  = acVals[bestLag]
        val bestPrior = priorVals[bestLag]

        val top5 = (minLag..maxLag)
            .sortedByDescending { scoreVals[it] }
            .take(5)
        val top5str = top5.joinToString(" | ") { lag ->
            val ms = lag * hopMs
            "${ms}ms(${60_000L/ms}BPM) ac=%.3f prior=%.3f sc=%.4f".format(
                acVals[lag], priorVals[lag], scoreVals[lag])
        }
        Log.d(TAG, "V2$t TOP5: $top5str")

        Log.d(TAG, "V2$t WINNER: ${bestMs}ms(${bestBpm}BPM) ac=$bestAc prior=$bestPrior score=$bestScore")

        val halfLag = bestLag / 2
        val halfMs  = halfLag * hopMs
        val halfAc  = if (halfLag >= minLag) acVals[halfLag] else run {
            var s = 0f; var c = 0
            for (i in 0 until odf.size - halfLag) { s += odf[i] * odf[i + halfLag]; c++ }
            if (c > 0) s / c else 0f
        }
        val halfRatio = if (bestAc > 0f) halfAc / bestAc else 0f
        Log.d(TAG, "V2$t HALF: lag=${halfMs}ms(${if(halfMs>0) 60_000L/halfMs else 0}BPM)" +
            " ac=$halfAc ratio=$halfRatio  [threshold=${BPM_HALF_TEMPO_RATIO}]" +
            if (halfLag >= minLag) "" else " (below minLag)")

        val doubleLag    = bestLag * 2
        val doubleMs     = doubleLag * hopMs
        val doubleAc     = if (doubleLag <= maxLag) acVals[doubleLag] else 0f
        val doubleRatio  = if (bestAc > 0f) doubleAc / bestAc else 0f
        val subBeatLag   = bestLag / 2   // halfLag와 동일
        val subBeatRatio = halfRatio      // halfRatio와 동일
        if (doubleLag <= maxLag) {
            Log.d(TAG, "V2$t DOUBLE: lag=${doubleMs}ms(${60_000L/doubleMs}BPM)" +
                " ac=$doubleAc doubleRatio=$doubleRatio" +
                " | subBeat=${subBeatLag*hopMs}ms subRatio=$subBeatRatio")
        } else {
            Log.d(TAG, "V2$t DOUBLE: doubleLag=${doubleMs}ms > maxLag=${maxLag*hopMs}ms (범위 초과)" +
                " | subBeat=${subBeatLag*hopMs}ms subRatio=$subBeatRatio")
        }

        val snapBpms = longArrayOf(60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160)
        val priorSnap = snapBpms.joinToString(" ") { bpm ->
            val ms = 60_000L / bpm
            val lag = (ms / hopMs).toInt().coerceIn(minLag, maxLag)
            "${bpm}=${priorVals[lag].let { "%.3f".format(it) }}"
        }
        Log.d(TAG, "V2$t PRIOR_SNAP: $priorSnap")

        // ════════════════════════════════════════════════════════════════════════════════════════
        // 📊 조건 조정용 상세 메트릭 로그 (모든 곡에 대해 기록 - if 조건 밖)
        // ════════════════════════════════════════════════════════════════════════════════════════
        // 모든 곡의 원본 감지 메트릭 기록
        Log.d(TAG, "BEAT_METRICS | song=$songName | bestLag=$bestLag | bestMs=$bestMs | bestBpm=$bestBpm | " +
            "bestAc=$bestAc | bestPrior=$bestPrior | halfLag=$halfLag | halfMs=$halfMs | halfAc=$halfAc | " +
            "halfRatio=$halfRatio | doubleLag=$doubleLag | doubleMs=$doubleMs | doubleAc=$doubleAc | " +
            "doubleRatio=$doubleRatio | subBeatRatio=$subBeatRatio | minLag=$minLag | maxLag=$maxLag | hopMs=$hopMs")

        // 절반비트 감지 가능성 있는 곡들에 대해서만 tempoRatio 계산 및 조건 검사 로그
        if (halfLag >= minLag && bestAc > 0f) {
            val tempoRatio = bestMs.toFloat() / halfMs.toFloat()

            // 조건별 체크 결과 (디버깅용)
            val cond1_tempoOk = tempoRatio in 1.95f..2.05f
            val cond1_halfOk = halfRatio >= 0.68f
            val cond2_halfOk = halfRatio >= BPM_HALF_TEMPO_RATIO
            val cond3_tempoOk = tempoRatio in 1.95f..2.05f
            val cond3_halfOk = halfRatio in 0.45f..0.59999f

            Log.d(TAG, "BEAT_TEMPO_RATIO | song=$songName | tempoRatio=$tempoRatio | " +
                "cond1=${if(cond1_tempoOk && cond1_halfOk) "✓" else "✗"}(tempo=$cond1_tempoOk half=$cond1_halfOk) | " +
                "cond2=${if(cond2_halfOk) "✓" else "✗"}(half=$cond2_halfOk) | " +
                "cond3=${if(cond3_tempoOk && cond3_halfOk) "✓" else "✗"}(tempo=$cond3_tempoOk half=$cond3_halfOk)")
        }

        if (halfLag >= minLag && bestAc > 0f) {
            val tempoRatio = bestMs.toFloat() / halfMs.toFloat()
            val normLag = (bestLag - minLag).toFloat() / (maxLag - minLag).toFloat()

            // ── 명시적 2배 Octave Error (낮은 halfRatio + 강한 doubleRatio 확인) ────
            // Madmom 기준 2배 오류 곡들:
            // - 초혼: tempoRatio=1.98 halfRatio=0.618 doubleRatio=0.951
            // - 진미령: tempoRatio=2.00 halfRatio=0.536 doubleRatio=1.313
            // - 별보러가자: tempoRatio=2.00 halfRatio=0.556 doubleRatio=1.109
            // - TOMBOY: tempoRatio=2.00 halfRatio=0.643 doubleRatio=1.094
            //
            // 보호 대상: "모든 날" (doubleRatio=0, HR=0.654 → 조건 제외)
            //
            // 핵심 메트릭:
            // - 2배 오류는 낮은 halfRatio (< 0.70)
            // - 2배 오류는 강한 doubleRatio (>= 0.95)
            // - doubleRatio=0인 곡은 자동 제외되어 보호됨
            if (tempoRatio in 1.95f..2.05f && halfRatio < 0.70f && doubleRatio >= 0.95f) {
                Log.d(TAG, "V2$t halfTempoFix FIRED (2x octave low halfRatio): ${bestMs}ms(${bestBpm}BPM)" +
                    " → ${halfMs}ms(${if(halfMs>0) 60_000L/halfMs else 0}BPM)" +
                    " halfRatio=$halfRatio tempoRatio=${String.format("%.3f", tempoRatio)} doubleRatio=$doubleRatio")
                return halfMs
            }
        }

        // doubleTempoFix 조건 1: 심각한 2배 오류 (오류율 > 50%)
        val doubleErrorRate = kotlin.math.abs(doubleMs.toFloat() / bestMs.toFloat() - 1.0f) * 100
        if (doubleLag <= maxLag &&
            doubleRatio > 0.5f &&
            subBeatRatio < BPM_SUBBEAT_RATIO_MAX &&
            doubleErrorRate > 50) {
            Log.d(TAG, "V2$t doubleTempoFix CONDITION1 FIRED: ${bestMs}ms(${bestBpm}BPM)" +
                " → ${doubleMs}ms(${60_000L/doubleMs}BPM)" +
                " doubleRatio=$doubleRatio subRatio=$subBeatRatio errorRate=$doubleErrorRate%")
            return doubleMs
        }

        // doubleTempoFix 조건 2: 중간 수준 오류 (25% < 오류율 ≤ 50%)
        if (doubleLag <= maxLag &&
            doubleRatio > 0.70f &&
            subBeatRatio < BPM_SUBBEAT_RATIO_MAX &&
            doubleErrorRate > 25 &&
            doubleErrorRate <= 50) {
            Log.d(TAG, "V2$t doubleTempoFix CONDITION2 FIRED: ${bestMs}ms(${bestBpm}BPM)" +
                " → ${doubleMs}ms(${60_000L/doubleMs}BPM)" +
                " doubleRatio=$doubleRatio subRatio=$subBeatRatio errorRate=$doubleErrorRate%")
            return doubleMs
        }

        Log.d(TAG, "V2$t RESULT: ${bestMs}ms (${bestBpm}BPM)")
        return bestMs
    }

    private fun estimatePhaseFromOdf(odf: List<Float>, beatMs: Long, hopMs: Long): Long {
        val fpb = max(1, (beatMs / hopMs).toInt())
        if (odf.size < fpb * 2) return 0L
        var bestPhase = 0; var bestScore = Float.NEGATIVE_INFINITY
        for (ph in 0 until fpb) {
            var score = 0f; var f = ph
            while (f < odf.size) { score += odf[f]; f += fpb }
            if (score > bestScore) { bestScore = score; bestPhase = ph }
        }
        return bestPhase.toLong() * hopMs
    }

    private fun dpBeatTracker(
        odf: List<Float>, targetPeriodMs: Long, hopMs: Long,
        anchorMs: Long = 0L
    ): LongArray {
        if (odf.isEmpty() || targetPeriodMs <= 0L) return LongArray(0)
        val n           = odf.size
        val fpb         = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
        val anchorFrame = if (anchorMs > 0L) (anchorMs / hopMs).toInt().coerceIn(0, n - 1) else -1

        val gaussSize = fpb * 2 + 1
        val gaussWin  = FloatArray(gaussSize) { k ->
            val i = (k - fpb).toFloat()
            exp(-0.5f * (i * 32.0f / fpb) * (i * 32.0f / fpb))
        }
        val localscore = FloatArray(n)
        for (t in 0 until n) {
            var sc = 0f
            for (k in 0 until gaussSize) {
                val idx = t - fpb + k
                if (idx in 0 until n) sc += gaussWin[k] * odf[idx]
            }

            if (anchorFrame >= 0) {
                val distanceToGrid = abs(t - anchorFrame) % fpb
                val phaseDiff = min(distanceToGrid, fpb - distanceToGrid)
                if (phaseDiff <= fpb / 4) {
                    val phaseBonus = 1.0f + 0.2f * (1f - (phaseDiff.toFloat() / (fpb / 4f)))
                    sc *= phaseBonus
                }
            }
            localscore[t] = sc
        }

        val cumscore    = FloatArray(n) { Float.NEGATIVE_INFINITY }
        val prev        = IntArray(n)  { -1 }
        val searchRange = fpb * 2

        for (t in 0 until n) {
            val pLo = max(0, t - searchRange); val pHi = max(0, t - max(1, fpb / 2))
            var bestVal = Float.NEGATIVE_INFINITY
            for (p in pLo..pHi) {
                val isFreeStart = (p == 0 || p == anchorFrame)
                if (cumscore[p] == Float.NEGATIVE_INFINITY && !isFreeStart) continue
                val pScore   = if (isFreeStart) 0f else cumscore[p]
                val lag      = (t - p).toFloat().coerceAtLeast(1f)
                val logRatio = ln(lag / fpb)
                val penalty  = DP_TIGHTNESS * logRatio * logRatio
                val cand     = pScore - penalty
                if (cand > bestVal) { bestVal = cand; prev[t] = p }
            }
            cumscore[t] = if (bestVal == Float.NEGATIVE_INFINITY) localscore[t]
            else bestVal + localscore[t]
        }

        var t     = cumscore.indices.maxByOrNull { cumscore[it] } ?: return LongArray(0)
        val beats = mutableListOf<Long>()
        var iter  = 0
        while (t > 0 && iter < n) {
            beats.add(t.toLong() * hopMs)
            val p = prev[t]; if (p < 0 || p == t) break
            t = p; iter++
        }

        val result = beats.reversed().toLongArray()
        if (result.size < 2) return result

        val rms = sqrt(localscore.map { it * it }.average().toFloat()); val trimTh = 0.15f * rms
        var ss = 0
        while (ss < result.size && localscore[(result[ss] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) ss++
        var e = result.size - 1
        while (e > ss && localscore[(result[e] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) e--
        return if (ss > e) result else result.sliceArray(ss..e)
    }

    private fun fallbackSegmentBeats(
        odf: List<Float>, hopMs: Long, beatMs: Long
    ): List<TimedBeat> {
        val segmentMs = 20_000L
        val segFrames = (segmentMs / hopMs).toInt().coerceAtLeast(1)
        val result    = ArrayList<TimedBeat>()
        var segIdx    = 0
        while (segIdx * segFrames < odf.size) {
            val sFrame = segIdx * segFrames
            val eFrame = min(odf.size, sFrame + segFrames)
            if (eFrame - sFrame < 8) { segIdx++; continue }
            val segOdf   = odf.subList(sFrame, eFrame)
            val segPhase = estimatePhaseFromOdf(segOdf, beatMs, hopMs)
            val segTimes = dpBeatTracker(segOdf, beatMs, hopMs, anchorMs = segPhase)
            val offset   = sFrame.toLong() * hopMs
            segTimes.forEach { result += TimedBeat(offset + it, FILL_CONFIDENCE) }
            segIdx++
        }
        return result.sortedBy { it.timeMs }
    }

    private fun clipToAudioContent(beats: LongArray, odf: List<Float>, hopMs: Long, beatMs: Long): LongArray {
        if (beats.isEmpty() || odf.size < 4) return beats
        val fpb = max(1, (beatMs / hopMs).toInt())
        val win = fpb * 2

        val envelope = FloatArray(odf.size) { i ->
            val s = max(0, i - win / 2); val e = min(odf.size - 1, i + win / 2)
            var sum = 0f; for (k in s..e) sum += odf[k]; sum / (e - s + 1)
        }
        val globalMean = envelope.average().toFloat().coerceAtLeast(1e-9f)
        val silentTh   = 0.08f * globalMean

        var firstActive = 0
        while (firstActive < odf.size - fpb && envelope[firstActive] < silentTh) firstActive++

        var lastActive = odf.size - 1
        while (lastActive > fpb && envelope[lastActive] < silentTh) lastActive--

        val startMs  = max(0L, firstActive.toLong() * hopMs - beatMs)
        val cutoffMs = lastActive.toLong() * hopMs + beatMs

        return beats.filter { it in startMs..cutoffMs }.toLongArray()
    }

    private fun detectTimeSignature(odf: List<Float>, beatMs: Long, hopMs: Long): TimeSignature {
        if (odf.size < 8 || beatMs <= 0L) return TimeSignature.FOUR_FOUR
        val bf    = (beatMs / hopMs).toInt().coerceAtLeast(1)
        val corr3 = lagCorr(odf, bf * 3); val corr4 = lagCorr(odf, bf * 4); val corr6 = lagCorr(odf, bf * 6)
        return when {
            corr3 > corr4 * TIME_SIG_THREE_RATIO                          -> TimeSignature.THREE_FOUR
            corr6 > corr4 * TIME_SIG_SIX_RATIO && corr3 > corr4 * 0.85f -> TimeSignature.SIX_EIGHT
            else                                                           -> TimeSignature.FOUR_FOUR
        }
    }

    private fun lagCorr(odf: List<Float>, lag: Int): Float {
        if (lag <= 0 || lag >= odf.size) return 0f
        var sum = 0f; var i = 0
        while (i + lag < odf.size) { sum += odf[i] * odf[i + lag]; i++ }
        return sum / i.toFloat().coerceAtLeast(1f)
    }

    private fun detectDownbeatEnhanced(
        beatTimesMs: List<Long>, odf: List<Float>,
        beatMs: Long, beatsPerBar: Int, hopMs: Long
    ): Long {
        if (beatTimesMs.isEmpty() || beatMs <= 0L) return 0L
        if (beatTimesMs.size < beatsPerBar) return beatTimesMs.first()

        val phaseSum = FloatArray(beatsPerBar); val phaseCnt = IntArray(beatsPerBar)
        for (i in beatTimesMs.indices) {
            val ph = i % beatsPerBar
            val fr = (beatTimesMs[i] / hopMs).toInt().coerceIn(0, odf.lastIndex)
            phaseSum[ph] += odf[fr]; phaseCnt[ph]++
        }
        val avgEnergy = FloatArray(beatsPerBar) { p ->
            if (phaseCnt[p] > 0) phaseSum[p] / phaseCnt[p] else 0f
        }

        val barFrames = ((beatMs * beatsPerBar) / hopMs).toInt().coerceAtLeast(1)
        val combScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val anchor = (beatTimesMs.getOrElse(ph) { ph.toLong() * beatMs } / hopMs).toInt()
            var k = anchor; var sum = 0f; var cnt = 0
            while (k < odf.size) { sum += odf[k.coerceIn(0, odf.lastIndex)]; cnt++; k += barFrames }
            k = anchor - barFrames
            while (k >= 0) { sum += odf[k.coerceIn(0, odf.lastIndex)]; cnt++; k -= barFrames }
            combScore[ph] = if (cnt > 0) sum / cnt else 0f
        }

        val consistScore = FloatArray(beatsPerBar)
        for (ph in 0 until beatsPerBar) {
            val energies = beatTimesMs.filterIndexed { i, _ -> i % beatsPerBar == ph }
                .map { t -> odf[(t / hopMs).toInt().coerceIn(0, odf.lastIndex)] }
            if (energies.size >= 2) {
                val avg = energies.average().toFloat()
                val std = sqrt(energies.map { (it - avg) * (it - avg) }.average().toFloat())
                val cv  = if (avg > 0.001f) std / avg else 1f
                consistScore[ph] = ((1f - cv.coerceIn(0f, 1f)) * avg).coerceAtLeast(0f)
            }
        }

        fun FloatArray.normMax(): FloatArray {
            val mx = maxOrNull() ?: return this
            return if (mx > 0.001f) FloatArray(size) { this[it] / mx } else this
        }
        val bestPhase = (0 until beatsPerBar).maxByOrNull { p ->
            avgEnergy.normMax()[p] * DOWNBEAT_W_LOW_ENERGY +
                    combScore.normMax()[p]  * DOWNBEAT_W_BAR_COMB  +
                    consistScore.normMax()[p] * DOWNBEAT_W_CONSISTENCY
        } ?: 0

        return beatTimesMs.getOrElse(bestPhase) { beatTimesMs.first() }
    }
}
