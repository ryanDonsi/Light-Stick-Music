package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

/**
 * BeatDetectorV3 — Dual ODF Architecture
 * 1. odfTempo (순수 ODF): BPM 및 Time Signature 측정용 — V2와 동일한 순수 SuperFlux
 * 2. odfTrack (가중치 ODF): Phase, DP Tracker, Downbeat 측정용 (킥 대역 1.5x 가중치)
 */
object BeatDetectorV3 {

    private const val TAG = "AutoTimelineV3"

    private const val FFT_SIZE  = 2048
    private const val HOP_MS    = 10L
    private const val LOG_LAMBDA = 1000f

    private const val NUM_BANDS = 24
    private const val FB_FMIN   = 27.5f
    private const val FB_FMAX   = 16000f

    // V1 방식 BPM 추정 파라미터 (librosa log-normal prior + half/double-tempo 체크)
    private const val BPM_PRIOR_CENTER_MS    = 500L    // 120 BPM
    private const val BPM_PRIOR_STD_OCTAVE   = 1.0f    // σ = 1 octave
    private const val BPM_HALF_TEMPO_RATIO   = 0.60f   // 반박자 오류 방지 (K-pop 배속 정정)
    private const val BPM_DOUBLE_TEMPO_RATIO = 0.80f   // 두배박자 오류 방지 (발라드 절반속 정정)

    private const val FILL_CONFIDENCE   = 0.20f
    private const val DP_MIN_BEAT_RATIO = 0.25f

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

    enum class BeatSource { LOW, MID, FULL, LOW_MID, MID_FULL, LOW_FULL }

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
        Log.d(TAG, "V3 ------------------ Beat Detection Start ------------------")

