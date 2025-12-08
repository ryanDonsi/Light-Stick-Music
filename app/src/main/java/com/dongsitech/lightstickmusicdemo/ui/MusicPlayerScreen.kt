package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.effect.MusicEffectManager
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.components.MusicControlBar
import com.dongsitech.lightstickmusicdemo.ui.components.MusicItemCard
import com.dongsitech.lightstickmusicdemo.util.EffectDirectoryManager
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel

@UnstableApi
@Composable
fun MusicPlayerScreen(
    viewModel: MusicPlayerViewModel,
    onRequestEffectsDirectory: () -> Unit
) {
    val context = LocalContext.current
    val musicList = viewModel.musicList.collectAsState().value
    val nowPlaying = viewModel.nowPlaying.collectAsState().value
    val isPlaying = viewModel.isPlaying.collectAsState().value
    val currentPosition = viewModel.currentPosition.collectAsState().value
    val duration = viewModel.duration.collectAsState().value

    // ✅ Effects 디렉토리 설정 상태 확인
    val isEffectsConfigured = EffectDirectoryManager.isDirectoryConfigured(context)
    val effectCount = MusicEffectManager.getLoadedEffectCount()

    val fallbackItem = MusicItem(
        title = "재생 중인 음악 없음",
        artist = "음악을 선택해주세요",
        filePath = "",
        albumArtPath = null,
        hasEffect = false
    )

    val currentItem = nowPlaying ?: fallbackItem

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ✅ Effects 상태 표시
        if (!isEffectsConfigured) {
            EffectsNotConfiguredBanner(
                onSetupClick = onRequestEffectsDirectory
            )
        } else if (effectCount == 0) {
            EffectsEmptyBanner(
                onSetupClick = onRequestEffectsDirectory
            )
        } else {
            EffectsInfoBanner(
                effectCount = effectCount,
                onChangeDirectoryClick = onRequestEffectsDirectory
            )
        }

        // 음악 컨트롤 바
        MusicControlBar(
            musicItem = currentItem,
            albumArtResId = R.drawable.ic_album_placeholder,
            title = currentItem.title,
            artist = currentItem.artist,
            currentPosition = if (nowPlaying != null) currentPosition else 0,
            duration = if (nowPlaying != null) duration else 0,
            isPlaying = isPlaying,
            onPrevClick = { viewModel.playPrevious() },
            onPlayPauseClick = { viewModel.togglePlayPause() },
            onNextClick = { viewModel.playNext() },
            onSeekTo = { position -> viewModel.seekTo(position) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 음악 목록
        if (musicList.isEmpty()) {
            EmptyMusicListPlaceholder()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(musicList) { item: MusicItem ->
                    MusicItemCard(
                        musicItem = item,
                        isPlaying = nowPlaying?.filePath == item.filePath,
                        onClick = { viewModel.playMusic(item) }
                    )
                }
            }
        }
    }
}

/**
 * ✅ Effects 디렉토리가 설정되지 않았을 때
 */
@Composable
private fun EffectsNotConfiguredBanner(
    onSetupClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "경고",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Effects 폴더 미설정",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "EFX 파일을 읽으려면 폴더를 선택해주세요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onSetupClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "폴더 선택",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("설정")
            }
        }
    }
}

/**
 * ✅ Effects 폴더는 설정되었지만 EFX 파일이 없을 때
 */
@Composable
private fun EffectsEmptyBanner(
    onSetupClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "정보",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EFX 파일 없음",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "선택한 폴더에 EFX 파일이 없습니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(onClick = onSetupClick) {
                Text("다시 선택")
            }
        }
    }
}

/**
 * ✅ Effects 정상 설정 시
 */
@Composable
private fun EffectsInfoBanner(
    effectCount: Int,
    onChangeDirectoryClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Effects",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "EFX 파일 $effectCount 개 로드됨",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = onChangeDirectoryClick,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    text = "변경",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * ✅ 음악 목록이 비어있을 때
 */
@Composable
private fun EmptyMusicListPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "음악 없음",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "음악 파일이 없습니다",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Music 폴더에 음악 파일을 추가해주세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}