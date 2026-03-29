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

/**
 * BLACK 색상 체크 (RGB가 모두 0.1 미만)
 */
private fun Color.isBlack(): Boolean {
    return this.red < 0.1f && this.green < 0.1f && this.blue < 0.1f
}

// ──────────────────────────────────────────────────────────────────────────────
// [신규] TimelineEffectBadge
// TIMELINE_EFFECT 수신 시 현재 이펙트 타입을 실시간으로 표시하는 뱃지
// Connected 상태 statusText 옆에만 노출 — 레이아웃 변경 없음
// ──────────────────────────────────────────────────────────────────────────────

private fun effectTypeLabel(effectType: EffectType?): String = when (effectType) {
    EffectType.ON     -> "ON"
    EffectType.OFF    -> "OFF"
    EffectType.BLINK  -> "BLINK"
    EffectType.STROBE -> "STROBE"
    EffectType.BREATH -> "BREATH"
    null              -> "–"
}

@Composable
private fun effectTypeBadgeColor(effectType: EffectType?): Color = when (effectType) {
    EffectType.STROBE -> MaterialTheme.customColors.primary.copy(alpha = 0.25f)
    EffectType.BLINK  -> MaterialTheme.customColors.secondary.copy(alpha = 0.25f)
    EffectType.BREATH -> MaterialTheme.customColors.primaryLevel2.copy(alpha = 0.35f)
    EffectType.ON     -> Color.White.copy(alpha = 0.15f)
    EffectType.OFF    -> Color.White.copy(alpha = 0.06f)
    null              -> Color.White.copy(alpha = 0.08f)
}

/**
 * 타임라인 이펙트 뱃지 — 재사용 가능한 독립 컴포넌트
 */
