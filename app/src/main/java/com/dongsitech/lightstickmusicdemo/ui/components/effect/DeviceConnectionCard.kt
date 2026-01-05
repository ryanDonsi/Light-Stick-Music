package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.ui.theme.customColors
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import com.lightstick.device.Device

/**
 * ✅ 디바이스 연결 상태 카드 - Figma 완벽 구현
 *
 * - 부드러운 사이즈 애니메이션
 * - 스크롤 여부에 따라 레이아웃 변경
 */
@Composable
fun DeviceConnectionCard(
    connectionState: EffectViewModel.DeviceConnectionState,
    onConnectClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    currentEffectColor: Color = Color.Red,
    isScrolled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // ✅ 부드러운 사이즈 애니메이션
    val animatedSize by animateDpAsState(
        targetValue = if (isScrolled) 124.dp else 180.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "size"
    )

    val animatedIconSize by animateDpAsState(
        targetValue = if (isScrolled) 50.dp else 70.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "iconSize"
    )

    val animatedCornerRadius by animateDpAsState(
        targetValue = if (isScrolled) 20.dp else 32.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cornerRadius"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (connectionState) {
            is EffectViewModel.DeviceConnectionState.NoBondedDevice -> {
                NoBondedDeviceState(onConnectClick = onConnectClick)
            }

            is EffectViewModel.DeviceConnectionState.Scanning -> {
                ScanningState()
            }

            is EffectViewModel.DeviceConnectionState.ScanFailed -> {
                ScanFailedState(onRetryClick = onRetryClick)
            }

            is EffectViewModel.DeviceConnectionState.Connected -> {
                ConnectedState(
                    device = connectionState.device,
                    effectColor = currentEffectColor,
                    isScrolled = isScrolled,
                    boxSize = animatedSize,
                    iconSize = animatedIconSize,
                    cornerRadius = animatedCornerRadius
                )
            }
        }
    }
}

/**
 * ✅ 1. 등록된 기기 없음
 */
@Composable
private fun NoBondedDeviceState(
    onConnectClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RoundedIconBox(
            size = 180.dp,
            backgroundColor = Color.White.copy(alpha = 0.06f),
            cornerRadius = 32.dp
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(70.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "연결된 기기 없음",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.customColors.surfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onConnectClick,
            modifier = Modifier
                .width(156.dp)
                .height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF843DFF)
            )
        ) {
            Text(
                text = "기기 연결하기",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFCF9FF)
            )
        }
    }
}

/**
 * ✅ 2. 스캔 중 (로딩 애니메이션)
 */
@Composable
private fun ScanningState() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RoundedIconBox(
            size = 180.dp,
            backgroundColor = Color.White.copy(alpha = 0.06f),
            cornerRadius = 32.dp
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(70.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "연결된 기기 없음",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "등록된 기기 확인 중",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "확인 중",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
                tint = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * ✅ 3. 스캔 실패
 */
@Composable
private fun ScanFailedState(
    onRetryClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RoundedIconBox(
            size = 180.dp,
            backgroundColor = Color.White.copy(alpha = 0.06f),
            cornerRadius = 32.dp
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(70.dp),
                tint = Color.White.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "연결된 기기 없음",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.customColors.surfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "연결 가능한 기기가 없습니다",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.customColors.onSurface
            )

            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "재시도",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        onClick = onRetryClick
                    ),
                tint = MaterialTheme.customColors.onSurface
            )
        }
    }
}

/**
 * ✅ 4-5. 연결 성공 (애니메이션)
 */
@Composable
private fun ConnectedState(
    device: Device,
    effectColor: Color,
    isScrolled: Boolean,
    boxSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp
) {
    if (isScrolled) {
        // 스크롤 후: 가로 레이아웃
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            RoundedIconBox(
                size = boxSize,
                backgroundColor = Color(0xFFA774FF).copy(alpha = 0.22f),
                cornerRadius = cornerRadius,
                showGradient = true,
                gradientColor = effectColor
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = effectColor
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = "연결 됨",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = device.name ?: "기기 모델명",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        // 스크롤 전: 세로 레이아웃
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RoundedIconBox(
                size = boxSize,
                backgroundColor = Color(0xFFA774FF).copy(alpha = 0.22f),
                cornerRadius = cornerRadius,
                showGradient = true,
                gradientColor = effectColor
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = effectColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "연결 됨",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = device.mac,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * ✅ 라운드 아이콘 박스
 */
@Composable
private fun RoundedIconBox(
    size: androidx.compose.ui.unit.Dp,
    backgroundColor: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    showGradient: Boolean = false,
    gradientColor: Color = Color.Transparent,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .size(size)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(cornerRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showGradient) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                gradientColor.copy(alpha = pulseAlpha),
                                Color.Transparent
                            ),
                            radius = 300f
                        ),
                        shape = RoundedCornerShape(cornerRadius)
                    )
            )
        }

        content()
    }
}