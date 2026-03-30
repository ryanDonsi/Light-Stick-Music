package com.lightstick.music.ui.components.music

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
import com.lightstick.music.R
import com.lightstick.music.core.util.TimeFormatter
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 🎵 Music Player Card (글라스모피즘 + Figma 디자인)
 *
 * ✅ 수정 사항: **색상만 theme로 교체, UI는 그대로 유지**
 *
 * [추가] latestTransmission 파라미터
 *   → TIMELINE_EFFECT 수신 시 앨범 이미지 우측 하단에 오버레이 뱃지 표시
 *      (LightStick 색상 원형 + 이펙트명)
 */
@Composable
fun MusicPlayerCard(
    musicItem: MusicItem?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    latestTransmission: BleTransmissionEvent? = null
) {
    if (musicItem == null) {
        EmptyMusicCard(modifier = modifier)
        return
    }

    val hasMusic = musicItem.filePath.isNotEmpty()

    val imageBitmap = musicItem.albumArtPath?.let { path ->
        try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier              = modifier.fillMaxWidth(),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation    = 20.dp,
                    shape        = RoundedCornerShape(20.dp),
                    ambientColor = MaterialTheme.customColors.shadowCard,
                    spotColor    = MaterialTheme.customColors.shadowCard
                )
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.customColors.onSurface.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.customColors.onSurface.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 32.dp, vertical = 32.dp)
        ) {
            Column(
                modifier            = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 앨범 아트 + 이펙트 뱃지 오버레이 ──────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // 앨범 아트
                    if (imageBitmap != null) {
                        Image(
                            bitmap             = imageBitmap,
                            contentDescription = "Album Art",
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(20.dp))
                        )
                    } else {
                        Icon(
                            painter            = painterResource(id = R.drawable.ic_music_note),
                            contentDescription = "Default Music Icon",
                            modifier           = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(20.dp)),
                            tint = MaterialTheme.customColors.surfaceVariant
                        )
                    }

                    // [추가] 이펙트 뱃지 — TIMELINE_EFFECT 수신 시 우측 하단 오버레이
                    val isTimeline = latestTransmission?.source == TransmissionSource.TIMELINE_EFFECT
                    if (isTimeline && latestTransmission != null) {
                        EffectOverlayBadge(
                            transmission = latestTransmission,
                            modifier     = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 곡 제목 + EFX 뱃지 (원본 그대로)
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text      = musicItem.title,
                        style     = MaterialTheme.typography.titleLarge,
                        color     = MaterialTheme.colorScheme.onSurface,
                        maxLines  = 1,
                        overflow  = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.weight(1f, fill = false)
                    )

                    if (musicItem.hasEffect) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape    = RoundedCornerShape(4.dp),
                            color    = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .height(18.dp)
                                .widthIn(min = 40.dp)
                        ) {
                            Box(
                                modifier         = Modifier,
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text  = "EFX",
                                    style = MaterialTheme.customTextStyles.badgeMedium,
                                    color = MaterialTheme.colorScheme.surface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 아티스트명
                Text(
                    text      = musicItem.artist,
                    style     = MaterialTheme.typography.bodyLarge,
                    color     = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis
                )

                // 시간 표시
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text  = if (hasMusic) TimeFormatter.formatTime(currentPosition) else "0:00",
                        style = MaterialTheme.customTextStyles.badgeMedium,
                        color = MaterialTheme.customColors.textTertiary
                    )
                    Text(
                        text  = if (hasMusic) TimeFormatter.formatTime(duration) else "0:00",
                        style = MaterialTheme.customTextStyles.badgeMedium,
                        color = MaterialTheme.customColors.textTertiary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 진행바
                val progress = if (duration > 0)
                    (currentPosition / duration.toFloat()).coerceIn(0f, 1f)
                else 0f

                Box(
                    modifier         = Modifier
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(9999.dp))
                                .background(MaterialTheme.customColors.outline)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .clip(RoundedCornerShape(9999.dp))
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            MaterialTheme.customColors.gradientStart,
                                            MaterialTheme.customColors.gradientEnd
                                        )
                                    )
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 플레이어 컨트롤
                Row(
                    modifier = Modifier
                        .height(64.dp)
                        .wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    PressableIconButton(
                        onClick            = onPrevClick,
                        enabled            = hasMusic,
                        normalIcon         = R.drawable.ic_player_back_n,
                        pressedIcon        = R.drawable.ic_player_back_p,
                        contentDescription = "Previous",
                        size               = 48.dp
                    )
                    PressableIconButton(
                        onClick            = onPlayPauseClick,
                        enabled            = hasMusic,
                        normalIcon         = if (isPlaying) R.drawable.ic_player_pause_n
                        else R.drawable.ic_player_play_n,
                        pressedIcon        = if (isPlaying) R.drawable.ic_player_pause_p
                        else R.drawable.ic_player_play_p,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        size               = 64.dp
                    )
                    PressableIconButton(
                        onClick            = onNextClick,
                        enabled            = hasMusic,
                        normalIcon         = R.drawable.ic_player_forward_n,
                        pressedIcon        = R.drawable.ic_player_forward_p,
                        contentDescription = "Next",
                        size               = 48.dp
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// EmptyMusicCard / PressableIconButton (원본 그대로)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyMusicCard(modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation    = 20.dp,
                    shape        = RoundedCornerShape(20.dp),
                    ambientColor = MaterialTheme.customColors.shadowCard,
                    spotColor    = MaterialTheme.customColors.shadowCard
                )
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.customColors.onSurface.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.customColors.onSurface.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text      = "재생중인 음악이 없습니다",
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.surfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

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
                enabled           = enabled,
                interactionSource = interactionSource,
                indication        = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter            = painterResource(
                id = if (isPressed && enabled) pressedIcon else normalIcon
            ),
            contentDescription = contentDescription,
            modifier           = Modifier.fillMaxSize(),
            contentScale       = ContentScale.Fit
        )
    }
}