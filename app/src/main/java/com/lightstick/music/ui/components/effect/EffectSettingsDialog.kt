package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.viewmodel.EffectViewModel

/**
 * ✅ (수정됨) 상태를 직접 소유하지 않으며, Custom Effect 설정을 지원하는 Stateless 다이얼로그
 */
@Composable
fun EffectSettingsDialog(
    effect: EffectViewModel.UiEffectType,
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit,
    onDismiss: () -> Unit
) {
    BaseDialog(
        title = "효과 상세 설정",
        onDismiss = onDismiss,
        onConfirm = onDismiss, // 확인 버튼은 닫기 역할만 함
        confirmText = "확인",
        dismissText = "취소",
        scrollable = true
    ) {
        when (effect) {
            is EffectViewModel.UiEffectType.On -> {
                OnEffectSettingsContent(settings, onSettingsChange)
            }
            is EffectViewModel.UiEffectType.Off -> {
                OffEffectSettingsContent(settings, onSettingsChange)
            }
            is EffectViewModel.UiEffectType.Strobe,
            is EffectViewModel.UiEffectType.Blink,
            is EffectViewModel.UiEffectType.Breath -> {
                PeriodicEffectSettingsContent(settings, onSettingsChange)
            }
            // ✅ 추가: Custom 타입 처리
            is EffectViewModel.UiEffectType.Custom -> {
                // 기반 타입에 맞는 설정 UI를 표시
                when (effect.baseType) {
                    EffectViewModel.UiEffectType.BaseEffectType.ON -> OnEffectSettingsContent(settings, onSettingsChange)
                    EffectViewModel.UiEffectType.BaseEffectType.OFF -> OffEffectSettingsContent(settings, onSettingsChange)
                    EffectViewModel.UiEffectType.BaseEffectType.STROBE,
                    EffectViewModel.UiEffectType.BaseEffectType.BLINK,
                    EffectViewModel.UiEffectType.BaseEffectType.BREATH -> PeriodicEffectSettingsContent(settings, onSettingsChange)
                }
            }
            is EffectViewModel.UiEffectType.EffectList -> {
                Text(
                    text = "EFFECT LIST는 설정할 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * ON 이펙트 설정 UI
 */
@Composable
private fun OnEffectSettingsContent(
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EffectSliderItem(
            icon = R.drawable.ic_transit,
            iconTint = Color(0xFFA774FF),
            label = "TRANSIT",
            value = settings.transit,
            onValueChange = { onSettingsChange(settings.copy(transit = it)) },
            labels = listOf("0s", "5s", "10s")
        )
        EffectSliderItem(
            icon = R.drawable.ic_random,
            iconTint = Color(0xFF84E366),
            label = "RANDOM DELAY",
            value = settings.randomDelay,
            onValueChange = { onSettingsChange(settings.copy(randomDelay = it)) },
            labels = listOf("0s", "5s", "10s")
        )
        EffectToggleItem(
            icon = R.drawable.ic_color,
            iconTint = Color(0xFF22D3EE),
            label = "RANDOM COLOR",
            checked = settings.randomColor,
            onCheckedChange = { onSettingsChange(settings.copy(randomColor = it)) }
        )
    }
}

/**
 * OFF 이펙트 설정 UI
 */
@Composable
private fun OffEffectSettingsContent(
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit
) {
    EffectSliderItem(
        icon = R.drawable.ic_transit,
        iconTint = Color(0xFFA774FF),
        label = "TRANSIT",
        value = settings.transit,
        onValueChange = { onSettingsChange(settings.copy(transit = it)) },
        labels = listOf("0s", "5s", "10s")
    )
}

/**
 * STROBE, BLINK, BREATH 공용 설정 UI
 */
@Composable
private fun PeriodicEffectSettingsContent(
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EffectSliderItem(
            icon = R.drawable.ic_period,
            iconTint = Color(0xFFFFD46F),
            label = "PERIOD",
            value = settings.period,
            onValueChange = { onSettingsChange(settings.copy(period = it)) },
            labels = listOf("0s", "5s", "10s")
        )
        EffectSliderItem(
            icon = R.drawable.ic_random,
            iconTint = Color(0xFF84E366),
            label = "RANDOM DELAY",
            value = settings.randomDelay,
            onValueChange = { onSettingsChange(settings.copy(randomDelay = it)) },
            labels = listOf("0s", "5s", "10s")
        )
        EffectToggleItem(
            icon = R.drawable.ic_color,
            iconTint = Color(0xFF22D3EE),
            label = "RANDOM COLOR",
            checked = settings.randomColor,
            onCheckedChange = { onSettingsChange(settings.copy(randomColor = it)) }
        )
    }
}