package com.lightstick.music.ui.components.effect

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.types.EffectType
import com.lightstick.types.Color as LightStickColor

/**
 * ✅ BLACK 색상 체크 (RGB가 모두 0.1 미만)
 */
private fun Color.isBlack(): Boolean {
    return this.red < 0.1f && this.green < 0.1f && this.blue < 0.1f
}

/**
 * Device Connection Card
 *
 * ✅ 원본 UI 유지 + latestTransmission 기능 추가
 */
@Composable
fun DeviceConnectionCard(
    connectionState: EffectViewModel.DeviceConnectionState,
    onConnectClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    isScrolled: Boolean = false,
    selectedEffect: EffectViewModel.UiEffectType? = null,
    effectSettings: EffectViewModel.EffectSettings? = null,
    isPlaying: Boolean = false,
    latestTransmission: BleTransmissionEvent? = null,  // ✅ 새로 추가 (기본값 null)
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

    // ✅ latestTransmission 우선 → selectedEffect fallback
    val effectColorData = if (latestTransmission != null) {
        calculateEffectColorFromTransmission(latestTransmission)
    } else {
        calculateEffectColor(
            isPlaying = isPlaying,
            selectedEffect = selectedEffect,
            effectSettings = effectSettings
        )
    }

    when (connectionState) {
        is EffectViewModel.DeviceConnectionState.NoBondedDevice -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = Color.White.copy(alpha = 0.06f),
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
                backgroundColor = Color.White.copy(alpha = 0.06f),
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
                backgroundColor = Color.White.copy(alpha = 0.06f),
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
                backgroundColor = Color(0xFFA774FF).copy(alpha = 0.22f),
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
 * ✅ 새로 추가: transmission 기반 색상 계산
 */
@Composable
private fun calculateEffectColorFromTransmission(
    transmission: BleTransmissionEvent
): EffectColorData? {
    return when (transmission.source) {
        TransmissionSource.MANUAL_EFFECT, TransmissionSource.TIMELINE_EFFECT -> {
            when (transmission.effectType) {
                EffectType.ON -> {
                    buildEffectData(transmission.color?.toComposeColor() ?: Color.White)
                }
                EffectType.OFF -> {
                    buildEffectData(Color.White.copy(alpha = 0.3f))
                }
                EffectType.STROBE -> {
                    val period = transmission.period ?: 20
                    animatePeriodicEffect(
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period,
                        randomColor = false,
                        fgThreshold = 0.1f
                    )
                }
                EffectType.BLINK -> {
                    val period = transmission.period ?: 30
                    animatePeriodicEffect(
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period,
                        randomColor = false,
                        fgThreshold = 0.5f
                    )
                }
                EffectType.BREATH -> {
                    val period = transmission.period ?: 40
                    animateBreathEffect(
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period,
                        randomColor = false
                    )
                }
                else -> buildEffectData(Color.White)
            }
        }
        TransmissionSource.FFT_EFFECT -> {
            val fftColor = transmission.color?.toComposeColor() ?: Color.White
            EffectColorData(
                iconColor = fftColor,
                iconBrush = Brush.linearGradient(
                    colors = listOf(
                        fftColor,
                        fftColor.copy(alpha = 0.7f),
                        fftColor.copy(alpha = 0.4f)
                    )
                ),
                gradientColor = null
            )
        }
        TransmissionSource.CONNECTION_EFFECT, TransmissionSource.BROADCAST -> {
            buildEffectData(Color.White)
        }
    }
}

/**
 * ✅ 원본: calculateEffectColor - key를 사용해서 완전히 격리
 */
@Composable
private fun calculateEffectColor(
    isPlaying: Boolean,
    selectedEffect: EffectViewModel.UiEffectType?,
    effectSettings: EffectViewModel.EffectSettings?
): EffectColorData? {
    val lastColorRef = remember { mutableStateOf(Color.White) }

    if (!isPlaying || selectedEffect == null || effectSettings == null) {
        lastColorRef.value = Color.White
        return createEffectColorData(
            color = Color.White,
            alpha = 1f,
            randomColor = false
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
                    onColorUpdate = { lastColorRef.value = it }
                )
            }
            is EffectViewModel.UiEffectType.Off -> {
                animateOffEffect(
                    fromColor = lastColorRef.value,
                    transit = effectSettings.transit,
                    onColorUpdate = { lastColorRef.value = it }
                )
            }
            is EffectViewModel.UiEffectType.Strobe -> {
                animatePeriodicEffect(
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    fgThreshold = 0.1f
                )
            }
            is EffectViewModel.UiEffectType.Blink -> {
                animatePeriodicEffect(
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    fgThreshold = 0.5f
                )
            }
            is EffectViewModel.UiEffectType.Breath -> {
                animateBreathEffect(
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor
                )
            }
            else -> null  // ✅ 원본: EffectList 등은 null 반환
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
 * ✅ 통합 헬퍼 함수
 */
private fun buildEffectData(finalColor: Color): EffectColorData {
    val iconColor = finalColor.copy(alpha = 1f)
    val gradientColor = if (finalColor.isBlack()) null else finalColor

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        gradientColor = gradientColor
    )
}

/**
 * ✅ 원본: ON - LaunchedEffect만 사용
 */
@Composable
private fun animateOnEffect(
    fromColor: Color,
    targetColor: Color,
    transit: Int,
    randomColor: Boolean,
    onColorUpdate: (Color) -> Unit
): EffectColorData {
    val animatable = remember { Animatable(0f) }
    val initialColor = remember { fromColor }

    LaunchedEffect(transit, targetColor, randomColor) {
        animatable.snapTo(0f)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = transit * 100, easing = LinearEasing)  // ✅ 원본: * 100
        )
    }

    val progress = animatable.value

    return if (randomColor) {
        val randomHue = rememberRandomHueColor(alpha = 1f)
        onColorUpdate(randomHue)
        buildEffectData(randomHue.copy(alpha = progress))
    } else {
        val interpolatedColor = lerp(initialColor, targetColor, progress)
        onColorUpdate(interpolatedColor.copy(alpha = 1f))
        buildEffectData(interpolatedColor)
    }
}

