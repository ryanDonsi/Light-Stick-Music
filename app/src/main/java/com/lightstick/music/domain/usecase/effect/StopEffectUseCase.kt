package com.lightstick.music.domain.usecase.effect

import android.content.Context
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.Job

/**
 * Effect 중지 UseCase
 *
 * 책임:
 * - EffectList Job 취소
 * - OFF 이펙트 전송
 * - Monitor 자동 기록 (EffectEngineController에서)
 *
 * 사용:
 * - EffectViewModel.stopEffect()
 */
class StopEffectUseCase {

    /**
     * Effect 중지
     *
     * @param context Android Context
     * @param effectListJob 실행 중인 EffectList Job (null이면 무시)
     * @return Result<Unit> 성공 시 Unit, 실패 시 Exception
     */
    operator fun invoke(
        context: Context,
        effectListJob: Job?
    ): Result<Unit> {
        return try {
            // ✅ 1. EffectList Job 취소
            effectListJob?.cancel()

            // ✅ 2. OFF 전송 (자동 Monitor 기록)
            val offPayload = LSEffectPayload.Effects.off()

            EffectEngineController.sendEffect(
                context = context,
                payload = offPayload,
                source = TransmissionSource.MANUAL_EFFECT,
                metadata = mapOf("type" to "manual_stop")
            )

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}