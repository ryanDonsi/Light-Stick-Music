package com.lightstick.music.ui.components.effect

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp // Compose의 lerp를 import합니다.
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.types.Color as LightStickColor
import kotlin.math.abs


/**
 * ✅ LightStickColor를 Compose Color로 변환
 */
private fun LightStickColor.toComposeColor(): Color {
    return Color(
        red = this.r / 255f,
        green = this.g / 255f,
        blue = this.b / 255f
    )
}

/**
 * ✅ BLACK 색상 체크 (RGB가 모두 0.1 미만)
 */
private fun Color.isBlack(): Boolean {
    return this.red < 0.1f && this.green < 0.1f && this.blue < 0.1f
}

@Composable
fun DeviceConnectionCard(
    connectionState: EffectViewModel.DeviceConnectionState,
    onConnectClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    isScrolled: Boolean = false,
    selectedEffect: EffectViewModel.UiEffectType? = null,
    effectSettings: EffectViewModel.EffectSettings? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val animatedBoxSize by animateDpAsState(
        targetValue = if (isScrolled) 124.dp else 180.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "boxSize"
    )

    val animatedEffectSize by animateDpAsState(
        targetValue = if (isScrolled) 50.dp else 70.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "effectSize"
    )

    val animatedCornerRadius by animateDpAsState(
        targetValue = if (isScrolled) 20.dp else 32.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cornerRadius"
    )

    val cardBackgroundColor = if (connectionState is EffectViewModel.DeviceConnectionState.Connected) {
        Color(0xFFA774FF).copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }

    val effectColorData = calculateEffectColor(
        isPlaying = isPlaying,
        selectedEffect = selectedEffect,
        effectSettings = effectSettings,
        backgroundColor = cardBackgroundColor
    )

    when (connectionState) {
        is EffectViewModel.DeviceConnectionState.NoBondedDevice -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = Color.White.copy(alpha = 0.6f),
                isLightStickAnimating = false,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                descriptionText = null,
                descriptionTextColor = null,
                effectColorData = null,
                buttonText = "기기 연결하기",
                onButtonClick = onConnectClick
            )
        }

        is EffectViewModel.DeviceConnectionState.Scanning -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = Color.White.copy(alpha = 0.6f),
                isLightStickAnimating = false,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                descriptionText = "등록된 기기 확인 중",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon = Icons.Default.Refresh,
                isDescriptionIconAnimating = true,
                onDescriptionIconClick = null,
                effectColorData = null,
                buttonText = null,
                onButtonClick = null
            )
        }

        is EffectViewModel.DeviceConnectionState.ScanFailed -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = Color.White.copy(alpha = 0.6f),
                isLightStickAnimating = false,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                descriptionText = "연결 가능한 기기가 없습니다",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon = Icons.Default.Refresh,
                isDescriptionIconAnimating = false,
                onDescriptionIconClick = onRetryClick,
                effectColorData = null,
                buttonText = null,
                onButtonClick = null
            )
        }

        is EffectViewModel.DeviceConnectionState.Connected -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = effectColorData?.iconColor ?: Color.White,
                isLightStickAnimating = isPlaying,
                statusText = "연결 됨",
                statusTextColor = MaterialTheme.customColors.onSurface,
                descriptionText = connectionState.device.name ?: "UNKNOWN",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                effectColorData = effectColorData,
                buttonText = null,
                onButtonClick = null
            )
        }
    }
}

data class EffectColorData(
    val iconColor: Color,
    val iconBrush: Brush?,
    val gradientColor: Color?
)

/**
 * ✅ calculateEffectColor - key를 사용해서 완전히 격리
 */
