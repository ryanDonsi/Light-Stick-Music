package com.lightstick.music.data.model

import com.lightstick.device.DeviceInfo

/**
 * Device 상세 정보를 담는 데이터 클래스
 */
data class DeviceDetailInfo(
    val mac: String,
    val name: String?,
    val rssi: Int?,
    val isConnected: Boolean = false,
    val deviceInfo: DeviceInfo? = null,
    val batteryLevel: Int? = null,
    val otaProgress: Int? = null,
    val isOtaInProgress: Boolean = false,
    val callEventEnabled: Boolean = true,
    val smsEventEnabled: Boolean = true,
    val broadcasting: Boolean = true,
    /** FF09 지원 여부 — BLE 모드 응원봉만 true (중계기는 항상 브로드캐스트) */
    val supportsBroadcasting: Boolean = false
)