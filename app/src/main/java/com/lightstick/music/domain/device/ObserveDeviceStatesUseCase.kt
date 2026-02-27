package com.lightstick.music.domain.device

import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 디바이스 상태 변경 이벤트를 관찰하는 UseCase
 *
 * 책임:
 * - SDK SharedFlow 이벤트를 앱 도메인으로 래핑
 * - DeviceViewModel, EffectViewModel의 공통 상태 처리 진입점
 *
 * 사용처:
 * - DeviceViewModel: 연결/해제 처리
 * - EffectViewModel: 연결/해제 UI 전환
 *
 * 변경 이력:
 * - 기존 5개 함수(observeConnectedDevices, observeConnectionStates,
 *   observeConnectedCount, observeFirstConnectedDevice, observeDisconnectEvents)
 *   → StateFlow 기반 Conflation 버그 제거
 *   → SharedFlow 기반 observeDeviceStateEvents() 1개로 통합
 */
object ObserveDeviceStatesUseCase {

    private const val TAG = AppConstants.Feature.UC_OBSERVE_DEVICE

    /**
     * 디바이스 상태 변경 이벤트
     *
     * @property mac   상태가 변경된 디바이스 MAC 주소
     * @property state 변경된 연결 상태
     */
    data class DeviceStateEvent(
        val mac:   String,
        val state: ConnectionState
    )

    /**
     * 디바이스 상태 변경 이벤트를 관찰합니다.
     *
     * SDK SharedFlow 기반으로 StateFlow Conflation 문제 없이
     * Connected / Disconnected / Connecting 전환을 모두 수신합니다.
     *
     * ## 수신 이벤트 종류
     * | State            | 상황                              |
     * |------------------|-----------------------------------|
     * | Connected        | GATT 연결 완료                    |
     * | Disconnected     | 전원 OFF / 범위 이탈 / GATT 오류  |
     * | Connecting       | 연결 시도 중                      |
     * | Disconnecting    | 연결 해제 진행 중                 |
     *
     * @return Flow<DeviceStateEvent>
     */
    fun observeDeviceStateEvents(): Flow<DeviceStateEvent> {
        return LSBluetooth.observeDeviceStateEvents()
            .map { event ->
                Log.d(TAG, "DeviceStateEvent: mac=${event.mac} state=${event.state}")
                DeviceStateEvent(mac = event.mac, state = event.state)
            }
    }
}