@Composable
private fun calculateEffectColor(
    isPlaying: Boolean,
    selectedEffect: EffectViewModel.UiEffectType?,
    effectSettings: EffectViewModel.EffectSettings?,
    backgroundColor: Color
): EffectColorData? {
    val lastColorRef = remember { mutableStateOf(Color.White) }

    if (!isPlaying || selectedEffect == null || effectSettings == null) {
        lastColorRef.value = Color.White
        return EffectColorData(
            iconColor = Color.White,
            iconBrush = null,
            gradientColor = null
        )
    }

    return key(selectedEffect) {
        when (selectedEffect) {
            is EffectViewModel.UiEffectType.On -> {
                animateOnEffect(
                    fromColor = lastColorRef.value,
                    targetColor = effectSettings.color.toComposeColor(),
                    transit = effectSettings.transit,
                    randomColor = effectSettings.randomColor,
                    onColorUpdate = { lastColorRef.value = it },
                    backgroundColor = backgroundColor
                )
            }
            is EffectViewModel.UiEffectType.Off -> {
                animateOffEffect(
                    fromColor = lastColorRef.value,
                    transit = effectSettings.transit,
                    onColorUpdate = { lastColorRef.value = it },
                    backgroundColor = backgroundColor
                )
            }
            is EffectViewModel.UiEffectType.Strobe -> {
                animatePeriodicEffect(
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    fgThreshold = 0.1f,
                    backgroundColor = backgroundColor
                )
            }
            is EffectViewModel.UiEffectType.Blink -> {
                animatePeriodicEffect(
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    fgThreshold = 0.5f,
                    backgroundColor = backgroundColor
                )
            }
            is EffectViewModel.UiEffectType.Breath -> {
                animateBreathEffect(
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    backgroundColor = backgroundColor,
                    onColorUpdate = { lastColorRef.value = it }
                )
            }
            is EffectViewModel.UiEffectType.Custom -> {
                when (selectedEffect.baseType) {
                    EffectViewModel.UiEffectType.BaseEffectType.ON -> {
                        animateOnEffect(
                            fromColor = lastColorRef.value,
                            targetColor = effectSettings.color.toComposeColor(),
                            transit = effectSettings.transit,
                            randomColor = effectSettings.randomColor,
                            onColorUpdate = { lastColorRef.value = it },
                            backgroundColor = backgroundColor
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.OFF -> {
                        animateOffEffect(
                            fromColor = lastColorRef.value,
                            transit = effectSettings.transit,
                            onColorUpdate = { lastColorRef.value = it },
                            backgroundColor = backgroundColor
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.STROBE -> {
                        animatePeriodicEffect(
                            fgColor = effectSettings.color.toComposeColor(),
                            bgColor = effectSettings.backgroundColor.toComposeColor(),
                            period = effectSettings.period,
                            randomColor = effectSettings.randomColor,
                            fgThreshold = 0.1f,
                            backgroundColor = backgroundColor
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.BLINK -> {
                        animatePeriodicEffect(
                            fgColor = effectSettings.color.toComposeColor(),
                            bgColor = effectSettings.backgroundColor.toComposeColor(),
                            period = effectSettings.period,
                            randomColor = effectSettings.randomColor,
                            fgThreshold = 0.5f,
                            backgroundColor = backgroundColor
                        )
                    }
                    EffectViewModel.UiEffectType.BaseEffectType.BREATH -> {
                        animateBreathEffect(
                            fgColor = effectSettings.color.toComposeColor(),
                            bgColor = effectSettings.backgroundColor.toComposeColor(),
                            period = effectSettings.period,
                            randomColor = effectSettings.randomColor,
                            backgroundColor = backgroundColor,
                            onColorUpdate = { lastColorRef.value = it }
                        )
                    }
                }
            }
            else -> null
        }
    }
}

private fun createEffectColorData(
    color: Color,
    alpha: Float = 1f,
    randomColor: Boolean
): EffectColorData {
    val isBlackColor = color.isBlack()

    return EffectColorData(
        iconColor = color.copy(alpha = alpha),
        iconBrush = null,
        gradientColor = if (isBlackColor) null else color.copy(alpha = alpha)
    )
}

@Composable
private fun rememberRandomHueColor(alpha: Float = 1f): Color {
    val infiniteTransition = rememberInfiniteTransition(label = "hueRotation")

    val hue by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hue"
    )

    return Color.hsv(hue, 1f, 1f).copy(alpha = alpha)
}

/**
 * ✅ 사용자의 제안으로 탄생한 최종 통합 헬퍼 함수
 * 계산된 최종 색상을 기반으로, iconColor와 gradientColor의 역할을 분리하여 데이터를 생성합니다.
 */
private fun buildEffectData(finalColor: Color): EffectColorData {
    // 1. 아이콘 색상은 언제나 불투명해야 합니다.
    val iconColor = finalColor.copy(alpha = 1f)

    // 2. 그라데이션(Glow)은 색상이 검은색이 되면 불필요하므로 제거합니다.
    val gradientColor = if (finalColor.isBlack()) null else finalColor

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        gradientColor = gradientColor
    )
}

/**
 * ✅ ON: LaunchedEffect만 사용, SideEffect 제거
 */
@Composable
private fun animateOnEffect(
    fromColor: Color,
    targetColor: Color,
    transit: Int,
    randomColor: Boolean,
    onColorUpdate: (Color) -> Unit,
    backgroundColor: Color
): EffectColorData {
    val animatable = remember { Animatable(0f) }
    val initialColor = remember { fromColor }

    LaunchedEffect(transit, targetColor, randomColor) {
        animatable.snapTo(0f)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = transit * 100, easing = LinearEasing)
        )
    }

    val progress = animatable.value

    val iconColor = if (randomColor) {
        rememberRandomHueColor(alpha = progress)
    } else {
        hsvLerp(initialColor, targetColor, progress)
    }

    onColorUpdate(iconColor)

    // 아이콘 색상과 카드 배경을 혼합하여 빛 번짐 색상을 만듭니다.
    val blendedGradientColor = lerp(iconColor, backgroundColor, 0.5f)

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        // 아이콘이 검은색이면 빛 번짐도 없습니다.
        gradientColor = blendedGradientColor
    )
}


/**
 * ✅ OFF: RingIcon과 배경 그라데이션의 색상을 분리하여 제어
 */
@Composable
private fun animateOffEffect(
    fromColor: Color,
    transit: Int,
    onColorUpdate: (Color) -> Unit,
    backgroundColor: Color
): EffectColorData {
    val animatable = remember { Animatable(0f) }
    val initialColor = remember { fromColor.copy(alpha = 1f) }

    LaunchedEffect(transit) {
        animatable.snapTo(0f)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = transit * 100, easing = LinearEasing)
        )
    }

    val progress = animatable.value

    // Icon color fades to black
    val iconColor = hsvLerp(initialColor, Color.Black, progress)
    onColorUpdate(iconColor)

    // 아이콘 색상과 카드 배경을 혼합하여 빛 번짐 색상을 만듭니다.
    val blendedGradientColor = lerp(iconColor, backgroundColor, 0.5f)

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        // 아이콘이 검은색이 되면 빛 번짐도 자연스럽게 사라집니다.
        gradientColor = blendedGradientColor
    )
}

