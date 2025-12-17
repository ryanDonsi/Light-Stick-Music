package com.dongsitech.lightstickmusicdemo.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.components.music.MusicListItemCard
import com.dongsitech.lightstickmusicdemo.ui.theme.Secondary
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel

/**
 * 🎵 Music List Screen (글라스모피즘)
 */
@UnstableApi
@Composable
fun MusicListScreen(
    viewModel: MusicPlayerViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val musicList by viewModel.musicList.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val isAutoModeEnabled by viewModel.isAutoModeEnabled.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // ✅ 배경 이미지 (블러 처리)
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(50.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.6f
        )

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
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = "Music List",
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

            // ✅ 음악 목록
            if (musicList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "음악 파일이 없습니다",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(musicList, key = { it.filePath }) { musicItem ->
                        MusicListItemCard(
                            musicItem = musicItem,
                            isPlaying = nowPlaying?.filePath == musicItem.filePath,
                            onClick = { viewModel.playMusic(musicItem) }
                        )
                    }
                }
            }
        }
    }
}