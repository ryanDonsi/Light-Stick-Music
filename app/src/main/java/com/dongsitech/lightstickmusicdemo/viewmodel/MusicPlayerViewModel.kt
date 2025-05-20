package com.dongsitech.lightstickmusicdemo.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.dongsitech.lightstickmusicdemo.ble.BleGattManager
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.util.LedColorBand
import com.dongsitech.lightstickmusicdemo.util.MyAudioProcessor
import com.dongsitech.lightstickmusicdemo.util.MyPlaybackListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.net.toUri

@UnstableApi
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _musicList = MutableStateFlow<List<MusicItem>>(emptyList())
    val musicList: StateFlow<List<MusicItem>> = _musicList.asStateFlow()

    private val _nowPlaying = MutableStateFlow<MusicItem?>(null)
    val nowPlaying: StateFlow<MusicItem?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    private val audioProcessor = MyAudioProcessor { band: LedColorBand ->
        if (PermissionUtils.hasPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                BleGattManager.sendLedColorToAll(
                    red = band.red.toByte(),
                    green = band.green.toByte(),
                    blue = band.blue.toByte(),
                    transit = 10 // 1초 = 10프레임 선형 변화
                )
            } catch (e: SecurityException) {
                Log.e("MusicPlayerViewModel", "SecurityException: $e")
            }

        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            // Media3에서는 직접 AudioProcessor를 삽입하는 API는 없고, setAudioSink도 없음
            // 하지만 직접 DefaultAudioSink 설정하려면 별도 AudioSink 구현 필요
            setHandleAudioBecomingNoisy(true)
            addListener(MyPlaybackListener(audioProcessor))
        }

    init {
        loadMusic()
        monitorPlayback()
    }

    private fun monitorPlayback() {
        viewModelScope.launch {
            while (true) {
                if (player.isPlaying) {
                    _currentPosition.value = player.currentPosition.toInt()
                    _duration.value = player.duration.toInt()
                }
                delay(500)
            }
        }
    }

    fun loadMusic() {
        viewModelScope.launch {
            val resolver = context.contentResolver
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            val musicItems = mutableListOf<MusicItem>()

            resolver.query(uri, projection, selection, null, sort)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol)
                    val title = cursor.getString(nameCol) ?: "Unknown"
                    val artist = cursor.getString(artistCol) ?: "Unknown"

                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(path)
                    val art = retriever.embeddedPicture?.let {
                        val file = File(context.cacheDir, "${title.hashCode()}.jpg")
                        file.writeBytes(it)
                        file.absolutePath
                    }
                    retriever.release()

                    musicItems.add(MusicItem(title, artist, path, art))
                }
            }

            _musicList.value = musicItems
        }
    }

    fun playMusic(item: MusicItem) {
        _nowPlaying.value = item
        _isPlaying.value = true
        _duration.value = 0
        _currentPosition.value = 0

        val mediaItem = MediaItem.fromUri(item.filePath.toUri())
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.play()
            _isPlaying.value = true
        }
    }

    fun playNext() {
        val index = _musicList.value.indexOfFirst { it == _nowPlaying.value }
        val next = _musicList.value.getOrNull(index + 1)
        next?.let { playMusic(it) }
    }

    fun playPrevious() {
        val index = _musicList.value.indexOfFirst { it == _nowPlaying.value }
        val prev = _musicList.value.getOrNull(index - 1)
        prev?.let { playMusic(it) }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
