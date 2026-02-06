package com.lightstick.music.domain.device

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.lightstick.device.Device
import com.lightstick.music.core.ble.BleTransmissionCoordinator
import com.lightstick.music.core.ble.ControlMode
import com.lightstick.music.core.ble.TransmissionPriority
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.delay

/**
 * 디바이스 연결 효과 헬퍼
 *
 * 디바이스 연결 성공 시 표시할 연출 효과를 관리합니다.
 *
 * ✅ Phase 2-3 개선:
 * - BleTransmissionCoordinator 통합
 * - CONNECTION_EFFECT 우선순위 (최고)
 * - Session 기반 독점 제어
 */
object DeviceConnectionEffectHelper {

    private const val TAG = "DeviceConnEffect"

    /**
     * 디바이스 연결 성공 연출 실행
     *
     * BLINK 3번 → WHITE ON으로 전환
     *
     * @param device 연결된 디바이스
     * @return true if animation completed successfully
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun playConnectionAnimation(device: Device): Boolean {
        return try {
            // ✅ Session 시작 (최고 우선순위)
            val started = BleTransmissionCoordinator.startSession(
                source = TransmissionSource.CONNECTION_EFFECT,
                priority = TransmissionPriority.CONNECTION_EFFECT,
                mode = ControlMode.EXCLUSIVE
            )

            if (!started) {
                Log.w(TAG, "❌ Cannot start connection animation - control denied")
                return false
            }

            try {
                // 연결 애니메이션 타임라인
                val connectionAnimation = listOf(
                    0L to LSEffectPayload.Effects.blink(3, Colors.WHITE).toByteArray(),
                    1200L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
                )

                // 타임라인 로드
                if (!device.loadTimeline(connectionAnimation)) {
                    Log.w(TAG, "⚠️ Failed to load connection animation timeline")
                    return false
                }

                Log.d(TAG, "🎬 Connection animation timeline loaded (2 frames)")

                // ✅ Monitor 기록 (시작)
                val startEvent = BleTransmissionEvent(
                    source = TransmissionSource.CONNECTION_EFFECT,
                    deviceMac = device.mac,
                    effectType = EffectType.BLINK,
                    payload = LSEffectPayload.Effects.blink(3, Colors.WHITE),
                    color = Colors.WHITE,
                    metadata = mapOf("animation" to "connection_start")
                )
                BleTransmissionCoordinator.sendEffect(startEvent)

                // 재생 위치 업데이트 (1.2초 동안)
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

                // 타임라인 정지
                device.stopTimeline()

                // ✅ Monitor 기록 (종료)
                val endEvent = BleTransmissionEvent(
                    source = TransmissionSource.CONNECTION_EFFECT,
                    deviceMac = device.mac,
                    effectType = EffectType.ON,
                    payload = LSEffectPayload.Effects.on(Colors.WHITE),
                    color = Colors.WHITE,
                    metadata = mapOf("animation" to "connection_end")
                )
                BleTransmissionCoordinator.sendEffect(endEvent)

                Log.d(TAG, "✅ Connection animation completed")
                true

            } finally {
                // ✅ Session 종료 (항상 실행)
                BleTransmissionCoordinator.endSession(TransmissionSource.CONNECTION_EFFECT)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection animation failed: ${e.message}")

            // 에러 발생 시에도 Session 해제
            BleTransmissionCoordinator.endSession(TransmissionSource.CONNECTION_EFFECT)
            false
        }
    }

    /**
     * 간단한 연결 확인 신호 (짧은 버전)
     *
     * BLINK 1번만 실행
     *
     * @param device 연결된 디바이스
     * @return true if signal sent successfully
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendConnectionSignal(device: Device): Boolean {
        return try {
            // Session 시작
            val started = BleTransmissionCoordinator.startSession(
                source = TransmissionSource.CONNECTION_EFFECT,
                priority = TransmissionPriority.CONNECTION_EFFECT,
                mode = ControlMode.EXCLUSIVE
            )

            if (!started) {
                Log.w(TAG, "❌ Cannot send connection signal - control denied")
                return false
            }

            try {
                // 짧은 BLINK
                val payload = LSEffectPayload.Effects.blink(1, Colors.WHITE)

                // Monitor 기록
                val event = BleTransmissionEvent(
                    source = TransmissionSource.CONNECTION_EFFECT,
                    deviceMac = device.mac,
                    effectType = EffectType.BLINK,
                    payload = payload,
                    color = Colors.WHITE,
                    metadata = mapOf("signal" to "connection_confirm")
                )
                BleTransmissionCoordinator.sendEffect(event)

                // 전송
                device.sendEffect(payload)

                // 짧은 대기 (애니메이션 완료 대기)
                delay(300)

                Log.d(TAG, "✅ Connection signal sent")
                true

            } finally {
                // Session 종료
                BleTransmissionCoordinator.endSession(TransmissionSource.CONNECTION_EFFECT)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection signal failed: ${e.message}")
            BleTransmissionCoordinator.endSession(TransmissionSource.CONNECTION_EFFECT)
            false
        }
    }
}