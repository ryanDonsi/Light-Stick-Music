package com.lightstick.music.domain.music

import com.lightstick.music.core.util.Log
import com.lightstick.music.core.constants.AppConstants
import kotlin.math.sqrt

/**
 * 음악 스타일 분류기.
 *
 * 입력: IIR 밴드별 에너지 envelope + 비트 간격(beatMs)
 * 출력: MusicStyle (DANCE / BALLAD / ROCK / POP)
 *
 * 분류 우선순위:
 *  1. BALLAD  — 느린 템포(>700ms) + 낮은 평균 에너지
 *  2. DANCE   — 빠른 템포(<530ms) + 높은 베이스 비율
 *  3. ROCK    — 높은 고음역 비율 + 큰 에너지 분산
 *  4. POP     — 나머지
 */
object MusicStyleClassifier {

    private const val TAG = AppConstants.Feature.AUTO_TIMELINE

    // ── 임계값 ────────────────────────────────────────────────────

    // BALLAD
    private const val BALLAD_BEAT_MS_MIN      = 700L    // 이상이면 느린 템포
    private const val BALLAD_ENERGY_MAX        = 0.52f   // 평균 에너지 상한
    private const val BALLAD_ENERGY_MAX_SLOW   = 0.62f   // beatMs > 900ms 일 때 완화

    // DANCE
    private const val DANCE_BEAT_MS_MAX        = 530L    // 미만이면 빠른 템포
    private const val DANCE_LOW_RATIO_MIN      = 0.40f   // 베이스 비율 하한
    private const val DANCE_PERIODICITY_MIN    = 0.55f   // 비트 규칙성 하한

    // ROCK
    private const val ROCK_HIGH_RATIO_MIN      = 0.30f   // 고음역 비율 하한
    private const val ROCK_ENERGY_CV_MIN       = 0.55f   // 에너지 변동계수(CV) 하한

    // ── 공개 API ──────────────────────────────────────────────────

    enum class MusicStyle { DANCE, BALLAD, ROCK, POP }

    data class ClassifyResult(
        val style: MusicStyle,
        // 분류에 쓰인 주요 피처 (디버그·오버레이용)
        val beatMs: Long,
        val avgEnergy: Float,
        val lowRatio: Float,
        val midRatio: Float,
        val highRatio: Float,
        val energyCV: Float,      // 에너지 변동계수 (std / mean)
        val periodicity: Float    // 비트 규칙성 (0~1)
    )

