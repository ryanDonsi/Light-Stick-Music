package com.lightstick.music.domain.usecase.device

import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.music.core.permission.PermissionManager

/**
 * 현재 연결된 디바이스 목록 조회 UseCase
 *
 * 책임:
 * - BLUETOOTH_CONNECT 권한 체크
 * - LSBluetooth.connectedDevices() 호출
 * - 결과 반환
 *
 * 사용처:
 * - EffectViewModel: startAutoScan() → 이미 연결된 기기 있으면 스캔 skip
 */
class GetConnectedDevicesUseCase {

    operator fun invoke(context: Context): Result<List<Device>> {
        return try {
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_CONNECT permission required")
                )
            }

            Result.success(LSBluetooth.connectedDevices())

        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}