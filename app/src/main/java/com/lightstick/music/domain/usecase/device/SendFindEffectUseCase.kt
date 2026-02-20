package com.lightstick.music.domain.usecase.device

import android.content.Context
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.delay

/**
 * FIND Effect 전송 UseCase
 *
 * 책임:
 * - 특정 디바이스에 빠른 BLINK 전송
 * - 3초 후 자동 OFF
 * - Monitor 자동 기록
 *
 * 사용:
 * - DeviceViewModel.sendFindEffect()
 */
class SendFindEffectUseCase {

    /**
     * FIND Effect 전송
     *
     * @param context Android Context
     * @param deviceMac 대상 디바이스 MAC 주소
     * @return Result<Unit>
     */
    suspend operator fun invoke(
        context: Context,
        deviceMac: String
    ): Result<Unit> {
        return try {
            // ✅ 1. BLINK payload 생성 (빠른 깜빡임)
            val findPayload = LSEffectPayload.Effects.blink(
                period = 3,
                color = Colors.WHITE,
                randomColor = 1
            )

            // ✅ 2. 특정 디바이스에 전송 (자동 Monitor 기록)
            val success = EffectEngineController.sendEffectToDevice(
                context = context,
                deviceMac = deviceMac,
                payload = findPayload,
                source = TransmissionSource.MANUAL_EFFECT,
                metadata = mapOf("type" to "find_effect")
            )

            if (!success) {
                return Result.failure(Exception("Device not found or not connected"))
            }

            // ✅ 3. 3초 후 자동 OFF
            delay(3000)

            val offPayload = LSEffectPayload.Effects.on(Colors.WHITE)
            EffectEngineController.sendEffectToDevice(
                context = context,
                deviceMac = deviceMac,
                payload = offPayload,
                source = TransmissionSource.MANUAL_EFFECT,
                metadata = mapOf("type" to "find_effect_end")
            )

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}