    /**
     * 음악 스타일 분류.
     *
     * @param lowEnv   저음역 에너지 envelope (IIR alpha≈0.12)
     * @param midEnv   중음역 에너지 envelope
     * @param fullEnv  전대역 에너지 envelope
     * @param highEnv  고음역 에너지 envelope (IIR alpha≈0.40)
     * @param beatMs   BeatDetector 결과의 평균 비트 간격
     * @param beats    BeatDetector 결과의 비트 목록 (규칙성 계산용, 없으면 빈 리스트)
     */
    fun classify(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        highEnv: List<Float>,
        beatMs: Long,
        beats: List<BeatDetectorRouter.BeatInfo.Beat> = emptyList()
    ): ClassifyResult {

        if (fullEnv.isEmpty()) {
            return ClassifyResult(MusicStyle.POP, beatMs, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // ── 1. 기본 피처 계산 ─────────────────────────────────────

        // 무음에 가까운 프레임 제외 (노이즈 영향 최소화)
        val activeThreshold = fullEnv.max() * 0.05f
        val activeIdx       = fullEnv.indices.filter { fullEnv[it] > activeThreshold }

        fun activeAvg(env: List<Float>) =
            if (activeIdx.isEmpty()) 0f
            else activeIdx.sumOf { env[it].toDouble() }.toFloat() / activeIdx.size

        val avgFull = activeAvg(fullEnv).coerceAtLeast(1e-6f)
        val avgLow  = activeAvg(lowEnv)
        val avgMid  = activeAvg(midEnv)
        val avgHigh = activeAvg(highEnv)

        val bandSum    = (avgLow + avgMid + avgHigh).coerceAtLeast(1e-6f)
        val lowRatio   = avgLow  / bandSum
        val midRatio   = avgMid  / bandSum
        val highRatio  = avgHigh / bandSum

        // 에너지 변동계수 (CV = std / mean) — 다이나믹 레인지 지표
        val variance   = if (activeIdx.isEmpty()) 0f
                         else activeIdx.sumOf { v -> val d = fullEnv[v] - avgFull; (d * d).toDouble() }
                                  .toFloat() / activeIdx.size
        val energyCV   = sqrt(variance) / avgFull

        // 비트 규칙성 — 비트 간격의 CV 역수
        val periodicity = computePeriodicity(beats, beatMs)

        // ── 2. 분류 ──────────────────────────────────────────────

        val style = when {
            isBalladStyle(beatMs, avgFull) -> MusicStyle.BALLAD
            isDanceStyle(beatMs, lowRatio, periodicity) -> MusicStyle.DANCE
            isRockStyle(highRatio, energyCV) -> MusicStyle.ROCK
            else -> MusicStyle.POP
        }

        val result = ClassifyResult(
            style       = style,
            beatMs      = beatMs,
            avgEnergy   = avgFull,
            lowRatio    = lowRatio,
            midRatio    = midRatio,
            highRatio   = highRatio,
            energyCV    = energyCV,
            periodicity = periodicity
        )

        Log.d(TAG, "[MusicStyle] $style | beatMs=$beatMs avgEnergy=${"%.3f".format(avgFull)} " +
            "low=${lowRatio.fmt()} mid=${midRatio.fmt()} high=${highRatio.fmt()} " +
            "cv=${energyCV.fmt()} period=${periodicity.fmt()}")

        return result
    }

    // ── 분류 조건 ─────────────────────────────────────────────────

    private fun isBalladStyle(beatMs: Long, avgEnergy: Float): Boolean {
        if (beatMs < BALLAD_BEAT_MS_MIN) return false
        val energyMax = if (beatMs >= 900L) BALLAD_ENERGY_MAX_SLOW else BALLAD_ENERGY_MAX
        return avgEnergy < energyMax
    }

    private fun isDanceStyle(beatMs: Long, lowRatio: Float, periodicity: Float): Boolean {
        if (beatMs >= DANCE_BEAT_MS_MAX) return false
        // 베이스 비율과 비트 규칙성 중 하나는 충족해야 함
        return lowRatio >= DANCE_LOW_RATIO_MIN || periodicity >= DANCE_PERIODICITY_MIN
    }

    private fun isRockStyle(highRatio: Float, energyCV: Float): Boolean {
        return highRatio >= ROCK_HIGH_RATIO_MIN && energyCV >= ROCK_ENERGY_CV_MIN
    }

    // ── 비트 규칙성 계산 ──────────────────────────────────────────

    /**
     * 비트 간격의 안정성을 0~1로 반환.
     * 간격이 일정할수록 1에 가까움 (댄스/EDM 특성).
     */
    private fun computePeriodicity(
        beats: List<BeatDetectorRouter.BeatInfo.Beat>,
        globalBeatMs: Long
    ): Float {
        if (beats.size < 4 || globalBeatMs <= 0L) return 0f

        val intervals = beats.zipWithNext { a, b -> (b.timeMs - a.timeMs).toFloat() }
        val mean      = intervals.average().toFloat().coerceAtLeast(1f)
        val variance  = intervals.sumOf { v -> val d = v - mean; (d * d).toDouble() }
                            .toFloat() / intervals.size
        val cv        = sqrt(variance) / mean   // 낮을수록 규칙적
        // CV 0 → periodicity 1.0, CV 0.5 → ~0.5, CV 1.0+ → 0
        return (1f - cv * 2f).coerceIn(0f, 1f)
    }

    private fun Float.fmt() = "%.2f".format(this)
}
