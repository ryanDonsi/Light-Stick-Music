package com.lightstick.music.domain.ble

import android.util.Log
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

    private const val TAG = "BleTransmissionMonitor"

    // ═══════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * BLE 전송 이벤트 기록
     *
     * **우선순위 규칙**:
     * - MANUAL_EFFECT > TIMELINE_EFFECT > FFT_EFFECT
     * - 마지막 전달된 이펙트가 항상 화면에 연출됨
     *
     * @param event 전송 이벤트
     */
    fun recordTransmission(event: BleTransmissionEvent) {
        // ✅ 우선순위 기반 업데이트 로직
        val currentLatest = _latestTransmission.value

        if (currentLatest != null) {
            // 같은 디바이스에 대한 전송만 비교
            if (currentLatest.deviceMac == event.deviceMac) {
                val timeSinceLast = event.timestamp - currentLatest.timestamp

                // 우선순위 정의
                val currentPriority = getSourcePriority(currentLatest.source)
                val newPriority = getSourcePriority(event.source)

                // 우선순위가 낮은 소스는 최근(500ms 이내) 높은 우선순위 이벤트를 덮어쓰지 못함
                if (newPriority < currentPriority && timeSinceLast < 500) {
                    Log.d(TAG, "⏭️ Skipping lower priority event: ${event.source} (current: ${currentLatest.source})")

                    // 히스토리에는 추가 (통계용)
                    val updated = (_transmissionHistory.value + event)
                        .takeLast(AppConstants.MAX_TRANSMISSION_HISTORY)
                    _transmissionHistory.value = updated

                    return // latestTransmission은 업데이트하지 않음
                }
            }
        }

        // 최신 전송 업데이트
        _latestTransmission.value = event

        // 히스토리 업데이트 (최대 100개 유지)
        val updated = (_transmissionHistory.value + event)
            .takeLast(AppConstants.MAX_TRANSMISSION_HISTORY)
        _transmissionHistory.value = updated

        // 통계 업데이트
        transmissionCounts[event.source] = (transmissionCounts[event.source] ?: 0) + 1
        lastTransmissionByDevice[event.deviceMac] = event.timestamp

        // 디버그 로깅
        if (AppConstants.DEBUG_MODE && AppConstants.VERBOSE_LOGGING) {
            Log.d(TAG, "📤 [${event.getSourceDisplayName()}] ${event.getEffectTypeDisplayName()} → ${event.deviceMac}")
        }
    }

    /**
     * 소스별 우선순위 반환
     * 숫자가 클수록 높은 우선순위
     */
    private fun getSourcePriority(source: TransmissionSource): Int {
        return when (source) {
            TransmissionSource.CONNECTION_EFFECT -> 100
            TransmissionSource.MANUAL_EFFECT -> 80
            TransmissionSource.TIMELINE_EFFECT -> 60
            TransmissionSource.FFT_EFFECT -> 40
            TransmissionSource.BROADCAST -> 20
        }
    }

    /**
     * 히스토리 초기화
     */
    fun clearHistory() {
        _transmissionHistory.value = emptyList()
        _latestTransmission.value = null
        Log.d(TAG, "🗑️ Transmission history cleared")
    }

    /**
     * 통계 초기화
     */
    fun clearStatistics() {
        transmissionCounts.clear()
        lastTransmissionByDevice.clear()
        Log.d(TAG, "📊 Statistics cleared")
    }

    /**
     * 모든 데이터 초기화
     */
    fun reset() {
        clearHistory()
        clearStatistics()
        Log.d(TAG, "♻️ Monitor reset")
    }

    // ═══════════════════════════════════════════════════════════
    // Statistics API
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // Debug API
    // ═══════════════════════════════════════════════════════════

    /**
     * 디버그 정보 출력
     */
    fun printDebugInfo() {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "📊 BLE Transmission Monitor Statistics")
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "Total Transmissions: ${getTotalTransmissionCount()}")
        Log.d(TAG, "History Size: ${_transmissionHistory.value.size}")
        Log.d(TAG, "")
        Log.d(TAG, "Transmissions by Source:")
        transmissionCounts.forEach { (source, count) ->
            Log.d(TAG, "  - ${source.name}: $count")
        }
        Log.d(TAG, "")
        Log.d(TAG, "Active Devices: ${lastTransmissionByDevice.size}")
        lastTransmissionByDevice.forEach { (mac, timestamp) ->
            val elapsed = System.currentTimeMillis() - timestamp
            Log.d(TAG, "  - $mac: ${elapsed}ms ago")
        }
        Log.d(TAG, "═══════════════════════════════════════")
    }

    /**
     * 최근 N개 전송 이벤트 출력
     *
     * @param count 출력할 개수 (기본: 10)
     */
    fun printRecentTransmissions(count: Int = 10) {
        val recent = _transmissionHistory.value.takeLast(count)
        Log.d(TAG, "Recent $count transmissions:")
        recent.forEach { event ->
            Log.d(TAG, "  [${event.getTimestampFormatted()}] ${event.getSourceDisplayName()} - ${event.getEffectTypeDisplayName()} → ${event.deviceMac}")
        }
    }
}