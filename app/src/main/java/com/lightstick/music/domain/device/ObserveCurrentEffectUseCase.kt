package com.lightstick.music.domain.device

import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import kotlinx.coroutines.flow.StateFlow

/**
 * 현재 전송 중인 이펙트를 관찰하는 UseCase
 *
 * 책임:
 * - BleTransmissionMonitor의 latestTransmission을 ViewModel에 노출
 * - Effect 화면의 DeviceConnectionCard에서 사용 (선택적)
 *
 * 사용처:
 * - EffectViewModel: Effect 화면의 라이트스틱 연출 표시
 * - DeviceConnectionCard: 현재 재생 중인 이펙트 시각화
 *
 * 모든 전송 소스의 이펙트가 자동으로 기록됨:
 * - MUSIC_PLAYBACK: Music 재생 중 FFT 이펙트
 * - MANUAL_EFFECT: Effect List에서 선택한 이펙트
 * - CONNECTION_EFFECT: Device 연결 시 연출
 * - DEVICE_SEARCH: FindEffect 연출
 */
object ObserveCurrentEffectUseCase {

    /**
     * 최신 전송 이벤트를 관찰합니다.
     *
     * 모든 소스(Music, Effect, Device, Connection)에서 전송된
     * 이펙트가 실시간으로 업데이트됩니다.
     *
     * @return StateFlow<BleTransmissionEvent?>
     */
    fun invoke(): StateFlow<BleTransmissionEvent?> {
        return BleTransmissionMonitor.latestTransmission
    }

    /**
     * 전송 히스토리를 관찰합니다 (최근 100개).
     *
     * @return StateFlow<List<BleTransmissionEvent>>
     */
    fun observeHistory(): StateFlow<List<BleTransmissionEvent>> {
        return BleTransmissionMonitor.transmissionHistory
    }
}