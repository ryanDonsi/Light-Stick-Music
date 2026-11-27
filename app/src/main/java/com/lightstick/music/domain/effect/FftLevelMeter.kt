package com.lightstick.music.domain.effect

import com.lightstick.music.data.model.FrequencyBand
import com.lightstick.types.Color
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * FFT 에너지를 이퀄라이저 막대처럼 0~[LEVELS] 단계로 바꾼다.
 *
 * 응원봉은 LED 색 하나만 표현하므로, 막대 높이 대신 "단계별 색"으로 보여 준다.
 * - 0단계 = OFF(검정), 1~7단계 = [PALETTE] 의 색 (낮은 에너지 초록 → 높은 에너지 빨강)
 * - 올라갈 때는 바로, 내려갈 때는 [FALL_LEVELS_PER_SEC] 속도로 천천히 떨어진다(막대가 내려오는 느낌)
 * - 새 최고 단계는 [PEAK_HOLD_MS] 동안 유지한 뒤 떨어진다(피크 홀드)
 *
 * 곡마다 음량이 달라서 절대값 대신 dB 를 곡 안에서 정규화한다: 최근 최댓값(천천히 내려감)과
 * 바닥값(천천히 올라감) 사이에서 지금 에너지가 어디쯤인지로 단계를 정한다.
 *
 * 값은 모두 상수로 두어 실기기에서 맞춰 보며 조정한다.
 */
class FftLevelMeter {

    companion object {
        /** 색 단계 수 (OFF 제외) */
        const val LEVELS = 7

        /**
         * 단계별 색 (1단계 → 7단계). 크기는 [LEVELS] 와 같아야 한다.
         * 초록 → 노랑 → 주황 → 빨강, 최상단은 분홍으로 피크를 강조.
         */
        val PALETTE: List<Color> = listOf(
            Color(r = 0, g = 255, b = 60),    // 1 초록
            Color(r = 120, g = 255, b = 0),   // 2 연두
            Color(r = 255, g = 230, b = 0),   // 3 노랑
            Color(r = 255, g = 150, b = 0),   // 4 주황
            Color(r = 255, g = 70, b = 0),    // 5 진주황
            Color(r = 255, g = 0, b = 0),     // 6 빨강
            Color(r = 255, g = 0, b = 140)    // 7 분홍(피크)
        )
        val OFF: Color = Color(r = 0, g = 0, b = 0)

        /** 정규화 값이 이보다 낮으면 OFF (0~1). 1/(LEVELS+1) 보다 조금 높게 잡아 잔잔한 구간을 끈다 */
        const val OFF_THRESHOLD = 0.18f

        /** 대역 가중치: 리듬감은 저음이 좌우하므로 저음 비중을 크게 */
        const val BASS_WEIGHT = 1.0f
        const val MID_WEIGHT = 0.6f
        const val TREBLE_WEIGHT = 0.3f

        /** 최근 최댓값이 내려가는 속도 (dB/초). 작을수록 큰 소리 뒤 한동안 단계가 낮게 나온다 */
        const val PEAK_DECAY_DB_PER_SEC = 6f
        /** 바닥값이 올라가는 속도 (dB/초) */
        const val FLOOR_RISE_DB_PER_SEC = 3f
        /** 최댓값-바닥값 최소 폭 (dB). 조용한 곡에서 작은 변화가 과장되지 않게 */
        const val MIN_SPAN_DB = 18f

        /** 단계가 내려가는 속도 (단계/초) */
        const val FALL_LEVELS_PER_SEC = 14f
        /** 새 최고 단계 유지 시간 (ms) */
        const val PEAK_HOLD_MS = 120L
    }

    private var peakDb = Float.NaN
    private var floorDb = Float.NaN
    private var shownLevel = 0f
    private var heldLevel = 0
    private var heldUntilMs = 0L
    private var lastAtMs = 0L

    fun reset() {
        peakDb = Float.NaN
        floorDb = Float.NaN
        shownLevel = 0f
        heldLevel = 0
        heldUntilMs = 0L
        lastAtMs = 0L
    }

    /** FFT 한 창을 넣고 지금 보여 줄 단계(0=OFF, 1..[LEVELS])를 돌려준다 */
    fun update(band: FrequencyBand, nowMs: Long): Int {
        val dt = if (lastAtMs == 0L) 0f else ((nowMs - lastAtMs).coerceIn(0L, 500L) / 1000f)
        lastAtMs = nowMs

        val energy = band.bass * BASS_WEIGHT + band.mid * MID_WEIGHT + band.treble * TREBLE_WEIGHT
        val db = 10f * log10(energy.coerceAtLeast(1e-12f))

        if (peakDb.isNaN()) { peakDb = db; floorDb = db - MIN_SPAN_DB }
        peakDb = max(db, peakDb - PEAK_DECAY_DB_PER_SEC * dt)
        floorDb = min(db, floorDb + FLOOR_RISE_DB_PER_SEC * dt)
        val span = max(peakDb - floorDb, MIN_SPAN_DB)
        val norm = ((db - (peakDb - span)) / span).coerceIn(0f, 1f)

        val target = if (norm < OFF_THRESHOLD) 0
        else (1 + floor((norm - OFF_THRESHOLD) / (1f - OFF_THRESHOLD) * LEVELS).toInt()).coerceIn(1, LEVELS)

        // 올라갈 때는 즉시, 내려갈 때는 천천히 + 피크 홀드
        if (target >= shownLevel) {
            shownLevel = target.toFloat()
            if (target > heldLevel || nowMs >= heldUntilMs) {
                heldLevel = target
                heldUntilMs = nowMs + PEAK_HOLD_MS
            }
        } else if (nowMs >= heldUntilMs) {
            shownLevel = max(target.toFloat(), shownLevel - FALL_LEVELS_PER_SEC * dt)
        }
        return shownLevel.toInt().coerceIn(0, LEVELS)
    }

    fun colorFor(level: Int): Color = if (level <= 0) OFF else PALETTE[(level - 1).coerceIn(0, PALETTE.size - 1)]
}
