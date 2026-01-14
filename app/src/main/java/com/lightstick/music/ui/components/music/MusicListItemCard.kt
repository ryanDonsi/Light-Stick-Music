package com.lightstick.music.ui.components.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.core.util.TimeFormatter

/**
 * Music List 아이템 카드 (글라스모피즘)
 *
 * ✅ 수정 사항: **색상만 theme로 교체, UI는 그대로 유지**
 * - Color.White → MaterialTheme.customColors.onSurface
 * - Color.White.copy(alpha = 0.05f) → customColors.onSurface.copy(alpha = 0.05f)
 * - Color.White.copy(alpha = 0.6f) → customColors.textTertiary
 */
@Composable
fun MusicListItemCard(
    musicItem: MusicItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                // ✅ 색상만 교체: Color.White.copy(alpha = 0.05f) → customColors.onSurface.copy(alpha = 0.05f)
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
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "앨범아트",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        // ✅ 색상만 교체: Color.White → customColors.onSurface
                        tint = if (isPlaying)
                            MaterialTheme.customColors.onSurface
                        else
                            MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // ✅ UI 유지: fontSize = 15.sp, fontWeight = SemiBold
                // ✅ 색상만 교체: Color.White → customColors.onSurface
                Text(
                    text = musicItem.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.customColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ✅ UI 유지: fontSize = 13.sp
                // ✅ 색상만 교체: Color.White.copy(alpha = 0.7f) → customColors.onSurface.copy(alpha = 0.7f)
                Text(
                    text = musicItem.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.customColors.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (isPlaying) {
                    // ✅ UI 유지: fontSize = 12.sp
                    // ✅ 색상만 교체: Color.White.copy(alpha = 0.6f) → customColors.textTertiary
                    Text(
                        text = "Playing",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.customColors.textTertiary
                    )
                } else {
                    // ✅ UI 유지: fontSize = 12.sp
                    // ✅ 색상만 교체: Color.White.copy(alpha = 0.6f) → customColors.textTertiary
                    Text(
                        text = TimeFormatter.formatTime(musicItem.duration),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.customColors.textTertiary
                    )

                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        // ✅ 색상만 교체: Color.White.copy(alpha = 0.6f) → customColors.textTertiary
                        tint = MaterialTheme.customColors.textTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}