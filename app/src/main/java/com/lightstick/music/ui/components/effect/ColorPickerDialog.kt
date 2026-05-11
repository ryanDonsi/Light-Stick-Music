package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.CustomSlider

/**
 *  색상 선택 다이얼로그 (Foreground / Background)
 *
 * ## 주요 기능
 * - Hue 색상 조절 (무지개 그라데이션 슬라이더 - 고정)
 * - Saturation 선명도 조절 (무채색 → 선명한 색 - 동적)
 * - Value 밝기 조절 (밝음 → 어두움 - 동적)
 * - 10개 색상 프리셋 (2행 5열)
 * - 프리셋 클릭: 색상 선택
 * - 선택된 프리셋 다시 클릭: 프리셋 편집 다이얼로그
 *
 * ## 사용 예시
 * ```kotlin
 * // Foreground Color
 * ColorPickerDialog(
 *     title = "Forground Color 설정",
 *     initialColor = Color.White,
 *     presetColors = fgPresetColors,
 *     selectedPresetIndex = selectedFgPreset,
 *     onColorSelected = { color -> },
 *     onPresetSelected = { index -> },
 *     onPresetEdit = { index -> },
 *     onDismiss = { }
 * )
 * ```
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Color,
    presetColors: List<Color>,
    selectedPresetIndex: Int?,
    onColorSelected: (Color) -> Unit,
    onPresetSelected: (Int) -> Unit,
    onPresetEdit: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember { colorToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    val currentColor = hsvToColor(hue, saturation, brightness)

    BaseDialog(
        title = title,
        subtitle = null,
        onDismiss = onDismiss,
        onConfirm = {
            onColorSelected(currentColor)
            onDismiss()
        },
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "색상 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CustomSlider(
                    value = 360f - hue,
                    onValueChange = { hue = 360f - it },
                    valueRange = 0f..360f,
                    steps = 359,
                    enableHaptic = true,
                    trackGradient = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFF0000),
                            Color(0xFFFF00FF),
                            Color(0xFF0000FF),
                            Color(0xFF00FFFF),
                            Color(0xFF00FF00),
                            Color(0xFFFFFF00),
                            Color(0xFFFF0000)
                        )
                    ),
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "선명도 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val saturationGradient = remember(hue, brightness) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, 0f, brightness),
                            hsvToColor(hue, 1f, brightness)
                        )
                    )
                }

                CustomSlider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                    steps = 99,
                    enableHaptic = true,
                    trackGradient = saturationGradient,
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "밝기 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val brightnessGradient = remember(hue, saturation) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, saturation, 1f),
                            hsvToColor(hue, saturation, 0f)
                        )
                    )
                }

                CustomSlider(
                    value = 1f - brightness,
                    onValueChange = { brightness = 1f - it },
                    valueRange = 0f..1f,
                    steps = 99,
                    enableHaptic = true,
                    trackGradient = brightnessGradient,
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "프리셋",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(presetColors) { index, color ->
                        PresetColorBox(
                            color = color,
                            isSelected = index == selectedPresetIndex,
                            onClick = {
                                if (index == selectedPresetIndex) {
                                    onPresetEdit(index)
                                } else {
                                    onPresetSelected(index)
                                    val hsv = colorToHsv(color)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    brightness = hsv[2]
                                }
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 *  프리셋 색상 박스
 *
 * ## 상태
 * - 기본: 투명 테두리
 * - 선택: 보라색 테두리
 */
@Composable
private fun PresetColorBox(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
    )
}

/**
 *  프리셋 색상 설정 다이얼로그
 *
 * ## 주요 기능
 * - 선택된 프리셋의 색상 편집
 * - 색상 조절 + 선명도 조절 + 밝기 조절 (프리셋 없음)
 *
 * ## 사용 예시
 * ```kotlin
 * PresetColorEditDialog(
 *     presetIndex = 0,
 *     initialColor = Color.Red,
 *     onColorSelected = { color -> },
 *     onDismiss = { }
 * )
 * ```
 */
@Composable
fun PresetColorEditDialog(
    presetIndex: Int,
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember { colorToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    val currentColor = hsvToColor(hue, saturation, brightness)

    BaseDialog(
        title = "프리셋 색상 설정",
        subtitle = null,
        onDismiss = onDismiss,
        onConfirm = {
            onColorSelected(currentColor)
            onDismiss()
        },
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "색상 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CustomSlider(
                    value = 360f - hue,
                    onValueChange = { hue = 360f - it },
                    valueRange = 0f..360f,
                    steps = 359,
                    enableHaptic = true,
                    trackGradient = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFF0000),
                            Color(0xFFFF00FF),
                            Color(0xFF0000FF),
                            Color(0xFF00FFFF),
                            Color(0xFF00FF00),
                            Color(0xFFFFFF00),
                            Color(0xFFFF0000)
                        )
                    ),
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "선명도 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val saturationGradient = remember(hue, brightness) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, 0f, brightness),
                            hsvToColor(hue, 1f, brightness)
                        )
                    )
                }

                CustomSlider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                    steps = 99,
                    enableHaptic = true,
                    trackGradient = saturationGradient,
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "밝기 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val brightnessGradient = remember(hue, saturation) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, saturation, 1f),
                            hsvToColor(hue, saturation, 0f)
                        )
                    )
                }

                CustomSlider(
                    value = 1f - brightness,
                    onValueChange = { brightness = 1f - it },
                    valueRange = 0f..1f,
                    steps = 99,
                    enableHaptic = true,
                    trackGradient = brightnessGradient,
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 *  Color to HSV 변환 (표준 공식)
 *
 * @return FloatArray [hue, saturation, value]
 */
private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6)
        max == g -> 60f * (((b - r) / delta) + 2)
        else -> 60f * (((r - g) / delta) + 4)
    }.let { if (it < 0) it + 360f else it }

    val saturation = if (max == 0f) 0f else delta / max

    val value = max

    return floatArrayOf(hue, saturation, value)
}

/**
 *  HSV to Color 변환 (표준 공식)
 */
private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val c = value * saturation
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = value - c

    val (r, g, b) = when {
        hue < 60 -> Triple(c, x, 0f)
        hue < 120 -> Triple(x, c, 0f)
        hue < 180 -> Triple(0f, c, x)
        hue < 240 -> Triple(0f, x, c)
        hue < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(r + m, g + m, b + m)
}

/**
 *  기본 프리셋 색상 (10개)
 */
object PresetColors {
    val defaultForegroundPresets = listOf(
        Color(0xFFFFFFFF),
        Color(0xFFFF0000),
        Color(0xFFFF9800),
        Color(0xFFFFEB3B),
        Color(0xFF4CAF50),
        Color(0xFF2196F3),
        Color(0xFF3F51B5),
        Color(0xFF9C27B0),
        Color(0xFFE91E63),
        Color(0xFF00FFFF)
    )

    val defaultBackgroundPresets = listOf(
        Color(0xFF000000),
        Color(0xFF424242),
        Color(0xFF616161),
        Color(0xFF757575),
        Color(0xFF9E9E9E),
        Color(0xFF1A237E),
        Color(0xFF0D47A1),
        Color(0xFF01579B),
        Color(0xFF006064),
        Color(0xFF004D40)
    )
}
