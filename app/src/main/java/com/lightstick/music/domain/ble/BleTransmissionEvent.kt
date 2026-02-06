package com.lightstick.music.domain.ble

import com.lightstick.types.Color
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload

/**
 * BLE 전송 이벤트
 *
 * 모든 BLE 전송을 기록하기 위한 데이터 클래스
 * 전송 소스, 디바이스, 페이로드 정보를 포함합니다.
 */
data class BleTransmissionEvent(
    /** 전송 시각 (밀리초) */
    val timestamp: Long = System.currentTimeMillis(),

    /** 전송 소스 */
    val source: TransmissionSource,

    /** 대상 디바이스 MAC 주소 */
    val deviceMac: String,

    /** 이펙트 타입 */
    val effectType: EffectType?,

    /** 전송 페이로드 */
    val payload: LSEffectPayload,

    /** 색상 (ON, STROBE 등) */
    val color: Color? = null,

    /** 배경색 (STROBE, BLINK 등) */
    val backgroundColor: Color? = null,

    /** Transit 값 (색상 전환 속도) */
    val transit: Int? = null,

    /** Period 값 (주기 효과) */
    val period: Int? = null,

    /** 추가 메타데이터 */
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * 전송 소스를 읽기 쉬운 형태로 변환
     */
    fun getSourceDisplayName(): String {
        return when (source) {
            TransmissionSource.MANUAL_EFFECT -> "수동 효과"
            TransmissionSource.TIMELINE_EFFECT -> "타임라인 효과"
            TransmissionSource.FFT_EFFECT -> "FFT 효과"
            TransmissionSource.CONNECTION_EFFECT -> "연결 효과"
            TransmissionSource.BROADCAST -> "브로드캐스트"
        }
    }

    /**
     * 이펙트 타입을 읽기 쉬운 형태로 변환
     */
    fun getEffectTypeDisplayName(): String {
        return when (effectType) {
            EffectType.ON -> "ON"
            EffectType.OFF -> "OFF"
            EffectType.STROBE -> "STROBE"
            EffectType.BLINK -> "BLINK"
            EffectType.BREATH -> "BREATH"
            null -> "UNKNOWN"
        }
    }

    /**
     * 전송 시각을 읽기 쉬운 형태로 변환
     */
    fun getTimestampFormatted(): String {
        val seconds = (timestamp / 1000).toInt()
        val minutes = seconds / 60
        val secs = seconds % 60
        val millis = (timestamp % 1000).toInt()
        return String.format("%02d:%02d.%03d", minutes, secs, millis)
    }

    /**
     * 색상 정보가 있는지 확인
     */
    fun hasColorInfo(): Boolean {
        return color != null
    }

    /**
     * 주기 효과인지 확인
     */
    fun isPeriodicEffect(): Boolean {
        return effectType in listOf(EffectType.STROBE, EffectType.BLINK, EffectType.BREATH)
    }
}

/**
 * 전송 소스 타입
 */
enum class TransmissionSource {
    /** Effect 탭에서 수동으로 실행하는 효과 */
    MANUAL_EFFECT,

    /** Music 재생 중 타임라인 동기화 효과 */
    TIMELINE_EFFECT,

    /** Music 재생 중 주파수 분석 효과 */
    FFT_EFFECT,

    /** Device 연결 시 연출 효과 */
    CONNECTION_EFFECT,

    /** 모든 디바이스에 브로드캐스트 */
    BROADCAST
}