package com.lightstick.music.ui.screen.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.R
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.ui.components.common.TopBarCentered
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.components.music.MusicPlayerCard
import com.lightstick.music.ui.theme.Secondary
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.ui.viewmodel.MusicViewModel

/**
 * 🎵 Music Control Screen (글라스모피즘)
 *
 * [수정] latestTransmission collectAsState 추가 → MusicPlayerCard 에 전달
 */
@UnstableApi
@Composable
fun MusicControlScreen(
    viewModel: MusicViewModel,
    onNavigateToMusicList: () -> Unit,
    onRequestEffectsDirectory: () -> Unit
) {
    val nowPlaying        by viewModel.nowPlaying.collectAsState()
    val isPlaying         by viewModel.isPlaying.collectAsState()
    val currentPosition   by viewModel.currentPosition.collectAsState()
    val duration          by viewModel.duration.collectAsState()
    val isAutoModeEnabled by viewModel.isAutoModeEnabled.collectAsState()
    // [추가] TimelineEffectBadge 표시용
    val latestTransmission by viewModel.latestTransmission.collectAsState()

    val isEffectsConfigured = EffectPathPreferences.isDirectoryConfigured(LocalContext.current)
    val effectCount = MusicEffectManager.getLoadedEffectCount()

    // ✅ CustomToast 상태
    val toastState = rememberToastState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ✅ Top Bar (배경색만, 이미지 없음)
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
                    toastState.show(message)  // ✅ CustomToast 사용
                },
                actionTextColor = if (isAutoModeEnabled) Secondary else Color.Gray
            )

            // ✅ 배경 이미지 영역 (TopBar 아래부터, 오버레이 없음)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 배경 이미지
                Image(
                    painter            = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop,
                    alignment          = Alignment.TopCenter
                )

                // 콘텐츠
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    // ✅ Effects 배너
                    when {
                        !isEffectsConfigured -> {
                            EffectsWarningBanner(
                                message       = "Effects 폴더 미설정",
                                description   = "EFX 파일을 읽으려면 폴더를 선택해주세요",
                                buttonText    = "설정",
                                onButtonClick = onRequestEffectsDirectory
                            )
                        }
                        effectCount == 0 -> {
                            EffectsWarningBanner(
                                message       = "EFX 파일 없음",
                                description   = "선택한 폴더에 EFX 파일이 없습니다",
                                buttonText    = "다시 선택",
                                onButtonClick = onRequestEffectsDirectory,
                                isError       = false
                            )
                        }
                    }

                    // ✅ 중앙 앨범아트 영역 (전체 공간 사용)
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
                            modifier         = Modifier.fillMaxWidth(),  // ✅ 전체 공간 채우기
                            latestTransmission = latestTransmission       // [추가]
                        )
                    }

                    // ✅ MUSIC LIST 버튼 (원본 그대로)
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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

        // ✅ CustomToast
        CustomToast(
            message   = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = { toastState.dismiss() },
            modifier  = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * Effects 경고 배너 (원본 그대로)
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
                if (isError) Color(0x40CF6679) else Color(0x40FFB74D)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = Color.White,
                modifier           = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = message,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
                Text(
                    text  = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onButtonClick,
                colors  = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isError) Color(0xFFCF6679) else Secondary,
                    contentColor   = Color.White
                )
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        }
    }
}