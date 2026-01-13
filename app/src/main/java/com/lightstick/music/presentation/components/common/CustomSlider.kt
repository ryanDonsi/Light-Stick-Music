package com.lightstick.music.presentation.components.common

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * ✅ CustomSlider - Material3 Slider 대체 컴포넌트
 *
 * ## 목적
 * - Material3 Slider의 패딩 문제 해결
 * - 완전한 커스터마이징 가능
 * - 순수 슬라이더 (Labels 없음)
 *
 * ## 특징
 * - 패딩 없음 (전체 너비 사용)
 * - Track: Color 또는 Gradient 지원
 * - Thumb: 크기, 색상, 그림자 커스터마이징
 * - Haptic 피드백 지원
 * - Steps 지원
 * - Int/Float 버전 모두 제공
 *
 * ## 사용 예시
 *
 * ### 기본 사용 (EffectSettingsDialog)
 * ```kotlin
 * CustomSlider(
 *     value = transit,
 *     onValueChange = { transit = it },
 *     valueRange = 0..100,
 *     steps = 99,
 *     trackColor = Color(0xFFD9D9D9),
 *     thumbColor = Color.White
 * )
 * ```
 *
 * ### 그라데이션 트랙 (ColorPickerDialog)
 * ```kotlin
 * CustomSlider(
 *     value = hue,
 *     onValueChange = { hue = it },
 *     trackGradient = Brush.horizontalGradient(
 *         colors = listOf(Color.Red, Color.Green, Color.Blue)
 *     ),
 *     thumbColor = Color.White
 * )
 * ```
 */
@Composable
fun CustomSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: IntRange = 0..100,
    steps: Int = 0,
    enableHaptic: Boolean = true,
    trackColor: Color = Color.White,
    trackGradient: Brush? = null,
    thumbColor: Color = Color.White,
    trackHeight: Dp = 8.dp,
    thumbSize: Dp = 24.dp,
    thumbShadowElevation: Dp = 2.dp
) {
    val view = LocalView.current
    val density = LocalDensity.current

    var sliderWidth by remember { mutableIntStateOf(0) }
    var lastHapticValue by remember { mutableIntStateOf(value) }

    // 값을 0~1 범위로 정규화
    val normalizedValue = (value - valueRange.first).toFloat() /
            (valueRange.last - valueRange.first).coerceAtLeast(1)

    // Thumb 위치 계산 (thumb 반지름 고려)
    val thumbRadius = with(density) { thumbSize.toPx() / 2 }
    val availableWidth = (sliderWidth - with(density) { thumbSize.toPx() }).coerceAtLeast(0f)
    val thumbPosition = if (availableWidth > 0f) {
        thumbRadius + (normalizedValue * availableWidth).coerceIn(0f, availableWidth)
    } else {
        thumbRadius
    }

    // 값 계산 함수
    fun calculateValue(offsetX: Float): Int {
        if (sliderWidth <= 0) return value

        // Thumb 반지름 고려
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val availableWidth = (sliderWidth - thumbSizePx).coerceAtLeast(0f)

        if (availableWidth <= 0f) return value

        val adjustedOffset = (offsetX - thumbSizePx / 2).coerceIn(0f, availableWidth)
        val newNormalizedValue = adjustedOffset / availableWidth

        val rawValue = valueRange.first +
                (newNormalizedValue * (valueRange.last - valueRange.first))

        // Steps 적용
        return if (steps > 0) {
            val stepSize = (valueRange.last - valueRange.first).toFloat() / (steps + 1)
            val steppedValue = (rawValue / stepSize).roundToInt() * stepSize
            steppedValue.roundToInt().coerceIn(valueRange)
        } else {
            rawValue.roundToInt().coerceIn(valueRange)
        }
    }

    // 값 변경 및 햅틱 처리
    fun updateValue(newValue: Int) {
        if (enableHaptic && newValue != lastHapticValue) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticValue = newValue
        }
        onValueChange(newValue)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thumbSize)
            .onSizeChanged { size ->
                sliderWidth = size.width
            }
            .pointerInput(Unit) {
                // 탭 처리
                detectTapGestures { offset ->
                    val newValue = calculateValue(offset.x)
                    updateValue(newValue)
                }
            }
            .pointerInput(Unit) {
                // 드래그 처리
                detectDragGestures { change, _ ->
                    change.consume()
                    val newValue = calculateValue(change.position.x)
                    updateValue(newValue)
                }
            }
    ) {
        // ===== Track =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(trackHeight / 2))
                .then(
                    if (trackGradient != null) {
                        Modifier.background(trackGradient)
                    } else {
                        Modifier.background(trackColor)
                    }
                )
        )

        // ===== Thumb =====
        Box(
            modifier = Modifier
                .size(thumbSize)
                .offset(x = with(density) { (thumbPosition - thumbSize.toPx() / 2).toDp() })
                .align(Alignment.CenterStart)
                .shadow(
                    elevation = thumbShadowElevation,
                    shape = CircleShape,
                    clip = false,
                    spotColor = Color.Black.copy(alpha = 0.6f)

                )
                .background(thumbColor, CircleShape)
        )
    }
}

