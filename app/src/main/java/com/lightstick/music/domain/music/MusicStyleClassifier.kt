package com.lightstick.music.domain.music

import com.lightstick.music.core.util.Log
import com.lightstick.music.core.constants.AppConstants
import kotlin.math.sqrt

/**
 * 음악 스타일 분류기 (6종).
 *
 * 입력: IIR 4밴드 envelope + beatMs + beats
 * 출력: MusicStyle
 *
 * 분류 우선순위:
 *  1. BALLAD    — 느린 템포 + 낮은 에너지
 *  2. EDM       — 빠른 템포 + 베이스 강함 + 비트 규칙성 최고 + onset 밀도 높음
 *  3. DANCE_POP — 빠른 템포 + 베이스 높음 + 규칙적
 *  4. HIPHOP_RNB— 중간 템포 + 베이스 강함 + 규칙성 낮음
 *  5. ROCK      — 고음역 비율 높음 + energyFlux 큼
 *  6. POP       — catch-all
 */
object MusicStyleClassifier {

    private const val TAG = AppConstants.Feature.AUTO_TIMELINE

    // ── 임계값 ────────────────────────────────────────────────────

    // BALLAD
    // V4.3: 700ms(85.7 BPM) → 800ms(75 BPM)로 상향. IYKYK가 BeatDetector 오검출(85.7 BPM,
    // 정답은 127.7 BPM)로 정확히 700ms 경계에 걸려 BALLAD로 오분류되던 문제 완화.
    // 43곡 정답 기준 BPM 75~85.7(beatMs 700~800ms) 구간에 실제로 걸리는 곡이 없어
    // 다른 발라드 판정에는 영향 없음.
    private const val BALLAD_BEAT_MS_MIN        = 800L
    private const val BALLAD_ENERGY_MAX          = 0.52f
    private const val BALLAD_ENERGY_MAX_SLOW     = 0.62f   // beatMs >= 900ms 완화

    // EDM
    private const val EDM_BEAT_MS_MAX            = 480L
    private const val EDM_LOW_RATIO_MIN          = 0.38f
    private const val EDM_PERIODICITY_MIN        = 0.65f
    private const val EDM_ONSET_DENSITY_MIN      = 4.0f    // 초당 onset 수

    // DANCE_POP
    private const val DANCE_BEAT_MS_MAX          = 545L
    private const val DANCE_LOW_RATIO_MIN        = 0.36f
    private const val DANCE_PERIODICITY_MIN      = 0.50f

    // HIPHOP_RNB
    private const val HH_BEAT_MS_MIN             = 545L
    private const val HH_BEAT_MS_MAX             = 750L
    private const val HH_LOW_RATIO_MIN           = 0.38f
    private const val HH_PERIODICITY_MAX         = 0.62f   // 댄스보다 규칙성 낮음

    // ROCK
    private const val ROCK_HIGH_RATIO_MIN        = 0.28f
    private const val ROCK_FLUX_MIN              = 0.040f  // energyFlux (양의 차분 평균)

    // ── 공개 API ──────────────────────────────────────────────────

    enum class MusicStyle { EDM, DANCE_POP, HIPHOP_RNB, BALLAD, ROCK, POP }

    data class ClassifyResult(
        val style: MusicStyle,
        val beatMs: Long,
        val avgEnergy: Float,
        val lowRatio: Float,
        val midRatio: Float,
        val highRatio: Float,
        val energyCV: Float,         // 에너지 변동계수 (std / mean)
        val periodicity: Float,      // 비트 규칙성 (0~1)
        val onsetDensity: Float,     // 초당 onset 수
        val energyFlux: Float        // 양의 에너지 차분 평균 (거칠기)
    )