@Composable
fun TimelineEffectBadge(
    effectType: EffectType?,
    fgColor: Color,
    modifier: Modifier = Modifier
) {
    val bgColor  = effectTypeBadgeColor(effectType)
    val dotColor = if (fgColor.isBlack()) Color.White.copy(alpha = 0.4f) else fgColor

    Row(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(modifier = Modifier.size(6.dp)) {
            drawCircle(color = dotColor, radius = size.minDimension / 2f)
        }
        Text(
            text = effectTypeLabel(effectType),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DeviceConnectionCard  (원본 UI 그대로 유지)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Device Connection Card
 *
 * [추가] latestTransmission 이 TIMELINE_EFFECT 일 때 Connected 상태 statusText 옆에
 *        TimelineEffectBadge 표시 — 레이아웃/크기/색상 등 기존 UI 변경 없음
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

    // 원본 spring 애니메이션 유지
    val animatedBoxSize by animateDpAsState(
        targetValue = if (isScrolled) 124.dp else 180.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "boxSize"
    )
    val animatedEffectSize by animateDpAsState(
        targetValue = if (isScrolled) 70.dp else 98.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "effectSize"
    )
    val animatedCornerRadius by animateDpAsState(
        targetValue = if (isScrolled) 20.dp else 32.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cornerRadius"
    )

    // 원본 배경색 유지
    val cardBackgroundColor = if (connectionState is EffectViewModel.DeviceConnectionState.Connected) {
        Color(0xFFA774FF).copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }

    val effectColorData = if (latestTransmission != null) {
        calculateEffectColorFromTransmission(latestTransmission, cardBackgroundColor, lastColorRef)
    } else {
        calculateEffectColor(isPlaying, selectedEffect, effectSettings, cardBackgroundColor, lastColorRef)
    }

    when (connectionState) {

        // ── NoBondedDevice ────────────────────────────────────
        is EffectViewModel.DeviceConnectionState.NoBondedDevice -> {
            ConnectionStateLayout(
                boxSize         = animatedBoxSize,
                effectSize      = animatedEffectSize,
                cornerRadius    = animatedCornerRadius,
                isScrolled      = isScrolled,
                backgroundColor = cardBackgroundColor,
                lightstickColor = Color.White.copy(alpha = 0.1f),
                statusText      = "연결된 기기 없음",
                statusTextColor = MaterialTheme.customColors.surfaceVariant,
                effectColorData = null,
                buttonText      = "기기 연결하기",
                onButtonClick   = onConnectClick
            )
        }

        // ── Scanning ──────────────────────────────────────────
        is EffectViewModel.DeviceConnectionState.Scanning -> {
            ConnectionStateLayout(
                boxSize                    = animatedBoxSize,
                effectSize                 = animatedEffectSize,
                cornerRadius               = animatedCornerRadius,
                isScrolled                 = isScrolled,
                backgroundColor            = cardBackgroundColor,
                lightstickColor            = Color.White.copy(alpha = 0.1f),
                statusText                 = "연결된 기기 없음",
                statusTextColor            = MaterialTheme.customColors.surfaceVariant,
                descriptionText            = "등록된 기기 확인 중",
                descriptionTextColor       = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon            = Icons.Default.Refresh,
                isDescriptionIconAnimating = true,
                effectColorData            = null
            )
        }

        // ── ScanFailed ────────────────────────────────────────
        is EffectViewModel.DeviceConnectionState.ScanFailed -> {
            ConnectionStateLayout(
                boxSize                = animatedBoxSize,
                effectSize             = animatedEffectSize,
                cornerRadius           = animatedCornerRadius,
                isScrolled             = isScrolled,
                backgroundColor        = cardBackgroundColor,
                lightstickColor        = Color.White.copy(alpha = 0.1f),
                statusText             = "연결된 기기 없음",
                statusTextColor        = MaterialTheme.customColors.surfaceVariant,
                descriptionText        = "연결 가능한 기기가 없습니다",
                descriptionTextColor   = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon        = Icons.Default.Refresh,
                onDescriptionIconClick = onRetryClick,
                effectColorData        = null
            )
        }

        // ── Disconnected ──────────────────────────────────────
        is EffectViewModel.DeviceConnectionState.Disconnected -> {
            ConnectionStateLayout(
                boxSize                = animatedBoxSize,
                effectSize             = animatedEffectSize,
                cornerRadius           = animatedCornerRadius,
                isScrolled             = isScrolled,
                lightstickColor        = Color.White.copy(alpha = 0.1f),
                statusText             = "연결된 기기 없음",
                statusTextColor        = MaterialTheme.customColors.surfaceVariant,
                descriptionText        = "연결 가능한 기기가 없습니다",
                descriptionTextColor   = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                descriptionIcon        = Icons.Default.Refresh,
                onDescriptionIconClick = onRetryClick,
                effectColorData        = null
            )
        }

        // ── Connected ─────────────────────────────────────────
        is EffectViewModel.DeviceConnectionState.Connected -> {
            ConnectionStateLayout(
                boxSize               = animatedBoxSize,
                effectSize            = animatedEffectSize,
                cornerRadius          = animatedCornerRadius,
                isScrolled            = isScrolled,
                backgroundColor       = cardBackgroundColor,
                lightstickColor       = effectColorData?.iconColor ?: Color.White,
                isLightStickAnimating = isPlaying,
                statusText            = "연결 됨",
                statusTextColor       = MaterialTheme.customColors.secondary,
                descriptionText       = connectionState.device.name ?: "UNKNOWN",
                descriptionTextColor  = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                effectColorData       = effectColorData,
                // [추가] TIMELINE_EFFECT 수신 시 effectColorData.iconColor(애니메이션 색상) 기반 뱃지
                statusBadge = if (latestTransmission?.source == TransmissionSource.TIMELINE_EFFECT) {
                    {
                        TimelineEffectBadge(
                            effectType = latestTransmission.effectType,
                            fgColor    = effectColorData?.iconColor ?: Color.White
                        )
                    }
                } else null
            )
        }
    }
}

data class EffectColorData(
    val iconColor: Color,
    val iconBrush: Brush?,
    val gradientColor: Color?
)

// ──────────────────────────────────────────────────────────────────────────────
// ConnectionStateLayout  (원본 구조 유지 + timelineEffectBadge 파라미터만 추가)
// ──────────────────────────────────────────────────────────────────────────────

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
    // [추가] statusText 옆 뱃지 — effectColorData 기반 애니메이션 색상을 람다 안에서 직접 참조
    statusBadge: @Composable (() -> Unit)? = null
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
                    isAnimating = isLightStickAnimating
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
                    // [추가] 타임라인 뱃지
                    statusBadge?.invoke()
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
                    isAnimating = isLightStickAnimating
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
                // [추가] 타임라인 뱃지
                statusBadge?.invoke()
            }

            description?.invoke()

            if (button != null) {
                Spacer(modifier = Modifier.height(4.dp))
                button()
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DescriptionIcon (원본 그대로)
// ──────────────────────────────────────────────────────────────────────────────

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

// ──────────────────────────────────────────────────────────────────────────────
// 색상 계산 함수들 (원본 그대로)
// ──────────────────────────────────────────────────────────────────────────────


@Composable
private fun calculateEffectColorFromTransmission(
    transmission: BleTransmissionEvent,
    backgroundColor: Color,
    lastColorRef: MutableState<Color>
): EffectColorData? {
    return when (transmission.source) {
        TransmissionSource.PAYLOAD_EFFECT, TransmissionSource.TIMELINE_EFFECT -> {
            when (transmission.effectType) {
                EffectType.ON -> {
                    val transit = transmission.transit ?: 10
                    animateOnEffect(
                        fromColor = lastColorRef.value,
                        targetColor = transmission.color?.toComposeColor() ?: Color.White,
                        transit = transit, randomColor = false,
                        onColorUpdate = { lastColorRef.value = it },
                        backgroundColor = backgroundColor
                    )
                }
                EffectType.OFF -> {
                    val transit = transmission.transit ?: 10
                    animateOffEffect(
                        fromColor = lastColorRef.value, transit = transit,
                        onColorUpdate = { lastColorRef.value = it },
                        backgroundColor = backgroundColor
                    )
                }
                EffectType.STROBE -> {
                    val period = transmission.period ?: 5
                    animatePeriodicEffect(
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period, randomColor = false,
                        fgThreshold = 0.1f, backgroundColor = backgroundColor
                    )
                }
                EffectType.BLINK -> {
                    val period = transmission.period ?: 20
                    animatePeriodicEffect(
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period, randomColor = false,
                        fgThreshold = 0.5f, backgroundColor = backgroundColor
                    )
                }
                EffectType.BREATH -> {
                    val period = transmission.period ?: 40
                    animateBreathEffect(
                        fromColor = lastColorRef.value,
                        fgColor = transmission.color?.toComposeColor() ?: Color.White,
                        bgColor = transmission.backgroundColor?.toComposeColor() ?: Color.Black,
                        period = period, randomColor = false,
                        backgroundColor = backgroundColor,
                        onColorUpdate = { lastColorRef.value = it }
                    )
                }
                else -> EffectColorData(iconColor = Color.White, iconBrush = null, gradientColor = null)
            }
        }
        TransmissionSource.FFT_EFFECT -> {
            val fftColor = transmission.color?.toComposeColor() ?: Color.White
            EffectColorData(
                iconColor = fftColor,
                iconBrush = Brush.linearGradient(listOf(
                    fftColor, fftColor.copy(alpha = 0.7f), fftColor.copy(alpha = 0.4f)
                )),
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
    lastColorRef: MutableState<Color>
): EffectColorData? {
    if (!isPlaying || selectedEffect == null || effectSettings == null) {
        lastColorRef.value = Color.White
        return EffectColorData(iconColor = Color.White, iconBrush = null, gradientColor = null)
    }

    return key(selectedEffect) {
        when (selectedEffect) {
            is EffectViewModel.UiEffectType.On -> animateOnEffect(
                lastColorRef.value, effectSettings.color.toComposeColor(),
                effectSettings.transit, effectSettings.randomColor,
                { lastColorRef.value = it }, backgroundColor
            )
            is EffectViewModel.UiEffectType.Off -> animateOffEffect(
                lastColorRef.value, effectSettings.transit,
                { lastColorRef.value = it }, backgroundColor
            )
            is EffectViewModel.UiEffectType.Strobe -> animatePeriodicEffect(
                effectSettings.color.toComposeColor(),
                effectSettings.backgroundColor.toComposeColor(),
                effectSettings.period, effectSettings.randomColor, 0.1f, backgroundColor
            )
            is EffectViewModel.UiEffectType.Blink -> animatePeriodicEffect(
                effectSettings.color.toComposeColor(),
                effectSettings.backgroundColor.toComposeColor(),
                effectSettings.period, effectSettings.randomColor, 0.5f, backgroundColor
            )
            is EffectViewModel.UiEffectType.Breath -> animateBreathEffect(
                lastColorRef.value,
                effectSettings.color.toComposeColor(),
                effectSettings.backgroundColor.toComposeColor(),
                effectSettings.period, effectSettings.randomColor, backgroundColor,
                { lastColorRef.value = it }
            )
            is EffectViewModel.UiEffectType.Custom -> when (selectedEffect.baseType) {
                EffectViewModel.UiEffectType.BaseEffectType.ON -> animateOnEffect(
                    lastColorRef.value, effectSettings.color.toComposeColor(),
                    effectSettings.transit, effectSettings.randomColor,
                    { lastColorRef.value = it }, backgroundColor
                )
                EffectViewModel.UiEffectType.BaseEffectType.OFF -> animateOffEffect(
                    lastColorRef.value, effectSettings.transit,
                    { lastColorRef.value = it }, backgroundColor
                )
                EffectViewModel.UiEffectType.BaseEffectType.STROBE -> animatePeriodicEffect(
                    effectSettings.color.toComposeColor(),
                    effectSettings.backgroundColor.toComposeColor(),
                    effectSettings.period, effectSettings.randomColor, 0.1f, backgroundColor
                )
                EffectViewModel.UiEffectType.BaseEffectType.BLINK -> animatePeriodicEffect(
                    effectSettings.color.toComposeColor(),
                    effectSettings.backgroundColor.toComposeColor(),
                    effectSettings.period, effectSettings.randomColor, 0.5f, backgroundColor
                )
                EffectViewModel.UiEffectType.BaseEffectType.BREATH -> animateBreathEffect(
                    lastColorRef.value,
                    effectSettings.color.toComposeColor(),
                    effectSettings.backgroundColor.toComposeColor(),
                    effectSettings.period, effectSettings.randomColor, backgroundColor,
                    { lastColorRef.value = it }
                )
            }
            else -> null
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 이펙트 애니메이션 헬퍼 (원본 그대로)
// ──────────────────────────────────────────────────────────────────────────────

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
    fromColor: Color, targetColor: Color, transit: Int, randomColor: Boolean,
    onColorUpdate: (Color) -> Unit, backgroundColor: Color
): EffectColorData {
    return key(targetColor) {
        val startColor = fromColor
        val animatable = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = transit * 100, easing = LinearEasing)
            )
        }
        val progress = animatable.value
        val iconColor = if (randomColor) rememberRandomHueColor(alpha = progress)
        else hsvLerp(startColor, targetColor, progress)
        onColorUpdate(iconColor)
        val gradientColor = if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)
        EffectColorData(iconColor = iconColor, iconBrush = null, gradientColor = gradientColor)
    }
}

@Composable
private fun animateOffEffect(
    fromColor: Color, transit: Int,
    onColorUpdate: (Color) -> Unit, backgroundColor: Color
): EffectColorData {
    val startColor = fromColor.copy(alpha = 1f)
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = transit * 100, easing = LinearEasing)
        )
    }
    val progress = animatable.value
    val iconColor = hsvLerp(startColor, Color.Black, progress)
    onColorUpdate(iconColor)
    val gradientColor = if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)
    return EffectColorData(iconColor = iconColor, iconBrush = null, gradientColor = gradientColor)
}

