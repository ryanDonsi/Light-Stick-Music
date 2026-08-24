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

            // EFFECT LIST(그룹 물결 등)가 재생 중이었다면 SDK 내부 playJob이 우리 쪽
            // effectListJob 취소와 무관하게 예약된 프레임을 끝까지 전송한다. play()를
            // 다시 호출하면 SDK가 이전 playJob을 취소하므로, OFF 프레임 1개짜리 play를
            // 걸어서 즉시 중단시킨다(sendEffect()의 stopTimeline()은 playJob과 무관한
            // 별개 Job만 취소해서 이 용도로는 동작하지 않음).
            EffectEngineController.playFrames(
                context = context,
                frames = listOf(0L to offPayload.toByteArray())
            )

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
