package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.ui.components.MusicControlBar
import com.dongsitech.lightstickmusicdemo.ui.components.MusicItemCard

@UnstableApi
@Composable
fun MusicPlayerScreen(
    viewModel: MusicPlayerViewModel = hiltViewModel()
) {
    val musicList = viewModel.musicList.collectAsState().value
    val nowPlaying = viewModel.nowPlaying.collectAsState().value
    val isPlaying = viewModel.isPlaying.collectAsState().value
    val currentPosition = viewModel.currentPosition.collectAsState().value
    val duration = viewModel.duration.collectAsState().value

    val fallbackItem = MusicItem(
        title = "재생 중인 음악 없음",
        artist = "음악을 선택해주세요",
        filePath = "",
        albumArtPath = null
    )

    val currentItem = nowPlaying ?: fallbackItem

    Column(modifier = Modifier
        .fillMaxSize()
    ) {
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
            onSeekTo = { position -> viewModel.seekTo(position) } // 👈 추가
        )

        Spacer(modifier = Modifier.height(8.dp))

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
