package com.lightstick.music.domain.ble

import com.lightstick.music.core.constants.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE 전송 모니터
 *
 * 모든 BLE 전송을 실시간으로 모니터링하고 기록합니다.
 *
 * 주요 기능:
 * - 전송 이벤트 실시간 기록
 * - 최신 전송 상태 StateFlow로 제공
 * - 전송 히스토리 관리 (최근 100개)
 * - 소스별 전송 통계
 *
 * 사용 예시:
 * ```
 * // 전송 기록
 * BleTransmissionMonitor.recordTransmission(
 *     BleTransmissionEvent(
 *         source = TransmissionSource.MANUAL_EFFECT,
 *         deviceMac = "AA:BB:CC:DD:EE:FF",
 *         effectType = EffectType.ON,
 *         payload = payload,
 *         color = Colors.WHITE
 *     )
 * )
 *
 * // 최신 전송 관찰
 * BleTransmissionMonitor.latestTransmission.collect { event ->
 *     // UI 업데이트
 * }
 * ```
 */
object BleTransmissionMonitor {

    private const val TAG = AppConstants.Feature.BLE_MONITOR

    /** 최신 전송 이벤트 */
    private val _latestTransmission = MutableStateFlow<BleTransmissionEvent?>(null)
    val latestTransmission: StateFlow<BleTransmissionEvent?> = _latestTransmission.asStateFlow()

    /** 전송 히스토리 (최근 100개) */
    private val _transmissionHistory = MutableStateFlow<List<BleTransmissionEvent>>(emptyList())
    val transmissionHistory: StateFlow<List<BleTransmissionEvent>> = _transmissionHistory.asStateFlow()

    /** 소스별 전송 카운트 */
    private val transmissionCounts = mutableMapOf<TransmissionSource, Int>()

    /** 디바이스별 마지막 전송 시각 */
    private val lastTransmissionByDevice = mutableMapOf<String, Long>()

    /**
     * BLE 전송 이벤트 기록
     *
     * **우선순위 규칙**:
     * - MANUAL_EFFECT > EFX_EFFECT = TIMELINE_EFFECT > FFT_EFFECT
     * - 마지막 전달된 이펙트가 항상 화면에 연출됨
     *
     * @param event 전송 이벤트
     */
    fun recordTransmission(event: BleTransmissionEvent) {
        val currentLatest = _latestTransmission.value

        if (currentLatest != null) {
            if (currentLatest.deviceMac == event.deviceMac) {
                val timeSinceLast = event.timestamp - currentLatest.timestamp

                val currentPriority = getSourcePriority(currentLatest.source)
                val newPriority = getSourcePriority(event.source)

                if (newPriority < currentPriority && timeSinceLast < 500) {
                    val updated = (_transmissionHistory.value + event)
                        .takeLast(AppConstants.MAX_TRANSMISSION_HISTORY)
                    _transmissionHistory.value = updated

                    return
                }
            }
        }

        _latestTransmission.value = event

        val updated = (_transmissionHistory.value + event)
            .takeLast(AppConstants.MAX_TRANSMISSION_HISTORY)
        _transmissionHistory.value = updated

        transmissionCounts[event.source] = (transmissionCounts[event.source] ?: 0) + 1
        lastTransmissionByDevice[event.deviceMac] = event.timestamp
    }

    /**
     * 소스별 우선순위 반환
     * 숫자가 클수록 높은 우선순위
     */
    private fun getSourcePriority(source: TransmissionSource): Int {
        return when (source) {
            TransmissionSource.PAYLOAD_EFFECT -> 100
            TransmissionSource.EFX_EFFECT -> 60
            TransmissionSource.TIMELINE_EFFECT -> 60
            TransmissionSource.FFT_EFFECT -> 40
        }
    }

    /**
     * 히스토리 초기화
     */
    fun clearHistory() {
        _transmissionHistory.value = emptyList()
        _latestTransmission.value = null
    }

    /**
     * 통계 초기화
     */
    fun clearStatistics() {
        transmissionCounts.clear()
        lastTransmissionByDevice.clear()
    }

    /**
     * 모든 데이터 초기화
     */
    fun reset() {
        clearHistory()
        clearStatistics()
    }

    /**
     * 특정 소스의 전송 횟수 조회
     *
     * @param source 전송 소스
     * @return 전송 횟수
     */
    fun getTransmissionCount(source: TransmissionSource): Int {
        return transmissionCounts[source] ?: 0
    }

    /**
     * 모든 소스의 전송 횟수 조회
     *
     * @return 소스별 전송 횟수 맵
     */
    fun getAllTransmissionCounts(): Map<TransmissionSource, Int> {
        return transmissionCounts.toMap()
    }

    /**
     * 총 전송 횟수 조회
     *
     * @return 총 전송 횟수
     */
    fun getTotalTransmissionCount(): Int {
        return transmissionCounts.values.sum()
    }

    /**
     * 특정 디바이스의 마지막 전송 시각 조회
     *
     * @param deviceMac 디바이스 MAC 주소
     * @return 마지막 전송 시각 (밀리초) 또는 null
     */
    fun getLastTransmissionTime(deviceMac: String): Long? {
        return lastTransmissionByDevice[deviceMac]
    }

    /**
     * 특정 소스의 전송 히스토리 조회
     *
     * @param source 전송 소스
     * @param limit 최대 개수 (기본: 20)
     * @return 해당 소스의 전송 히스토리
     */
    fun getHistoryBySource(
        source: TransmissionSource,
        limit: Int = 20
    ): List<BleTransmissionEvent> {
        return _transmissionHistory.value
            .filter { it.source == source }
            .takeLast(limit)
    }

    /**
     * 특정 디바이스의 전송 히스토리 조회
     *
     * @param deviceMac 디바이스 MAC 주소
     * @param limit 최대 개수 (기본: 20)
     * @return 해당 디바이스의 전송 히스토리
     */
    fun getHistoryByDevice(
        deviceMac: String,
        limit: Int = 20
    ): List<BleTransmissionEvent> {
        return _transmissionHistory.value
            .filter { it.deviceMac == deviceMac }
            .takeLast(limit)
    }

    /**
     * 특정 시간 이후의 전송 히스토리 조회
     *
     * @param afterTimestamp 기준 시각 (밀리초)
     * @return 해당 시각 이후의 전송 히스토리
     */
    fun getHistoryAfter(afterTimestamp: Long): List<BleTransmissionEvent> {
        return _transmissionHistory.value
            .filter { it.timestamp > afterTimestamp }
    }

    /**
     * 특정 이펙트 타입의 전송 히스토리 조회
     *
     * @param effectType 이펙트 타입
     * @param limit 최대 개수 (기본: 20)
     * @return 해당 이펙트 타입의 전송 히스토리
     */
    fun getHistoryByEffectType(
        effectType: com.lightstick.types.EffectType,
        limit: Int = 20
    ): List<BleTransmissionEvent> {
        return _transmissionHistory.value
            .filter { it.effectType == effectType }
            .takeLast(limit)
    }

    /**
     * 디버그 정보 출력
     */
    fun printDebugInfo() {
    }

    /**
     * 최근 N개 전송 이벤트 출력
     *
     * @param count 출력할 개수 (기본: 10)
     */
    fun printRecentTransmissions(count: Int = 10) {
    }
}
