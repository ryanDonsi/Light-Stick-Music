package com.dongsitech.lightstickmusicdemo.viewmodel

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentMusicFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                _currentPosition.value = it.currentPosition
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val _title = MutableStateFlow("곡을 불러오는 중...")
    val title: StateFlow<String> = _title

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _musicFiles = MutableStateFlow<List<File>>(emptyList())
    val musicFiles: StateFlow<List<File>> = _musicFiles

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration

    fun loadMusicWithMediaStore(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val musicList = mutableListOf<File>()
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0"
            val sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC"

            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIndex)
                    musicList.add(File(path))
                }
            }

            if (musicList.isNotEmpty()) {
                _musicFiles.value = musicList
                selectAndPlay(musicList[0])
            } else {
                _title.value = "MP3 파일 없음"
            }
        }
    }

    fun selectAndPlay(file: File) {
        releaseMediaPlayer()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                _isPlaying.value = false
                _currentPosition.value = 0
            }
        }

        currentMusicFile = file
        _title.value = file.name
        _duration.value = mediaPlayer?.duration ?: 0
        _isPlaying.value = true
        startTracking()
    }

    fun playPauseMusic() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
            } else {
                it.start()
                _isPlaying.value = true
                startTracking()
            }
        }
    }

    fun stopMusic() {
        releaseMediaPlayer()
        _isPlaying.value = false
        _title.value = "정지됨"
        _currentPosition.value = 0
        _duration.value = 0
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        _currentPosition.value = position
    }

    private fun startTracking() {
        handler.post(updateRunnable)
    }

    private fun stopTracking() {
        handler.removeCallbacks(updateRunnable)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        stopTracking()
    }

    override fun onCleared() {
        super.onCleared()
        releaseMediaPlayer()
    }
}
