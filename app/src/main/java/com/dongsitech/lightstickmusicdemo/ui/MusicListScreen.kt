package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.components.common.TopBarWithBack
import com.dongsitech.lightstickmusicdemo.ui.components.common.CustomToast
import com.dongsitech.lightstickmusicdemo.ui.components.common.rememberToastState
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
    val musicList by viewModel.musicList.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val isAutoModeEnabled by viewModel.isAutoModeEnabled.collectAsState()
    
    val toastState = rememberToastState()

    Box(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            TopBarWithBack(
                title = "Music List",
                onBackClick = onNavigateBack,
                actionText = "AUTO",
                onActionClick = {
                    val newState = viewModel.toggleAutoMode()
                    val message = if (newState) {
                        "자동 연출 기능을 실행합니다."
                    } else {
                        "자동 연출 기능을 중지합니다."
                    }
                    toastState.show(message)
                },
                actionTextColor = if (isAutoModeEnabled) Secondary else Color.Gray
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 배경 이미지
                Image(
                    painter = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )

                // 콘텐츠
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Top  // ✅ 상단 정렬
                ) {


                    // 음악 리스트
                    if (musicList.isEmpty()) {
                        // 빈 리스트 표시
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "음악 파일이 없습니다",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(musicList) { item ->
                                MusicListItemCard(
                                    musicItem = item,
                                    isPlaying = nowPlaying?.filePath == item.filePath,
                                    onClick = { viewModel.playMusic(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        CustomToast(
            message = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = { toastState.dismiss() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}