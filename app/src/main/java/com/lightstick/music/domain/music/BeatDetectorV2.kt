package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

/**
 * BeatDetectorV2 — madmom 풀 파이프라인 (SuperFlux ODF + DBN HMM + Ellis DP)
 *
 * ODF:
 *   PCM 디코딩과 동시에 STFT 계산 (madmom 스트리밍 방식 — PCM 배열을 메모리에 쌓지 않음)
 *   ring buffer (FFT_SIZE) → Hanning windowed FFT → log magnitude: log(1 + 1000|X|)
 *   → 이전 프레임에 ±1 bin max filter 적용
 *   → positive spectral flux = SuperFlux ODF (Böck & Widmer, 2013)
 *
 * BPM + 위상:
 *   DBN HMM Forward (Viterbi-max 근사, madmom DBNBeatTrackingProcessor 방식)
 *   observationLambda=16 (madmom 기본값, SuperFlux 피크 선명도에 적합)
 *
 * Beat 출력:
 *   DBN으로 결정된 tempo + comb-phase 위상 앵커 → Ellis DP beat tracking
 */
object BeatDetectorV2 {

    private const val TAG = "AutoTimeline_BeatDetectorV2"

    // SuperFlux 파라미터 (madmom 기본값과 동일)
    private const val FFT_SIZE  = 2048
    private const val HOP_MS    = 10L     // madmom fps=100 → 10ms hop
    private const val LOG_LAMBDA = 1000f

    // Log filterbank 파라미터 (madmom SuperFluxProcessor 기본값)
    private const val NUM_BANDS = 24
    private const val FB_FMIN   = 27.5f   // Hz — 피아노 A0
    private const val FB_FMAX   = 16000f  // Hz

    // DBN 파라미터 (madmom DBNBeatTrackingProcessor 기본값)
    private const val DBN_TRANSITION_LAMBDA  = 100f
    private const val DBN_OBSERVATION_LAMBDA = 16

    // 하모닉 보정 비율: 0.5=절반 주기(2배 BPM)
    // 0.75도 My World(550→410ms), God's Menu(770→580ms), 모든날(890→670ms) 파괴하므로 제거
    private val HARM_RATIOS = floatArrayOf(0.5f)

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
        val minBeatMs: Long = 280L,   // madmom min_bpm=55 → ~1090ms, max_bpm=215 → ~280ms
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

    // =========================================================================
    // Public entry point — 파일 경로 입력 (madmom 스트리밍 방식)
    // =========================================================================

    fun detect(
        musicPath: String,
        params: Params = Params()
    ): DetectResult {
        // ── 1. 스트리밍 디코딩 + SuperFlux ODF 동시 계산 ─────────────────
        val (odf, hopMs, durationMs) = streamingOdf(musicPath)
        if (odf.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "empty input", 0L, TimeSignature.FOUR_FOUR)
        }
        Log.d(TAG, "V2 SuperFlux odf frames=${odf.size} hopMs=$hopMs durationMs=$durationMs")

        // ── 2. DBN HMM 템포 추정 ─────────────────────────────────────────
        val beatMs = dbnEstimateTempo(
            odf, hopMs, params.minBeatMs, params.maxBeatMs,
            DBN_TRANSITION_LAMBDA, DBN_OBSERVATION_LAMBDA
        )
        Log.d(TAG, "V2 beatMs=$beatMs (${60_000L / beatMs} BPM)")

        // ── 3. 위상 추정 (comb-phase) ──────────────────────────────────────
        val phaseMs = estimatePhaseFromOdf(odf, beatMs, hopMs)
        Log.d(TAG, "V2 phaseMs=$phaseMs")

        // ── 4. Global Ellis DP ────────────────────────────────────────────
        val dpTimes = dpBeatTracker(odf, beatMs, hopMs, durationMs, anchorMs = phaseMs)
        Log.d(TAG, "V2 dpTimes=${dpTimes.size}")

