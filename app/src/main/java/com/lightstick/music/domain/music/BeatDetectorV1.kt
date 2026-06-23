package com.lightstick.music.domain.music

import android.util.Log
import kotlin.math.*

/**
 * BeatDetectorV1 (= 버전 14)
 *
 * librosa beat_track 충실 재현. V4 의 두 가지 근본 결함을 해소:
 *
 * 문제 A — BPM 오추정 (V4 8-seed comb filter)
 *   V4: 8개 이산 씨드(80/90/100/110/120/125/140/160 BPM)만 채점
 *   → 비표준 BPM(74/117.5/126/147.7 등)을 잡지 못함 → 30-60% BPM 오차
 *   수정: 모든 lag 에 대해 autocorrelation 연속 스윕 + log-normal prior 직접 곱셈
 *         harmonic folding 규칙 제거 → prior 가 octave 해소
 *         (librosa: ac_df * logprior, prior = exp(-0.5*(log2(bpm/120)/1octave)^2))
 *
 * 문제 B — 세그먼트 비트가 DP 를 덮어씀 (V4 segment merge)
 *   V4: segment 감지 비트(confidence≈0.5~1.0)가 DP FILL 비트(0.20) override
 *   → 잘못된 위상·BPM 의 세그먼트 비트가 전곡 DP 를 무력화
 *   수정: 전곡 DP 결과를 주 출력으로 사용, 세그먼트 루프는 DP 실패 시 fallback 만 사용
 *
 * 유지:
 *   - Multi-band flux ODF + localNormalizeMean (V4 Fix E)
 *   - Ellis DP, Gaussian local scoring, tightness=100 (V4 Rev3)
 *   - 고정 위상 보정 제거 (DP 가 자연스럽게 최적 위상 탐색)
 */
object BeatDetectorV1 {

    private const val TAG = "AutoTimeline_BeatDetectorV1"

    private const val FILL_CONFIDENCE = 0.20f

    private const val LOCAL_NORM_WINDOW  = 60
    private const val GLOBAL_NORM_WINDOW = 80

    private const val TIME_SIG_THREE_RATIO = 1.20f
    private const val TIME_SIG_SIX_RATIO   = 1.25f

    private const val DOWNBEAT_W_LOW_ENERGY  = 0.50f
    private const val DOWNBEAT_W_BAR_COMB    = 0.30f
    private const val DOWNBEAT_W_CONSISTENCY = 0.20f

    // log-normal prior 중심: 120 BPM (500ms), std: 1 octave (librosa default)
    private const val PRIOR_CENTER_MS  = 500L
    private const val PRIOR_STD_OCTAVE = 1.0f

    // half-tempo 체크: autocorr[halfLag] / autocorr[bestLag] >= 이 값이면 빠른 템포 선택
    // prior 가 느린 BPM 쪽으로 과도하게 치우쳐 TOMBOY/Stars 등 140+ BPM 곡에서 반박자 오류 방지
    private const val HALF_TEMPO_RATIO = 0.60f

    // DP 실패 판단 기준: 예상 비트 수의 25% 미만이면 fallback
    private const val DP_MIN_BEAT_RATIO = 0.25f

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
        val hopMs: Long             = 50L,
        val minBeatMs: Long         = 375L,   // 160 BPM
        val maxBeatMs: Long         = 1000L,  // 60 BPM
        val minPeakDistanceMs: Long = 120L,
        val onsetSmoothWindow: Int  = 3,
        val segmentMs: Long         = 20_000L,
        val peakThresholdK: Float   = 0.22f,
        val minPeakAbs: Float       = 0.04f,
        val snapToleranceMs: Long   = 130L,
        val chainToleranceMs: Long  = 150L,
        val minChainCount: Int      = 3,
        val continuityBonus: Float  = 0.08f
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

    // IIR 계수 (AutoTimelineGeneratorBeat_v0 와 동일)
    private const val LOW_ALPHA     = 0.12f
    private const val MID_LP1_ALPHA = 0.35f
    private const val MID_LP2_ALPHA = 0.08f

