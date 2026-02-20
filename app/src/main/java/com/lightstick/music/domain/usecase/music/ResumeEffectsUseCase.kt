package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController

/**
 * 이펙트 재개 UseCase
 *
 * 책임:
 * - EffectEngineController.resumeEffects() 호출
 * - syncIndex 자동 증가 (재동기화)
 *
 * 사용:
 * - MusicViewModel.resume()
 */
class ResumeEffectsUseCase {

    /**
     * 이펙트 재개
     *
     * @param context Android Context
     * @return Result<Unit>
     */
    operator fun invoke(
        context: Context
    ): Result<Unit> {
        return try {
            // ✅ EffectEngineController 호출
            EffectEngineController.resumeEffects(context)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}