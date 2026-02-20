package com.lightstick.music.domain.usecase.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightstick.device.Device
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.delay

/**
 * Connection Effect 재생 UseCase
 *
 * 책임:
 * - 연결 성공 시 애니메이션 재생
 * - Timeline frames 생성 및 재생
 * - Monitor 기록
 *
 * 사용:
 * - EffectViewModel (디바이스 연결 시)
 */
@SuppressLint("MissingPermission")
class SendConnectionEffectUseCase {

    private val TAG = "SendConnectionEffectUseCase"

    /**
     * Connection Effect 재생
     *
     * @param context Android Context (호환성 유지용, 실제 사용 안 함)
     * @param device 연결된 디바이스
     * @return Result<Unit>
     */
    suspend operator fun invoke(
        context: Context,
        device: Device
    ): Result<Unit> {
        return try {
            // ✅ Permission 체크
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                return Result.failure(SecurityException("BLUETOOTH_CONNECT permission required"))
            }

            // ✅ 1. Connection 애니메이션 frames 생성
            val frames = createConnectionAnimationFrames()

            // ✅ 2. Timeline 로드
            if (!device.loadTimeline(frames)) {
                Log.w(TAG, "⚠️ Failed to load connection animation timeline")
                return Result.failure(Exception("Failed to load timeline"))
            }

            Log.d(TAG, "🎬 Connection animation timeline loaded")

            // ✅ 3. Monitor 기록 (시작)
            val startEvent = BleTransmissionEvent(
                source = TransmissionSource.CONNECTION_EFFECT,
                deviceMac = device.mac,
                effectType = EffectType.BLINK,
                payload = LSEffectPayload.Effects.blink(3, Colors.WHITE),
                color = Colors.WHITE,
                metadata = mapOf("animation" to "connection_start")
            )
            BleTransmissionMonitor.recordTransmission(startEvent)

            // ✅ 4. 재생 위치 업데이트 (1.2초 동안)
            val startTime = System.currentTimeMillis()
            val duration = 1200L

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= duration) {
                    device.updatePlaybackPosition(duration)
                    delay(50)
                    break
                }
                device.updatePlaybackPosition(elapsed)
                delay(16)  // 60 FPS
            }

            // ✅ 5. Timeline 정지
            device.stopTimeline()

            // ✅ 6. Monitor 기록 (종료)
            val endEvent = BleTransmissionEvent(
                source = TransmissionSource.CONNECTION_EFFECT,
                deviceMac = device.mac,
                effectType = EffectType.ON,
                payload = LSEffectPayload.Effects.on(Colors.WHITE),
                color = Colors.WHITE,
                metadata = mapOf("animation" to "connection_end")
            )
            BleTransmissionMonitor.recordTransmission(endEvent)

            Log.d(TAG, "✅ Connection animation completed")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection animation failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Connection 애니메이션 frames 생성
     *
     * BLINK 3번 → WHITE ON으로 전환
     */
    private fun createConnectionAnimationFrames(): List<Pair<Long, ByteArray>> {
        return listOf(
            0L to LSEffectPayload.Effects.blink(3, Colors.WHITE).toByteArray(),
            1200L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
        )
    }

//    /**
//     * BLUETOOTH_CONNECT 권한 확인
//     */
//    private fun hasBleConnectPermission(context: Context): Boolean =
//        ContextCompat.checkSelfPermission(
//            context,
//            Manifest.permission.BLUETOOTH_CONNECT
//        ) == PackageManager.PERMISSION_GRANTED
}