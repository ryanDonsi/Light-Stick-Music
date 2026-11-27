package com.lightstick.music.domain.usecase.device

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import com.lightstick.device.connectAwait
import com.lightstick.music.core.permission.PermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * 디바이스 연결 UseCase
 *
 * 책임:
 * - BLUETOOTH_CONNECT 권한 체크
 * - SDK [connectAwait] 호출 (연결 준비 완료까지 대기, 시간 제한 [CONNECT_TIMEOUT_MS])
 * - 연결 결과를 Result로 변환
 *
 * 예전에는 callback 을 CompletableDeferred 로 감싸 시간 제한 없이 기다렸고, 호출한 코루틴이
 * 취소돼도 SDK 연결은 계속됐다. connectAwait 는 취소되면 연결도 끊는다(시간 초과 포함).
 *
 * 사용처:
 * - DeviceViewModel: connect(), syncCurrentlyConnectedDevices()
 * - EffectViewModel: startAutoScan()
 */
class ConnectDeviceUseCase @Inject constructor() {

    companion object {
        /** SDK 내부 재시도(133/0x3E 1회)까지 포함해 넉넉히 잡은 연결 대기 상한 */
        const val CONNECT_TIMEOUT_MS = 30_000L
    }

    /**
     * 디바이스 연결
     *
     * @param context Android Context
     * @param device 연결할 디바이스
     * @param onConnected 연결 성공 시 콜백 (메인 스레드)
     * @param onFailed 연결 실패 시 콜백 (시간 초과 포함)
     * @param onDeviceInfo 연결 중 읽은 DIS 가 있으면 연결 성공 직전에 한 번 호출
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
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission required"))
        }

        return try {
            val info = withTimeout(CONNECT_TIMEOUT_MS) { device.connectAwait() }
            info?.let(onDeviceInfo)
            onConnected()
            Result.success(Unit)
        } catch (e: TimeoutCancellationException) {
            val error = IllegalStateException("Connection timed out after ${CONNECT_TIMEOUT_MS}ms", e)
            onFailed(error)
            Result.failure(error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailed(e)
            Result.failure(e)
        }
    }
}
