package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    Column(modifier = Modifier.fillMaxSize()) {
        nowPlaying?.let { music ->
            MusicControlBar(
                musicItem = music,
                albumArtResId = music.albumArtPath?.let { R.drawable.ic_album_placeholder } ?: R.drawable.ic_album_placeholder,
                title = music.title,
                artist = music.artist,
                currentPosition = currentPosition,
                duration = duration,
                isPlaying = isPlaying,
                onPrevClick = { viewModel.playPrevious() },
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onNextClick = { viewModel.playNext() }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
