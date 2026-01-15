package com.lightstick.music.ui.components.device

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.theme.customColors

/**
 * 애니메이션 스캔 버튼 (Figma 디자인: ic_research 아이콘 사용)
 *
 * @param isScanning 스캔 중 여부
 * @param onStartScan 스캔 시작 콜백
 */
@Composable
fun AnimatedScanButton(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotationAnimation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAnimation"
    )

    IconButton(
        onClick = onStartScan,
        modifier = modifier,
        enabled = !isScanning,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.customColors.onSurface,
            disabledContentColor = MaterialTheme.customColors.onSurface
        )
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_research),
            contentDescription = if (isScanning) "스캔 중" else "스캔 시작",
            tint = if (isScanning) {
                MaterialTheme.customColors.primary
            } else {
                MaterialTheme.customColors.onSurface
            },
            modifier = Modifier
                .height(24.dp)
                .then(
                    if (isScanning) {
                        Modifier.graphicsLayer { rotationZ = rotation }
                    } else {
                        Modifier
                    }
                )
        )
    }
}