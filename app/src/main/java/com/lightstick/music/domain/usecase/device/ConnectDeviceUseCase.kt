package com.lightstick.music.domain.usecase.device

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import com.lightstick.music.core.permission.PermissionManager
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject

/**
 * 디바이스 연결 UseCase
 *
 * 책임:
 * - BLUETOOTH_CONNECT 권한 체크
 * - device.connect() 호출
 * - 연결 결과를 Result로 변환
 *
 * 사용처:
 * - DeviceViewModel: connect()
 * - EffectViewModel: startAutoScan()
 */
class ConnectDeviceUseCase @Inject constructor() {

    /**
     * 디바이스 연결
     *
     * @param context Android Context
     * @param device 연결할 디바이스
     * @param onConnected 연결 성공 시 콜백
     * @param onFailed 연결 실패 시 콜백
     * @return Result<Unit> 성공 또는 실패
     */
    @SuppressLint("MissingPermission")
    suspend operator fun invoke(
        context: Context,
        device: Device,
        onConnected: () -> Unit = {},
        onFailed: (Throwable) -> Unit = {},
        onDeviceInfo: (DeviceInfo) -> Unit = {}
    ): Result<Unit> {
        return try {
            // ✅ 1. Permission 체크
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_CONNECT permission required")
                )
            }

            // ✅ 2. 비동기 연결을 동기적으로 대기
            val completionDeferred = CompletableDeferred<Result<Unit>>()

            device.connect(
                onConnected = {
                    onConnected()
                    completionDeferred.complete(Result.success(Unit))
                },
                onFailed = { error ->
                    onFailed(error)
                    completionDeferred.complete(Result.failure(error))
                },
                onDeviceInfo = { info ->
                    onDeviceInfo(info)
                }
            )

            // ✅ 3. 연결 완료 대기
            completionDeferred.await()

        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}