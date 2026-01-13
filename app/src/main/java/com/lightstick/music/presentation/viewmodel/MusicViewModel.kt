package com.lightstick.music.presentation.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.music.domain.effect.MusicEffectManager
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.domain.music.player.FftAudioProcessor
import com.lightstick.music.domain.music.player.createFftPlayer
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.core.bus.MusicPlayerCommandBus
import com.lightstick.music.service.ServiceController
import com.lightstick.music.data.local.preferences.AutoModePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val effectEngineController = EffectEngineController

    // ✅ AUTO 모드 상태
    private val _isAutoModeEnabled = MutableStateFlow(true)
    val isAutoModeEnabled: StateFlow<Boolean> = _isAutoModeEnabled.asStateFlow()

    // ✅ FFT -> LED 전송 (AUTO 모드 체크 포함)
    val audioProcessor = FftAudioProcessor { band ->
        // AUTO 모드일 때만 FFT 효과 처리
        if (_isAutoModeEnabled.value && PermissionManager.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                EffectEngineController.processFftEffect(band, context)
            } catch (e: SecurityException) {
                Log.e("MusicPlayerVM", "FFT effect send failed: ${e.message}")
            }
        }
    }

    val player = createFftPlayer(context, audioProcessor)

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

    init {
        initializeEffects()
        EffectEngineController.reset()

        // ✅ AUTO 모드 초기값 로드
        _isAutoModeEnabled.value = AutoModePreferences.isAutoModeEnabled(getApplication())

        viewModelScope.launch {
            monitorPosition()
        }

        viewModelScope.launch {
            MusicPlayerCommandBus.commands.collect { command ->
                when (command) {
                    is MusicPlayerCommandBus.Command.TogglePlay -> togglePlayPause()
                    is MusicPlayerCommandBus.Command.Next -> playNext()
                    is MusicPlayerCommandBus.Command.Previous -> playPrevious()
                    is MusicPlayerCommandBus.Command.SeekTo -> seekTo(command.position)
                }
            }
        }

        loadCachedMusicOrScan()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playNext()
            }
        })
    }

    private fun initializeEffects() {
        if (EffectPathPreferences.isDirectoryConfigured(context)) {
            MusicEffectManager.initializeFromSAF(context)
            val count = MusicEffectManager.getLoadedEffectCount()
            Log.d("MusicPlayerVM", "✅ Initialized $count effects")
        } else {
            Log.w("MusicPlayerVM", "⚠️ Effects directory not configured")
        }
    }

    private fun loadCachedMusicOrScan() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            val isInitialized = prefs.getBoolean("is_initialized", false)

            if (isInitialized) {
                Log.d("MusicPlayerVM", "📦 Loading from initialized state")
                loadMusic()
            } else {
                Log.d("MusicPlayerVM", "🔍 First launch, scanning music...")
                loadMusic()
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

                    val musicFile = File(path)
                    val hasEffect = try {
                        MusicEffectManager.hasEffectFor(musicFile)
                    } catch (e: Exception) {
                        Log.e("MusicPlayerVM", "Failed to check effect: ${e.message}")
                        false
                    }

                    // MediaMetadataRetriever로 앨범아트 + duration 추출
                    val retriever = MediaMetadataRetriever()
                    var art: String? = null
                    var duration: Long = 0L

                    try {
                        retriever.setDataSource(path)

                        // 앨범아트 추출
                        art = retriever.embeddedPicture?.let { bytes ->
                            val file = File(context.cacheDir, "${title.hashCode()}.jpg")
                            file.writeBytes(bytes)
                            file.absolutePath
                        }

                        // Duration 추출
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        duration = durationStr?.toLongOrNull() ?: 0L

                    } catch (e: Exception) {
                        Log.e("MusicPlayerVM", "Failed to extract metadata: ${e.message}")
                    } finally {
                        retriever.release()
                    }

                    musicItems.add(
                        MusicItem(
                            title = title,
                            artist = artist,
                            filePath = path,
                            albumArtPath = art,
                            hasEffect = hasEffect,
                            duration = duration
                        )
                    )
                }
            }

            _musicList.value = musicItems
            Log.d("MusicPlayerVM", "📀 Loaded ${musicItems.size} music files, ${musicItems.count { it.hasEffect }} with effects")
        }
    }

    fun updateNotificationProgress() {
        val item = _nowPlaying.value ?: return
        ServiceController.updateNotificationProgress(
            context = context,
            musicItem = item,
            isPlaying = _isPlaying.value,
            position = _currentPosition.value.toLong(),
            duration = _duration.value.toLong()
        )
    }

    /**
     * ✅ 음악 재생 (AUTO 모드 체크 포함)
     */
    fun playMusic(item: MusicItem) {
        _nowPlaying.value = item
        _isPlaying.value = true
        _duration.value = 0
        _currentPosition.value = 0

        // ✅ AUTO 모드일 때만 EFX 로드
        if (_isAutoModeEnabled.value) {
            val musicFile = File(item.filePath)
            EffectEngineController.reset()
            EffectEngineController.loadEffectsFor(musicFile, context)
            Log.d("MusicPlayerVM", "🎵 AUTO ON - EFX loaded for: ${item.title}")
        } else {
            // AUTO OFF - EFX 로드 안 함
            EffectEngineController.reset()
            Log.d("MusicPlayerVM", "🔕 AUTO OFF - EFX not loaded")
        }

        ServiceController.startMusicEffectService(
            context = context,
            musicItem = item,
            isPlaying = true,
            position = 0L,
            duration = 0L
        )

        val mediaItem = MediaItem.fromUri(item.filePath)
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
        updateNotificationProgress()
    }

    /**
     * ✅ AUTO 모드 토글 (완전 구현)
     */
    fun toggleAutoMode(): Boolean {
        val context = getApplication<Application>()
        val newState = AutoModePreferences.toggleAutoMode(context)
        _isAutoModeEnabled.value = newState

        if (!newState) {
            // ✅ AUTO OFF - EFX 언로드
            effectEngineController.reset()
            Log.d("MusicPlayerVM", "🔕 AUTO OFF - EFX unloaded, FFT analysis disabled")
        } else {
            // ✅ AUTO ON - 현재 재생 중인 곡이 있으면 EFX 로드
            val currentMusic = _nowPlaying.value
            if (currentMusic != null) {
                val musicFile = File(currentMusic.filePath)
                effectEngineController.reset()
                effectEngineController.loadEffectsFor(musicFile, context)
                Log.d("MusicPlayerVM", "🎵 AUTO ON - EFX loaded for: ${currentMusic.title}")
            }
        }

        return newState
    }

    fun playNext() {
        val index = _musicList.value.indexOfFirst { it == _nowPlaying.value }
        _musicList.value.getOrNull(index + 1)?.let { playMusic(it) }
    }

    fun playPrevious() {
        val index = _musicList.value.indexOfFirst { it == _nowPlaying.value }
        _musicList.value.getOrNull(index - 1)?.let { playMusic(it) }
    }

    fun seekTo(position: Long) {
        player.seekTo(position)
        _currentPosition.value = position.toInt()
        updateNotificationProgress()
    }

    fun setTargetAddress(address: String?) {
        EffectEngineController.setTargetAddress(address)
    }

    /**
     * ✅ 위치 모니터링 (AUTO 모드 체크 포함)
     */
    @SuppressLint("MissingPermission")
    private fun monitorPosition() {
        viewModelScope.launch {
            var lastSecond = -1
            while (true) {
                val current = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                _currentPosition.value = current
                _duration.value = duration

                // ✅ AUTO 모드일 때만 타임라인 효과 처리
                if (player.isPlaying && _isAutoModeEnabled.value && current / 1000 != lastSecond) {
                    try {
                        EffectEngineController.processPosition(context, current)
                    } catch (e: SecurityException) {
                        Log.e("MusicPlayerVM", "processPosition() failed: ${e.message}")
                    }
                    updateNotificationProgress()
                    lastSecond = current / 1000
                }

                delay(100)
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}