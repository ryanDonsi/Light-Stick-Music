package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController
import java.io.File
import javax.inject.Inject

/**
 * EFX 파일 기반 이펙트 로드 UseCase
 *
 * 책임:
 * - EffectEngineController.loadEffectsFor() 호출
 * - 로드된 이펙트 개수 반환
 *
 * 사용:
 * - MusicViewModel.playMusic()
 */
class LoadEfxUseCase @Inject constructor() {

    /**
     * EFX 이펙트 로드
     *
     * @param context Android Context
     * @param musicFile 음악 파일
     * @return Result<Int> 성공 시 로드된 이펙트 개수, 실패 시 Exception
     */
    operator fun invoke(
        context: Context,
        musicFile: File
    ): Result<Int> {
        return try {
            EffectEngineController.loadEffectsFor(context, musicFile)

            Result.success(EffectEngineController.getLoadedEffectCount())

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
