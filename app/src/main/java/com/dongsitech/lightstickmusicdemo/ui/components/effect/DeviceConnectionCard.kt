package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import com.lightstick.device.Device

/**
 * 🎨 디바이스 연결 상태 카드 (State별 분리 버전)
 *
 * 장점:
 * - 각 상태가 명확하게 분리됨
 * - 코드 가독성 좋음
 * - 유지보수 용이
 * - 각 상태별 커스터마이징 쉬움
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (connectionState) {
            is EffectViewModel.DeviceConnectionState.NoBondedDevice -> {
                // ✅ 1. 등록된 기기 없음 (최초)
                NoBondedDeviceState(onConnectClick = onConnectClick)
            }

            is EffectViewModel.DeviceConnectionState.Scanning -> {
                // ✅ 2. 등록된 기기 확인 중 (로딩 애니메이션)
                ScanningState()
            }

            is EffectViewModel.DeviceConnectionState.ScanFailed -> {
                // ✅ 3. 연결 가능한 기기 없음 (Retry 버튼)
                ScanFailedState(onRetryClick = onRetryClick)
            }

            is EffectViewModel.DeviceConnectionState.Connected -> {
                // ✅ 4-5. 연결 성공 (스크롤 여부에 따라)
                ConnectedState(
                    device = connectionState.device,
                    effectColor = currentEffectColor,
                    isScrolled = isScrolled
                )
            }
        }
    }
}

/**
 * ✅ 1. 등록된 기기 없음 (최초)
 */
@Composable
private fun NoBondedDeviceState(
    onConnectClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 라운드 박스 (아이콘만)
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
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            ),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = Color(0xFFFCF9FF)
            )
        }
    }
}

/**
 * ✅ 2. 등록된 기기 확인 중 (로딩 애니메이션)
 */
@Composable
private fun ScanningState() {
    // ✅ 회전 애니메이션
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
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            ),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ✅ 로딩 아이콘 + 텍스트
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "등록된 기기 확인 중",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = Color.White.copy(alpha = 0.7f)
            )

            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "확인 중",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),  // ✅ 회전 애니메이션
                tint = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * ✅ 3. 연결 가능한 기기 없음 (Retry 버튼)
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

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "연결된 기기 없음",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            ),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ✅ Retry 아이콘 + 텍스트 (클릭 가능)
        TextButton(
            onClick = onRetryClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "연결 가능한 기기가 없습니다",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = Color(0xFFFF5252)
                )

                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "재시도",
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFFF5252)
                )
            }
        }
    }
}

/**
 * ✅ 4-5. 연결 성공 (스크롤 여부에 따라)
 */
@Composable
private fun ConnectedState(
    device: Device,
    effectColor: Color,
    isScrolled: Boolean
) {
    if (isScrolled) {
        // ✅ 5. 스크롤 후 (MIN: 124×124, 우측 텍스트)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            RoundedIconBox(
                size = 124.dp,
                backgroundColor = Color(0xFFA774FF).copy(alpha = 0.22f),
                cornerRadius = 20.dp,
                showGradient = true,
                gradientColor = effectColor
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = effectColor
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = "연결 됨",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = device.name ?: "기기 먼벨",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        // ✅ 4. 스크롤 전 (MAX: 180×180, 하단 텍스트)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RoundedIconBox(
                size = 180.dp,
                backgroundColor = Color(0xFFA774FF).copy(alpha = 0.22f),
                cornerRadius = 32.dp,
                showGradient = true,
                gradientColor = effectColor
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(70.dp),
                    tint = effectColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "연결 됨",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = device.mac,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * ✅ 라운드 아이콘 박스 (공통)
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