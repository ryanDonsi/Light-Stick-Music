package com.lightstick.music.domain.usecase.device

import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.music.core.permission.PermissionManager
import javax.inject.Inject

/**
 * 페어링된 디바이스 조회 UseCase
 *
 * 책임:
 * - BLUETOOTH_CONNECT 권한 체크
 * - LSBluetooth.bondedDevices() 호출
 * - 결과 반환
 *
 * 사용처:
 * - DeviceViewModel: syncConnectedDevices()
 * - EffectViewModel: startAutoScan()
 */
class GetBondedDevicesUseCase @Inject constructor() {

    /**
     * 페어링된 디바이스 목록 조회
     *
     * @param context Android Context
     * @return Result<List<Device>> 성공 시 디바이스 리스트, 실패 시 에러
     */
    operator fun invoke(context: Context): Result<List<Device>> {
        return try {
            // ✅ 1. Permission 체크
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_CONNECT permission required")
                )
            }

            // ✅ 2. Bonded 디바이스 조회
            val bondedDevices = LSBluetooth.bondedDevices()

            // ✅ 3. 성공 반환
            Result.success(bondedDevices)

        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}