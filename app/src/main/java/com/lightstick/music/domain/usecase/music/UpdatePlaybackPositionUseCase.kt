package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController

/**
 * 재생 위치 업데이트 UseCase
 *
 * 책임:
 * - EffectEngineController.updatePlaybackPosition() 호출
 * - Timeline Effect 자동 Monitor 기록 (내부에서)
 *
 * 사용:
 * - MusicViewModel.updatePosition()
 */
class UpdatePlaybackPositionUseCase {

    /**
     * 재생 위치 업데이트
     *
     * @param context Android Context
     * @param currentPositionMs 현재 재생 위치 (밀리초)
     * @return Result<Unit>
     */
    operator fun invoke(
        context: Context,
        currentPositionMs: Long
    ): Result<Unit> {
        return try {
            // ✅ EffectEngineController 호출 (내부에서 Monitor 기록)
            EffectEngineController.updatePlaybackPosition(context, currentPositionMs)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}