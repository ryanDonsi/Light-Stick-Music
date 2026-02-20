package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController

/**
 * 이펙트 일시정지 UseCase
 *
 * 책임:
 * - EffectEngineController.pauseEffects() 호출
 * - Timeline 추적은 유지하되 BLE 전송만 중단
 *
 * 사용:
 * - MusicViewModel.pause()
 */
class PauseEffectsUseCase {

    /**
     * 이펙트 일시정지
     *
     * @param context Android Context
     * @return Result<Unit>
     */
    operator fun invoke(
        context: Context
    ): Result<Unit> {
        return try {
            // ✅ EffectEngineController 호출
            EffectEngineController.pauseEffects(context)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}