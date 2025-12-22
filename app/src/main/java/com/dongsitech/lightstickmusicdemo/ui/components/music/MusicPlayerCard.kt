package com.dongsitech.lightstickmusicdemo.ui.components.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles

/**
 * 🎵 Music Player Card (글라스모피즘 + Figma 디자인)
 *
 * Figma 색상:
 * - EFX 뱃지: #FFD46F 배경, #111111 텍스트
 * - 시간: #9CA3AF
 * - 진행바: 그라데이션 #9D79BC → #8A40C4
 * - 재생 버튼: #A774FF
 */
@Composable
fun MusicPlayerCard(
    musicItem: MusicItem?,  // ✅ nullable로 변경
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ musicItem이 null이면 Empty 상태 표시
    if (musicItem == null) {
        EmptyMusicCard(modifier = modifier)
        return
    }

    val hasMusic = musicItem.filePath.isNotEmpty()

    // ✅ 앨범아트 로드
    val imageBitmap = musicItem.albumArtPath?.let { path ->
        try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    // ✅ 화면을 가득 채우는 Column
    Column(
        modifier = modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top  // ✅ 상단 정렬
    ) {

        // ✅ 글라스모피즘 카드 (투명도 증가)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color.Black.copy(alpha = 0.20f),
                    spotColor = Color.Black.copy(alpha = 0.20f)
                )
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.05f)) // 5%
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 32.dp, vertical = 32.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ═══════════════════════════════════════
                // ✅ 앨범 아트 (BoxWithConstraints 없이!)
                // ═══════════════════════════════════════
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(20.dp))
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                id = R.drawable.ic_music_note
                            ),
                            contentDescription = "Default Music Icon",
                            modifier = Modifier.size(120.dp),  // ✅ 고정 크기
                            tint = Color(0xFF8E8E93)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ✅ 곡 제목 + EFX 뱃지
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = musicItem.title,
                        style = MaterialTheme.typography.titleLarge,    // SemiBold 20sp, 140%
                        color = MaterialTheme.colorScheme.onSurface,    // Color.White,
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
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .height(18.dp)
                                .widthIn(min = 40.dp)
                        ) {
                            Box(
                                modifier = Modifier,
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "EFX",
                                    style = MaterialTheme.customTextStyles.badgeMedium,  // SemiBold 12sp, 140%
                                    color = MaterialTheme.colorScheme.surface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ✅ 아티스트명
                Text(
                    text = musicItem.artist,
                    style = MaterialTheme.typography.bodyLarge,  // Regular 16sp, 140%
                    color = MaterialTheme.colorScheme.onSurface, //Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

//                Spacer(modifier = Modifier.height(24.dp))

                // ✅ 시간 표시
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (hasMusic) formatTime(currentPosition.toInt()) else "0:00",
                        style = MaterialTheme.customTextStyles.badgeMedium,  // SemiBold 12sp, 140%
                        color = Color(0xFF9CA3AF)
                    )

                    Text(
                        text = if (hasMusic) formatTime(duration.toInt()) else "0:00",
                        style = MaterialTheme.customTextStyles.badgeMedium,  // SemiBold 12sp, 140%
                        color = Color(0xFF9CA3AF)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ✅ 진행바 (Figma 스펙: 279px 너비, 4px 높이, 그라데이션)
                val progress = if (duration > 0) {
                    (currentPosition / duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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

                // ✅ 플레이어 컨트롤 (Figma 스펙: 48×48, 64×64)
                Row(
                    modifier = Modifier
                        .height(64.dp)
                        .wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 이전 (48)
                    PressableIconButton(
                        onClick = onPrevClick,
                        enabled = hasMusic,
                        normalIcon = R.drawable.ic_player_back_n,
                        pressedIcon = R.drawable.ic_player_back_p,
                        contentDescription = "Previous",
                        size = 48.dp
                    )

                    // 재생 (64)
                    PressableIconButton(
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
                        size = 64.dp
                    )

                    // 다음 (48)
                    PressableIconButton(
                        onClick = onNextClick,
                        enabled = hasMusic,
                        normalIcon = R.drawable.ic_player_forward_n,
                        pressedIcon = R.drawable.ic_player_forward_p,
                        contentDescription = "Next",
                        size = 48.dp
                    )
                }
            }
        }
    }
}

/**
 * ✅ 빈 음악 카드 (재생 중인 음악이 없을 때)
 */
@Composable
private fun EmptyMusicCard(modifier: Modifier = Modifier) {
    // ✅ 상단 정렬로 변경
    Column(
        modifier = modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top  // ✅ 상단 정렬
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color.Black.copy(alpha = 0.20f),
                    spotColor = Color.Black.copy(alpha = 0.20f)
                )
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "재생중인 음악이 없습니다",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
                textAlign = TextAlign.Center
            )
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
    size: Dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(999.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(
                id = if (isPressed && enabled) pressedIcon else normalIcon
            ),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * 시간 포맷 (밀리초 → mm:ss)
 */
private fun formatTime(millis: Int): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}