    fun detectPcm(
        monoSamples: FloatArray,
        sampleRate: Int,
        params: Params = Params()
    ): DetectResult {
        if (monoSamples.isEmpty() || sampleRate <= 0) {
            return DetectResult(emptyList(), 0L, null, "empty pcm", 0L, TimeSignature.FOUR_FOUR)
        }
        val hopSamples = max(1, (sampleRate * params.hopMs / 1000).toInt())
        val numFrames  = monoSamples.size / hopSamples
        val outLow  = ArrayList<Float>(numFrames)
        val outMid  = ArrayList<Float>(numFrames)
        val outFull = ArrayList<Float>(numFrames)

        var lowZ = 0f; var midLP1 = 0f; var midLP2 = 0f
        var lowSumSq = 0f; var midSumSq = 0f; var fullSumSq = 0f; var winPos = 0

        for (sample in monoSamples) {
            lowZ   += LOW_ALPHA     * (sample - lowZ)
            midLP1 += MID_LP1_ALPHA * (sample - midLP1)
            midLP2 += MID_LP2_ALPHA * (sample - midLP2)
            val lowVal = kotlin.math.abs(lowZ)
            val midVal = kotlin.math.abs(midLP1 - midLP2)
            lowSumSq  += lowVal * lowVal
            midSumSq  += midVal * midVal
            fullSumSq += sample * sample
            winPos++
            if (winPos >= hopSamples) {
                outLow  += kotlin.math.sqrt(lowSumSq  / winPos)
                outMid  += kotlin.math.sqrt(midSumSq  / winPos)
                outFull += kotlin.math.sqrt(fullSumSq / winPos)
                lowSumSq = 0f; midSumSq = 0f; fullSumSq = 0f; winPos = 0
            }
        }

        fun normalizeEnv(src: List<Float>): List<Float> {
            val mx = src.maxOrNull() ?: 0f
            return if (mx > 1e-6f) src.map { (it / mx).coerceIn(0f, 1f) } else src
        }
        return detect(normalizeEnv(outLow), normalizeEnv(outMid), normalizeEnv(outFull), params)
    }

    fun detect(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        params: Params = Params()
    ): DetectResult {
        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            return DetectResult(emptyList(), 0L, null, "empty env", 0L, TimeSignature.FOUR_FOUR)
        }

        val minSize    = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        val low        = lowEnv.take(minSize)
        val mid        = midEnv.take(minSize)
        val full       = fullEnv.take(minSize)
        val durationMs = minSize * params.hopMs

        val globalOdf = computeMultiBandFluxOdf(low, mid, full, params)

        val beatMs = estimateBpmDense(globalOdf, params.hopMs, params.minBeatMs, params.maxBeatMs)
                     ?: 500L
        Log.d(TAG, "V1 beatMs=$beatMs (${60_000L / beatMs} BPM) durationMs=$durationMs")

        val phaseMs = estimatePhaseFromOdf(globalOdf, beatMs, params.hopMs)
        Log.d(TAG, "V1 phaseMs=$phaseMs")

        val dpTimes = dpBeatTracker(globalOdf, beatMs, params.hopMs, durationMs, anchorMs = phaseMs)
        Log.d(TAG, "V1 dpTimes=${dpTimes.size}")

        val expectedBeats = max(1, (durationMs / beatMs).toInt())
        val dpOk = dpTimes.size >= max(4, (expectedBeats * DP_MIN_BEAT_RATIO).toInt())

        val beats: List<TimedBeat>
        val reason: String
        if (dpOk) {
            beats  = dpTimes.map { TimedBeat(it, 1f) }
            reason = "dp"
        } else {
            Log.w(TAG, "V1 DP insufficient (${dpTimes.size}/$expectedBeats) → segment fallback")
            beats  = fallbackSegmentBeats(low, mid, full, params, beatMs, durationMs)
            reason = if (beats.isNotEmpty()) "dp+fallback" else "failed"
        }

        if (beats.isEmpty()) {
            Log.w(TAG, "V1 detect FAIL")
            return DetectResult(emptyList(), 0L, null, "all failed", 0L, TimeSignature.FOUR_FOUR)
        }

        val timeSignature = detectTimeSignature(globalOdf, beatMs, params.hopMs)
        val downbeatMs    = detectDownbeatEnhanced(
            beats.map { it.timeMs }, low, beatMs, timeSignature.beatsPerBar, params.hopMs)
        val downbeatOffsetMs = (downbeatMs - (beats.firstOrNull()?.timeMs ?: 0L)).coerceAtLeast(0L)

        val beatTimes = beats.map { it.timeMs }
        for (i in 1 until beatTimes.size) {
            val gap = beatTimes[i] - beatTimes[i - 1]
            if (gap < beatMs * 3L / 4L) {
                Log.w(TAG, "V1 detect() short-gap FINAL: ${beatTimes[i-1]}ms→${beatTimes[i]}ms gap=${gap}ms (beatMs=$beatMs) idx=$i reason=$reason")
            }
        }

        Log.d(TAG, "V1 OK beats=${beats.size} beatMs=$beatMs " +
            "timeSig=${timeSignature.type} reason=$reason first=${beatTimes.firstOrNull()} last=${beatTimes.lastOrNull()}")

        return DetectResult(
            beats            = beats,
            beatMs           = beatMs,
            source           = BeatSource.FULL,
            reason           = reason,
            downbeatOffsetMs = downbeatOffsetMs,
            timeSignature    = timeSignature
        )
    }

    // Dense BPM 추정 — librosa beat_track 방식
