package com.lightstick.music.domain.usecase.device

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.device.Device
import com.lightstick.music.core.permission.PermissionManager

/**
 * 디바이스 연결 해제 UseCase
 *
 * 책임:
 * - BLUETOOTH_CONNECT 권한 체크
 * - device.disconnect() 호출
 *
 * 사용처:
 * - DeviceViewModel: disconnect()
 */
class DisconnectDeviceUseCase {

    /**
     * 디바이스 연결 해제
     *
     * @param context Android Context
     * @param device 연결 해제할 디바이스
     * @return Result<Unit> 성공 또는 실패
     */
    @SuppressLint("MissingPermission")
    operator fun invoke(
        context: Context,
        device: Device
    ): Result<Unit> {
        return try {
            // ✅ 1. Permission 체크
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_CONNECT permission required")
                )
            }

            // ✅ 2. 연결 해제
            device.disconnect()

            // ✅ 3. 성공 반환
            Result.success(Unit)

        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}