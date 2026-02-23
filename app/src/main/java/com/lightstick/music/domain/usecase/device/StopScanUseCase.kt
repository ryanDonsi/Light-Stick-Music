package com.lightstick.music.domain.usecase.device

import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.music.core.permission.PermissionManager

/**
 * BLE 스캔 중지 UseCase
 *
 * 책임:
 * - BLUETOOTH_SCAN 권한 체크
 * - LSBluetooth.stopScan() 호출
 *
 * 사용처:
 * - DeviceViewModel: stopScan()
 */
class StopScanUseCase {

    /**
     * BLE 스캔 중지
     *
     * @param context Android Context
     * @return Result<Unit> 성공 또는 실패
     */
    operator fun invoke(context: Context): Result<Unit> {
        return try {
            // ✅ 1. Permission 체크
            if (!PermissionManager.hasBluetoothScanPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_SCAN permission required")
                )
            }

            // ✅ 2. 스캔 중지
            LSBluetooth.stopScan()

            // ✅ 3. 성공 반환
            Result.success(Unit)

        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}