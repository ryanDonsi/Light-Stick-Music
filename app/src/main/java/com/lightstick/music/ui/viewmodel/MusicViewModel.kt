package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.core.bus.MusicPlayerCommandBus
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.service.ServiceController
import com.lightstick.music.core.state.MusicPlaybackState
import com.lightstick.music.core.util.FileHelper
import com.lightstick.music.core.util.Log
import com.lightstick.music.data.local.preferences.AutoModePreferences
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.music.domain.music.AutoTimelineConfig
import com.lightstick.music.domain.music.AutoTimelineStorage
import com.lightstick.music.domain.music.FftAudioProcessor
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.domain.music.createFftPlayer
import com.lightstick.music.domain.usecase.music.HandleSeekUseCase
import com.lightstick.music.domain.usecase.music.LoadEfxUseCase
import com.lightstick.music.domain.usecase.music.ProcessFFTUseCase
import com.lightstick.music.domain.usecase.music.UpdatePlaybackPositionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@HiltViewModel
@UnstableApi
class MusicViewModel @Inject constructor(
    application: Application,
    private val loadEfxUseCase:                LoadEfxUseCase,
    private val updatePlaybackPositionUseCase: UpdatePlaybackPositionUseCase,
    private val handleSeekUseCase:             HandleSeekUseCase,
    private val processFFTUseCase:             ProcessFFTUseCase
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = AppConstants.Feature.VM_MUSIC
    }

    private val context = application.applicationContext

    private val _isAutoModeEnabled = MutableStateFlow(true)
    val isAutoModeEnabled: StateFlow<Boolean> = _isAutoModeEnabled.asStateFlow()

    val audioProcessor = FftAudioProcessor { band ->
        val canSendFft =
            _isAutoModeEnabled.value &&
                    PermissionManager.hasBluetoothConnectPermission(context) &&
                    !EffectEngineController.isTimelineActive()
        if (canSendFft) {
            try { processFFTUseCase(context, band) }
            catch (e: Exception) { Log.e(TAG, "FFT effect send failed: ${e.message}") }
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

    val latestTransmission: StateFlow<BleTransmissionEvent?> =
        BleTransmissionMonitor.latestTransmission

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) playNext()
        }
    }

    init {
        initializeEffects()
        EffectEngineController.reset()

        _isAutoModeEnabled.value = AutoModePreferences.isAutoModeEnabled(getApplication())

        viewModelScope.launch {
            combine(_isPlaying, _isAutoModeEnabled) { playing, autoMode ->
                playing && autoMode
            }.collect {
                MusicPlaybackState.update(
                    isPlaying  = _isPlaying.value,
                    isAutoMode = _isAutoModeEnabled.value
                )
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

        player.addListener(playerListener)
    }

    private fun initializeEffects() {
        Log.d(TAG, "initializeEffects: start (thread=${Thread.currentThread().name})")
        MusicEffectManager.initializeFromSAF(context)
        Log.d(TAG, "initializeEffects: done — ${MusicEffectManager.getLoadedEffectCount()} EFX loaded")
    }

    private fun loadCachedMusicOrScan() {
        viewModelScope.launch {
            loadMusic()
        }
    }

    fun loadMusic() {
        if (!PermissionManager.hasStoragePermission(context)) {
            Log.w(TAG, "loadMusic skipped: no storage permission")
            return
        }
        viewModelScope.launch {
            val musicItems = withContext(Dispatchers.IO) {
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

                val allowedDirs = FileHelper.allowedMusicDirs()

                val items = mutableListOf<MusicItem>()
                resolver.query(uri, projection, selection, null, sort)?.use { cursor ->
                    val titleCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val nameCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val dataCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                    while (cursor.moveToNext()) {
                        val path      = cursor.getString(dataCol) ?: continue
                        val file      = File(path)
                        if (file.extension.lowercase() !in AppConstants.SUPPORTED_AUDIO_EXTENSIONS) continue
                        if (allowedDirs.none { path.startsWith(it) }) continue
                        if (FileHelper.isRecordingsPath(path)) continue

                        val metaTitle = cursor.getString(titleCol)
                        val fileName  = cursor.getString(nameCol)
                        val title     = if (!metaTitle.isNullOrBlank()) metaTitle
                        else fileName.substringBeforeLast(".")
                        val artist    = cursor.getString(artistCol) ?: "Unknown"

                        val hasEffect = try {
                            MusicEffectManager.hasEffectFor(file)
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
                            val durationStr = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )
                            duration = durationStr?.toLongOrNull() ?: 0L
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to extract metadata: ${e.message}")
                        } finally {
                            retriever.release()
                        }

                        items.add(
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
                items
            }

            _musicList.value = musicItems
            Log.d(TAG, "loadMusic: done — ${musicItems.count { it.hasEffect }}/${musicItems.size} songs have EFX")

            // 재생 중인 곡의 hasEffect도 함께 갱신
            val playing = _nowPlaying.value
            if (playing != null) {
                val refreshed = musicItems.find { it.filePath == playing.filePath }
                if (refreshed != null && refreshed.hasEffect != playing.hasEffect) {
                    _nowPlaying.value = refreshed
                }
            }
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

    fun playMusic(item: MusicItem) {
        _nowPlaying.value      = item
        _isPlaying.value       = true
        _duration.value        = 0
        _currentPosition.value = 0

        if (_isAutoModeEnabled.value) {
            val musicFile = File(item.filePath)
            EffectEngineController.reset()

            if (MusicEffectManager.hasEffectFor(musicFile)) {
                loadEfxUseCase(context, musicFile)
            } else {
                val musicId = com.lightstick.efx.MusicId.fromFile(musicFile)
                val ver     = AutoTimelineConfig.VERSION
                val storage = AutoTimelineStorage(version = ver)
                val frames  = storage.load(context, musicId)

                if (!frames.isNullOrEmpty()) {
                    EffectEngineController.loadTimelineFromFrames(context, frames)
                } else {
                    Log.w(TAG, "자동 타임라인 없음 (v=$ver) → FFT 폴백")
                }
            }
        } else {
            EffectEngineController.reset()
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

        if (_isAutoModeEnabled.value) {
            try {
                updatePlaybackPositionUseCase(context, 0L)
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed: ${e.message}")
            }
        }

        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
            if (_isAutoModeEnabled.value) EffectEngineController.pauseEffects(context)
        } else {
            player.play()
            _isPlaying.value = true
            if (_isAutoModeEnabled.value) EffectEngineController.resumeEffects(context)
        }
        updateNotificationProgress()
    }

    fun toggleAutoMode(): Boolean {
        val ctx      = getApplication<Application>()
        val newState = AutoModePreferences.toggleAutoMode(ctx)

        _isAutoModeEnabled.value = newState

        if (!newState) {
            EffectEngineController.reset()
        } else {
            val currentMusic = _nowPlaying.value
            if (currentMusic != null) {
                val musicFile = File(currentMusic.filePath)
                EffectEngineController.reset()

                if (MusicEffectManager.hasEffectFor(musicFile)) {
                    loadEfxUseCase(context, musicFile)
                } else {
                    val musicId = com.lightstick.efx.MusicId.fromFile(musicFile)
                    val ver     = AutoTimelineConfig.VERSION
                    val storage = AutoTimelineStorage(version = ver)
                    val frames  = storage.load(context, musicId)
                    if (!frames.isNullOrEmpty()) {
                        EffectEngineController.loadTimelineFromFrames(context, frames)
                    }
                }

                val currentPos = _currentPosition.value.toLong()
                try {
                    updatePlaybackPositionUseCase(context, currentPos)
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

    fun seekTo(position: Long) {
        player.seekTo(position)
        _currentPosition.value = position.toInt()

        if (_isAutoModeEnabled.value) {
            try { handleSeekUseCase(context, position) }
            catch (e: Exception) { Log.e(TAG, "handleSeek() failed: ${e.message}") }
        }

        updateNotificationProgress()
    }

    @SuppressLint("MissingPermission")
    private fun monitorPosition() {
        viewModelScope.launch {
            var prevPosition = -1L
            while (true) {
                val current  = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                _currentPosition.value = current
                _duration.value        = duration

                if (player.isPlaying && _isAutoModeEnabled.value) {
                    val jump = current.toLong() - prevPosition
                    if (prevPosition >= 0 && jump > AppConstants.POSITION_MONITOR_INTERVAL_MS * 3) {
                        Log.w(TAG, "[pos] 큰 점프 감지: ${prevPosition}ms → ${current}ms (+${jump}ms)")
                    }
                    Log.d(TAG, "[pos] updatePlaybackPosition pos=${current}ms jump=${if (prevPosition < 0) "first" else "${jump}ms"}")
                    prevPosition = current.toLong()
                    try { updatePlaybackPositionUseCase(context, current.toLong()) }
                    catch (e: Exception) {
                        Log.e(TAG, "updatePlaybackPosition() failed: ${e.message}")
                    }
                } else {
                    prevPosition = -1L
                }

                delay(AppConstants.POSITION_MONITOR_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        MusicPlaybackState.reset()
        player.release()
        super.onCleared()
    }
}
