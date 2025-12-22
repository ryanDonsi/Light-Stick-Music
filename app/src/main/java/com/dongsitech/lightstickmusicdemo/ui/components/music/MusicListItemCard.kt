package com.dongsitech.lightstickmusicdemo.ui.components.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.theme.Primary
import com.dongsitech.lightstickmusicdemo.ui.theme.Secondary
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles
import com.dongsitech.lightstickmusicdemo.ui.theme.customColors
import com.dongsitech.lightstickmusicdemo.util.TimeFormatter

/**
 * Music List 아이템 카드 (글라스모피즘)
 */
@Composable
fun MusicListItemCard(
    musicItem: MusicItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 앨범아트 로드
    val imageBitmap = musicItem.albumArtPath?.let { path ->
        try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                color = if (isPlaying) MaterialTheme.customColors.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f)
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
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 앨범아트 썸네일 (실제 이미지 또는 기본 아이콘)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    // 실제 앨범아트 표시
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "앨범아트",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 기본 아이콘
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = if (isPlaying) Color.White else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 제목 + 아티스트
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = musicItem.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // EFX 배지
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

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = musicItem.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Absolute.Right
            ) {
            // ✅ 재생 중 표시 또는 재생 시간
                if (isPlaying) {
                    Text(
                        text = "Playing",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                } else {
                    Text(
                        text = TimeFormatter.formatTime(musicItem.duration),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )

                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}