package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.effect.EffectEngineController
import com.dongsitech.lightstickmusicdemo.effect.MusicEffectManager
import com.dongsitech.lightstickmusicdemo.model.MusicItem
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.player.FftAudioProcessor
import com.dongsitech.lightstickmusicdemo.player.createFftPlayer
import com.dongsitech.lightstickmusicdemo.util.EffectDirectoryManager
import com.dongsitech.lightstickmusicdemo.util.MusicPlayerCommandBus
import com.dongsitech.lightstickmusicdemo.util.ServiceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // SDK와 독립된 이펙트 컨트롤러 직접 사용
    private val effectEngine = EffectEngineController()

    // FFT -> LED 전송 (컨트롤러가 내부에서 권한체크 + 전송)
    val audioProcessor = FftAudioProcessor { band ->
        if (PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                effectEngine.processFftEffect(band, context)
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
        // ✅ Effects 초기화 (인트로에서 이미 완료되었을 수 있음)
        initializeEffects()

        effectEngine.reset()

        viewModelScope.launch {
            // 플레이어 포지션 모니터링 루프
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

        // ✅ 인트로에서 캐시된 음악 리스트 로드 시도
        loadCachedMusicOrScan()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playNext()
            }
        })
    }

    /**
     * ✅ Effects 초기화
     */
    private fun initializeEffects() {
        if (EffectDirectoryManager.isDirectoryConfigured(context)) {
            MusicEffectManager.initializeFromSAF(context)
            val count = MusicEffectManager.getLoadedEffectCount()
            Log.d("MusicPlayerVM", "✅ Initialized $count effects")
        } else {
            Log.w("MusicPlayerVM", "⚠️ Effects directory not configured")
        }
    }

    /**
     * ✅ 캐시된 음악 리스트 로드 또는 새로 스캔
     */
    private fun loadCachedMusicOrScan() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)
            val isInitialized = prefs.getBoolean("is_initialized", false)

            if (isInitialized) {
                // 인트로에서 이미 초기화됨 → 빠르게 로드
                Log.d("MusicPlayerVM", "📦 Loading from initialized state")
                loadMusic()
            } else {
                // 인트로를 거치지 않음 → 직접 스캔
                Log.d("MusicPlayerVM", "🔍 First launch, scanning music...")
                loadMusic()
            }
        }
    }

    /**
     * ✅ SDK의 MusicId API 사용
     */
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

                    // ✅ SDK의 MusicId API 사용
                    val musicFile = File(path)
                    val hasEffect = try {
                        MusicEffectManager.hasEffectFor(musicFile)
                    } catch (e: Exception) {
                        Log.e("MusicPlayerVM", "Failed to check effect: ${e.message}")
                        false
                    }

                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(path)
                    val art = retriever.embeddedPicture?.let {
                        val file = File(context.cacheDir, "${title.hashCode()}.jpg")
                        file.writeBytes(it)
                        file.absolutePath
                    }
                    retriever.release()

                    musicItems.add(MusicItem(title, artist, path, art, hasEffect))
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
     * ✅ File 객체로 effectEngine에 전달
     */
    fun playMusic(item: MusicItem) {
        _nowPlaying.value = item
        _isPlaying.value = true
        _duration.value = 0
        _currentPosition.value = 0

        // ✅ File 객체로 이펙트 로드
        val musicFile = File(item.filePath)
        effectEngine.reset()
        effectEngine.loadEffectsFor(musicFile, context)

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

    /** Light Stick 단일 타깃 주소 설정 (디바이스 탭 시 호출) */
    fun setTargetAddress(address: String?) {
        effectEngine.setTargetAddress(address)
    }

    /** 위치를 모니터링하며 타임라인 이펙트 전송 */
    @SuppressLint("MissingPermission")
    private fun monitorPosition() {
        viewModelScope.launch {
            var lastSecond = -1
            while (true) {
                val current = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                _currentPosition.value = current
                _duration.value = duration

                if (player.isPlaying && current / 1000 != lastSecond) {
                    // ✅ .efx 타임라인 구동
                    try {
                        effectEngine.processPosition(context, current)
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