package com.dongsitech.lightstickmusicdemo.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun MusicControlBar(
    musicItem: MusicItem,
    albumArtResId: Int?,
    title: String,
    artist: String,
    currentPosition: Int,
    duration: Int,
    isPlaying: Boolean,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val hasMusic = musicItem.filePath.isNotEmpty()

    val imageBitmap = musicItem.albumArtPath?.let { path ->
        try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.wrapContentSize()
                ) {
                    // 앨범 아트
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (imageBitmap != null) {
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Album Art",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Image(
                                painter = painterResource(id = albumArtResId ?: R.drawable.ic_music_note),
                                contentDescription = "Default Music Icon",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // efx 뱃지
                    if (musicItem.hasEffect) {
                        Box(
                            modifier = Modifier
                                .offset(x = (+6).dp, y = (-6).dp)
                                .align(Alignment.TopEnd)
                                .background(Color.Blue, shape = CircleShape)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "efx",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 마키 텍스트는 기존 프로젝트의 Composable 사용
                    MarqueeText(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (hasMusic) formatTime(currentPosition) else "--:--",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = if (hasMusic) formatTime(duration) else "--:--",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            val progress = if (duration > 0) (currentPosition / duration.toFloat()).coerceIn(0f, 1f) else 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp) // 파도 표현을 위해 살짝 키움
                    .padding(horizontal = 16.dp)
                    .pointerInput(duration) {
                        detectTapGestures { offset ->
                            if (duration > 0) {
                                val percent = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeekTo((duration * percent).toLong())
                            }
                        }
                    }
            ) {
                CustomProgressBar(
                    progress = progress,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    waveColor = MaterialTheme.colorScheme.primary,
                    cornerRadiusDp = 6f,
                    amplitudeDp = 3f,       // 파도 높이
                    wavelengthDp = 24f,     // 파도 길이
                    speedPxPerSec = 120f     // 파도 이동 속도
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onPrevClick, enabled = hasMusic) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_skip_previous),
                        contentDescription = "Previous"
                    )
                }
                IconButton(onClick = onPlayPauseClick, enabled = hasMusic) {
                    Icon(
                        painter = if (isPlaying) painterResource(id = R.drawable.ic_pause)
                        else painterResource(id = R.drawable.ic_play),
                        contentDescription = "Play/Pause"
                    )
                }
                IconButton(onClick = onNextClick, enabled = hasMusic) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_skip_next),
                        contentDescription = "Next"
                    )
                }
            }
        }
    }
}

/**
 * 파도 애니메이션이 흐르는 진행 바.
 * - 트랙(배경) → 진행된 구간 채움 → 진행 구간 안에서만 사인파를 클리핑해 그립니다.
 */
@Composable
private fun CustomProgressBar(
    progress: Float,
    backgroundColor: Color,
    fillColor: Color,
    waveColor: Color,
    cornerRadiusDp: Float = 6f,
    amplitudeDp: Float = 3f,
    wavelengthDp: Float = 24f,
    speedPxPerSec: Float = 120f // px/s
) {
    val density = LocalDensity.current
    val infinite = rememberInfiniteTransition(label = "wave")
    // 파도 위상(phase)을 계속 증가시켜 좌→우로 흐르게
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f, // 1.0을 한 주기로 보고 아래에서 2π 곱해 사용
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    // dp → px 변환 값들 캐싱
    val cornerPx = rememberDpToPx(density, cornerRadiusDp)
    val ampPx = rememberDpToPx(density, amplitudeDp)
    val waveLenPx = rememberDpToPx(density, wavelengthDp)

    // draw scope
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 트랙 모양
        val track = RoundRect(
            rect = Rect(0f, 0f, w, h),
            cornerRadius = CornerRadius(cornerPx, cornerPx)
        )

        // 배경
        drawIntoCanvas {
            drawRoundRect(
                color = backgroundColor,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        }

        // 진행된 구간 폭
        val pw = (w * progress.coerceIn(0f, 1f))

        if (pw > 1f) {
            // 진행 채움(단색)
            clipPath(path = Path().apply { addRoundRect(track) }) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset.Zero,
                    size = androidx.compose.ui.geometry.Size(pw, h),
                    cornerRadius = CornerRadius(cornerPx, cornerPx)
                )
            }

            // 파도 경로: 진행된 구간만 클리핑
            val wavePath = Path().apply {
                // 파도 y = mid + A * sin(2π/λ * x + θ)
                val mid = h * 0.5f
                val twoPi = (2f * PI).toFloat()
                val theta = twoPi * phase // 0..2π
                val k = twoPi / waveLenPx

                // 시작
                moveTo(0f, mid)
                var x = 0f
                val step = 2f // px 스텝 (너무 작으면 과도한 세그먼트)
                while (x <= pw) {
                    val y = (mid + ampPx * sin(k * x + theta)).toFloat()
                    lineTo(x, y)
                    x += step
                }
                // 아래쪽 닫기
                lineTo(pw, h)
                lineTo(0f, h)
                close()
            }

            // 진행된 구간 모양으로 먼저 클립, 그 안에 파도만 그림
            val progressClip = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, 0f, pw, h),
                        cornerRadius = CornerRadius(cornerPx, cornerPx)
                    )
                )
            }
            clipPath(progressClip) {
                drawPath(
                    path = wavePath,
                    color = waveColor
                )
            }
        }
    }
}

@Composable
private fun rememberDpToPx(density: Density, dp: Float): Float {
    return remember(dp, density) { with(density) { dp.dp.toPx() } }
}

fun formatTime(millis: Int): String {
    if (millis <= 0) return "--:--"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