/**
 * ✅ CustomSlider - Float 버전
 *
 * Float 값을 사용하는 슬라이더 (정밀한 색상 조절 등에 유용)
 *
 * ## 사용 예시
 * ```kotlin
 * CustomSlider(
 *     value = 0.5f,
 *     onValueChange = { value = it },
 *     valueRange = 0f..1f,
 *     trackColor = Color.Red
 * )
 * ```
 */
@Composable
fun CustomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enableHaptic: Boolean = true,
    trackColor: Color = Color.White,
    trackGradient: Brush? = null,
    thumbColor: Color = Color.White,
    trackHeight: Dp = 8.dp,
    thumbSize: Dp = 24.dp,
    thumbShadowElevation: Dp = 2.dp
) {
    val view = LocalView.current
    val density = LocalDensity.current

    var sliderWidth by remember { mutableIntStateOf(0) }
    var lastHapticStep by remember { mutableIntStateOf(-1) }

    // 값을 0~1 범위로 정규화
    val normalizedValue = ((value - valueRange.start) /
            (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    // Thumb 위치 계산 (thumb 반지름 고려)
    val thumbRadius = with(density) { thumbSize.toPx() / 2 }
    val availableWidth = (sliderWidth - with(density) { thumbSize.toPx() }).coerceAtLeast(0f)
    val thumbPosition = if (availableWidth > 0f) {
        thumbRadius + (normalizedValue * availableWidth).coerceIn(0f, availableWidth)
    } else {
        thumbRadius
    }

    // 값 계산 함수
    fun calculateValue(offsetX: Float): Float {
        if (sliderWidth <= 0) return value

        // Thumb 반지름 고려
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val availableWidth = (sliderWidth - thumbSizePx).coerceAtLeast(0f)

        if (availableWidth <= 0f) return value

        val adjustedOffset = (offsetX - thumbSizePx / 2).coerceIn(0f, availableWidth)
        val newNormalizedValue = adjustedOffset / availableWidth

        val rawValue = valueRange.start +
                (newNormalizedValue * (valueRange.endInclusive - valueRange.start))

        // Steps 적용
        return if (steps > 0) {
            val stepSize = (valueRange.endInclusive - valueRange.start) / (steps + 1)
            val steppedValue = (rawValue / stepSize).roundToInt() * stepSize
            steppedValue.coerceIn(valueRange)
        } else {
            rawValue.coerceIn(valueRange)
        }
    }

    // 값 변경 및 햅틱 처리
    fun updateValue(newValue: Float) {
        if (enableHaptic && steps > 0) {
            val currentStep = ((newValue - valueRange.start) /
                    ((valueRange.endInclusive - valueRange.start) / (steps + 1))).roundToInt()
            if (currentStep != lastHapticStep) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastHapticStep = currentStep
            }
        }
        onValueChange(newValue)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thumbSize)
            .onSizeChanged { size ->
                sliderWidth = size.width
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newValue = calculateValue(offset.x)
                    updateValue(newValue)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val newValue = calculateValue(change.position.x)
                    updateValue(newValue)
                }
            }
    ) {
        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(trackHeight / 2))
                .then(
                    if (trackGradient != null) {
                        Modifier.background(trackGradient)
                    } else {
                        Modifier.background(trackColor)
                    }
                )
        )

        // Thumb
        Box(
            modifier = Modifier
                .size(thumbSize)
                .offset(x = with(density) { (thumbPosition - thumbSize.toPx() / 2).toDp() })
                .align(Alignment.CenterStart)
                .shadow(
                    elevation = thumbShadowElevation,
                    shape = CircleShape,
                    clip = false,
                    spotColor = Color.Black.copy(alpha = 0.6f)
                )
                .background(thumbColor, CircleShape)
        )
    }
}