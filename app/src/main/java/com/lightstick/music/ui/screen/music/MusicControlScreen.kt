package com.lightstick.music.ui.screen.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.R
import com.lightstick.music.ui.components.common.TopBarCentered
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.components.music.MusicPlayerCard
import com.lightstick.music.ui.theme.Secondary
import com.lightstick.music.ui.viewmodel.MusicViewModel

/**
 *  Music Control Screen (글라스모피즘)
 *
 * [수정] latestTransmission collectAsState 추가 → MusicPlayerCard 에 전달
 */
@UnstableApi
@Composable
fun MusicControlScreen(
    viewModel: MusicViewModel,
    onNavigateToMusicList: () -> Unit
) {
    val nowPlaying              by viewModel.nowPlaying.collectAsState()
    val isPlaying               by viewModel.isPlaying.collectAsState()
    val currentPosition         by viewModel.currentPosition.collectAsState()
    val duration                by viewModel.duration.collectAsState()
    val isAutoModeEnabled       by viewModel.isAutoModeEnabled.collectAsState()
    val latestTransmission      by viewModel.latestTransmission.collectAsState()
    val currentSections         by viewModel.currentSections.collectAsState()
    val isSectionOverlayEnabled by viewModel.isSectionOverlayEnabled.collectAsState()

    val toastState = rememberToastState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            TopBarCentered(
                title      = "Music Control",
                actionText = "AUTO",
                onActionClick = {
                    val newState = viewModel.toggleAutoMode()
                    val message  = if (newState) {
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
                Image(
                    painter            = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop,
                    alignment          = Alignment.TopCenter
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        MusicPlayerCard(
                            musicItem        = nowPlaying,
                            isPlaying        = isPlaying,
                            currentPosition  = currentPosition.toLong(),
                            duration         = duration.toLong(),
                            onPrevClick      = { viewModel.playPrevious() },
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onNextClick      = { viewModel.playNext() },
                            onSeekTo         = { position -> viewModel.seekTo(position) },
                            modifier         = Modifier.fillMaxWidth(),
                            latestTransmission      = latestTransmission,
                            sections                = currentSections,
                            isSectionOverlayEnabled = isSectionOverlayEnabled
                        )
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // 섹션 오버레이 토글
                        TextButton(
                            onClick        = { viewModel.toggleSectionOverlay() },
                            contentPadding = PaddingValues(
                                start  = 0.dp,
                                top    = 8.dp,
                                end    = 0.dp,
                                bottom = 8.dp
                            )
                        ) {
                            Text(
                                text       = "EFFECT INFO",
                                style      = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color      = if (isSectionOverlayEnabled) com.lightstick.music.ui.theme.Secondary
                                             else Color.Gray
                            )
                        }

                        // 음악 목록 이동
                        TextButton(
                            onClick        = onNavigateToMusicList,
                            contentPadding = PaddingValues(
                                start  = 0.dp,
                                top    = 8.dp,
                                end    = 0.dp,
                                bottom = 8.dp
                            )
                        ) {
                            Text(
                                text       = "MUSIC LIST",
                                style      = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color      = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector        = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint               = Color.White,
                                modifier           = Modifier.size(28.dp)
                            )
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

