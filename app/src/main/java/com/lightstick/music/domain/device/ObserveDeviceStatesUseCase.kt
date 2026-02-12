package com.lightstick.music.domain.device

import android.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.device.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    private const val TAG = "ObserveDeviceStatesUseCase"

    /**
     * 연결된 디바이스 목록을 관찰합니다.
     *
     * @return Flow<List<Device>>
     */
    fun observeConnectedDevices(): Flow<List<Device>> {
        return LSBluetooth.observeDeviceStates()
            .map { states ->
                val connected = states
                    .filter { (_, state) ->
                        state.connectionState is ConnectionState.Connected
                    }
                    .map { (mac, state) ->
                        Device(
                            mac = mac,
                            name = state.deviceInfo?.deviceName,
                            rssi = state.deviceInfo?.rssi
                        )
                    }

                Log.d(TAG, "📱 Connected devices: ${connected.size}")
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
     *
     * @return Flow<Int>
     */
    fun observeConnectedCount(): Flow<Int> {
        return LSBluetooth.observeDeviceStates()
            .map { states ->
                states.count { (_, state) ->
                    state.connectionState is ConnectionState.Connected
                }
            }
    }

    /**
     * 연결된 디바이스 중 첫 번째를 가져옵니다.
     * EffectViewModel에서 사용.
     *
     * @param preferLsDevice "LS"로 끝나는 디바이스 우선
     * @return Flow<Device?>
     */
    fun observeFirstConnectedDevice(preferLsDevice: Boolean = true): Flow<Device?> {
        return observeConnectedDevices()
            .map { devices ->
                if (preferLsDevice) {
                    devices.firstOrNull { it.name?.endsWith("LS") == true }
                        ?: devices.firstOrNull()
                } else {
                    devices.firstOrNull()
                }
            }
    }
}