        val expectedBeats = max(1, (durationMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            Log.w(TAG, "V2 DP insufficient (${dpTimes.size}/$expectedBeats) → segment fallback")
            beats  = fallbackSegmentBeats(odf, hopMs, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V2 detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        // ── 5. 페이드아웃/무음 구간 비트 제거 ───────────────────────────────
        val clippedTimes  = clipToAudioContent(beats.map { it.timeMs }.toLongArray(), odf, hopMs, beatMs)
        val clippedBeats  = if (clippedTimes.size < beats.size) {
            val timeSet = clippedTimes.toHashSet()
            beats.filter { it.timeMs in timeSet }
                 .also { Log.d(TAG, "V2 clipToContent: ${beats.size}→${it.size} beats") }
        } else beats

        val timeSignature = detectTimeSignature(odf, beatMs, hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            clippedBeats.map { it.timeMs }, odf, beatMs, timeSignature.beatsPerBar, hopMs)
        val downbeatOffsetMs = (downbeatMs - (clippedBeats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        Log.d(TAG, "V2 OK beats=${clippedBeats.size} beatMs=$beatMs timeSig=${timeSignature.type} reason=$reason")

        return DetectResult(
            beats            = clippedBeats,
            beatMs           = beatMs,
            source           = BeatSource.FULL,
            reason           = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature
        )
    }

    // =========================================================================
    // 스트리밍 SuperFlux ODF — 디코딩과 STFT를 단일 패스로 처리
    //
    // madmom과 동일한 방식:
    //   ring buffer(FFT_SIZE) 유지 → hopSamples마다 FFT 계산
    //   PCM 배열을 메모리에 쌓지 않으므로 메모리 사용량 최소화
    // =========================================================================

    // =========================================================================
    // Log Filterbank — madmom SuperFluxProcessor와 동일한 로그 주파수 삼각 필터뱅크
    // =========================================================================

    private data class FilterBand(val startBin: Int, val weights: FloatArray)

    private fun buildLogFilterbank(sampleRate: Int): Array<FilterBand> {
        val numBins  = FFT_SIZE / 2 + 1
        val freqBin  = sampleRate.toFloat() / FFT_SIZE
        val fmax     = minOf(FB_FMAX, sampleRate / 2f)
        val logMin   = ln(FB_FMIN.toDouble())
        val logMax   = ln(fmax.toDouble())
        // NUM_BANDS + 2 경계 포함 중심점 (삼각 필터의 lo/center/hi)
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

    private data class OdfResult(val odf: List<Float>, val hopMs: Long, val durationMs: Long)

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
                return OdfResult(emptyList(), HOP_MS, 0L)
            }
            extractor.selectTrack(trackIndex)

            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = max(1, (sampleRate * HOP_MS / 1000).toInt())
            val hopMs        = hopSamples * 1000L / sampleRate
            val stepBytes    = channelCount * 2

            // FFT / ODF 상태
            val fft        = FloatFFT_1D(FFT_SIZE.toLong())
            val hannWindow = FloatArray(FFT_SIZE) { i ->
                (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
            }
            val numBins    = FFT_SIZE / 2 + 1
            val fftBuf     = FloatArray(FFT_SIZE)
            val curMag     = FloatArray(numBins)   // raw magnitude (필터뱅크 입력)
            val filterbank = buildLogFilterbank(sampleRate)
            val curBand    = FloatArray(NUM_BANDS)
            val prevBand   = FloatArray(NUM_BANDS)
            val odf        = ArrayList<Float>()

            // ring buffer — 마지막 FFT_SIZE 샘플을 순환 저장
            val ringBuf    = FloatArray(FFT_SIZE)
            var ringHead   = 0               // 다음 쓰기 위치
            var totalSamples         = 0L
            var samplesUntilNextFrame = FFT_SIZE  // 첫 프레임은 FFT_SIZE 샘플 필요

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
                                // 스테레오 → 모노 다운믹스
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    val lo = chunk[byteIdx + c * 2].toInt() and 0xFF
                                    val hi = chunk[byteIdx + c * 2 + 1].toInt()
                                    monoSum += (hi shl 8 or lo).toShort().toFloat()
                                }
                                val mono = monoSum / channelCount / 32768f

                                // ring buffer에 저장
                                ringBuf[ringHead] = mono
                                ringHead = (ringHead + 1) % FFT_SIZE
                                totalSamples++
                                samplesUntilNextFrame--

                                if (samplesUntilNextFrame <= 0) {
                                    samplesUntilNextFrame = hopSamples

                                    // ring buffer에서 현재 윈도우 추출 + Hanning 적용
                                    val oldest = ringHead  // ringHead가 가장 오래된 샘플 위치
                                    for (i in 0 until FFT_SIZE) {
                                        fftBuf[i] = ringBuf[(oldest + i) % FFT_SIZE] * hannWindow[i]
                                    }
                                    fft.realForward(fftBuf)

                                    // raw magnitude (필터뱅크 입력 — log 압축 전)
                                    curMag[0]           = abs(fftBuf[0])
                                    curMag[numBins - 1] = abs(fftBuf[1])
                                    for (k in 1 until numBins - 1) {
                                        val re = fftBuf[2 * k]; val im = fftBuf[2 * k + 1]
                                        curMag[k] = sqrt(re * re + im * im)
                                    }

                                    // Log filterbank: magnitude → 24 bands → log(1+λ×band)
                                    for (b in 0 until NUM_BANDS) {
                                        val fb = filterbank[b]
                                        var sum = 0f
                                        for (i in fb.weights.indices) sum += fb.weights[i] * curMag[fb.startBin + i]
                                        curBand[b] = ln(1f + LOG_LAMBDA * sum)
                                    }

                                    if (odf.isEmpty()) {
                                        odf.add(0f)  // 첫 프레임은 이전 프레임 없음
                                    } else {
                                        // SuperFlux: ±1 band max filter → positive flux
                                        var flux = 0f
                                        for (b in 0 until NUM_BANDS) {
                                            var prevMax = prevBand[b]
                                            if (b > 0 && prevBand[b - 1] > prevMax) prevMax = prevBand[b - 1]
                                            if (b < NUM_BANDS - 1 && prevBand[b + 1] > prevMax) prevMax = prevBand[b + 1]
                                            val diff = curBand[b] - prevMax
                                            if (diff > 0f) flux += diff
                                        }
                                        odf.add(flux)
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

            // 전역 정규화 → [0, 1]
            val maxVal = odf.maxOrNull() ?: 1f
            val normOdf = if (maxVal > 1e-6f) odf.map { it / maxVal } else odf

            OdfResult(normOdf, hopMs, durationMs)
        } catch (t: Throwable) {
            Log.e(TAG, "V2 streamingOdf fail: ${t.message}")
            try { codec?.stop() }     catch (_: Throwable) {}
            try { codec?.release() }  catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            OdfResult(emptyList(), HOP_MS, 0L)
        }
    }

    // =========================================================================
    // DBN 템포 추정 — madmom DBNBeatTrackingProcessor 핵심 알고리즘
    // =========================================================================

    private fun dbnEstimateTempo(
        odf: List<Float>,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long,
        transitionLambda: Float = DBN_TRANSITION_LAMBDA,
        observationLambda: Int  = DBN_OBSERVATION_LAMBDA
    ): Long {
        val minInterval = max(1, (minBeatMs / hopMs).toInt())
        val maxInterval = max(minInterval + 1, (maxBeatMs / hopMs).toInt())
        val intervals   = IntArray(maxInterval - minInterval + 1) { minInterval + it }
        val numIntv     = intervals.size
        if (numIntv == 0 || odf.size < minInterval * 2) return minBeatMs

        val LOG_BPM_CENTER = ln(120f)
        val LOG_BPM_SX2    = 2f * 0.5f * 0.5f  // σ=0.5 (log-BPM) — 옥타브 오류 강하게 방지

        val totalStates    = intervals.sum()
        val stateIntv      = IntArray(totalStates)
        val statePos       = IntArray(totalStates)
        val stateIntvIdx   = IntArray(totalStates)
        val intvStartState = IntArray(numIntv)

        var s = 0
        for (ii in 0 until numIntv) {
            intvStartState[ii] = s
            for (p in 0 until intervals[ii]) {
                stateIntv[s] = intervals[ii]; statePos[s] = p; stateIntvIdx[s] = ii; s++
            }
        }

        val bbLogTrans = Array(numIntv) { fromII ->
            val fi  = intervals[fromII].toFloat()
            val raw = FloatArray(numIntv) { toII ->
                -transitionLambda * abs(intervals[toII].toFloat() / fi - 1f)
            }
            val maxR = raw.max(); var sumE = 0.0
            for (v in raw) sumE += exp((v - maxR).toDouble())
            val logZ = maxR + ln(sumE.toFloat())
            FloatArray(numIntv) { toII -> raw[toII] - logZ }
        }

        val LOG_ZERO    = -1e9f

        // Initial distribution: log-BPM prior (tempos near 120 BPM favored)
        val logInitDist = FloatArray(numIntv) { ii ->
            val bpm   = 60_000f / intervals[ii].toFloat()
            val d     = ln(bpm) - LOG_BPM_CENTER
            val prior = exp(-(d * d) / LOG_BPM_SX2.toDouble()).toFloat()
            ln(prior.coerceAtLeast(1e-6f))
        }
        val initMax = logInitDist.max(); var initSumE = 0.0
        for (v in logInitDist) initSumE += exp((v - initMax).toDouble())
        val initLogZ = initMax + ln(initSumE.toFloat())
        for (i in logInitDist.indices) logInitDist[i] -= initLogZ

        var logFwd = FloatArray(totalStates) { LOG_ZERO }
        for (ii in 0 until numIntv) {
            logFwd[intvStartState[ii]] = logInitDist[ii]  // log-BPM prior를 초기 분포에 직접 적용
        }

        val intvBeatAccum = FloatArray(numIntv)
        val n = odf.size

        for (t in 0 until n) {
            val act        = odf[t].coerceIn(1e-6f, 1f - 1e-6f)
            val logBeat    = ln(act)
            val logNonBeat = ln((1f - act) / (observationLambda - 1).toFloat())

            val lastLogFwd = FloatArray(numIntv) { ii ->
                logFwd[intvStartState[ii] + intervals[ii] - 1]
            }

            val logFwdNew = FloatArray(totalStates) { LOG_ZERO }
            for (st in 0 until totalStates) {
                val p      = statePos[st]
                val logObs = if (p == 0) logBeat else logNonBeat
                val logPrev = if (p > 0) {
                    logFwd[st - 1]
                } else {
                    val toII = stateIntvIdx[st]
                    var maxVal = LOG_ZERO
                    for (fromII in 0 until numIntv) {
                        val cand = lastLogFwd[fromII] + bbLogTrans[fromII][toII]
                        if (cand > maxVal) maxVal = cand
                    }
                    maxVal
                }
                if (logPrev > LOG_ZERO) logFwdNew[st] = logPrev + logObs
            }

            val peak = logFwdNew.max()
            if (peak > LOG_ZERO) for (i in logFwdNew.indices) {
                if (logFwdNew[i] > LOG_ZERO) logFwdNew[i] -= peak
            }
            logFwd = logFwdNew

            for (ii in 0 until numIntv) {
                val bss = intvStartState[ii]
                if (logFwd[bss] > LOG_ZERO) intvBeatAccum[ii] += exp(logFwd[bss].toDouble()).toFloat()
            }
        }

        var bestII = 0
        for (ii in 1 until numIntv) {
            val cntI = (n.toFloat() / intervals[ii]).coerceAtLeast(1f)
            val cntB = (n.toFloat() / intervals[bestII]).coerceAtLeast(1f)
            if (intvBeatAccum[ii] / cntI > intvBeatAccum[bestII] / cntB) bestII = ii
        }

        fun combPriorScore(beatMs: Long): Float {
            val fpb = max(1, (beatMs / hopMs).toInt())
            if (odf.size < fpb * 2) return 0f
            var best = Float.NEGATIVE_INFINITY
            for (ph in 0 until fpb) {
                var sc = 0f; var f = ph
                while (f < odf.size) { sc += odf[f]; f += fpb }
                if (sc > best) best = sc
            }
            // 비트 수로 정규화 — 미정규화 합산은 주기가 짧을수록 항상 유리해져
            // 2x BPM 오류 시 400ms(2배 비트)가 800ms보다 합산이 2배 높아 무조건 이김
            val expectedBeats = (odf.size.toFloat() / fpb.toFloat()).coerceAtLeast(1f)
            val normalizedBest = best / expectedBeats
            val bpm   = 60_000f / beatMs.toFloat()
            val d     = ln(bpm) - LOG_BPM_CENTER
            val prior = exp(-(d * d) / LOG_BPM_SX2.toDouble()).toFloat()
            return normalizedBest * prior
        }

        val resultMs   = intervals[bestII].toLong() * hopMs
        var bestCorrMs = resultMs
        var bestCorrPS = combPriorScore(resultMs)

        for (r in HARM_RATIOS) {
            if (r == 0.5f && resultMs < 910L) continue
            val frames = ((resultMs.toFloat() * r) / hopMs.toFloat() + 0.5f).toInt().coerceAtLeast(1)
            val cMs = frames.toLong() * hopMs
            if (cMs < minBeatMs || cMs > maxBeatMs) continue
            val ps = combPriorScore(cMs)
            if (ps > bestCorrPS) { bestCorrPS = ps; bestCorrMs = cMs }
        }

        if (bestCorrMs != resultMs)
            Log.d(TAG, "V2 dbnTempo harmonic fix: ${resultMs}ms→${bestCorrMs}ms " +
                "(${60_000L / resultMs}→${60_000L / bestCorrMs} BPM)")
        Log.d(TAG, "V2 dbnTempo: ${bestCorrMs}ms (${60_000L / bestCorrMs} BPM)")
        return bestCorrMs
    }

    // =========================================================================
    // 위상 추정 — comb-phase scoring
    // =========================================================================

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

    // =========================================================================
    // Ellis DP Beat Tracker
    // =========================================================================

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
        // 트림: 0.15×RMS — 기존 0.5×RMS는 Magnetic처럼 에너지 편차가 큰 곡에서 앞부분을 과도하게 제거했음
        val rms = sqrt(localscore.map { it * it }.average().toFloat()); val trimTh = 0.15f * rms
        var ss = 0
        while (ss < result.size && localscore[(result[ss] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) ss++
        var e = result.size - 1
        while (e > ss && localscore[(result[e] / hopMs).toInt().coerceIn(0, n - 1)] < trimTh) e--
        return if (ss > e) result else result.sliceArray(ss..e)
    }

    // =========================================================================
    // Fallback — ODF 세그먼트 단위 DP (PCM 불필요, ODF subList 사용)
    // =========================================================================

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

    // =========================================================================
    // 페이드아웃/무음 구간 비트 클리핑
    //   sliding mean(2비트 창)으로 에너지 엔벨로프 계산 →
    //   전체 평균의 8% 이하가 1비트 이상 지속되는 지점을 오디오 종료로 판단
    // =========================================================================

    private fun clipToAudioContent(beats: LongArray, odf: List<Float>, hopMs: Long, beatMs: Long): LongArray {
        if (beats.isEmpty() || odf.size < 4) return beats
        val fpb = max(1, (beatMs / hopMs).toInt())
        val win = fpb * 2  // sliding mean window = 2 beats

        // sliding mean envelope
        val envelope = FloatArray(odf.size) { i ->
            val s = max(0, i - win / 2); val e = min(odf.size - 1, i + win / 2)
            var sum = 0f; for (k in s..e) sum += odf[k]; sum / (e - s + 1)
        }
        val globalMean = envelope.average().toFloat().coerceAtLeast(1e-9f)
        val silentTh   = 0.08f * globalMean  // 전체 평균 8% 이하 = 무음/페이드아웃

        // 앞쪽: 첫 활성 프레임
        var firstActive = 0
        while (firstActive < odf.size - fpb && envelope[firstActive] < silentTh) firstActive++

        // 뒤쪽: 마지막 활성 프레임
        var lastActive = odf.size - 1
        while (lastActive > fpb && envelope[lastActive] < silentTh) lastActive--

        val startMs  = max(0L, firstActive.toLong() * hopMs - beatMs)
        val cutoffMs = lastActive.toLong() * hopMs + beatMs  // 1비트 여유

        return beats.filter { it >= startMs && it <= cutoffMs }.toLongArray()
    }

    // =========================================================================
    // 박자표 감지
    // =========================================================================

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

    // =========================================================================
    // 다운비트 감지
    // =========================================================================

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
