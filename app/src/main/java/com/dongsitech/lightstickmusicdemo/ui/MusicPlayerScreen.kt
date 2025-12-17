package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.effect.MusicEffectManager
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.components.MusicControlBar
import com.dongsitech.lightstickmusicdemo.ui.components.MusicItemCard
import com.dongsitech.lightstickmusicdemo.ui.components.music.EffectsEmptyBanner
import com.dongsitech.lightstickmusicdemo.ui.components.music.EffectsInfoBanner
import com.dongsitech.lightstickmusicdemo.ui.components.music.EffectsNotConfiguredBanner
import com.dongsitech.lightstickmusicdemo.ui.components.music.EmptyMusicList
import com.dongsitech.lightstickmusicdemo.util.EffectDirectoryManager
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel

/**
 * ✅ 리팩토링: 컴포넌트 분리 완료
 *
 * Before: 200+ 줄
 * After: ~70 줄
 */
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

    Column(modifier = Modifier.fillMaxSize()) {
//        // ✅ Effects 상태 배너 (컴포넌트)
//        when {
//            !isEffectsConfigured -> EffectsNotConfiguredBanner(
//                onSetupClick = onRequestEffectsDirectory
//            )
//            effectCount == 0 -> EffectsEmptyBanner(
//                onSetupClick = onRequestEffectsDirectory
//            )
//            else -> EffectsInfoBanner(
//                effectCount = effectCount,
//                onChangeDirectoryClick = onRequestEffectsDirectory
//            )
//        }

        // ✅ 음악 컨트롤 바 (기존 컴포넌트)
        MusicControlBar(
            musicItem = currentItem,
            albumArtResId = R.drawable.background,
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

//        // ✅ 음악 목록
//        if (musicList.isEmpty()) {
//            EmptyMusicList()  // 컴포넌트
//        } else {
//            LazyColumn(modifier = Modifier.fillMaxSize()) {
//                items(musicList) { item ->
//                    MusicItemCard(
//                        musicItem = item,
//                        isPlaying = nowPlaying?.filePath == item.filePath,
//                        onClick = { viewModel.playMusic(item) }
//                    )
//                }
//            }
//        }
    }
}