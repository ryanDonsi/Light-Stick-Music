package com.lightstick.music.ui.components.effect

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.types.EffectType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * BLACK 색상 체크 (RGB가 모두 0.1 미만)
 */
private fun Color.isBlack(): Boolean {
    return this.red < 0.1f && this.green < 0.1f && this.blue < 0.1f
}

/**
 * Device Connection Card
 *
 * [추가] latestTransmission 이 TIMELINE_EFFECT 일 때 Connected 상태 statusText 옆에
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
    latestTransmission: BleTransmissionEvent? = null
) {
    val lastColorRef = remember { mutableStateOf(Color.White) }
    val currentDisplayedColorRef = remember { mutableStateOf(Color.White) }

    val animatedBoxSize by animateDpAsState(
        targetValue = if (isScrolled) 124.dp else 180.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "boxSize"
    )
    val animatedEffectSize by animateDpAsState(
        targetValue = if (isScrolled) 70.dp else 98.dp,
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

    val effectColorData = if (latestTransmission != null) {
        calculateEffectColorFromTransmission(
            transmission = latestTransmission,
            backgroundColor = cardBackgroundColor,
            lastColorRef = lastColorRef,
            currentDisplayedColorRef = currentDisplayedColorRef
        )
    } else {
        calculateEffectColor(
            isPlaying = isPlaying,
            selectedEffect = selectedEffect,
            effectSettings = effectSettings,
            backgroundColor = cardBackgroundColor,
            lastColorRef = lastColorRef,
            currentDisplayedColorRef = currentDisplayedColorRef
        )
    }

    SideEffect {
        effectColorData?.iconColor?.let { iconColor ->
            currentDisplayedColorRef.value = iconColor
        }
    }

    when (connectionState) {
        is EffectViewModel.DeviceConnectionState.NoBondedDevice -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = MaterialTheme.customColors.disable,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
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
                lightstickColor = MaterialTheme.customColors.disable,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                descriptionText = "등록된 기기 확인 중",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon = Icons.Default.Refresh,
                isDescriptionIconAnimating = true,
                effectColorData = null
            )
        }

        is EffectViewModel.DeviceConnectionState.ScanFailed -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = MaterialTheme.customColors.disable,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                descriptionText = "연결 가능한 기기가 없습니다",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon = Icons.Default.Refresh,
                onDescriptionIconClick = onRetryClick,
                effectColorData = null
            )
        }

        is EffectViewModel.DeviceConnectionState.Disconnected -> {
            ConnectionStateLayout(
                boxSize = animatedBoxSize,
                effectSize = animatedEffectSize,
                cornerRadius = animatedCornerRadius,
                isScrolled = isScrolled,
                lightstickColor = MaterialTheme.customColors.disable,
                statusText = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                descriptionText = "연결 가능한 기기가 없습니다",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon = Icons.Default.Refresh,
                onDescriptionIconClick = onRetryClick,
                effectColorData = null
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
                statusTextColor = MaterialTheme.customColors.secondary,
                descriptionText = connectionState.device.name ?: "UNKNOWN",
                descriptionTextColor = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                effectColorData = effectColorData
            )
        }
    }
}

data class EffectColorData(
    val iconColor: Color,
    val iconBrush: Brush?,
    val gradientColor: Color?
)

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
    onButtonClick: (() -> Unit)? = null,

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
        } else null

    val button: @Composable (() -> Unit)? =
        if (buttonText != null && onButtonClick != null) {
            @Composable {
                BaseButton(
                    text = buttonText,
                    onClick = onButtonClick,
                    style = ButtonStyle.PRIMARY,
                    modifier = Modifier.width(180.dp).height(44.dp)
                )
            }
        } else null

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
                    isAnimating = isLightStickAnimating,
                    isDisabled = effectColorData == null
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        color = statusTextColor
                    )

                }
                description?.invoke()
            }

            Spacer(modifier = Modifier.weight(1f))
            if (button != null) button()
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
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
                    isAnimating = isLightStickAnimating,
                    isDisabled = effectColorData == null
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleLarge,
                    color = statusTextColor
                )
            }

            description?.invoke()

            if (button != null) {
                Spacer(modifier = Modifier.height(4.dp))
                button()
            }
        }
    }
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
            Icon(imageVector = icon, contentDescription = null,
                modifier = modifier.size(16.dp), tint = tint)
        }
    } else {
        Icon(imageVector = icon, contentDescription = null,
            modifier = modifier.padding(2.dp).size(16.dp), tint = tint)
    }
}

@Composable
private fun calculateEffectColorFromTransmission(
    transmission: BleTransmissionEvent,
    backgroundColor: Color,
    lastColorRef: MutableState<Color>,
    currentDisplayedColorRef: MutableState<Color>
): EffectColorData? {
    val lastAnimationKeyRef = remember { mutableStateOf<String?>(null) }

    fun staticColorData(color: Color): EffectColorData {
        val gradientColor =
            if (color.isBlack()) null else lerp(color, backgroundColor, 0.2f)
        return EffectColorData(
            iconColor = color,
            iconBrush = null,
            gradientColor = gradientColor
        )
    }

    return when (transmission.source) {
        TransmissionSource.PAYLOAD_EFFECT,
        TransmissionSource.EFX_EFFECT,
        TransmissionSource.TIMELINE_EFFECT,
        TransmissionSource.EVENT_EFFECT -> {
            when (transmission.effectType) {
                EffectType.ON -> {
                    val transit = transmission.transit ?: 10
                    val targetColor = transmission.color?.toComposeColor() ?: Color.White
                    val animKey = "TX_ON_${transit}_${targetColor.toArgb()}"

                    if (lastAnimationKeyRef.value == animKey &&
                        currentDisplayedColorRef.value == targetColor
                    ) {
                        staticColorData(targetColor)
                    } else {
                        lastAnimationKeyRef.value = animKey
                        animateOnEffect(
                            animationKey = animKey,
                            fromColor = currentDisplayedColorRef.value,
                            targetColor = targetColor,
                            transit = transit,
                            randomColor = false,
                            onColorUpdate = { lastColorRef.value = it },
                            backgroundColor = backgroundColor
                        )
                    }
                }

                EffectType.OFF -> {
                    val transit = transmission.transit ?: 10
                    val animKey = "TX_OFF_$transit"

                    if (lastAnimationKeyRef.value == animKey &&
                        currentDisplayedColorRef.value.isBlack()
                    ) {
                        staticColorData(Color.Black)
                    } else {
                        lastAnimationKeyRef.value = animKey
                        animateOffEffect(
                            animationKey = animKey,
                            fromColor = currentDisplayedColorRef.value,
                            transit = transit,
                            onColorUpdate = { lastColorRef.value = it },
                            backgroundColor = backgroundColor
                        )
                    }
                }

                EffectType.STROBE -> {
                    val period = transmission.period ?: 5
                    val fgThreshold =
                        if (period <= 10) 0.1f
                        else 1f / period.coerceAtLeast(1).toFloat()

                    lastAnimationKeyRef.value = "TX_STROBE_$period"

                    animatePeriodicEffect(
                        effectName = "STROBE",
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period,
                        randomColor = false,
                        fgThreshold = fgThreshold,
                        backgroundColor = backgroundColor
                    )
                }

                EffectType.BLINK -> {
                    val period = transmission.period ?: 20

                    lastAnimationKeyRef.value = "TX_BLINK_$period"

                    animatePeriodicEffect(
                        effectName = "BLINK",
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period,
                        randomColor = false,
                        fgThreshold = 0.5f,
                        backgroundColor = backgroundColor
                    )
                }

                EffectType.BREATH -> {
                    val period = transmission.period ?: 40
                    val fgColor = transmission.color?.toComposeColor() ?: Color.White
                    val bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black
                    val animKey = "TX_BREATH_${period}_${fgColor.toArgb()}_${bgColor.toArgb()}"

                    lastAnimationKeyRef.value = animKey

                    animateBreathEffect(
                        animationKey = animKey,
                        fromColor = currentDisplayedColorRef.value,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        period = period,
                        randomColor = false,
                        backgroundColor = backgroundColor,
                        onColorUpdate = { lastColorRef.value = it }
                    )
                }

                else -> {
                    EffectColorData(
                        iconColor = Color.White,
                        iconBrush = null,
                        gradientColor = null
                    )
                }
            }
        }

        TransmissionSource.FFT_EFFECT -> {
            lastAnimationKeyRef.value = "TX_FFT"
            val fftColor = transmission.color?.toComposeColor() ?: Color.White
            EffectColorData(
                iconColor = fftColor,
                iconBrush = Brush.linearGradient(
                    listOf(
                        fftColor,
                        fftColor.copy(alpha = 0.7f),
                        fftColor.copy(alpha = 0.4f)
                    )
                ),
                gradientColor = null
            )
        }
    }
}

@Composable
private fun calculateEffectColor(
    isPlaying: Boolean,
    selectedEffect: EffectViewModel.UiEffectType?,
    effectSettings: EffectViewModel.EffectSettings?,
    backgroundColor: Color,
    lastColorRef: MutableState<Color>,
    currentDisplayedColorRef: MutableState<Color>
): EffectColorData? {

    if (!isPlaying || selectedEffect == null || effectSettings == null) {
        lastColorRef.value = Color.White
        return EffectColorData(iconColor = Color.White, iconBrush = null, gradientColor = null)
    }

    return key(selectedEffect, effectSettings) {
        when (selectedEffect) {
            is EffectViewModel.UiEffectType.On -> {
                val targetColor = effectSettings.color.toComposeColor()
                val animKey = "SEL_ON_${effectSettings.transit}_${targetColor.toArgb()}_${effectSettings.randomColor}"
                animateOnEffect(
                    animationKey = animKey,
                    fromColor = currentDisplayedColorRef.value,
                    targetColor = targetColor,
                    transit = effectSettings.transit,
                    randomColor = effectSettings.randomColor,
                    onColorUpdate = { lastColorRef.value = it },
                    backgroundColor = backgroundColor
                )
            }

            is EffectViewModel.UiEffectType.Off -> {
                val animKey = "SEL_OFF_${effectSettings.transit}"
                animateOffEffect(
                    animationKey = animKey,
                    fromColor = currentDisplayedColorRef.value,
                    transit = effectSettings.transit,
                    onColorUpdate = { lastColorRef.value = it },
                    backgroundColor = backgroundColor
                )
            }

            is EffectViewModel.UiEffectType.Strobe -> {
                val period = effectSettings.period
                val fgThreshold =
                    if (period <= 10) 0.1f
                    else 1f / period.coerceAtLeast(1).toFloat()

                animatePeriodicEffect(
                    effectName = "STROBE",
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = period,
                    randomColor = effectSettings.randomColor,
                    fgThreshold = fgThreshold,
                    backgroundColor = backgroundColor
                )
            }

            is EffectViewModel.UiEffectType.Blink -> {
                animatePeriodicEffect(
                    effectName = "BLINK",
                    fgColor = effectSettings.color.toComposeColor(),
                    bgColor = effectSettings.backgroundColor.toComposeColor(),
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    fgThreshold = 0.5f,
                    backgroundColor = backgroundColor
                )
            }

            is EffectViewModel.UiEffectType.Breath -> {
                val fgColor = effectSettings.color.toComposeColor()
                val bgColor = effectSettings.backgroundColor.toComposeColor()
                val animKey = "SEL_BREATH_${effectSettings.period}_${fgColor.toArgb()}_${bgColor.toArgb()}_${effectSettings.randomColor}"
                animateBreathEffect(
                    animationKey = animKey,
                    fromColor = currentDisplayedColorRef.value,
                    fgColor = fgColor,
                    bgColor = bgColor,
                    period = effectSettings.period,
                    randomColor = effectSettings.randomColor,
                    backgroundColor = backgroundColor,
                    onColorUpdate = { lastColorRef.value = it }
                )
            }

            is EffectViewModel.UiEffectType.Custom -> when (selectedEffect.baseType) {
                EffectViewModel.UiEffectType.BaseEffectType.ON -> {
                    val targetColor = effectSettings.color.toComposeColor()
                    val animKey = "CUS_ON_${effectSettings.transit}_${targetColor.toArgb()}_${effectSettings.randomColor}"
                    animateOnEffect(
                        animationKey = animKey,
                        fromColor = currentDisplayedColorRef.value,
                        targetColor = targetColor,
                        transit = effectSettings.transit,
                        randomColor = effectSettings.randomColor,
                        onColorUpdate = { lastColorRef.value = it },
                        backgroundColor = backgroundColor
                    )
                }

                EffectViewModel.UiEffectType.BaseEffectType.OFF -> {
                    val animKey = "CUS_OFF_${effectSettings.transit}"
                    animateOffEffect(
                        animationKey = animKey,
                        fromColor = currentDisplayedColorRef.value,
                        transit = effectSettings.transit,
                        onColorUpdate = { lastColorRef.value = it },
                        backgroundColor = backgroundColor
                    )
                }

                EffectViewModel.UiEffectType.BaseEffectType.STROBE -> {
                    val period = effectSettings.period
                    val fgThreshold =
                        if (period <= 10) 0.1f
                        else 1f / period.coerceAtLeast(1).toFloat()

                    animatePeriodicEffect(
                        effectName = "STROBE",
                        fgColor = effectSettings.color.toComposeColor(),
                        bgColor = effectSettings.backgroundColor.toComposeColor(),
                        period = period,
                        randomColor = effectSettings.randomColor,
                        fgThreshold = fgThreshold,
                        backgroundColor = backgroundColor
                    )
                }

                EffectViewModel.UiEffectType.BaseEffectType.BLINK -> {
                    animatePeriodicEffect(
                        effectName = "BLINK",
                        fgColor = effectSettings.color.toComposeColor(),
                        bgColor = effectSettings.backgroundColor.toComposeColor(),
                        period = effectSettings.period,
                        randomColor = effectSettings.randomColor,
                        fgThreshold = 0.5f,
                        backgroundColor = backgroundColor
                    )
                }

                EffectViewModel.UiEffectType.BaseEffectType.BREATH -> {
                    val fgColor = effectSettings.color.toComposeColor()
                    val bgColor = effectSettings.backgroundColor.toComposeColor()
                    val animKey = "CUS_BREATH_${effectSettings.period}_${fgColor.toArgb()}_${bgColor.toArgb()}_${effectSettings.randomColor}"
                    animateBreathEffect(
                        animationKey = animKey,
                        fromColor = currentDisplayedColorRef.value,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        period = effectSettings.period,
                        randomColor = effectSettings.randomColor,
                        backgroundColor = backgroundColor,
                        onColorUpdate = { lastColorRef.value = it }
                    )
                }
            }

            else -> null
        }
    }
}

@Composable
private fun rememberRandomHueColor(alpha: Float = 1f): Color {
    val infiniteTransition = rememberInfiniteTransition(label = "hueRotation")
    val hue by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "hue"
    )
    return Color.hsv(hue, 1f, 1f, alpha)
}

@Composable
private fun animateOnEffect(
    animationKey: String,
    fromColor: Color,
    targetColor: Color,
    transit: Int,
    randomColor: Boolean,
    onColorUpdate: (Color) -> Unit,
    backgroundColor: Color
): EffectColorData {
    val fixedStartColor = remember(animationKey) { fromColor }
    val animatable = remember(animationKey) { Animatable(0f) }

    LaunchedEffect(animationKey) {

        animatable.snapTo(0f)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = transit.coerceAtLeast(0) * 100,
                easing = LinearEasing
            )
        )

        onColorUpdate(targetColor)

    }

    val progress = animatable.value
    val iconColor =
        if (randomColor) rememberRandomHueColor(alpha = progress)
        else hsvLerp(fixedStartColor, targetColor, progress)

    LaunchedEffect(animationKey, (progress * 10).toInt()) {
    }

    val gradientColor =
        if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        gradientColor = gradientColor
    )
}

@Composable
private fun animateOffEffect(
    animationKey: String,
    fromColor: Color,
    transit: Int,
    onColorUpdate: (Color) -> Unit,
    backgroundColor: Color
): EffectColorData {
    val fixedStartColor = remember(animationKey) {
        fromColor.copy(alpha = 1f)
    }

    val animatable = remember(animationKey) {
        Animatable(0f)
    }

    LaunchedEffect(animationKey) {

        animatable.snapTo(0f)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = transit.coerceAtLeast(0) * 100,
                easing = LinearEasing
            )
        )

        onColorUpdate(Color.Black)

    }

    val progress = animatable.value
    val iconColor = hsvLerp(fixedStartColor, Color.Black, progress)

    LaunchedEffect(animationKey, (progress * 10).toInt()) {
    }

    val gradientColor =
        if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        gradientColor = gradientColor
    )
}

@Composable
private fun animatePeriodicEffect(
    effectName: String,
    fgColor: Color,
    bgColor: Color,
    period: Int,
    randomColor: Boolean,
    fgThreshold: Float,
    backgroundColor: Color
): EffectColorData {
    val resolvedFg = if (randomColor) rememberRandomHueColor() else fgColor
    val resolvedBg = bgColor

    val uiPeriodScale = 103.7f
    val duration = (period * uiPeriodScale).roundToInt().coerceAtLeast(104)

    LaunchedEffect(effectName, fgColor, bgColor, period, randomColor, fgThreshold) {
    }

    val progress by rememberInfiniteTransition(label = "${effectName}_periodic")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "${effectName}_periodicProgress"
        )

    val iconColor = if (progress < fgThreshold) resolvedFg else resolvedBg

    LaunchedEffect((progress * 10).toInt()) {
    }

    val gradientColor =
        if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        gradientColor = gradientColor
    )
}

@Composable
private fun animateBreathEffect(
    animationKey: String,
    fromColor: Color,
    fgColor: Color,
    bgColor: Color,
    period: Int,
    randomColor: Boolean,
    backgroundColor: Color,
    onColorUpdate: (Color) -> Unit
): EffectColorData {
    if (period <= 0) {
        return EffectColorData(
            iconColor = fgColor,
            iconBrush = null,
            gradientColor = if (fgColor.isBlack()) null else lerp(fgColor, backgroundColor, 0.2f)
        )
    }

    val fixedStartColor = remember(animationKey) { fromColor }
    val resolvedFg = if (randomColor) rememberRandomHueColor(alpha = 1f) else fgColor
    val resolvedBg = bgColor

    val isFirstCycle = remember(animationKey) { mutableStateOf(true) }

    val uiPeriodScale = 104.5f
    val duration = (period * uiPeriodScale).roundToInt().coerceAtLeast(105)

    LaunchedEffect(animationKey) {
    }

    val transition = rememberInfiniteTransition(label = "breathEffect_$animationKey")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathProgress_$animationKey"
    )

    LaunchedEffect(animationKey, (progress * 10).toInt()) {
    }

    LaunchedEffect(progress) {
        if (progress >= 0.99f && isFirstCycle.value) {
            isFirstCycle.value = false
        }
    }

    val iconColor: Color = if (isFirstCycle.value) {
        when {
            progress < 0.35f -> {
                val p = progress / 0.35f
                hsvLerp(fixedStartColor, resolvedFg, p)
            }
            progress < 0.55f -> {
                resolvedFg
            }
            progress < 0.85f -> {
                val p = (progress - 0.55f) / 0.30f
                hsvLerp(resolvedFg, resolvedBg, p)
            }
            else -> {
                resolvedBg
            }
        }
    } else {
        when {
            progress < 0.25f -> {
                val p = progress / 0.25f
                hsvLerp(resolvedBg, resolvedFg, p)
            }
            progress < 0.50f -> {
                resolvedFg
            }
            progress < 0.75f -> {
                val p = (progress - 0.50f) / 0.25f
                hsvLerp(resolvedFg, resolvedBg, p)
            }
            else -> {
                resolvedBg
            }
        }
    }

    val gradientColor =
        if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)

    return EffectColorData(
        iconColor = iconColor,
        iconBrush = null,
        gradientColor = gradientColor
    )
}

private fun hsvLerp(start: Color, end: Color, fraction: Float): Color {
    val startHsv = FloatArray(3)
    AndroidColor.colorToHSV(start.toArgb(), startHsv)
    val endHsv = FloatArray(3)
    AndroidColor.colorToHSV(end.toArgb(), endHsv)
    if (startHsv[1] < 0.01f) startHsv[0] = endHsv[0]
    if (endHsv[1] < 0.01f) endHsv[0] = startHsv[0]
    val hueDiff = endHsv[0] - startHsv[0]
    val hue = if (kotlin.math.abs(hueDiff) > 180) {
        startHsv[0] + (if (hueDiff > 0) hueDiff - 360 else hueDiff + 360) * fraction
    } else startHsv[0] + hueDiff * fraction
    val finalHue = (hue + 360) % 360
    val saturation = startHsv[1] + (endHsv[1] - startHsv[1]) * fraction
    val value = startHsv[2] + (endHsv[2] - startHsv[2]) * fraction
    val alpha = start.alpha + (end.alpha - start.alpha) * fraction
    return Color.hsv(finalHue, saturation, value, alpha)
}

private fun buildEffectData(finalColor: Color, backgroundColor: Color = Color.Transparent): EffectColorData {
    val gradientColor = if (finalColor.isBlack()) null else lerp(finalColor, backgroundColor, 0.2f)
    return EffectColorData(iconColor = finalColor, iconBrush = null, gradientColor = gradientColor)
}

/**
 * LightStickIcon — 제품 형상 기반 아이콘 (위에서 본 원형 puck)
 *
 * 구조: 외부 글로우 → LED 링 (이펙트 색상) → 메탈 원판 (동심원 결) → 중앙 노브
 */
