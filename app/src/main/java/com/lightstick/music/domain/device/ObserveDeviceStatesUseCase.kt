package com.lightstick.music.domain.device

import com.lightstick.music.core.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.device.Device
import com.lightstick.music.core.constants.AppConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.transform

/**
 * 디바이스 연결 상태를 관찰하는 UseCase
 *
 * 책임:
 * - SDK의 복잡한 상태를 비즈니스 도메인으로 변환
 * - DeviceViewModel과 EffectViewModel의 공통 로직 통합
 * - 단일 진실 공급원 (Single Source of Truth)
 *
 * 사용처:
 * - DeviceViewModel: 전체 디바이스 목록 관리
 * - EffectViewModel: 연결된 디바이스 상태 표시
 */
object ObserveDeviceStatesUseCase {

    private const val TAG = AppConstants.Feature.UC_OBSERVE_DEVICE

    /**
     * 연결된 디바이스 목록을 관찰합니다.
     */
    fun observeConnectedDevices(): Flow<List<Device>> {
        return LSBluetooth.observeDeviceStates()
            .map { states ->
                val connected = states
                    .filter { (_, state) -> state.connectionState is ConnectionState.Connected }
                    .map { (mac, state) ->
                        Device(mac = mac, name = state.deviceInfo?.deviceName, rssi = state.deviceInfo?.rssi)
                    }
                Log.d(TAG, "Connected devices: ${connected.size}")
                connected
            }
    }

    /**
     * 연결 상태 Map을 관찰합니다.
     *
     * @return Flow<Map<MAC, isConnected>>
     */
    fun observeConnectionStates(): Flow<Map<String, Boolean>> {
        return LSBluetooth.observeDeviceStates()
            .map { states ->
                states.mapValues { (_, state) ->
                    state.connectionState is ConnectionState.Connected
                }
            }
    }

    /**
     * 연결된 디바이스 개수를 관찰합니다.
     */
    fun observeConnectedCount(): Flow<Int> {
        return LSBluetooth.observeDeviceStates()
            .map { states ->
                states.count { (_, state) -> state.connectionState is ConnectionState.Connected }
            }
    }

    /**
     * 연결된 디바이스 중 첫 번째를 가져옵니다.
     * EffectViewModel에서 연결 감지 시 사용.
     *
     * @param preferLsDevice "LS"로 끝나는 디바이스 우선
     */
    fun observeFirstConnectedDevice(preferLsDevice: Boolean = true): Flow<Device?> {
        return observeConnectedDevices()
            .map { devices ->
                if (preferLsDevice) {
                    devices.firstOrNull { it.name?.endsWith("LS") == true } ?: devices.firstOrNull()
                } else {
                    devices.firstOrNull()
                }
            }
    }

    /**
     * 디바이스 연결 해제 이벤트를 감지합니다.
     *
     * Connected → Disconnected 전환이 발생한 경우에만 emit 합니다.
     * 앱 시작 시 이미 Disconnected 상태인 기기는 감지하지 않습니다.
     *
     * emit 데이터: [DisconnectEvent]
     * - mac    : 연결 해제된 기기 MAC 주소
     * - reason : SDK 제공 해제 이유 (로그 출력 전용)
     *
     * ## DisconnectReason 종류
     * | Reason               | 상황                                 |
     * |----------------------|--------------------------------------|
     * | USER_REQUESTED       | 앱 또는 사용자가 직접 연결 해제       |
     * | DEVICE_POWERED_OFF   | 기기 전원 꺼짐 / 배터리 방전         |
     * | TIMEOUT              | 연결 응답 시간 초과                  |
     * | OUT_OF_RANGE         | 기기가 Bluetooth 범위 벗어남         |
     * | GATT_ERROR           | BLE GATT 오류 (status 133 등)        |
     * | UNKNOWN              | 원인 불명                            |
     */
    fun observeDisconnectEvents(): Flow<DisconnectEvent> {
        return LSBluetooth.observeDeviceStates()
            // scan: 이전 상태(prev)와 현재 상태(curr)를 함께 추적
            .scan(
                initial = Pair(
                    emptyMap<String, ConnectionState>(),
                    emptyMap<String, ConnectionState>()
                )
            ) { (_, prev), currDeviceStates ->
                val curr = currDeviceStates.mapValues { it.value.connectionState }
                Pair(prev, curr)
            }
            .transform { (prev, curr) ->
                curr.forEach { (mac, currState) ->
                    val prevState = prev[mac]
                    // Connected → Disconnected 전환만 emit
                    if (prevState is ConnectionState.Connected &&
                        currState is ConnectionState.Disconnected) {
                        emit(DisconnectEvent(mac = mac, reason = currState.reason))
                    }
                }
            }
    }

    /**
     * 디바이스 연결 해제 이벤트 데이터
     *
     * @property mac    연결 해제된 기기 MAC 주소
     * @property reason 연결 해제 이유 (로그 출력 전용)
     */
    data class DisconnectEvent(
        val mac:    String,
        val reason: ConnectionState.DisconnectReason
    )
}