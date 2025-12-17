package com.dongsitech.lightstickmusicdemo.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles

/**
 * 🎨 Figma 디자인 100% 정확한 Music Control Bar
 *
 * Typography.kt 사용:
 * - titleLarge: 제목 (SemiBold 20sp, 140%)
 * - bodyLarge: 아티스트 (Regular 16sp, 140%)
 * - customTextStyles.badgeMedium: 시간 & EFX (SemiBold 12sp, 140%)
 *
 * Figma 색상:
 * - EFX 뱃지: #FFD46F 배경, #111111 텍스트
 * - 시간: #9CA3AF
 * - 진행바: 그라데이션 #9D79BC → #8A40C4
 * - 재생 버튼: #A774FF
 */
@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
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
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C1B1F)  // Material3 Surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 앨범 아트 (동적 크기)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val albumSize = (maxWidth * 0.6f).coerceAtMost(200.dp)

                Box(
                    modifier = Modifier
                        .size(albumSize)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                id = albumArtResId ?: R.drawable.ic_music_note
                            ),
                            contentDescription = "Default Music Icon",
                            modifier = Modifier.size(albumSize * 0.4f),
                            tint = Color(0xFF8E8E93)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 곡 제목 + EFX 뱃지
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,  // SemiBold 20sp, 140%
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (musicItem.hasEffect) {
                    Spacer(modifier = Modifier.width(8.dp))

                    // Figma 스펙: 40×18, #FFD46F 배경, #111111 텍스트
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFFD46F),  // Theme color/Secondary
                        modifier = Modifier
                            .height(18.dp)
                            .widthIn(min = 40.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "EFX",
                                style = MaterialTheme.customTextStyles.badgeMedium,  // SemiBold 12sp, 140%
                                color = Color(0xFF111111)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 아티스트명
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyLarge,  // Regular 16sp, 140%
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 시간 표시
            Row(
                modifier = Modifier
                    .width(279.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasMusic) formatTime(currentPosition) else "0:00",
                    style = MaterialTheme.customTextStyles.badgeMedium,  // SemiBold 12sp, 140%
                    color = Color(0xFF9CA3AF)
                )

                Text(
                    text = if (hasMusic) formatTime(duration) else "0:00",
                    style = MaterialTheme.customTextStyles.badgeMedium,  // SemiBold 12sp, 140%
                    color = Color(0xFF9CA3AF)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 진행바 (Figma 스펙: 279px 너비, 4px 높이, 그라데이션)
            val progress = if (duration > 0) {
                (currentPosition / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .width(279.dp)
                    .height(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .pointerInput(duration) {
                            detectTapGestures { offset ->
                                if (duration > 0) {
                                    val percent = (offset.x / size.width).coerceIn(0f, 1f)
                                    onSeekTo((duration * percent).toLong())
                                }
                            }
                        }
                ) {
                    // 배경
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(Color(0xFF424242))
                    )

                    // 진행 (그라데이션)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF9D79BC),
                                        Color(0xFF8A40C4)
                                    )
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 플레이어 컨트롤 (Figma 스펙: 48×48, 64×64)
            Row(
                modifier = Modifier.width(279.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 이전 버튼 (48×48)
                PressableIconButton(
                    onClick = onPrevClick,
                    enabled = hasMusic,
                    normalIcon = R.drawable.ic_player_back_n,
                    pressedIcon = R.drawable.ic_player_back_p,
                    contentDescription = "Previous",
                    size = 48.dp,
                    iconSize = 48.dp
                )

                // 재생/일시정지 버튼 (64×64)
                PressableFilledIconButton(
                    onClick = onPlayPauseClick,
                    enabled = hasMusic,
                    normalIcon = if (isPlaying)
                        R.drawable.ic_player_pause_n
                    else
                        R.drawable.ic_player_play_n,
                    pressedIcon = if (isPlaying)
                        R.drawable.ic_player_pause_p
                    else
                        R.drawable.ic_player_play_p,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    size = 64.dp,
                    iconSize = 64.dp,
                    containerColor = Color(0xFFA774FF),
                    disabledContainerColor = Color(0xFF2C2C2E)
                )

                // 다음 버튼 (48×48)
                PressableIconButton(
                    onClick = onNextClick,
                    enabled = hasMusic,
                    normalIcon = R.drawable.ic_player_forward_n,
                    pressedIcon = R.drawable.ic_player_forward_p,
                    contentDescription = "Next",
                    size = 48.dp,
                    iconSize = 48.dp
                )
            }
        }
    }
}

/**
 * Press 상태를 감지하는 IconButton
 */
@Composable
private fun PressableIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    normalIcon: Int,
    pressedIcon: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        interactionSource = interactionSource
    ) {
        Icon(
            painter = painterResource(
                id = if (isPressed && enabled) pressedIcon else normalIcon
            ),
            contentDescription = contentDescription,
            tint = Color.Unspecified,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Press 상태를 감지하는 FilledIconButton
 */
@Composable
private fun PressableFilledIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    normalIcon: Int,
    pressedIcon: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    containerColor: Color,
    disabledContainerColor: Color,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        ),
        interactionSource = interactionSource
    ) {
        Icon(
            painter = painterResource(
                id = if (isPressed && enabled) pressedIcon else normalIcon
            ),
            contentDescription = contentDescription,
            tint = Color.Unspecified,
            modifier = Modifier.size(iconSize)
        )
    }
}

fun formatTime(millis: Int): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
