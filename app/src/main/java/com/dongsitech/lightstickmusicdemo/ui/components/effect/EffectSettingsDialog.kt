package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * 이펙트 설정 다이얼로그
 *
 * @param effect 이펙트 타입
 * @param settings 현재 설정
 * @param onSettingsChange 설정 변경 시 콜백
 * @param onDismiss 닫기 시 콜백
 */
@ExperimentalMaterial3Api
@Composable
fun EffectSettingsDialog(
    effect: EffectViewModel.UiEffectType,
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var currentSettings by remember { mutableStateOf(settings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${effect.displayName} 설정") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Period (Strobe, Blink, Breath만)
                if (effect is EffectViewModel.UiEffectType.Strobe ||
                    effect is EffectViewModel.UiEffectType.Blink ||
                    effect is EffectViewModel.UiEffectType.Breath
                ) {
                    ParameterSlider(
                        label = "Period",
                        value = currentSettings.period,
                        onValueChange = {
                            currentSettings = currentSettings.copy(period = it)
                        }
                    )
                }

                // Transit (ON 제외)
                if (effect !is EffectViewModel.UiEffectType.On) {
                    ParameterSlider(
                        label = "Transit",
                        value = currentSettings.transit,
                        onValueChange = {
                            currentSettings = currentSettings.copy(transit = it)
                        }
                    )
                }

                // Fade (Breath만)
                if (effect is EffectViewModel.UiEffectType.Breath) {
                    ParameterSlider(
                        label = "Fade",
                        value = currentSettings.fade,
                        onValueChange = {
                            currentSettings = currentSettings.copy(fade = it)
                        },
                        valueRange = 0f..100f
                    )
                }

                HorizontalDivider()

                // Random Color
                CompactSwitchRow(
                    label = "Random Color",
                    checked = currentSettings.randomColor,
                    onCheckedChange = {
                        currentSettings = currentSettings.copy(randomColor = it)
                    }
                )

                // Random Delay
                if (currentSettings.randomColor) {
                    ParameterSlider(
                        label = "Random Delay",
                        value = currentSettings.randomDelay,
                        onValueChange = {
                            currentSettings = currentSettings.copy(randomDelay = it)
                        }
                    )
                }

                HorizontalDivider()

                // Broadcasting
                CompactSwitchRow(
                    label = "Broadcasting",
                    checked = currentSettings.broadcasting,
                    onCheckedChange = {
                        currentSettings = currentSettings.copy(broadcasting = it)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSettingsChange(currentSettings)
                    onDismiss()
                }
            ) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}