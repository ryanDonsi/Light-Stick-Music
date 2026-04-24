package com.lightstick.music.ui.screen.music

import androidx.compose.foundation.Image
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
import com.lightstick.music.R
import com.lightstick.music.ui.components.common.TopBarWithBack
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.components.music.MusicListItemCard
import com.lightstick.music.ui.theme.Secondary
import com.lightstick.music.ui.viewmodel.MusicViewModel

/**
 * 🎵 Music List Screen (글라스모피즘)
 *
 * [수정] latestTransmission collectAsState 추가 → MusicListItemCard 에 전달
 *        → 재생 중인 아이템의 EFX 뱃지 옆 TimelineEffectBadge 표시
 */
@UnstableApi
@Composable
fun MusicListScreen(
    viewModel: MusicViewModel,
    onNavigateBack: () -> Unit
) {
    val musicList         by viewModel.musicList.collectAsState()
    val nowPlaying        by viewModel.nowPlaying.collectAsState()
    val isAutoModeEnabled by viewModel.isAutoModeEnabled.collectAsState()
    // [추가] TimelineEffectBadge 표시용
    val latestTransmission by viewModel.latestTransmission.collectAsState()

    val toastState = rememberToastState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            TopBarWithBack(
                title      = "Music List",
                onBackClick = onNavigateBack,
                actionText = "AUTO",
                onActionClick = {
                    val newState = viewModel.toggleAutoMode()
                    val message  = if (newState) "자동 연출 기능을 실행합니다."
                    else          "자동 연출 기능을 중지합니다."
                    toastState.show(message)
                },
                actionTextColor = if (isAutoModeEnabled) Secondary else Color.Gray
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter            = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop,
                    alignment          = Alignment.TopCenter
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    if (musicList.isEmpty()) {
                        Box(
                            modifier         = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text  = "음악 파일이 없습니다",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier            = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(musicList) { item ->
                                val isPlaying = nowPlaying?.filePath == item.filePath
                                MusicListItemCard(
                                    musicItem          = item,
                                    isPlaying          = isPlaying,
                                    onClick            = { viewModel.playMusic(item) },
                                    // [추가] 재생 중인 아이템에만 전달 (다른 아이템은 null)
                                    latestTransmission = if (isPlaying) latestTransmission else null
                                )
                            }
                        }
                    }
                }
            }
        }

        CustomToast(
            message   = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = { toastState.dismiss() },
            modifier  = Modifier.align(Alignment.BottomCenter)
        )
    }
}