package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.components.common.BaseDialog
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * ✅ (수정됨) 상태를 직접 소유하지 않는 Stateless 이펙트 설정 다이얼로그
 *
 * ## 주요 변경사항
 * - 내부 상태 `var currentSettings by remember` 제거
 * - 모든 컨트롤(슬라이더, 토글)이 부모로부터 전달받은 `settings` 파라미터를 직접 사용
 * - 값이 변경될 때마다(onValueChange) 즉시 `onSettingsChange` 콜백을 호출하여 부모에게 알림 (Hoisting State Up)
 */
@Composable
fun EffectSettingsDialog(
    effect: EffectViewModel.UiEffectType,
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    // 내부 상태를 제거하고, 모든 것을 부모가 제공하는 상태(settings)와 콜백(onSettingsChange)에 의존합니다.

    BaseDialog(
        title = "효과 상세 설정",
        subtitle = null,
        onDismiss = onDismiss,
        onConfirm = {
            // 확인 버튼은 이제 단순히 닫는 역할만 합니다.
            // 모든 변경사항은 이미 onSettingsChange를 통해 실시간으로 부모에게 전달되었습니다.
            onDismiss()
        },
        confirmText = "확인",
        dismissText = "취소",
        scrollable = true
    ) {
        when (effect) {
            // ===== ON: Transit, Random Delay, Random Color =====
            is EffectViewModel.UiEffectType.On -> {
                EffectSliderItem(
                    icon = R.drawable.ic_transit,
                    iconTint = Color(0xFFA774FF),
                    label = "TRANSIT",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    value = settings.transit,
                    onValueChange = { newValue ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(transit = newValue))
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectSliderItem(
                    icon = R.drawable.ic_random,
                    iconTint = Color(0xFF84E366),
                    label = "RANDOM DELAY",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    value = settings.randomDelay,
                    onValueChange = { newValue ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(randomDelay = newValue))
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectToggleItem(
                    icon = R.drawable.ic_color,
                    iconTint = Color(0xFF22D3EE),
                    label = "RANDOM COLOR",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    checked = settings.randomColor,
                    onCheckedChange = { newCheckedState ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(randomColor = newCheckedState))
                    }
                )
            }

            // ===== OFF: Transit만 =====
            is EffectViewModel.UiEffectType.Off -> {
                EffectSliderItem(
                    icon = R.drawable.ic_transit,
                    iconTint = Color(0xFFA774FF),
                    label = "TRANSIT",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    value = settings.transit,
                    onValueChange = { newValue ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(transit = newValue))
                    },
                    labels = listOf("0s", "5s", "10s")
                )
            }

            // ===== STROBE/BLINK/BREATH: Period, Random Delay, Random Color =====
            is EffectViewModel.UiEffectType.Strobe,
            is EffectViewModel.UiEffectType.Blink,
            is EffectViewModel.UiEffectType.Breath -> {
                EffectSliderItem(
                    icon = R.drawable.ic_period,
                    iconTint = Color(0xFFFFD46F),
                    label = "PERIOD",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    value = settings.period,
                    onValueChange = { newValue ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(period = newValue))
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectSliderItem(
                    icon = R.drawable.ic_random,
                    iconTint = Color(0xFF84E366),
                    label = "RANDOM DELAY",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    value = settings.randomDelay,
                    onValueChange = { newValue ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(randomDelay = newValue))
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectToggleItem(
                    icon = R.drawable.ic_color,
                    iconTint = Color(0xFF22D3EE),
                    label = "RANDOM COLOR",
                    // ✅ 수정: 부모의 상태를 직접 읽음
                    checked = settings.randomColor,
                    onCheckedChange = { newCheckedState ->
                        // ✅ 수정: 변경 즉시 부모에게 알림
                        onSettingsChange(settings.copy(randomColor = newCheckedState))
                    }
                )
            }

            // ===== EFFECT LIST: 설정값 없음 =====
            is EffectViewModel.UiEffectType.EffectList -> {
                Text(
                    text = "EFFECT LIST는 설정할 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            else -> {

            }
        }
    }
}