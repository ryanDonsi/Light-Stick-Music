package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
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
import com.lightstick.music.domain.usecase.music.LoadMusicTimelineUseCase
import com.lightstick.music.domain.usecase.music.ProcessFFTUseCase
import com.lightstick.music.domain.usecase.music.UpdatePlaybackPositionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
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
    private val loadMusicTimelineUseCase:      LoadMusicTimelineUseCase,
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

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // [추가] BleTransmissionMonitor.latestTransmission 직접 노출
    // MusicControlScreen / MusicListScreen 에서 TimelineEffectBadge 표시에 사용
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

    fun scanAndReloadMusic() {
        if (!PermissionManager.hasStoragePermission(context)) {
            Log.w(TAG, "scanAndReloadMusic skipped: no storage permission")
            return
        }
        if (_isScanning.value) return

        _isScanning.value = true
        Log.d(TAG, "MediaStore scan started")

        viewModelScope.launch(Dispatchers.IO) {
            val audioExtensions = setOf("mp3", "m4a", "flac", "aac", "ogg", "wav", "wma", "opus")
            val scanDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                Environment.getExternalStorageDirectory()
            ).filter { it != null && it.exists() }

            val audioFiles = scanDirs
                .flatMap { dir ->
                    dir.walkTopDown()
                        .filter { it.isFile && it.extension.lowercase() in audioExtensions }
                        .map { it.absolutePath }
                        .toList()
                }
                .distinct()

            Log.d(TAG, "Found ${audioFiles.size} audio files to scan")

            if (audioFiles.isEmpty()) {
                _isScanning.value = false
                loadMusic()
                return@launch
            }

            val remaining = AtomicInteger(audioFiles.size)
            MediaScannerConnection.scanFile(
                context,
                audioFiles.toTypedArray(),
                null
            ) { path, _ ->
                Log.d(TAG, "Scanned: $path")
                if (remaining.decrementAndGet() == 0) {
                    Log.d(TAG, "MediaStore scan complete")
                    viewModelScope.launch {
                        _isScanning.value = false
                        loadMusic()
                    }
                }
            }
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

                val items = mutableListOf<MusicItem>()
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
            Log.d(TAG, "Loaded ${musicItems.size} music files, " +
                    "${musicItems.count { it.hasEffect }} with effects")
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
                loadMusicTimelineUseCase(context, musicFile)
                Log.d(TAG, "EFX 재생")
            } else {
                val musicId = com.lightstick.efx.MusicId.fromFile(musicFile)
                val ver     = AutoTimelineConfig.VERSION
                val storage = AutoTimelineStorage(version = ver)
                val frames  = storage.load(context, musicId)

                Log.d(TAG, "AutoTimeline load v=$ver musicId=$musicId frames=${frames?.size ?: 0}")

                if (!frames.isNullOrEmpty()) {
                    Log.d(TAG, "자동 타임라인 재생 (v=$ver)")
                    EffectEngineController.loadTimelineFromFrames(context, frames)
                } else {
                    Log.w(TAG, "자동 타임라인 없음 (v=$ver) → FFT 폴백")
                }
            }
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

        if (_isAutoModeEnabled.value) {
            try {
                updatePlaybackPositionUseCase(context, 0L)
                Log.d(TAG, "Initial position synced at 0ms")
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
        } else {
            player.play()
            _isPlaying.value = true
        }
        updateNotificationProgress()
    }

    fun toggleAutoMode(): Boolean {
        val ctx      = getApplication<Application>()
        val newState = AutoModePreferences.toggleAutoMode(ctx)

        _isAutoModeEnabled.value = newState

        if (!newState) {
            EffectEngineController.reset()
            Log.d(TAG, "AUTO OFF - timeline reset, FFT enabled")
        } else {
            val currentMusic = _nowPlaying.value
            if (currentMusic != null) {
                val musicFile = File(currentMusic.filePath)
                EffectEngineController.reset()

                if (MusicEffectManager.hasEffectFor(musicFile)) {
                    loadMusicTimelineUseCase(context, musicFile)
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
            while (true) {
                val current  = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                _currentPosition.value = current
                _duration.value        = duration

                if (player.isPlaying && _isAutoModeEnabled.value) {
                    try { updatePlaybackPositionUseCase(context, current.toLong()) }
                    catch (e: Exception) {
                        Log.e(TAG, "updatePlaybackPosition() failed: ${e.message}")
                    }
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