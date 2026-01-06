package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.components.common.BaseDialog
import com.dongsitech.lightstickmusicdemo.ui.components.common.CustomSlider
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * ✅ 이펙트 설정 다이얼로그
 *
 * ## 주요 기능
 * - BaseDialog 기반 통일된 레이아웃
 * - CustomSlider 사용 (Material3 대체)
 * - 효과별 설정 항목 자동 표시
 * - Figma 스펙 구현
 *
 * ## 설정 항목 규칙
 * - ON: Transit, Random Delay, Random Color
 * - OFF: Transit만
 * - STROBE/BLINK/BREATH: Period, Random Delay, Random Color
 * - EFFECT LIST: 설정 불가
 *
 * ## 사용 예시
 * ```kotlin
 * EffectSettingsDialog(
 *     effect = UiEffectType.On,
 *     settings = effectSettings,
 *     onSettingsChange = { newSettings -> },
 *     onDismiss = { }
 * )
 * ```
 */
@Composable
fun EffectSettingsDialog(
    effect: EffectViewModel.UiEffectType,
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var currentSettings by remember { mutableStateOf(settings) }

    BaseDialog(
        title = "효과 상세 설정",
        subtitle = null,
        onDismiss = onDismiss,
        onConfirm = {
            onSettingsChange(currentSettings)
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
                    value = currentSettings.transit,
                    onValueChange = {
                        currentSettings = currentSettings.copy(transit = it)
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectSliderItem(
                    icon = R.drawable.ic_random,
                    iconTint = Color(0xFF84E366),
                    label = "RANDOM DELAY",
                    value = currentSettings.randomDelay,
                    onValueChange = {
                        currentSettings = currentSettings.copy(randomDelay = it)
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectToggleItem(
                    icon = R.drawable.ic_color,
                    iconTint = Color(0xFF22D3EE),
                    label = "RANDOM COLOR",
                    checked = currentSettings.randomColor,
                    onCheckedChange = {
                        currentSettings = currentSettings.copy(randomColor = it)
                    }
                )
            }

            // ===== OFF: Transit만 =====
            is EffectViewModel.UiEffectType.Off -> {
                EffectSliderItem(
                    icon = R.drawable.ic_transit,
                    iconTint = Color(0xFFA774FF),
                    label = "TRANSIT",
                    value = currentSettings.transit,
                    onValueChange = {
                        currentSettings = currentSettings.copy(transit = it)
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
                    value = currentSettings.period,
                    onValueChange = {
                        currentSettings = currentSettings.copy(period = it)
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectSliderItem(
                    icon = R.drawable.ic_random,
                    iconTint = Color(0xFF84E366),
                    label = "RANDOM DELAY",
                    value = currentSettings.randomDelay,
                    onValueChange = {
                        currentSettings = currentSettings.copy(randomDelay = it)
                    },
                    labels = listOf("0s", "5s", "10s")
                )

                EffectToggleItem(
                    icon = R.drawable.ic_color,
                    iconTint = Color(0xFF22D3EE),
                    label = "RANDOM COLOR",
                    checked = currentSettings.randomColor,
                    onCheckedChange = {
                        currentSettings = currentSettings.copy(randomColor = it)
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

            // TODO: Custom 타입은 마지막 단계에서 추가 예정
             is EffectViewModel.UiEffectType.Custom -> { }
        }
    }
}