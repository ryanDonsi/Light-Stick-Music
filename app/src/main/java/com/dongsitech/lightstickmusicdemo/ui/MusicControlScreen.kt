package com.dongsitech.lightstickmusicdemo.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.effect.MusicEffectManager
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.theme.Primary
import com.dongsitech.lightstickmusicdemo.ui.theme.Secondary
import com.dongsitech.lightstickmusicdemo.util.EffectDirectoryManager
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel

/**
 * 🎵 Music Control Screen (글라스모피즘)
 */
@UnstableApi
@Composable
fun MusicControlScreen(
    viewModel: MusicPlayerViewModel,
    onNavigateToMusicList: () -> Unit,
    onRequestEffectsDirectory: () -> Unit
) {
    val context = LocalContext.current
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val isAutoModeEnabled by viewModel.isAutoModeEnabled.collectAsState()

    val isEffectsConfigured = EffectDirectoryManager.isDirectoryConfigured(context)
    val effectCount = MusicEffectManager.getLoadedEffectCount()

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // ✅ 배경 이미지 (블러 처리)
        val currentMusic = nowPlaying
//        if (currentMusic != null) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(50.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.6f
        )
//        } else {
//            Box(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .background(Color(0xFF2A2A2A))
//            )
//        }

        // ✅ 콘텐츠
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ✅ Top Bar (어두운 반투명 배경)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(48.dp))

                    Text(
                        text = "Music Control",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )

                    TextButton(
                        onClick = {
                            val newState = viewModel.toggleAutoMode()
                            val message = if (newState) {
                                "자동 연출 기능을 사용합니다."
                            } else {
                                "자동 연출 기능이 중지됩니다."
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(
                            text = "AUTO",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isAutoModeEnabled) Secondary else Color.Gray
                        )
                    }
                }
            }

            // ✅ Effects 배너
            when {
                !isEffectsConfigured -> {
                    EffectsWarningBanner(
                        message = "Effects 폴더 미설정",
                        description = "EFX 파일을 읽으려면 폴더를 선택해주세요",
                        buttonText = "설정",
                        onButtonClick = onRequestEffectsDirectory
                    )
                }
                effectCount == 0 -> {
                    EffectsWarningBanner(
                        message = "EFX 파일 없음",
                        description = "선택한 폴더에 EFX 파일이 없습니다",
                        buttonText = "다시 선택",
                        onButtonClick = onRequestEffectsDirectory,
                        isError = false
                    )
                }
            }

            // ✅ 중앙 콘텐츠 + MUSIC LIST 버튼 (밝은 배경)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 중앙 앨범아트 영역
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentMusic == null) {
                        EmptyAlbumArt()
                    } else {
                        AlbumArtWithControls(
                            musicItem = currentMusic,
                            isPlaying = isPlaying,
                            currentPosition = currentPosition.toLong(),
                            duration = duration.toLong(),
                            onPrevClick = { viewModel.playPrevious() },
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onNextClick = { viewModel.playNext() },
                            onSeekTo = { position -> viewModel.seekTo(position) }
                        )
                    }
                }

                // ✅ MUSIC LIST 버튼
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onNavigateToMusicList
                    ) {
                        Text(
                            text = "MUSIC LIST",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Effects 경고 배너
 */
@Composable
private fun EffectsWarningBanner(
    message: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    isError: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isError) {
                    Color(0x40CF6679)
                } else {
                    Color(0x40FFB74D)
                }
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onButtonClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isError) Color(0xFFCF6679) else Secondary,
                    contentColor = Color.White
                )
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 빈 앨범아트
 */
@Composable
private fun EmptyAlbumArt() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "재생중인 음악이 없습니다",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 앨범아트 + 재생 컨트롤 (글라스모피즘 카드)
 */
@Composable
private fun AlbumArtWithControls(
    musicItem: MusicItem,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    // ✅ 앨범아트 로드 (기존 방식)
    val imageBitmap = musicItem.albumArtPath?.let { path ->
        try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ✅ 앨범아트 (실제 이미지 또는 기본 아이콘)
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageBitmap != null) {
                        // ✅ 실제 앨범아트 표시
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "앨범아트",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // ✅ 기본 아이콘
                        Image(
                            painter = painterResource(id = R.drawable.background),
                            contentDescription = "기본 앨범아트",
                            modifier = Modifier.size(120.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ✅ 곡 제목 + EFX 배지
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = musicItem.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (musicItem.hasEffect) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Secondary, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "EFX",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // ✅ 아티스트
                Text(
                    text = musicItem.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ✅ 프로그레스 바 (얇은 라인)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
                                )
                                .height(2.dp)
                                .background(Primary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatTime(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ✅ 재생 컨트롤 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPrevClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "이전",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // ✅ 보라색 원형 재생 버튼
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier
                            .size(64.dp)
                            .background(Primary, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "일시정지" else "재생",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    IconButton(
                        onClick = onNextClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "다음",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 시간 포맷 (밀리초 → MM:SS)
 */
private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000).toInt()
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}