@Composable
private fun animatePeriodicEffect(
    fgColor: Color,
    bgColor: Color,
    period: Int,
    randomColor: Boolean,
    fgThreshold: Float,
    backgroundColor: Color
): EffectColorData {
    // ✅ [수정] 주기가 0 이하이면 애니메이션을 실행하지 않고 즉시 반환합니다.
    if (period <= 0) {
        val blendedGradientColor = lerp(fgColor, backgroundColor, 0.5f)
        return EffectColorData(
            iconColor = fgColor,
            iconBrush = null,
            gradientColor = if (fgColor.isBlack()) null else blendedGradientColor
        )
    }

    return key(period, randomColor, bgColor) {
        val infiniteTransition = rememberInfiniteTransition(label = "periodicEffect")
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(period * 100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "periodicProgress"
        )

        val isFg = progress < fgThreshold
        val finalFgColor = if (randomColor) rememberRandomHueColor(alpha = 1f) else fgColor

        val iconColor = if (isFg) finalFgColor else bgColor
        val blendedGradientColor = lerp(iconColor, backgroundColor, 0.5f)

        EffectColorData(
            iconColor = iconColor,
            iconBrush = null,
            gradientColor = blendedGradientColor
        )
    }
}

@Composable
private fun animateBreathEffect(
    fgColor: Color,
    bgColor: Color,
    period: Int,
    randomColor: Boolean,
    backgroundColor: Color,
    onColorUpdate: (Color) -> Unit
): EffectColorData {
    // ✅ [수정] 주기가 0 이하이면 애니메이션을 실행하지 않고 즉시 반환합니다.
    if (period <= 0) {
        onColorUpdate(fgColor)
        val blendedGradientColor = lerp(fgColor, backgroundColor, 0.5f)
        return EffectColorData(
            iconColor = fgColor,
            iconBrush = null,
            gradientColor = if (fgColor.isBlack()) null else blendedGradientColor
        )
    }

    return key(period, randomColor, bgColor) {
        val breathProgress by rememberInfiniteTransition(label = "breathEffect").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(period * 100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "breathProgress"
        )

        val finalFgColor = if (randomColor) rememberRandomHueColor(alpha = 1f) else fgColor

        val iconColor: Color = when {
            breathProgress < 0.25f -> {
                // 1. Hold FG
                finalFgColor
            }
            breathProgress < 0.5f -> {
                // 2. Transition FG -> BG
                val progress = (breathProgress - 0.25f) / 0.25f
                hsvLerp(finalFgColor, bgColor, progress)
            }
            breathProgress < 0.75f -> {
                // 3. Hold BG
                bgColor
            }
            else -> {
                // 4. Transition BG -> FG
                val progress = (breathProgress - 0.75f) / 0.25f
                hsvLerp(bgColor, finalFgColor, progress)
            }
        }

        onColorUpdate(iconColor)

        // Blend current icon color with the card background for the gradient
        val blendedGradientColor = lerp(iconColor, backgroundColor, 0.5f)

        EffectColorData(
            iconColor = iconColor,
            iconBrush = null,
            gradientColor = if (iconColor.isBlack()) null else blendedGradientColor
        )
    }
}

