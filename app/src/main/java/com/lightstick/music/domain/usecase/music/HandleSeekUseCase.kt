package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController

/**
 * Seek 처리 UseCase
 *
 * 책임:
 * - EffectEngineController.handleSeek() 호출
 * - Timeline 인덱스 리셋
 *
 * 사용:
 * - MusicViewModel.handleSeek()
 */
class HandleSeekUseCase {

    /**
     * Seek 처리
     *
     * @param context Android Context
     * @param newPositionMs 새로운 재생 위치 (밀리초)
     * @return Result<Unit>
     */
    operator fun invoke(
        context: Context,
        newPositionMs: Long
    ): Result<Unit> {
        return try {
            // ✅ EffectEngineController 호출
            EffectEngineController.handleSeek(context, newPositionMs)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}