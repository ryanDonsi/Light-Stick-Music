package com.lightstick.music.domain.usecase.device

import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.music.core.permission.PermissionManager
import kotlinx.coroutines.delay

/**
 * BLE 스캔 시작 UseCase
 *
 * 책임:
 * - BLUETOOTH_SCAN 권한 체크
 * - LSBluetooth.startScan() 호출
 * - 스캔 결과 수집 및 필터링
 * - 자동 스캔 중지
 *
 * 사용처:
 * - DeviceViewModel: startScan()
 * - EffectViewModel: startAutoScan()
 */
class StartScanUseCase {

    /**
     * BLE 스캔 시작 및 결과 수집
     *
     * @param context Android Context
     * @param durationMs 스캔 지속 시간 (밀리초)
     * @param filter 디바이스 필터 함수 (기본: 모두 허용)
     * @return Result<List<Device>> 성공 시 스캔된 디바이스 리스트
     */
    suspend operator fun invoke(
        context: Context,
        durationMs: Long = 3000L,
        filter: (Device) -> Boolean = { true }
    ): Result<List<Device>> {
        return try {
            // ✅ 1. Permission 체크
            if (!PermissionManager.hasBluetoothScanPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_SCAN permission required")
                )
            }

            // ✅ 2. 스캔 결과 저장용 Map (MAC 주소로 중복 제거)
            val scannedDevices = mutableMapOf<String, Device>()

            // ✅ 3. 스캔 시작
            LSBluetooth.startScan { device ->
                // 필터 조건을 만족하는 디바이스만 수집
                if (filter(device)) {
                    scannedDevices[device.mac] = device
                }
            }

            // ✅ 4. 지정된 시간 동안 대기
            delay(durationMs)

            // ✅ 5. 스캔 중지
            LSBluetooth.stopScan()

            // ✅ 6. 결과 반환
            Result.success(scannedDevices.values.toList())

        } catch (e: SecurityException) {
            // 스캔 중지 시도
            try {
                LSBluetooth.stopScan()
            } catch (ignored: Exception) {
                // Ignore cleanup errors
            }
            Result.failure(e)
        } catch (e: Exception) {
            // 스캔 중지 시도
            try {
                LSBluetooth.stopScan()
            } catch (ignored: Exception) {
                // Ignore cleanup errors
            }
            Result.failure(e)
        }
    }
}