// Delete the old buildEffectData and createEffectColorData functions

// 기존에 중복되던 lerp 함수 하나를 삭제하고, 아래 코드로 교체합니다.
private fun hsvLerp(start: Color, end: Color, fraction: Float): Color {
    val startHsv = FloatArray(3)
    AndroidColor.colorToHSV(start.toArgb(), startHsv)

    val endHsv = FloatArray(3)
    AndroidColor.colorToHSV(end.toArgb(), endHsv)

    if (startHsv[1] < 0.01f) { // start is grayscale
        startHsv[0] = endHsv[0]
    }
    if (endHsv[1] < 0.01f) { // end is grayscale
        endHsv[0] = startHsv[0]
    }

    // Interpolate Hue, taking the shortest path around the color wheel
    val hue: Float
    val hueDiff = endHsv[0] - startHsv[0]
    if (abs(hueDiff) > 180) {
        if (hueDiff > 0) {
            hue = (startHsv[0] + (hueDiff - 360) * fraction)
        } else {
            hue = (startHsv[0] + (hueDiff + 360) * fraction)
        }
    } else {
        hue = startHsv[0] + hueDiff * fraction
    }

    val finalHue = (hue + 360) % 360

    // Interpolate Saturation and Value
    val saturation = startHsv[1] + (endHsv[1] - startHsv[1]) * fraction
    val value = startHsv[2] + (endHsv[2] - startHsv[2]) * fraction

    // Interpolate Alpha
    val alpha = start.alpha + (end.alpha - start.alpha) * fraction

    return Color.hsv(finalHue, saturation, value, alpha)
}

@Composable
private fun DescriptionIcon(
    icon: ImageVector,
    isAnimating: Boolean,
    onClick: (() -> Unit)?,
    tint: Color
) {
    val rotation by if (isAnimating) {
        val infiniteTransition = rememberInfiniteTransition(label = "description_icon_rotation")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val modifier = Modifier.rotate(rotation)

    if (onClick != null) {
        // 클릭 가능한 아이콘 (IconButton으로 터치 영역 확보)
        IconButton(onClick = onClick, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = modifier.size(16.dp),
                tint = tint
            )
        }
    } else {
        // 애니메이션만 있는 아이콘
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier
                .padding(2.dp) // IconButton과 시각적 정렬을 위한 미세 조정
                .size(16.dp),
            tint = tint
        )
    }
}

