package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.music.data.model.FrequencyBand

/**
 * FFT 데이터 처리 UseCase
 *
 * 책임:
 * - EffectEngineController.processFFT() 호출
 * - Timeline이 없을 때만 FFT 색상 전송
 *
 * 사용:
 * - MusicViewModel.processFFT()
 */
class ProcessFFTUseCase {

    /**
     * FFT 데이터 처리
     *
     * @param context Android Context
     * @param band FrequencyBand (bass, mid, treble)
     * @return Result<Unit>
     */
    operator fun invoke(
        context: Context,
        band: FrequencyBand
    ): Result<Unit> {
        return try {
            // ✅ EffectEngineController 호출
            EffectEngineController.processFFT(context, band)

            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}