        // 투 트랙 ODF 반환: odfTempo(BPM용 순정), odfTrack(트래킹용 킥 가중치)
        val (odfTempo, odfTrack, hopMs, durationMs) = streamingOdf(musicPath)
        if (odfTempo.isEmpty() || odfTrack.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "empty input", 0L, TimeSignature.FOUR_FOUR)
        }

        // BPM 추정: V1 방식 autocorr × log-normal prior + half-tempo 체크 (odfTempo 사용)
        val beatMs = estimateBpmV1Style(odfTempo, hopMs, params.minBeatMs, params.maxBeatMs)

        // [🔥 위상(Phase)과 DP 트래킹은 odfTrack(가중치)를 사용하여 스네어 엇박을 무시합니다]
        val phaseMs = estimatePhaseFromOdf(odfTrack, beatMs, hopMs)
        val dpTimes = dpBeatTracker(odfTrack, beatMs, hopMs, durationMs, anchorMs = phaseMs)

        val expectedBeats = max(1, (durationMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            beats  = fallbackSegmentBeats(odfTrack, hopMs, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        // 구간 클리핑은 전체 에너지를 보는 odfTempo 사용
        val clippedTimes  = clipToAudioContent(beats.map { it.timeMs }.toLongArray(), odfTempo, hopMs, beatMs)
        val clippedBeats  = if (clippedTimes.size < beats.size) {
            val timeSet = clippedTimes.toHashSet()
            beats.filter { it.timeMs in timeSet }
        } else beats

        // 박자표는 odfTempo, 다운비트(1박 강세)는 odfTrack 사용
        val timeSignature = detectTimeSignature(odfTempo, beatMs, hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            clippedBeats.map { it.timeMs }, odfTrack, beatMs, timeSignature.beatsPerBar, hopMs)
        val downbeatOffsetMs = (downbeatMs - (clippedBeats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V3 ------------------ Detect Complete ------------------")

        return DetectResult(
            beats            = clippedBeats,
            beatMs           = beatMs,
            source           = BeatSource.FULL,
            reason           = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature
        )
    }

    private data class FilterBand(val startBin: Int, val weights: FloatArray)

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

    private data class OdfResult(val odfTempo: List<Float>, val odfTrack: List<Float>, val hopMs: Long, val durationMs: Long)

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
                return OdfResult(emptyList(), emptyList(), HOP_MS, 0L)
            }
            extractor.selectTrack(trackIndex)

            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = max(1, (sampleRate * HOP_MS / 1000).toInt())
            val hopMs        = hopSamples * 1000L / sampleRate
            val stepBytes    = channelCount * 2

            val fft        = FloatFFT_1D(FFT_SIZE.toLong())
            val hannWindow = FloatArray(FFT_SIZE) { i ->
                (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
            }
            val numBins    = FFT_SIZE / 2 + 1
            val fftBuf     = FloatArray(FFT_SIZE)
            val curMag     = FloatArray(numBins)
            val filterbank = buildLogFilterbank(sampleRate)

            val curBand    = FloatArray(NUM_BANDS)
            val prevBand   = FloatArray(NUM_BANDS)

            // 두 개의 독립적인 ODF 배열 생성
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
                                                // 순정 에너지는 Tempo 산출용으로 적립
                                                fluxTempo += diff

                                                // 가중치 에너지는 Phase/DP 트래킹용으로 적립
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

            val durationMs = totalSamples * 1000L / sampleRate

            val maxTempo = odfTempo.maxOrNull() ?: 1f
            val normTempo = if (maxTempo > 1e-6f) odfTempo.map { it / maxTempo } else odfTempo

            val maxTrack = odfTrack.maxOrNull() ?: 1f
            val normTrack = if (maxTrack > 1e-6f) odfTrack.map { it / maxTrack } else odfTrack

            OdfResult(normTempo, normTrack, hopMs, durationMs)
        } catch (t: Throwable) {
            Log.e(TAG, "V3 streamingOdf fail: ${t.message}")
            try { codec?.stop() }     catch (_: Throwable) {}
            try { codec?.release() }  catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            OdfResult(emptyList(), emptyList(), HOP_MS, 0L)
        }
    }

    // =========================================================================
    // V1 방식 BPM 추정 — librosa beat_track 동일한 autocorr × log-normal prior
    //
    // autocorr[lag] = mean(odf[i] * odf[i+lag])
    // prior[lag]    = exp(-0.5*(log2(lagMs/500ms)/1octave)^2)
    // score[lag]    = autocorr[lag] * prior[lag]
    // half-tempo 체크: autocorr[halfLag]/autocorr[bestLag] >= 0.60 → 2배 BPM 선택
    // =========================================================================

    private fun estimateBpmV1Style(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Long {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt())
        if (odf.size <= maxLag + 2) return minBeatMs

        val acVals    = FloatArray(maxLag + 1)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestLag   = -1

        for (lag in minLag..maxLag) {
            var sum = 0f; var count = 0
            for (i in 0 until odf.size - lag) { sum += odf[i] * odf[i + lag]; count++ }
            if (count == 0) continue
            val acVal = sum / count
            acVals[lag] = acVal

            val lagMs    = lag * hopMs
            val logRatio = ln(lagMs.toFloat() / BPM_PRIOR_CENTER_MS) / ln(2f)
            val prior    = exp(-0.5f * (logRatio / BPM_PRIOR_STD_OCTAVE) * (logRatio / BPM_PRIOR_STD_OCTAVE))

            val score = acVal * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }

        if (bestLag <= 0) return minBeatMs

        // half-tempo 체크: prior가 느린 BPM 선호 → 2배속이 강하면 빠른 템포 선택
        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val halfAc = acVals[halfLag]
            val bestAc = acVals[bestLag]
            if (bestAc > 0f && halfAc / bestAc >= BPM_HALF_TEMPO_RATIO) {
                val halfMs = halfLag * hopMs
                Log.d(TAG, "V3 halfTempoFix: ${bestLag*hopMs}ms(${60_000L/(bestLag*hopMs)}BPM)" +
                    " → ${halfMs}ms(${60_000L/halfMs}BPM) ratio=${halfAc/bestAc}")
                return halfMs
            }
        }

        // double-tempo 체크: prior가 빠른 BPM 선호 → 절반속이 강하면 느린 템포 선택 (발라드 오탐 방지)
        // 발라드는 킥·스네어가 900ms 간격이나 ODF에서 450ms 교차 상관도 높아 반박자로 오탐
        // K-pop은 하이햇이 지속적으로 ODF를 평탄화해 2배 주기 자기상관이 상대적으로 낮음
        val doubleLag = bestLag * 2
        if (doubleLag <= maxLag) {
            val doubleAc  = acVals[doubleLag]
            val bestAcRef = acVals[bestLag]
            val doubleRatio = if (bestAcRef > 0f) doubleAc / bestAcRef else 0f
            Log.d(TAG, "V3 doubleTempoCheck: ${bestLag*hopMs}ms→${doubleLag*hopMs}ms ratio=$doubleRatio")
            if (doubleRatio >= BPM_DOUBLE_TEMPO_RATIO) {
                val doubleMs = doubleLag * hopMs
                Log.d(TAG, "V3 doubleTempoFix: ${bestLag*hopMs}ms(${60_000L/(bestLag*hopMs)}BPM)" +
                    " → ${doubleMs}ms(${60_000L/doubleMs}BPM) ratio=$doubleRatio")
                return doubleMs
            }
        }

        val resultMs = bestLag * hopMs
        Log.d(TAG, "V3 bpmEstimate: ${resultMs}ms (${60_000L/resultMs} BPM)")
        return resultMs
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
        durationMs: Long, anchorMs: Long = 0L
    ): LongArray {
        if (odf.isEmpty() || targetPeriodMs <= 0L) return LongArray(0)
        val n           = odf.size
        val fpb         = (targetPeriodMs / hopMs).toInt().coerceAtLeast(1)
        val tightness   = 100.0f
        val anchorFrame = if (anchorMs > 0L) (anchorMs / hopMs).toInt().coerceIn(0, n - 1) else -1

        val gaussHalf = fpb; val gaussSize = gaussHalf * 2 + 1
        val gaussWin  = FloatArray(gaussSize) { k ->
            val i = (k - gaussHalf).toFloat()
            exp(-0.5f * (i * 32.0f / fpb) * (i * 32.0f / fpb))
        }
        val localscore = FloatArray(n)
        for (t in 0 until n) {
            var sc = 0f
            for (k in 0 until gaussSize) {
                val idx = t - gaussHalf + k
                if (idx in 0 until n) sc += gaussWin[k] * odf[idx]
            }

            // 전역 위상 관성 추가 (Phase Drift 완화)
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
                val penalty  = tightness * logRatio * logRatio
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
        odf: List<Float>, hopMs: Long, beatMs: Long, durationMs: Long
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
            val segDur   = (eFrame - sFrame).toLong() * hopMs
            val segTimes = dpBeatTracker(segOdf, beatMs, hopMs, segDur, anchorMs = segPhase)
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

        return beats.filter { it >= startMs && it <= cutoffMs }.toLongArray()
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