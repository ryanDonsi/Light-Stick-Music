package com.lightstick.music.domain.ble

import com.lightstick.music.core.constants.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * BLE 전송 모니터
 *
 * 모든 BLE 전송을 실시간으로 모니터링하고 기록합니다.
 *
 * 주요 기능:
 * - 전송 이벤트 실시간 기록
 * - 최신 전송 상태 StateFlow로 제공
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

    // 예전에 있던 전송 히스토리(최근 100개)·소스별 통계는 앱 어디에서도 읽지 않아 제거했다.
    // 전송마다 리스트 전체를 복사하고, 오디오/메인 스레드가 동기화 없는 맵을 함께 고치던 비용이 있었다.

    /**
     * BLE 전송 이벤트 기록
     *
     * **우선순위 규칙**:
     * - MANUAL_EFFECT > EFX_EFFECT = TIMELINE_EFFECT > FFT_EFFECT
     * - 마지막 전달된 이펙트가 항상 화면에 연출됨
     * - 같은 기기에 500ms 안에 더 낮은 우선순위 전송이 오면 화면 표시는 바꾸지 않는다
     *
     * 여러 스레드(오디오 렌더·메인)에서 불리므로 비교와 교체를 [MutableStateFlow.update]로 원자적으로 한다.
     *
     * @param event 전송 이벤트
     */
    fun recordTransmission(event: BleTransmissionEvent) {
        _latestTransmission.update { current ->
            if (current != null && current.deviceMac == event.deviceMac) {
                val timeSinceLast = event.timestamp - current.timestamp
                if (getSourcePriority(event.source) < getSourcePriority(current.source) && timeSinceLast < 500) {
                    return@update current
                }
            }
            event
        }
    }

    /**
     * 소스별 우선순위 반환
     * 숫자가 클수록 높은 우선순위
     */
    private fun getSourcePriority(source: TransmissionSource): Int {
        return when (source) {
            TransmissionSource.PAYLOAD_EFFECT -> 100
            TransmissionSource.EVENT_EFFECT -> 90
            TransmissionSource.EFX_EFFECT -> 60
            TransmissionSource.TIMELINE_EFFECT -> 60
            TransmissionSource.FFT_EFFECT -> 40
        }
    }

    /** 최신 전송 표시 초기화 */
    fun reset() {
        _latestTransmission.value = null
    }
}