@Composable
private fun animatePeriodicEffect(
    fgColor: Color, bgColor: Color, period: Int, randomColor: Boolean,
    fgThreshold: Float, backgroundColor: Color
): EffectColorData {
    val resolvedFg = if (randomColor) rememberRandomHueColor() else fgColor
    // bgColor가 Black이면 그대로 사용 (카드 배경색으로 대체하지 않음)
    val resolvedBg = bgColor
    val progress by rememberInfiniteTransition(label = "periodic").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((period * 100).coerceAtLeast(100), easing = LinearEasing)),
        label = "periodicProgress"
    )
    val iconColor = if (progress < fgThreshold) resolvedFg else resolvedBg
    val gradientColor = if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)
    return EffectColorData(iconColor = iconColor, iconBrush = null, gradientColor = gradientColor)
}

@Composable
private fun animateBreathEffect(
    fromColor: Color, fgColor: Color, bgColor: Color, period: Int, randomColor: Boolean,
    backgroundColor: Color, onColorUpdate: (Color) -> Unit
): EffectColorData {
    if (period <= 0) {
        onColorUpdate(fgColor)
        val gradientColor = if (fgColor.isBlack()) null else lerp(fgColor, backgroundColor, 0.5f)
        return EffectColorData(iconColor = fgColor, iconBrush = null, gradientColor = gradientColor)
    }
    return key(fgColor, bgColor) {
        val startColor = fromColor
        val isFirstCycle = remember { mutableStateOf(true) }
        val breathProgress by rememberInfiniteTransition(label = "breathEffect").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(period * 100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "breathProgress"
        )
        LaunchedEffect(breathProgress) {
            if (breathProgress >= 0.99f && isFirstCycle.value) isFirstCycle.value = false
        }
        val resolvedFg = if (randomColor) rememberRandomHueColor(alpha = 1f) else fgColor
        // bgColor가 Black이면 그대로 사용 (카드 배경색으로 대체하지 않음)
        val resolvedBg = bgColor
        // 4단계: 0-25% 상승 / 25-50% FG 유지 / 50-75% 하강 / 75-100% BG 유지
        val iconColor: Color = when {
            breathProgress < 0.25f -> {
                val p = breathProgress / 0.25f
                hsvLerp(if (isFirstCycle.value) startColor else resolvedBg, resolvedFg, p)
            }
            breathProgress < 0.50f -> resolvedFg
            breathProgress < 0.75f -> {
                val p = (breathProgress - 0.5f) / 0.25f
                hsvLerp(resolvedFg, resolvedBg, p)
            }
            else -> resolvedBg
        }
        onColorUpdate(iconColor)
        val gradientColor = if (iconColor.isBlack()) null else lerp(iconColor, backgroundColor, 0.2f)
        EffectColorData(iconColor = iconColor, iconBrush = null, gradientColor = gradientColor)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 색상 보간 헬퍼 (원본)
// ──────────────────────────────────────────────────────────────────────────────

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

// ──────────────────────────────────────────────────────────────────────────────
// LightStick / RoundedIconBox (원본 그대로)
// ──────────────────────────────────────────────────────────────────────────────
// LightStick / RoundedIconBox
// ──────────────────────────────────────────────────────────────────────────────

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
    isAnimating: Boolean = false
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

    Canvas(modifier = Modifier.size(size)) {
        val w  = this.size.width
        val cx = w / 2f
        val cy = w / 2f
        val ct = Offset(cx, cy)

        // ── 비율 정의 ─────────────────────────────────────
        // 제품 실사: LED 발광부 = 외곽 테두리 ~ 메탈 원판 직전까지 전체 링
        // 메탈 원판은 중앙 소형 원판만, LED가 나머지 전체를 차지
        val ledOuterR = w * 0.47f   // LED 링 외곽
        val metalR    = w * 0.22f   // 메탈 원판 (작고 중앙에만)
        val ledInnerR = metalR               // LED 내곽 = 메탈판과 딱 붙임 (틈 없음)
        val knobR     = w * 0.09f   // 중앙 노브 (메탈판 내 작은 버튼)
        val ledMidR   = (ledOuterR + ledInnerR) / 2f
        val ledW      = ledOuterR - ledInnerR   // 링 폭 ≈ 0.23w (넓게)

        // ── 1. 외부 글로우 (radialGradient — 중심→투명 자연스럽게 퍼짐, 링 경계 없음)
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

        // ── 2. LED 발광부 (넓은 링 전체)
        // [수정1] color 그대로 사용 (ledColor 변수 제거)
        // [수정2] 외곽 Stroke 링 전부 제거 — 단순 채우기만
        val maskColor = Color(0xFF909090)  // 메탈판 색상과 동일 — 틈 없이 이어짐
        if (brush != null) {
            drawCircle(brush = brush, radius = ledOuterR, center = ct)
            drawCircle(color = maskColor, radius = ledInnerR, center = ct)
        } else {
            // 발광 베이스 채우기 — alpha 항상 1.0f (Black 포함 모든 색상 완전 불투명)
            // color.copy(alpha<1) 사용 시 배경색이 비쳐 보이므로 강제 불투명 처리
            drawCircle(color = color.copy(alpha = 1f), radius = ledOuterR, center = ct)
            // 메탈판 마스크
            drawCircle(color = maskColor, radius = ledInnerR, center = ct)
        }

        // ── 3. 메탈 원판 — [수정3] 단순화 (실버 원 + 하이라이트만)
        drawCircle(color = Color(0xFFB0B0B0), radius = metalR, center = ct)
        // 중앙 하이라이트 (입체감)
        drawCircle(
            brush  = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.60f), Color.Transparent),
                center = Offset(cx - metalR * 0.20f, cy - metalR * 0.25f),
                radius = metalR * 0.70f
            ),
            radius = metalR,
            center = ct
        )
        // 테두리 (메탈판 경계 구분)
        drawCircle(
            color  = Color.Black.copy(alpha = 0.15f),
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
        // gradient overlay — effectColorData.gradientColor 있을 때만
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