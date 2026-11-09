package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import com.lightstick.music.ui.theme.customColors
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.viewmodel.EffectViewModel

/**
 *  (수정됨) 상태를 직접 소유하지 않으며, Custom Effect 설정을 지원하는 Stateless 다이얼로그
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
        onConfirm = onDismiss,
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
            is EffectViewModel.UiEffectType.Custom -> {
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
        Spacer(modifier = Modifier.height(16.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
        DurationInputItem(settings, onSettingsChange)
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
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
        DurationInputItem(settings, onSettingsChange)
    }
}

/**
 * DURATION 숫자 입력 — 이펙트를 유지할 시간(10ms 단위 원시 값, SDK 2.1 LSEffectPayload.duration).
 * 0(기본값)이면 다음 명령까지 유지(무한), 0 초과면 해당 시간 뒤에 LED가 꺼진다. 최대 65535(≈655초).
 */
@Composable
private fun DurationInputItem(
    settings: EffectViewModel.EffectSettings,
    onSettingsChange: (EffectViewModel.EffectSettings) -> Unit
) {
    // 입력 중 빈 칸을 허용하기 위해 텍스트는 로컬 상태로 두고, 비어 있으면 0으로 저장한다
    var text by remember { mutableStateOf(settings.duration.toString()) }
    val ms = settings.duration * 10L

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_duration),
                contentDescription = null,
                tint = Color(0xFFFF8A65),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "DURATION",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.customColors.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(5)
                val value = (digits.toIntOrNull() ?: 0).coerceAtMost(DURATION_MAX)
                text = if (digits.isEmpty()) "" else value.toString()
                onSettingsChange(settings.copy(duration = value))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text("0") },
            suffix = { Text("× 10ms") },
            supportingText = {
                Text(
                    if (settings.duration == 0) "0 = 다음 명령까지 유지(무한)"
                    else "= ${ms}ms (${String.format(Locale.US, "%.2f", ms / 1000.0)}s) 뒤에 LED OFF"
                )
            }
        )
    }
}

/** duration 최대값 (u16) */
private const val DURATION_MAX = 65535
