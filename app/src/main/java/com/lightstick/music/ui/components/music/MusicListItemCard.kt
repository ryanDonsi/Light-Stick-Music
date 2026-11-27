package com.lightstick.music.ui.components.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.core.util.TimeFormatter
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.music.BackgroundTimelineGenerator.FileState
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * Music List 아이템 카드 (글라스모피즘)
 *
 *  수정 사항: **색상만 theme로 교체, UI는 그대로 유지**
 *
 * [추가] latestTransmission 파라미터
 *   → TIMELINE_EFFECT 수신 시 앨범 이미지 우측 하단에 오버레이 뱃지 표시
 *      (LightStick 색상 원형만)
 */
@Composable
fun MusicListItemCard(
    musicItem: MusicItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    latestTransmission: () -> BleTransmissionEvent? = { null },
    /** 자동 연출 생성 상태. 이 카드의 상태가 바뀔 때만 다시 그리도록 lambda 로 받는다 */
    autoGenState: () -> FileState? = { null }
) {
    val genState by remember(musicItem.filePath) { derivedStateOf(autoGenState) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                color = if (isPlaying)
                    MaterialTheme.customColors.primary.copy(alpha = 0.22f)
                else
                    MaterialTheme.customColors.onSurface.copy(alpha = 0.05f)
            )
            .let { mod ->
                if (isPlaying) {
                    mod.border(
                        width = 2.dp,
                        color = MaterialTheme.customColors.primary,
                        shape = RoundedCornerShape(20.dp)
                    )
                } else {
                    mod
                }
            }
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model              = musicItem.albumArtPath,
                    contentDescription = "앨범아트",
                    modifier           = Modifier.fillMaxSize()
                ) {
                    if (painter.state is AsyncImagePainter.State.Success) {
                        SubcomposeAsyncImageContent(contentScale = ContentScale.Crop)
                    } else {
                        Icon(
                            imageVector        = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.customColors.onSurface.copy(
                                alpha = if (isPlaying) 1f else 0.6f
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                EffectOverlayBadgeSlot(
                    transmission   = latestTransmission,
                    modifier       = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp),
                    maxLabelLength = 2
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text       = musicItem.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = MaterialTheme.customColors.onSurface,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        // EFX 뱃지가 먼저 자리를 잡고, 제목은 남는 폭 안에서만 줄임표로 잘린다
                        // (제목이 길어도 뒤따르는 EFX 뱃지가 밀려나지 않게 한다)
                        modifier   = Modifier.weight(1f, fill = false)
                    )
                    if (musicItem.hasEffect) {
                        Surface(
                            shape    = RoundedCornerShape(4.dp),
                            color    = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .height(16.dp)
                                .widthIn(min = 32.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier         = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text  = "EFX",
                                    style = MaterialTheme.customTextStyles.badgeSmall,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 가수 줄 높이(20sp)보다 낮은 16dp 배지라 붙거나 사라져도 줄 높이가 그대로다.
                // 가수명은 배지에 자리를 내주고 줄임표로 잘린다(시작 위치는 고정)
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text     = musicItem.artist,
                        style    = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        color    = MaterialTheme.customColors.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    genState?.let { AutoGenBadge(it) }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (isPlaying) {
                    Text(
                        text     = "Playing",
                        style    = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color    = MaterialTheme.customColors.textTertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Text(
                        text = TimeFormatter.formatTime(musicItem.duration),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.customColors.textTertiary
                    )
                }
            }
        }
    }
}

/**
 * 가수 줄 옆 자동 연출 상태 배지. 두 문구 중 긴 쪽 폭을 항상 차지해,
 * 대기 → 생성 중으로 바뀔 때 배지 폭이 달라지지 않는다.
 */
@Composable
private fun AutoGenBadge(state: FileState) {
    val generating = state == FileState.Generating
    val colors = MaterialTheme.customColors
    Surface(
        shape    = RoundedCornerShape(4.dp),
        color    = if (generating) colors.primary.copy(alpha = 0.25f) else colors.onSurface.copy(alpha = 0.10f),
        modifier = Modifier.height(16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier.padding(horizontal = 4.dp)
        ) {
            val style = MaterialTheme.customTextStyles.badgeSmall
            // 폭 고정용(보이지 않음)
            Text(text = WAIT_LABEL, style = style, maxLines = 1, modifier = Modifier.alpha(0f))
            Text(
                text     = if (generating) GEN_LABEL else WAIT_LABEL,
                style    = style,
                maxLines = 1,
                color    = if (generating) colors.onSurface else colors.textTertiary
            )
        }
    }
}

private const val GEN_LABEL = "이펙트 생성 중"
private const val WAIT_LABEL = "이펙트 생성 대기 중"
