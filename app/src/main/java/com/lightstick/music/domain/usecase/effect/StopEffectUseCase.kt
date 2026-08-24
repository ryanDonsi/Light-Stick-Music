package com.lightstick.music.domain.usecase.effect

import android.content.Context
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.Job
import javax.inject.Inject

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
class StopEffectUseCase @Inject constructor() {

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
            effectListJob?.cancel()

            val offPayload = LSEffectPayload.Effects.off()

            EffectEngineController.sendEffect(
                context = context,
                payload = offPayload,
                source = TransmissionSource.PAYLOAD_EFFECT,
                metadata = mapOf("type" to "manual_stop")
            )

            // EFFECT LIST(그룹 물결 등)는 loadTimeline() 기반이라 stopTimeline()으로
            // 즉시 중단 가능 — sendEffect()의 자체 stopTimeline() 호출과는 별개로,
            // playFrames()가 재생을 걸었던 대상 기기 쪽 loadTimeline도 명시적으로 멈춘다.
            EffectEngineController.stopPlayFrames(context)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
