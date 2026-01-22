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
 * ✅ 색상 선택 다이얼로그 (Foreground / Background)
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
    // HSV 값
    val hsv = remember { colorToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    // 현재 색상 계산 (모든 HSV 값 사용)
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
            // ===== 색상 조절 (Hue) - 고정 무지개 =====
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
                    value = 360f - hue,  // 슬라이더에 반전된 값 표시
                    onValueChange = { hue = 360f - it },  // 입력값 반전
                    valueRange = 0f..360f,
                    steps = 359,
                    enableHaptic = true,
                    trackGradient = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFF0000),  // Red 0°
                            Color(0xFFFF00FF),  // Magenta 60°
                            Color(0xFF0000FF),  // Blue 120°
                            Color(0xFF00FFFF),  // Cyan 180°
                            Color(0xFF00FF00),  // Green 240°
                            Color(0xFFFFFF00),  // Yellow 300°
                            Color(0xFFFF0000)   // Red 360°
                        )
                    ),
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            // ===== 선명도 조절 (Saturation) - 동적 =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "선명도 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 선명도 그라데이션: 무채색 → 선명한 색 (현재 H, V 반영)
                val saturationGradient = remember(hue, brightness) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, 0f, brightness),    // S=0: 무채색 (V에 따라 흰/회/검)
                            hsvToColor(hue, 1f, brightness)     // S=1: 최대 선명도
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

            // ===== 밝기 조절 (Value) - 동적 =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "밝기 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 밝기 그라데이션: 밝은 색 → 검정 (현재 H, S 반영)
                val brightnessGradient = remember(hue, saturation) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, saturation, 1f),   // V=1.0: 최대 밝기
                            hsvToColor(hue, saturation, 0f)    // V=0.0: 완전한 검정
                        )
                    )
                }

                CustomSlider(
                    value = 1f - brightness,  // 슬라이더 값 반전 (왼쪽=밝음)
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

            // ===== 프리셋 =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "프리셋",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 2행 5열 그리드
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
                                    // 선택된 프리셋 다시 클릭 -> 편집
                                    onPresetEdit(index)
                                } else {
                                    // 다른 프리셋 클릭 -> 선택
                                    onPresetSelected(index)
                                    // 색상 적용 (HSV 모두 적용)
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
        // space
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * ✅ 프리셋 색상 박스
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
 * ✅ 프리셋 색상 설정 다이얼로그
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
    // HSV 값
    val hsv = remember { colorToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    // 현재 색상 계산 (모든 HSV 값 사용)
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
            // ===== 색상 조절 (Hue) - 고정 무지개 =====
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
                    value = 360f - hue,  // 슬라이더에 반전된 값 표시
                    onValueChange = { hue = 360f - it },  // 입력값 반전
                    valueRange = 0f..360f,
                    steps = 359,
                    enableHaptic = true,
                    trackGradient = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFF0000),  // Red 0°
                            Color(0xFFFF00FF),  // Magenta 60°
                            Color(0xFF0000FF),  // Blue 120°
                            Color(0xFF00FFFF),  // Cyan 180°
                            Color(0xFF00FF00),  // Green 240°
                            Color(0xFFFFFF00),  // Yellow 300°
                            Color(0xFFFF0000)   // Red 360°
                        )
                    ),
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    trackHeight = 32.dp,
                    thumbSize = 28.dp
                )
            }

            // ===== 선명도 조절 (Saturation) - 동적 =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "선명도 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 선명도 그라데이션: 무채색 → 선명한 색 (현재 H, V 반영)
                val saturationGradient = remember(hue, brightness) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, 0f, brightness),    // S=0: 무채색 (V에 따라 흰/회/검)
                            hsvToColor(hue, 1f, brightness)     // S=1: 최대 선명도
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

            // ===== 밝기 조절 (Value) - 동적 =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "밝기 조절",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 밝기 그라데이션: 밝은 색 → 검정 (현재 H, S 반영)
                val brightnessGradient = remember(hue, saturation) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            hsvToColor(hue, saturation, 1f),   // V=1.0: 최대 밝기
                            hsvToColor(hue, saturation, 0f)    // V=0.0: 완전한 검정
                        )
                    )
                }

                CustomSlider(
                    value = 1f - brightness,  // 슬라이더 값 반전 (왼쪽=밝음)
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
        // space
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * ✅ Color to HSV 변환 (표준 공식)
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

    // Hue (표준 HSV)
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6)
        max == g -> 60f * (((b - r) / delta) + 2)
        else -> 60f * (((r - g) / delta) + 4)
    }.let { if (it < 0) it + 360f else it }

    // Saturation
    val saturation = if (max == 0f) 0f else delta / max

    // Value
    val value = max

    return floatArrayOf(hue, saturation, value)
}

/**
 * ✅ HSV to Color 변환 (표준 공식)
 */
private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val c = value * saturation
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = value - c

    val (r, g, b) = when {
        hue < 60 -> Triple(c, x, 0f)      // Red → Yellow
        hue < 120 -> Triple(x, c, 0f)     // Yellow → Green
        hue < 180 -> Triple(0f, c, x)     // Green → Cyan
        hue < 240 -> Triple(0f, x, c)     // Cyan → Blue
        hue < 300 -> Triple(x, 0f, c)     // Blue → Magenta
        else -> Triple(c, 0f, x)          // Magenta → Red
    }

    return Color(r + m, g + m, b + m)
}

/**
 * ✅ 기본 프리셋 색상 (10개)
 */
object PresetColors {
    val defaultForegroundPresets = listOf(
        Color(0xFFFFFFFF),  // 흰색
        Color(0xFFFF0000),  // 빨강
        Color(0xFFFF9800),  // 주황
        Color(0xFFFFEB3B),  // 노랑
        Color(0xFF4CAF50),  // 초록
        Color(0xFF2196F3),  // 파랑
        Color(0xFF3F51B5),  // 남색
        Color(0xFF9C27B0),  // 보라
        Color(0xFFE91E63),  // 분홍
        Color(0xFF00FFFF)   // 청록
    )

    val defaultBackgroundPresets = listOf(
        Color(0xFF000000),  // 검정
        Color(0xFF424242),  // 진회색
        Color(0xFF616161),  // 회색
        Color(0xFF757575),  // 연회색
        Color(0xFF9E9E9E),  // 밝은회색
        Color(0xFF1A237E),  // 진남색
        Color(0xFF0D47A1),  // 진파랑
        Color(0xFF01579B),  // 파랑
        Color(0xFF006064),  // 청록
        Color(0xFF004D40)   // 진초록
    )
}