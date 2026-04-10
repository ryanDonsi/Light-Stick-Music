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
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * Music List 아이템 카드 (글라스모피즘)
 *
 * ✅ 수정 사항: **색상만 theme로 교체, UI는 그대로 유지**
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
    latestTransmission: BleTransmissionEvent? = null
) {
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
            // ── 앨범아트 + 이펙트 뱃지 오버레이 ──────────────────────────
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                // 앨범아트 (Coil 비동기 로딩 — 메인 스레드 블로킹 없음)
                // SubcomposeAsyncImage: error/fallback에 Composable 슬롯 지원
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

                // [추가] 이펙트 뱃지 — TIMELINE_EFFECT 수신 시 우측 하단 오버레이 (원형만)
                val isTimeline = latestTransmission?.source == TransmissionSource.TIMELINE_EFFECT
                if (isTimeline && latestTransmission != null) {
                    EffectOverlayBadge(
                        transmission   = latestTransmission,
                        modifier       = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp),
                        maxLabelLength = 2
                    )
                }
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
                        overflow   = TextOverflow.Ellipsis
                    )
                    // EFX 배지 (원본 그대로)
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

                Text(
                    text     = musicItem.artist,
                    style    = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color    = MaterialTheme.customColors.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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