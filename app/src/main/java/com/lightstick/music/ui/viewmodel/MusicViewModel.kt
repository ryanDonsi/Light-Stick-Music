package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.lightstick.music.core.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.core.state.MusicPlaybackState
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.domain.music.FftAudioProcessor
import com.lightstick.music.domain.music.createFftPlayer
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.core.bus.MusicPlayerCommandBus
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.service.ServiceController
import com.lightstick.music.data.local.preferences.AutoModePreferences
import com.lightstick.music.domain.usecase.music.HandleSeekUseCase
import com.lightstick.music.domain.usecase.music.LoadMusicTimelineUseCase
import com.lightstick.music.domain.usecase.music.ProcessFFTUseCase
import com.lightstick.music.domain.usecase.music.UpdatePlaybackPositionUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = AppConstants.Feature.VM_MUSIC
    }

    private val context = application.applicationContext

    // ═══════════════════════════════════════════════════════════
    // UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val loadMusicTimelineUseCase      = LoadMusicTimelineUseCase()
    private val updatePlaybackPositionUseCase = UpdatePlaybackPositionUseCase()
    private val handleSeekUseCase             = HandleSeekUseCase()
    private val processFFTUseCase             = ProcessFFTUseCase()

    // ═══════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════

    private val _isAutoModeEnabled = MutableStateFlow(true)
    val isAutoModeEnabled: StateFlow<Boolean> = _isAutoModeEnabled.asStateFlow()

    // FFT → LED 전송 (AUTO 모드 체크 포함)
    val audioProcessor = FftAudioProcessor { band ->
        if (_isAutoModeEnabled.value && PermissionManager.hasBluetoothConnectPermission(context)) {
            try {
                processFFTUseCase(context, band)
            } catch (e: Exception) {
                Log.e(TAG, "FFT effect send failed: ${e.message}")
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

        _isAutoModeEnabled.value = AutoModePreferences.isAutoModeEnabled(getApplication())

        // ── [추가] isPlaying + isAutoModeEnabled 상태를 MusicPlaybackState 에 실시간 전파 ──
        // EffectViewModel 의 EXCLUSIVE 모드 잠금 판단에 사용됩니다.
        viewModelScope.launch {
            combine(_isPlaying, _isAutoModeEnabled) { playing, autoMode ->
                playing && autoMode
            }.collect { playingWithAutoMode ->
                MusicPlaybackState.update(
                    isPlaying  = _isPlaying.value,
                    isAutoMode = _isAutoModeEnabled.value
                )
                Log.d(TAG, "MusicPlaybackState → playingWithAutoMode=$playingWithAutoMode")
            }
        }

        viewModelScope.launch { monitorPosition() }

        viewModelScope.launch {
            MusicPlayerCommandBus.commands.collect { command ->
                when (command) {
                    is MusicPlayerCommandBus.Command.TogglePlay -> togglePlayPause()
                    is MusicPlayerCommandBus.Command.Next       -> playNext()
                    is MusicPlayerCommandBus.Command.Previous   -> playPrevious()
                    is MusicPlayerCommandBus.Command.SeekTo     -> seekTo(command.position)
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
            Log.d(TAG, "Initialized $count effects")
        } else {
            Log.w(TAG, "Effects directory not configured")
        }
    }

    private fun loadCachedMusicOrScan() {
        viewModelScope.launch {
            val prefs       = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            val initialized = prefs.getBoolean("is_initialized", false)
            Log.d(TAG, if (initialized) "Loading from initialized state" else "First launch, scanning music...")
            loadMusic()
        }
    }

    fun loadMusic() {
        viewModelScope.launch {
            val resolver   = context.contentResolver
            val uri        = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sort      = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            val musicItems = mutableListOf<MusicItem>()
            resolver.query(uri, projection, selection, null, sort)?.use { cursor ->
                val titleCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val path      = cursor.getString(dataCol)
                    val metaTitle = cursor.getString(titleCol)
                    val fileName  = cursor.getString(nameCol)
                    val title     = if (!metaTitle.isNullOrBlank()) metaTitle
                    else fileName.substringBeforeLast(".")
                    val artist    = cursor.getString(artistCol) ?: "Unknown"

                    val musicFile = File(path)
                    val hasEffect = try {
                        MusicEffectManager.hasEffectFor(musicFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check effect: ${e.message}")
                        false
                    }

                    val retriever = MediaMetadataRetriever()
                    var art: String? = null
                    var duration: Long = 0L
                    try {
                        retriever.setDataSource(path)
                        art = retriever.embeddedPicture?.let { bytes ->
                            val file = File(context.cacheDir, "${title.hashCode()}.jpg")
                            file.writeBytes(bytes)
                            file.absolutePath
                        }
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        duration = durationStr?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract metadata: ${e.message}")
                    } finally {
                        retriever.release()
                    }

                    musicItems.add(
                        MusicItem(
                            title        = title,
                            artist       = artist,
                            filePath     = path,
                            albumArtPath = art,
                            hasEffect    = hasEffect,
                            duration     = duration
                        )
                    )
                }
            }

            _musicList.value = musicItems
            Log.d(TAG, "Loaded ${musicItems.size} music files, ${musicItems.count { it.hasEffect }} with effects")
        }
    }

    fun updateNotificationProgress() {
        val item = _nowPlaying.value ?: return
        ServiceController.updateNotificationProgress(
            context   = context,
            musicItem = item,
            isPlaying = _isPlaying.value,
            position  = _currentPosition.value.toLong(),
            duration  = _duration.value.toLong()
        )
    }

    /**
     * 음악 재생
     *
     * 타임라인 동기화 순서:
     * 1. 타임라인 로드
     * 2. 초기 위치(0ms) 동기화
     * 3. 음악 재생 시작
     */
    fun playMusic(item: MusicItem) {
        _nowPlaying.value      = item
        _isPlaying.value       = true
        _duration.value        = 0
        _currentPosition.value = 0

        // 1. 타임라인 로드
        if (_isAutoModeEnabled.value) {
            val musicFile = File(item.filePath)
            EffectEngineController.reset()
            loadMusicTimelineUseCase(context, musicFile)
            Log.d(TAG, "AUTO ON - Timeline loaded for: ${item.title}")
        } else {
            EffectEngineController.reset()
            Log.d(TAG, "AUTO OFF - EFX not loaded")
        }

        ServiceController.startMusicEffectService(
            context   = context,
            musicItem = item,
            isPlaying = true,
            position  = 0L,
            duration  = 0L
        )

        val mediaItem = MediaItem.fromUri(item.filePath)
        player.setMediaItem(mediaItem)
        player.prepare()

        // 2. 초기 위치(0ms) 동기화
        if (_isAutoModeEnabled.value) {
            try {
                updatePlaybackPositionUseCase(context, 0L)
                Log.d(TAG, "Initial position synced at 0ms")
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed: ${e.message}")
            }
        }

        // 3. 재생 시작
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
     * AUTO 모드 토글
     */
    fun toggleAutoMode(): Boolean {
        val context  = getApplication<Application>()
        val newState = AutoModePreferences.toggleAutoMode(context)
        _isAutoModeEnabled.value = newState

        if (!newState) {
            EffectEngineController.reset()
            Log.d(TAG, "AUTO OFF - EFX unloaded, FFT analysis disabled")
        } else {
            val currentMusic = _nowPlaying.value
            if (currentMusic != null) {
                val musicFile = File(currentMusic.filePath)
                EffectEngineController.reset()
                loadMusicTimelineUseCase(context, musicFile)

                val currentPos = _currentPosition.value.toLong()
                try {
                    updatePlaybackPositionUseCase(context, currentPos)
                    Log.d(TAG, "AUTO ON - synced at ${currentPos}ms for: ${currentMusic.title}")
                } catch (e: Exception) {
                    Log.e(TAG, "AUTO ON sync failed: ${e.message}")
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
     * Seek 처리
     */
    fun seekTo(position: Long) {
        player.seekTo(position)
        _currentPosition.value = position.toInt()

        if (_isAutoModeEnabled.value) {
            try {
                handleSeekUseCase(context, position)
            } catch (e: Exception) {
                Log.e(TAG, "handleSeek() failed: ${e.message}")
            }
        }

        updateNotificationProgress()
    }

    /**
     * 위치 모니터링 - 매 POSITION_MONITOR_INTERVAL_MS 마다 SDK에 재생 위치 전달
     */
    @SuppressLint("MissingPermission")
    private fun monitorPosition() {
        viewModelScope.launch {
            while (true) {
                val current  = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                _currentPosition.value = current
                _duration.value        = duration

                if (player.isPlaying && _isAutoModeEnabled.value) {
                    try {
                        updatePlaybackPositionUseCase(context, current.toLong())
                    } catch (e: Exception) {
                        Log.e(TAG, "updatePlaybackPosition() failed: ${e.message}")
                    }
                }

                delay(AppConstants.POSITION_MONITOR_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        MusicPlaybackState.reset()
        player.release()
        super.onCleared()
    }
}