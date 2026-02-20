package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController
import java.io.File

/**
 * 음악 타임라인 로드 UseCase
 *
 * 책임:
 * - EffectEngineController.loadEffectsFor() 호출
 * - 로드된 이펙트 개수 반환
 *
 * 사용:
 * - MusicViewModel.loadMusicFile()
 */
class LoadMusicTimelineUseCase {

    /**
     * 음악 타임라인 로드
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
            // ✅ EffectEngineController 호출
            EffectEngineController.loadEffectsFor(context, musicFile)

            // TODO: 개수 반환 (현재는 0 반환, 필요시 EffectEngineController에서 개수 반환하도록 수정)
            Result.success(0)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}