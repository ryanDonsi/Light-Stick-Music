package com.lightstick.music.domain.usecase.device

import com.lightstick.LSBluetooth
import com.lightstick.device.DeviceInfo

/**
 * 캐시된 디바이스 정보 조회 UseCase
 *
 * 책임:
 * - Connected 이벤트 수신 후 SDK 캐시에서 DeviceInfo 조회
 * - GATT 연결 후 비동기로 읽혀진 firmware, battery, model 등 정보 제공
 *
 * 사용처:
 * - DeviceViewModel: onDeviceConnectedFromSdk()
 */
class GetCachedDeviceInfoUseCase {

    /**
     * 캐시된 디바이스 정보 조회
     *
     * @param mac 디바이스 MAC 주소
     * @return DeviceInfo? 캐시 있으면 반환, 없으면 null
     */
    operator fun invoke(mac: String): DeviceInfo? {
        return LSBluetooth.getCachedDeviceInfo(mac)
    }
}