@Composable
private fun ConnectionStateLayout(
    boxSize: Dp,
    effectSize: Dp,
    cornerRadius: Dp,
    isScrolled: Boolean,
    backgroundColor: Color = Color.White.copy(alpha = 0.06f),
    lightstickColor: Color = Color.White,
    isLightStickAnimating: Boolean = false,
    statusText: String,
    statusTextColor: Color,
    descriptionText: String? = null,
    descriptionTextColor: Color? = null,
    descriptionIcon: ImageVector? = null,
    isDescriptionIconAnimating: Boolean = false,
    onDescriptionIconClick: (() -> Unit)? = null,
    effectColorData: EffectColorData?,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null
) {
    // Description 영역 Composable
    val description: @Composable (() -> Unit)? =
        if (descriptionText != null && descriptionTextColor != null) {
            @Composable {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = descriptionTextColor
                    )
                    descriptionIcon?.let {
                        DescriptionIcon(
                            icon = it,
                            isAnimating = isDescriptionIconAnimating,
                            onClick = onDescriptionIconClick,
                            tint = descriptionTextColor
                        )
                    }
                }
            }
        } else {
            null
        }

    // Button 영역 Composable
    val button: @Composable (() -> Unit)? =
        if (buttonText != null && onButtonClick != null) {
            @Composable {
                BaseButton(
                    text = buttonText,
                    onClick = onButtonClick,
                    style = ButtonStyle.PRIMARY,
                    modifier = Modifier
                        .width(180.dp)
                        .height(44.dp)  // ✅ Dialog 버튼은 48dp
                )
            }
        } else {
            null
        }


    if (isScrolled) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RoundedIconBox(
                size = boxSize,
                backgroundColor = backgroundColor,
                cornerRadius = cornerRadius,
                effectColorData = effectColorData
            ) {
                LightStickIcon(
                    size = effectSize,
                    color = lightstickColor,
                    brush = effectColorData?.iconBrush,
                    isAnimating = isLightStickAnimating
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleLarge,
                    color = statusTextColor
                )

                description?.invoke()

                if (button != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    button()
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp), // ✅ 큰 카드일 때 상하 여백 추가
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RoundedIconBox(
                size = boxSize,
                backgroundColor = backgroundColor,
                cornerRadius = cornerRadius,
                effectColorData = effectColorData
            ) {
                LightStickIcon(
                    size = effectSize,
                    color = lightstickColor,
                    brush = effectColorData?.iconBrush,
                    isAnimating = isLightStickAnimating
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleLarge,
                color = statusTextColor
            )

            description?.invoke()

            if (button != null) {
                Spacer(modifier = Modifier.height(4.dp))
                button()
            }
        }
    }
}

/**
 * ✅ RoundedIconBox - 동적 radius 계산
 */
@Composable
private fun RoundedIconBox(
    size: Dp,
    backgroundColor: Color,
    cornerRadius: Dp,
    effectColorData: EffectColorData?,
    content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(cornerRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        // gradientColor가 null이 아니면, 전달받은 색상과 투명도 그대로 그라데이션을 그림
        if (effectColorData?.gradientColor != null) {
            val localDensity = androidx.compose.ui.platform.LocalDensity.current
            val sizeInPx = with(localDensity) { size.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                // ✅ 수정: 내부 애니메이션을 제거하고 전달받은 색을 그대로 사용
                                effectColorData.gradientColor,
                                Color.Transparent
                            ),
                            radius = sizeInPx * 0.5f
                        ),
                        shape = RoundedCornerShape(cornerRadius)
                    )
            )
        }

        content()
    }
}

@Composable
private fun LightStickIcon(
    size: Dp,
    color: Color,
    brush: Brush? = null,
    isAnimating: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val scanRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isAnimating) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanRotation"
    )

    Canvas(
        modifier = Modifier
            .size(size)
            .rotate(scanRotation)
    ) {
        val strokeWidth = this.size.width * 0.15f
        val radius = (this.size.width - strokeWidth) / 2
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        if (brush != null) {
            drawCircle(
                brush = brush,
                radius = this.size.width / 2f,
                center = center
            )
            drawCircle(
                color = Color.Transparent,
                radius = radius - strokeWidth / 2,
                center = center,
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )
        } else {
            drawCircle(
                color = color,
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}