/**
 * ✅ 원본: OFF
 */
@Composable
private fun animateOffEffect(
    fromColor: Color,
    transit: Int,
    onColorUpdate: (Color) -> Unit
): EffectColorData {
    val animatable = remember { Animatable(0f) }
    val initialColor = remember { fromColor }

    LaunchedEffect(transit) {
        animatable.snapTo(0f)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = transit * 100, easing = LinearEasing)  // ✅ 원본: * 100
        )
    }

    val progress = animatable.value
    val interpolatedColor = lerp(initialColor, Color.Black, progress)

    onColorUpdate(interpolatedColor.copy(alpha = 1f))

    return buildEffectData(interpolatedColor)
}

/**
 * ✅ 원본: STROBE, BLINK
 */
@Composable
private fun animatePeriodicEffect(
    fgColor: Color,
    bgColor: Color,
    period: Int,
    randomColor: Boolean,
    fgThreshold: Float
): EffectColorData {
    return key(period, randomColor, bgColor) {
        val infiniteTransition = rememberInfiniteTransition(label = "periodicEffect")
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(period * 100, easing = LinearEasing),  // ✅ 원본: * 100
                repeatMode = RepeatMode.Restart
            ),
            label = "periodicProgress"
        )

        val isFg = progress < fgThreshold
        val finalFgColor = if (randomColor) rememberRandomHueColor(alpha = 1f) else fgColor

        if (isFg) {
            buildEffectData(finalFgColor)
        } else {
            buildEffectData(bgColor)
        }
    }
}

/**
 * ✅ 원본: BREATH
 */
@Composable
private fun animateBreathEffect(
    fgColor: Color,
    bgColor: Color,
    period: Int,
    randomColor: Boolean
): EffectColorData {
    return key(period, randomColor, bgColor) {
        val breathTransition = rememberInfiniteTransition(label = "breathEffect")
        val breathProgress by breathTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(period * 100, easing = LinearEasing),  // ✅ 원본: * 100
                repeatMode = RepeatMode.Restart
            ),
            label = "breathProgress"
        )

        val finalFgColor = if (randomColor) rememberRandomHueColor(alpha = 1f) else fgColor

        val interpolatedColor = if (breathProgress < 0.5f) {
            lerp(bgColor, finalFgColor, breathProgress / 0.5f)
        } else {
            lerp(finalFgColor, bgColor, (breathProgress - 0.5f) / 0.5f)
        }

        buildEffectData(interpolatedColor)
    }
}

private fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
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
        IconButton(onClick = onClick, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = modifier.size(16.dp),
                tint = tint
            )
        }
    } else {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier
                .padding(2.dp)
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

    val button: @Composable (() -> Unit)? =
        if (buttonText != null && onButtonClick != null) {
            @Composable {
                BaseButton(
                    onClick = onButtonClick,
                    text = buttonText
                )
            }
        } else {
            null
        }

    if (isScrolled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
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
 * ✅ 원본: RoundedIconBox
 */
@Composable
private fun RoundedIconBox(
    size: Dp,
    backgroundColor: Color,
    cornerRadius: Dp,
    effectColorData: EffectColorData?,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(cornerRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (effectColorData?.gradientColor != null) {
            val localDensity = androidx.compose.ui.platform.LocalDensity.current
            val sizeInPx = with(localDensity) { size.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
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

/**
 * ✅ 원본: LightStickIcon
 */
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