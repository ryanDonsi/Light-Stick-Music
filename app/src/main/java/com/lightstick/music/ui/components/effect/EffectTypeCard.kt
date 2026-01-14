package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles
import com.lightstick.music.ui.viewmodel.EffectViewModel
import java.util.Locale

@Composable
fun EffectTypeCard(
    effect: EffectViewModel.UiEffectType,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    onEffectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onForegroundColorClick: () -> Unit,
    onBackgroundColorClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val cardBackgroundColor = when {
        isSelected -> MaterialTheme.customColors.primary.copy(alpha = 0.22f)
        else -> MaterialTheme.customColors.onSurface.copy(alpha = 0.05f)
    }
    val borderColor = when {
        isSelected -> MaterialTheme.customColors.primary
        else -> MaterialTheme.customColors.onSurface.copy(alpha = 0.14f)
    }
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val titleColor = if (isEnabled) MaterialTheme.customColors.onSurface else MaterialTheme.customColors.disable

    Card(
        onClick = { if (isEnabled) onEffectClick() },
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = Color.Black.copy(alpha = 0.20f),
                ambientColor = Color.Transparent
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
        border = BorderStroke(width = borderWidth, color = borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = effect.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = titleColor,
                    // ✅ 요청사항 1: 긴 타이틀 축약
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                EffectParameters(
                    effect = effect,
                    effectSettings = effectSettings,
                    isSelected = isSelected,
                    isEnabled = isEnabled
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ✅ 요청사항 2: FG/BG 버튼 로직 함수화 및 재귀 적용
                ShowColorChipForEffect(
                    effect = effect,
                    chipType = "FG",
                    effectSettings = effectSettings,
                    isSelected = isSelected,
                    isEnabled = isEnabled,
                    onClick = onForegroundColorClick
                )
                ShowColorChipForEffect(
                    effect = effect,
                    chipType = "BG",
                    effectSettings = effectSettings,
                    isSelected = isSelected,
                    isEnabled = isEnabled,
                    onClick = onBackgroundColorClick
                )
                // ✅ 수정: Custom Effect일 경우 메뉴를 띄우도록 Box로 감쌈
                Box {
                    var showCustomMenu by remember { mutableStateOf(false) }

                    IconButton(
                        onClick = {
                            if (effect.isConfigurable()) {
                                if (effect is EffectViewModel.UiEffectType.Custom) {
                                    showCustomMenu = true
                                } else {
                                    onSettingsClick()
                                }
                            }
                        },
                        enabled = isEnabled,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "설정",
                            tint = when {
                                isSelected -> MaterialTheme.colorScheme.onSurface
                                isEnabled -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showCustomMenu,
                        onDismissRequest = { showCustomMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("이름변경") },
                            onClick = {
                                showCustomMenu = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("이펙트 설정") },
                            onClick = {
                                showCustomMenu = false
                                onSettingsClick()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showCustomMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectParameters(
    effect: EffectViewModel.UiEffectType,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isEnabled: Boolean
) {
    val iconTint = when {
        isSelected -> Color.Unspecified // ParameterItem 내부에서 개별 색상 지정
        isEnabled -> MaterialTheme.customColors.surfaceVariant
        else -> MaterialTheme.customColors.disable
    }
    val textColor = when {
        isSelected -> MaterialTheme.customColors.onSurface
        isEnabled -> MaterialTheme.customColors.surfaceVariant
        else -> MaterialTheme.customColors.disable
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (effect) {
            is EffectViewModel.UiEffectType.On -> {
                ParameterItem(R.drawable.ic_transit, formatSeconds(effectSettings.transit), if (isSelected) Color(0xFFA774FF) else iconTint, textColor)
                ParameterItem(R.drawable.ic_random, formatSeconds(effectSettings.randomDelay), if (isSelected) Color(0xFF84E366) else iconTint, textColor)
                ParameterItem(R.drawable.ic_color, if (effectSettings.randomColor) "R" else "-", if (isSelected) Color(0xFF22D3EE) else iconTint, textColor)
            }
            is EffectViewModel.UiEffectType.Off -> {
                ParameterItem(R.drawable.ic_transit, formatSeconds(effectSettings.transit), if (isSelected) Color(0xFFA774FF) else iconTint, textColor)
            }
            is EffectViewModel.UiEffectType.Strobe,
            is EffectViewModel.UiEffectType.Blink,
            is EffectViewModel.UiEffectType.Breath -> {
                ParameterItem(R.drawable.ic_period, formatSeconds(effectSettings.period), if (isSelected) Color(0xFFFFD46F) else iconTint, textColor)
                ParameterItem(R.drawable.ic_random, formatSeconds(effectSettings.randomDelay), if (isSelected) Color(0xFF84E366) else iconTint, textColor)
                ParameterItem(R.drawable.ic_color, if (effectSettings.randomColor) "R" else "-", if (isSelected) Color(0xFF22D3EE) else iconTint, textColor)
            }
            is EffectViewModel.UiEffectType.Custom -> {
                val baseUiEffect = when (effect.baseType) {
                    EffectViewModel.UiEffectType.BaseEffectType.ON -> EffectViewModel.UiEffectType.On
                    EffectViewModel.UiEffectType.BaseEffectType.OFF -> EffectViewModel.UiEffectType.Off
                    EffectViewModel.UiEffectType.BaseEffectType.STROBE -> EffectViewModel.UiEffectType.Strobe
                    EffectViewModel.UiEffectType.BaseEffectType.BLINK -> EffectViewModel.UiEffectType.Blink
                    EffectViewModel.UiEffectType.BaseEffectType.BREATH -> EffectViewModel.UiEffectType.Breath
                }
                EffectParameters(baseUiEffect, effectSettings, isSelected, isEnabled)
            }
            else -> {}
        }
    }
}

@Composable
private fun ShowColorChipForEffect(
    effect: EffectViewModel.UiEffectType,
    chipType: String,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val baseEffect = if (effect is EffectViewModel.UiEffectType.Custom) {
        when (effect.baseType) {
            EffectViewModel.UiEffectType.BaseEffectType.ON -> EffectViewModel.UiEffectType.On
            EffectViewModel.UiEffectType.BaseEffectType.OFF -> EffectViewModel.UiEffectType.Off
            EffectViewModel.UiEffectType.BaseEffectType.STROBE -> EffectViewModel.UiEffectType.Strobe
            EffectViewModel.UiEffectType.BaseEffectType.BLINK -> EffectViewModel.UiEffectType.Blink
            EffectViewModel.UiEffectType.BaseEffectType.BREATH -> EffectViewModel.UiEffectType.Breath
        }
    } else {
        effect
    }

    val showFg = baseEffect is EffectViewModel.UiEffectType.On || baseEffect is EffectViewModel.UiEffectType.Strobe || baseEffect is EffectViewModel.UiEffectType.Blink || baseEffect is EffectViewModel.UiEffectType.Breath
    val showBg = baseEffect is EffectViewModel.UiEffectType.Strobe || baseEffect is EffectViewModel.UiEffectType.Blink || baseEffect is EffectViewModel.UiEffectType.Breath

    if ((chipType == "FG" && showFg) || (chipType == "BG" && showBg)) {
        val colorSource = if (chipType == "FG") effectSettings.color else effectSettings.backgroundColor
        ColorChip(
            label = chipType,
            color = if (isSelected) Color(colorSource.r / 255f, colorSource.g / 255f, colorSource.b / 255f) else MaterialTheme.customColors.disable,
            onClick = onClick,
            enabled = isEnabled,
            isSelected = isSelected
        )
    }
}

@Composable
private fun ParameterItem(iconRes: Int, value: String, iconTint: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(id = iconRes), contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

@Composable
private fun ColorChip(label: String, color: Color, onClick: () -> Unit, enabled: Boolean, isSelected: Boolean) {
    val backgroundColor = when {
        isSelected -> color
        enabled -> MaterialTheme.customColors.surfaceVariant
        else -> MaterialTheme.customColors.disable
    }
    val textColor = when {
        isSelected -> if (color.luminance() > 0.5f) Color.Black else Color.White
        else -> MaterialTheme.customColors.onDisable
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.customTextStyles.badgeMedium, color = textColor)
        }
    }
}

private fun formatSeconds(value: Int): String {
    val seconds = value * 0.1
    return String.format(Locale.US, "%.1f", seconds).removeSuffix(".0") + "s"
}

private fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}

private fun EffectViewModel.UiEffectType.isConfigurable(): Boolean {
    return when (this) {
        is EffectViewModel.UiEffectType.On,
        is EffectViewModel.UiEffectType.Off,
        is EffectViewModel.UiEffectType.Strobe,
        is EffectViewModel.UiEffectType.Blink,
        is EffectViewModel.UiEffectType.Breath,
        is EffectViewModel.UiEffectType.Custom -> true
        else -> false
    }
}
