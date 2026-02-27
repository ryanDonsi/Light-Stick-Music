package com.lightstick.music.domain.usecase.device

import android.content.Context
import com.lightstick.music.core.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.permission.PermissionManager
import kotlinx.coroutines.delay

/**
 * BLE 스캔 시작 UseCase
 *
 * 책임:
 * - BLUETOOTH_SCAN 권한 체크
 * - LSBluetooth.startScan() 호출
 * - 스캔 결과 수집 및 필터링
 * - durationMs 경과 후 스캔 중지 및 결과 반환
 *
 * 사용처:
 * - DeviceViewModel.startScan() / refreshScan()  → DEVICE_SCAN_DURATION_MS (30초)
 * - EffectViewModel.startAutoScan()              → EFFECT_SCAN_DURATION_MS  (3초)
 *
 * [수정]
 * - onFound 콜백 파라미터 추가
 *   → 발견 즉시 ViewModel에서 _devices 실시간 업데이트 가능
 */
class StartScanUseCase {

    companion object {
        private const val TAG = AppConstants.Feature.UC_START_SCAN
    }

    /**
     * BLE 스캔 시작 및 결과 수집
     *
     * @param context    Android Context
     * @param durationMs 스캔 지속 시간 (밀리초). 기본값: [AppConstants.DEVICE_SCAN_DURATION_MS]
     * @param filter     디바이스 필터 함수 (기본: 모두 허용)
     * @param onFound    디바이스 발견 즉시 호출되는 실시간 콜백 (optional)
     *                   - null 이면 최종 결과만 반환
     *                   - 신규/기존 디바이스 모두 RSSI 갱신 시 호출됨
     * @return Result<List<Device>> 스캔 완료 후 전체 결과
     */
    suspend operator fun invoke(
        context: Context,
        durationMs: Long = AppConstants.DEVICE_SCAN_DURATION_MS,
        filter: (Device) -> Boolean = { true },
        onFound: ((Device) -> Unit)? = null
    ): Result<List<Device>> {
        return try {
            // 1. Permission 체크
            if (!PermissionManager.hasBluetoothScanPermission(context)) {
                return Result.failure(SecurityException("BLUETOOTH_SCAN permission required"))
            }

            // 2. 스캔 결과 저장용 Map (MAC 주소로 중복 제거)
            val scannedDevices = mutableMapOf<String, Device>()

            // 3. 스캔 시작
            val durationSec = (durationMs / 1000).toInt().coerceIn(1, 300)

            LSBluetooth.startScan(scanTimeSeconds = durationSec) { device ->
                if (filter(device)) {
                    val isNew = !scannedDevices.containsKey(device.mac)
                    scannedDevices[device.mac] = device

                    // 신규 발견 또는 RSSI 갱신 모두 즉시 콜백
                    onFound?.invoke(device)

                    if (isNew) {
                        Log.d(TAG, "📡 New device: ${device.mac} (${device.name}) RSSI:${device.rssi}")
                    }
                }
            }

            Log.d(TAG, "🔍 Scan started: ${durationSec}s")

            // 4. 지정된 시간 동안 대기
            delay(durationMs)

            // 5. 스캔 중지
            LSBluetooth.stopScan()
            Log.d(TAG, "✅ Scan done: ${scannedDevices.size} device(s) found")

            // 6. 결과 반환
            Result.success(scannedDevices.values.toList())

        } catch (e: SecurityException) {
            runCatching { LSBluetooth.stopScan() }
            Result.failure(e)
        } catch (e: Exception) {
            runCatching { LSBluetooth.stopScan() }
            Result.failure(e)
        }
    }
}