@Composable
private fun LightStickIcon(
    size: Dp,
    color: Color,
    brush: Brush?,
    isAnimating: Boolean = false,
    isDisabled: Boolean = false
) {
    val glowAlpha by if (isAnimating) {
        rememberInfiniteTransition(label = "ledGlow").animateFloat(
            initialValue = 0.3f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation  = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    val resolvedColor = if (isDisabled) color.copy(alpha = 0.35f) else color

    Canvas(modifier = Modifier.size(size)) {
        val w  = this.size.width
        val cx = w / 2f
        val cy = w / 2f
        val ct = Offset(cx, cy)

        val ledOuterR = w * 0.47f
        val metalR    = w * 0.22f
        val ledInnerR = metalR
        val knobR     = w * 0.09f
        val ledMidR   = (ledOuterR + ledInnerR) / 2f
        val ledW      = ledOuterR - ledInnerR

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = glowAlpha * 0.70f),
                    color.copy(alpha = glowAlpha * 0.20f),
                    Color.Transparent
                ),
                center = ct,
                radius = w * 0.53f
            ),
            radius = w * 0.53f,
            center = ct
        )

        val maskColor = Color(0xFF909090)
        if (brush != null) {
            drawCircle(brush = brush, radius = ledOuterR, center = ct)
            drawCircle(color = maskColor, radius = ledInnerR, center = ct)
        } else {
            drawCircle(color = resolvedColor.copy(alpha = 1f), radius = ledOuterR, center = ct)
            drawCircle(color = maskColor, radius = ledInnerR, center = ct)
        }

        val metalBaseColor = if (isDisabled)
            Color(0xFF505050)
        else
            Color(0xFFB0B0B0)

        drawCircle(color = metalBaseColor, radius = metalR, center = ct)

        if (!isDisabled) {
            drawCircle(
                brush  = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(cx - metalR * 0.20f, cy - metalR * 0.25f),
                    radius = metalR * 0.70f
                ),
                radius = metalR,
                center = ct
            )
        }

        drawCircle(
            color  = Color.Black.copy(alpha = if (isDisabled) 0.30f else 0.15f),
            radius = metalR,
            center = ct,
            style  = Stroke(width = w * 0.008f)
        )
    }
}

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
            .background(color = backgroundColor, shape = RoundedCornerShape(cornerRadius)),
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
                            colors = listOf(effectColorData.gradientColor, Color.Transparent),
                            radius = sizeInPx * 0.5f
                        ),
                        shape = RoundedCornerShape(cornerRadius)
                    )
            )
        }
        content()
    }
}