    fun classify(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        highEnv: List<Float>,
        beatMs: Long,
        beats: List<BeatDetectorRouter.BeatInfo.Beat> = emptyList(),
        hopMs: Long = 50L
    ): ClassifyResult {

        if (fullEnv.isEmpty()) {
            return ClassifyResult(MusicStyle.POP, beatMs, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // ── 1. 피처 계산 ──────────────────────────────────────────

        val activeThreshold = fullEnv.max() * 0.05f
        val activeIdx       = fullEnv.indices.filter { fullEnv[it] > activeThreshold }

        fun activeAvg(env: List<Float>) =
            if (activeIdx.isEmpty()) 0f
            else activeIdx.sumOf { env[it].toDouble() }.toFloat() / activeIdx.size

        val avgFull = activeAvg(fullEnv).coerceAtLeast(1e-6f)
        val avgLow  = activeAvg(lowEnv)
        val avgMid  = activeAvg(midEnv)
        val avgHigh = activeAvg(highEnv)

        val bandSum  = (avgLow + avgMid + avgHigh).coerceAtLeast(1e-6f)
        val lowRatio  = avgLow  / bandSum
        val midRatio  = avgMid  / bandSum
        val highRatio = avgHigh / bandSum

        // 에너지 변동계수 (CV = std / mean)
        val variance = if (activeIdx.isEmpty()) 0f
                       else activeIdx.sumOf { i -> val d = fullEnv[i] - avgFull; (d * d).toDouble() }
                                .toFloat() / activeIdx.size
        val energyCV = sqrt(variance) / avgFull

        // 비트 규칙성
        val periodicity = computePeriodicity(beats)

        // onset 밀도: fullEnv에서 국소 피크 수 / 총 재생 시간(초)
        val onsetDensity = computeOnsetDensity(fullEnv, hopMs)

        // energyFlux: 양의 프레임 간 차분 평균 → 락/EDM의 거칠기 지표
        val energyFlux = computeEnergyFlux(fullEnv, activeIdx)

        // ── 2. 분류 (우선순위 순) ─────────────────────────────────

        val style = when {
            isBalladStyle(beatMs, avgFull)                              -> MusicStyle.BALLAD
            isEdmStyle(beatMs, lowRatio, periodicity, onsetDensity)     -> MusicStyle.EDM
            isDancePop(beatMs, lowRatio, periodicity)                   -> MusicStyle.DANCE_POP
            isHiphopRnb(beatMs, lowRatio, periodicity)                  -> MusicStyle.HIPHOP_RNB
            isRockStyle(highRatio, energyFlux)                          -> MusicStyle.ROCK
            else                                                        -> MusicStyle.POP
        }

        Log.d(TAG, "[MusicStyle] $style | beatMs=$beatMs " +
            "energy=${"%.3f".format(avgFull)} " +
            "low=${lowRatio.f()} mid=${midRatio.f()} high=${highRatio.f()} " +
            "cv=${energyCV.f()} period=${periodicity.f()} " +
            "onset=${"%.1f".format(onsetDensity)} flux=${energyFlux.f()}")

        return ClassifyResult(
            style        = style,
            beatMs       = beatMs,
            avgEnergy    = avgFull,
            lowRatio     = lowRatio,
            midRatio     = midRatio,
            highRatio    = highRatio,
            energyCV     = energyCV,
            periodicity  = periodicity,
            onsetDensity = onsetDensity,
            energyFlux   = energyFlux
        )
    }

    // ── 분류 조건 ─────────────────────────────────────────────────

    private fun isBalladStyle(beatMs: Long, avgEnergy: Float): Boolean {
        if (beatMs < BALLAD_BEAT_MS_MIN) return false
        val energyMax = if (beatMs >= 900L) BALLAD_ENERGY_MAX_SLOW else BALLAD_ENERGY_MAX
        return avgEnergy < energyMax
    }

    private fun isEdmStyle(
        beatMs: Long, lowRatio: Float, periodicity: Float, onsetDensity: Float
    ): Boolean {
        if (beatMs >= EDM_BEAT_MS_MAX) return false
        // 베이스 강함 + 규칙성 높음 → 둘 다 충족
        if (lowRatio < EDM_LOW_RATIO_MIN || periodicity < EDM_PERIODICITY_MIN) return false
        // onset 밀도로 일반 댄스팝과 구분
        return onsetDensity >= EDM_ONSET_DENSITY_MIN
    }

    private fun isDancePop(beatMs: Long, lowRatio: Float, periodicity: Float): Boolean {
        if (beatMs >= DANCE_BEAT_MS_MAX) return false
        return lowRatio >= DANCE_LOW_RATIO_MIN || periodicity >= DANCE_PERIODICITY_MIN
    }

    private fun isHiphopRnb(beatMs: Long, lowRatio: Float, periodicity: Float): Boolean {
        if (beatMs < HH_BEAT_MS_MIN || beatMs >= HH_BEAT_MS_MAX) return false
        // 베이스는 강하지만 댄스만큼 규칙적이지 않음
        return lowRatio >= HH_LOW_RATIO_MIN && periodicity < HH_PERIODICITY_MAX
    }

    private fun isRockStyle(highRatio: Float, energyFlux: Float): Boolean {
        return highRatio >= ROCK_HIGH_RATIO_MIN && energyFlux >= ROCK_FLUX_MIN
    }

    // ── 피처 계산 ─────────────────────────────────────────────────

    /** 비트 간격 안정성 (0~1, 높을수록 규칙적) */
    private fun computePeriodicity(beats: List<BeatDetectorRouter.BeatInfo.Beat>): Float {
        if (beats.size < 4) return 0f
        val intervals = beats.zipWithNext { a, b -> (b.timeMs - a.timeMs).toFloat() }
        val mean      = intervals.average().toFloat().coerceAtLeast(1f)
        val variance  = intervals.sumOf { v -> val d = v - mean; (d * d).toDouble() }
                            .toFloat() / intervals.size
        val cv = sqrt(variance) / mean
        return (1f - cv * 2f).coerceIn(0f, 1f)
    }

    /**
     * 초당 onset 수.
     * fullEnv에서 이웃보다 높은 국소 피크를 onset으로 간주.
     */
    private fun computeOnsetDensity(fullEnv: List<Float>, hopMs: Long): Float {
        if (fullEnv.size < 3) return 0f
        val threshold = fullEnv.max() * 0.15f
        var count = 0
        for (i in 1 until fullEnv.size - 1) {
            if (fullEnv[i] > fullEnv[i - 1] && fullEnv[i] > fullEnv[i + 1]
                && fullEnv[i] > threshold) {
                count++
            }
        }
        val durationSec = fullEnv.size * hopMs / 1000f
        return if (durationSec > 0f) count / durationSec else 0f
    }

    /**
     * 에너지 flux: 양의 프레임 간 차분의 평균.
     * 락/EDM처럼 급격한 에너지 변화가 잦을수록 높음.
     */
    private fun computeEnergyFlux(fullEnv: List<Float>, activeIdx: List<Int>): Float {
        if (fullEnv.size < 2 || activeIdx.isEmpty()) return 0f
        var posSum = 0.0
        var count  = 0
        for (i in 1 until fullEnv.size) {
            val diff = fullEnv[i] - fullEnv[i - 1]
            if (diff > 0f) { posSum += diff; count++ }
        }
        return if (count > 0) (posSum / count).toFloat() else 0f
    }

    private fun Float.f() = "%.2f".format(this)
}
