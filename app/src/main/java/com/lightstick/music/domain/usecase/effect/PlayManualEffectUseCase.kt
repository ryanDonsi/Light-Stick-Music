package com.lightstick.music.domain.usecase.effect

import android.content.Context
import com.lightstick.music.core.constants.EffectKeys
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.types.LSEffectPayload
import javax.inject.Inject

/**
 * Manual Effect 재생 UseCase
 *
 * 책임:
 * - EffectSettings → LSEffectPayload 변환
 * - EffectEngineController.sendEffect() 호출
 * - 성공/실패 결과 반환
 *
 * 사용:
 * - EffectViewModel.playEffect()
 */
class PlayManualEffectUseCase @Inject constructor() {

    private companion object {
        /** duration 0 = 다음 명령까지 유지(무한) — EFFECT CONTROL 기본 */
        const val DURATION_HOLD = 0
    }

    /**
     * Manual Effect 재생
     *
     * @param context Android Context
     * @param effectType Effect 타입
     * @param settings Effect 설정
     * @return Result<Unit> 성공 시 Unit, 실패 시 Exception
     */
    operator fun invoke(
        context: Context,
        effectType: EffectViewModel.UiEffectType,
        settings: EffectViewModel.EffectSettings
    ): Result<Unit> {
        return try {
            val payload = createPayload(effectType, settings)
                ?: return Result.failure(IllegalArgumentException("Cannot create payload for $effectType"))

            val success = EffectEngineController.sendEffect(
                context = context,
                payload = payload,
                source = TransmissionSource.PAYLOAD_EFFECT,
                metadata = mapOf("effectType" to EffectKeys.of(effectType))
            )

            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("No connected devices"))
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * EffectSettings → LSEffectPayload 변환
     */
    private fun createPayload(
        effectType: EffectViewModel.UiEffectType,
        settings: EffectViewModel.EffectSettings
    ): LSEffectPayload? {
        val randomColorValue = if (settings.randomColor) 1 else 0
        val randomDelayValue = settings.randomDelay

        return when (effectType) {
            is EffectViewModel.UiEffectType.On -> {
                LSEffectPayload.Effects.on(
                    color = settings.color,
                    transit = settings.transit,
                    randomColor = randomColorValue,
                    randomDelay = randomDelayValue,
                    duration = DURATION_HOLD
                )
            }
            is EffectViewModel.UiEffectType.Off -> {
                LSEffectPayload.Effects.off(
                    transit = settings.transit,
                    randomDelay = randomDelayValue,
                    duration = DURATION_HOLD
                )
            }
            is EffectViewModel.UiEffectType.Strobe -> {
                LSEffectPayload.Effects.strobe(
                    period = settings.period,
                    color = settings.color,
                    backgroundColor = settings.backgroundColor,
                    randomColor = randomColorValue,
                    randomDelay = randomDelayValue,
                    duration = DURATION_HOLD
                )
            }
            is EffectViewModel.UiEffectType.Blink -> {
                LSEffectPayload.Effects.blink(
                    period = settings.period,
                    color = settings.color,
                    backgroundColor = settings.backgroundColor,
                    randomColor = randomColorValue,
                    randomDelay = randomDelayValue,
                    duration = DURATION_HOLD
                )
            }
            is EffectViewModel.UiEffectType.Breath -> {
                LSEffectPayload.Effects.breath(
                    period = settings.period,
                    color = settings.color,
                    backgroundColor = settings.backgroundColor,
                    randomColor = randomColorValue,
                    randomDelay = randomDelayValue,
                    duration = DURATION_HOLD
                )
            }
            is EffectViewModel.UiEffectType.Custom -> {
                when (effectType.baseType) {
                    EffectViewModel.UiEffectType.BaseEffectType.ON -> {
                        LSEffectPayload.Effects.on(
                            color = settings.color,
                            transit = settings.transit,
                            randomColor = randomColorValue,
                            randomDelay = randomDelayValue,
                            duration = DURATION_HOLD
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.OFF -> {
                        LSEffectPayload.Effects.off(
                            transit = settings.transit,
                            randomDelay = randomDelayValue,
                            duration = DURATION_HOLD
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.STROBE -> {
                        LSEffectPayload.Effects.strobe(
                            period = settings.period,
                            color = settings.color,
                            backgroundColor = settings.backgroundColor,
                            randomColor = randomColorValue,
                            randomDelay = randomDelayValue,
                            duration = DURATION_HOLD
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.BLINK -> {
                        LSEffectPayload.Effects.blink(
                            period = settings.period,
                            color = settings.color,
                            backgroundColor = settings.backgroundColor,
                            randomColor = randomColorValue,
                            randomDelay = randomDelayValue,
                            duration = DURATION_HOLD
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.BREATH -> {
                        LSEffectPayload.Effects.breath(
                            period = settings.period,
                            color = settings.color,
                            backgroundColor = settings.backgroundColor,
                            randomColor = randomColorValue,
                            randomDelay = randomDelayValue,
                            duration = DURATION_HOLD
                        )
                    }
                }
            }
            is EffectViewModel.UiEffectType.EffectList -> {
                null
            }
        }
    }
}
