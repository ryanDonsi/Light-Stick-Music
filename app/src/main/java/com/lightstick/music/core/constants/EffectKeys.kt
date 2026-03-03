package com.lightstick.music.core.constants

import com.lightstick.music.ui.viewmodel.EffectViewModel

/**
 * Effect 식별자(Stable Key) 생성기
 *
 * - UI 표시용 문자열과 분리 (i18n 대상 아님)
 * - prefs key / metadata / logging / analytics 등에 사용
 */
object EffectKeys {

    fun of(effectType: EffectViewModel.UiEffectType): String = when (effectType) {
        is EffectViewModel.UiEffectType.On         -> "on"
        is EffectViewModel.UiEffectType.Off        -> "off"
        is EffectViewModel.UiEffectType.Strobe     -> "strobe"
        is EffectViewModel.UiEffectType.Blink      -> "blink"
        is EffectViewModel.UiEffectType.Breath     -> "breath"
        is EffectViewModel.UiEffectType.EffectList -> "effect_list_${effectType.number}"
        is EffectViewModel.UiEffectType.Custom     -> "custom_${effectType.id}"
    }
}