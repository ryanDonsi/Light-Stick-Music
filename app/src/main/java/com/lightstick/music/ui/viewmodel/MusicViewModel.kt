package com.lightstick.music.ui.viewmodel

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
import com.lightstick.music.domain.music.FftAudioProcessor
import com.lightstick.music.domain.music.createFftPlayer
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.core.bus.MusicPlayerCommandBus
import com.lightstick.music.core.service.ServiceController
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
                effectEngineController.processFftEffect(band, context)
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
     *
     * 타임라인 동기화 순서:
     * 1. 타임라인 로드
     * 2. 초기 위치(0ms) 동기화
     * 3. 음악 재생 시작
     */
    fun playMusic(item: MusicItem) {
        _nowPlaying.value = item
        _isPlaying.value = true
        _duration.value = 0
        _currentPosition.value = 0

        // ✅ 1. 먼저 타임라인 로드 (음악 재생 전!)
        if (_isAutoModeEnabled.value) {
            val musicFile = File(item.filePath)
            EffectEngineController.reset()
            EffectEngineController.loadEffectsFor(musicFile, context)
            Log.d("MusicPlayerVM", "🎵 AUTO ON - Timeline loaded for: ${item.title}")
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

        // ✅ 2. 재생 전 초기 위치(0ms) 동기화
        if (_isAutoModeEnabled.value) {
            try {
                EffectEngineController.updatePlaybackPosition(context, 0L)
                Log.d("MusicPlayerVM", "📍 Initial position synced at 0ms")
            } catch (e: Exception) {
                Log.e("MusicPlayerVM", "Initial sync failed: ${e.message}")
            }
        }

        // ✅ 3. 음악 재생 시작
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

                // ✅ 현재 재생 위치로 동기화
                val currentPosition = _currentPosition.value.toLong()
                try {
                    effectEngineController.updatePlaybackPosition(context, currentPosition)
                    Log.d("MusicPlayerVM", "🎵 AUTO ON - EFX loaded and synced at ${currentPosition}ms for: ${currentMusic.title}")
                } catch (e: Exception) {
                    Log.e("MusicPlayerVM", "AUTO ON sync failed: ${e.message}")
                }
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

    /**
     * ✅ Seek 처리 개선
     */
    fun seekTo(position: Long) {
        player.seekTo(position)
        _currentPosition.value = position.toInt()

        // ✅ Seek 시 즉시 타임라인 위치 업데이트
        if (_isAutoModeEnabled.value) {
            try {
                EffectEngineController.handleSeek(context, position)
            } catch (e: SecurityException) {
                Log.e("MusicPlayerVM", "handleSeek() failed: ${e.message}")
            }
        }

        updateNotificationProgress()
    }

    fun setTargetAddress(address: String?) {
        EffectEngineController.setTargetAddress(address)
    }

    /**
     * ✅ 위치 모니터링 (100ms마다 정확하게 업데이트)
     *
     * 주요 변경점:
     * 1. 매 100ms마다 updatePlaybackPosition() 호출 (1초 제한 제거)
     * 2. SDK가 내부적으로 타이밍을 정확하게 관리
     */
    @SuppressLint("MissingPermission")
    private fun monitorPosition() {
        viewModelScope.launch {
            while (true) {
                val current = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                _currentPosition.value = current
                _duration.value = duration

                // ✅ AUTO 모드이고 재생 중일 때 매 100ms마다 위치 업데이트
                if (player.isPlaying && _isAutoModeEnabled.value) {
                    try {
                        // SDK의 updatePlaybackPosition() 호출
                        // SDK 내부에서 정확한 타이밍에 이펙트 전송
                        EffectEngineController.updatePlaybackPosition(context, current.toLong())
                    } catch (e: SecurityException) {
                        Log.e("MusicPlayerVM", "updatePlaybackPosition() failed: ${e.message}")
                    }
                }

                delay(100)  // 100ms마다 업데이트 (SDK 권장 주기)
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}