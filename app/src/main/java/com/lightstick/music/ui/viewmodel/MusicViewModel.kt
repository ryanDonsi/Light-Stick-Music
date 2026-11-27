package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
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
import com.lightstick.music.data.local.preferences.SectionOverlayPreferences
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.music.domain.music.AutoTimelineConfig
import com.lightstick.music.domain.music.AutoTimelineStorage
import com.lightstick.music.domain.music.effectmatchingengine.EffectMatchingEngineRouter
import com.lightstick.music.domain.music.sectiondetector.meta.SectionMeta
import com.lightstick.music.domain.music.sectiondetector.meta.SectionMetaStorage
import com.lightstick.music.domain.music.FftAudioProcessor
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.domain.music.MusicIdCache
import com.lightstick.music.domain.music.createFftPlayer
import com.lightstick.music.domain.usecase.device.ObserveDeviceStatesUseCase
import com.lightstick.music.domain.usecase.music.HandleSeekUseCase
import com.lightstick.device.ConnectionState
import com.lightstick.music.domain.usecase.music.LoadEfxUseCase
import com.lightstick.music.domain.usecase.music.ProcessFFTUseCase
import com.lightstick.music.domain.usecase.music.UpdatePlaybackPositionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

        /** 화면용 위치 갱신 주기 = 50ms × 4 = 200ms */
        private const val UI_POSITION_EVERY_TICKS = 4

        /** 새 기기 연결 후 타임라인 동기화까지 대기 (연결 애니메이션 1.2초 + 여유) */
        private const val NEW_DEVICE_SYNC_DELAY_MS = 1_500L
    }

    private val context get() = getApplication<Application>().applicationContext

    private val _isAutoModeEnabled = MutableStateFlow(true)
    val isAutoModeEnabled: StateFlow<Boolean> = _isAutoModeEnabled.asStateFlow()

    // 권한 확인은 시스템 호출이라 오디오 버퍼마다 하지 않고 1초 동안 결과를 재사용한다
    @Volatile private var btPermissionCheckedAt = 0L
    @Volatile private var btPermissionGranted = false

    private fun hasBtPermissionCached(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - btPermissionCheckedAt > 1_000L) {
            btPermissionGranted = PermissionManager.hasBluetoothConnectPermission(context)
            btPermissionCheckedAt = now
        }
        return btPermissionGranted
    }

    val audioProcessor = FftAudioProcessor(
        // FFT 색상을 보낼 상황이 아니면 FFT 계산 자체를 건너뛴다
        shouldAnalyze = {
            _isAutoModeEnabled.value &&
                !EffectEngineController.isTimelineActive() &&
                hasBtPermissionCached()
        }
    ) { band ->
        try { processFFTUseCase(context, band) }
        catch (e: Exception) { Log.e(TAG, "FFT effect send failed: ${e.message}") }
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

    private val _currentSections = MutableStateFlow<List<SectionMeta>?>(null)
    val currentSections: StateFlow<List<SectionMeta>?> = _currentSections.asStateFlow()

    // 이 곡의 팔레트가 선택한 색 계열 이름(EffectMatchingEngine.Palette.colorFamily, 예
    // "PURPLE"/"RED") — Effect Info 오버레이의 PALETTE 배지 표시용. SectionMeta는 섹션 감지
    // 결과만 담고, 팔레트 관련 값은 EffectMatchingEngine에서 직접 가져와 별도로 보관한다.
    private val _currentColorFamily = MutableStateFlow<String?>(null)
    val currentColorFamily: StateFlow<String?> = _currentColorFamily.asStateFlow()

    private val _isSectionOverlayEnabled = MutableStateFlow(false)
    val isSectionOverlayEnabled: StateFlow<Boolean> = _isSectionOverlayEnabled.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) publishPosition()
            if (state == Player.STATE_ENDED) onTrackEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.d(TAG, "onPlayWhenReadyChanged: playWhenReady=$playWhenReady reason=$reason")

            when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                    // AUDIO_FOCUS_LOSS는 포커스를 "잃을 때"뿐 아니라 통화 종료 등으로
                    // 다시 "되찾을 때"도 같은 reason으로 전달된다(ExoPlayer는 GAIN 전용
                    // reason을 따로 두지 않음) — playWhenReady로 둘을 구분해야 한다.
                    // 과거엔 이 분기에서 항상 false로 취급해서 통화 종료 후 자동 재개가
                    // isPlaying/이펙트에 반영되지 않는 문제가 있었다.
                    if (playWhenReady) {
                        Log.i(TAG, "전화 종료 등으로 오디오 포커스 회복 → 자동 재개 감지")
                        _isPlaying.value = true
                        if (_isAutoModeEnabled.value) {
                            resyncEffectsOnResume()
                        }
                    } else {
                        Log.i(TAG, "전화/다른 앱으로 인한 자동 일시정지 감지 → 이펙트 일시정지")
                        if (_isAutoModeEnabled.value) {
                            EffectEngineController.pauseEffects(context)
                        }
                        _isPlaying.value = false
                    }
                }
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> {
                    Log.d(TAG, "사용자 요청으로 인한 재생/일시정지 → togglePlayPause에서 처리됨")
                }
                else -> {
                    Log.d(TAG, "기타 원인으로 인한 상태 변화: $reason")
                    if (!playWhenReady && _isPlaying.value && _isAutoModeEnabled.value) {
                        Log.i(TAG, "일시정지 감지 → 이펙트 일시정지")
                        EffectEngineController.pauseEffects(context)
                    }
                    _isPlaying.value = playWhenReady
                }
            }
            updateNotificationProgress()
        }
    }

    init {
        EffectEngineController.reset()

        _isAutoModeEnabled.value      = AutoModePreferences.isAutoModeEnabled(getApplication())
        _isSectionOverlayEnabled.value = SectionOverlayPreferences.isEnabled(getApplication())

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

        // 곡 재생 중 새로 연결된 기기에도 현재 타임라인을 실어 준다
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeDeviceStateEvents().collect { event ->
                if (event.state is ConnectionState.Connected) syncTimelineToNewDevice(event.mac)
            }
        }

        viewModelScope.launch {
            MusicPlayerCommandBus.commands.collect { command ->
                when (command) {
                    is MusicPlayerCommandBus.Command.TogglePlay -> togglePlayPause()
                    is MusicPlayerCommandBus.Command.Play       -> if (!player.isPlaying) togglePlayPause()
                    is MusicPlayerCommandBus.Command.Pause      -> if (player.isPlaying) togglePlayPause()
                    is MusicPlayerCommandBus.Command.Next       -> playNext()
                    is MusicPlayerCommandBus.Command.Previous   -> playPrevious()
                    is MusicPlayerCommandBus.Command.SeekTo     -> seekTo(command.position)
                }
            }
        }

        // EFX 폴더 읽기(SAF 조회·복사·파싱)는 IO에서 끝낸 뒤 목록을 만든다 — hasEffect 판정이 EFX 맵에 의존
        viewModelScope.launch {
            withContext(Dispatchers.IO) { initializeEffects() }
            loadMusic()
        }

        player.addListener(playerListener)
    }

    private fun initializeEffects() {
        Log.d(TAG, "initializeEffects: start (thread=${Thread.currentThread().name})")
        MusicEffectManager.initializeFromSAF(context)
        Log.d(TAG, "initializeEffects: done — ${MusicEffectManager.getLoadedEffectCount()} EFX loaded")
    }

    /** 곡별 메타데이터 캐시 (경로 → 크기·수정 시각이 같으면 MediaMetadataRetriever 생략) */
    private data class CachedMeta(
        val size: Long,
        val modified: Long,
        val albumArtPath: String?,
        val duration: Long
    )
    private val metaCache = java.util.concurrent.ConcurrentHashMap<String, CachedMeta>()

    /** 마지막 스캔 시점의 MediaStore 세대 + EFX 맵 버전. 같으면 onResume 재스캔을 건너뛴다. */
    private var lastScanKey: String? = null
    private var loadMusicJob: Job? = null

    private fun currentScanKey(): String? = try {
        val volumes = MediaStore.getExternalVolumeNames(context).sorted().joinToString(",") { volume ->
            "$volume=${MediaStore.getVersion(context, volume)}/${MediaStore.getGeneration(context, volume)}"
        }
        "$volumes:${MusicEffectManager.mapVersion}"
    } catch (e: Exception) {
        null // 판단 불가 → 매번 스캔(기존 동작)
    }

    /**
     * @param force true 면 MediaStore/EFX 변화가 없어도 다시 스캔한다.
     */
    fun loadMusic(force: Boolean = false) {
        if (!PermissionManager.hasStoragePermission(context)) {
            Log.w(TAG, "loadMusic skipped: no storage permission")
            return
        }
        loadMusicJob?.cancel()
        loadMusicJob = viewModelScope.launch {
            val scanKey = withContext(Dispatchers.IO) { currentScanKey() }
            if (!force && scanKey != null && scanKey == lastScanKey && _musicList.value.isNotEmpty()) {
                Log.d(TAG, "loadMusic: MediaStore/EFX 변화 없음 → 스캔 생략")
                return@launch
            }
            val musicItems = withContext(Dispatchers.IO) {
                val resolver    = context.contentResolver
                val allowedDirs = FileHelper.allowedMusicDirs()
                val items       = mutableListOf<MusicItem>()
                val seenPaths   = mutableSetOf<String>()

                // Audio MediaStore (mp3, m4a, flac, aac)
                val audioProjection = arrayOf(
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DATA
                )
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    audioProjection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val titleCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val nameCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val dataCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol) ?: continue
                        if (!seenPaths.add(path)) continue
                        addMusicItemIfValid(
                            path, cursor.getString(titleCol),
                            cursor.getString(nameCol), cursor.getString(artistCol),
                            allowedDirs, items
                        )
                    }
                }

                // 파일시스템 직접 탐색 (mp4) — MediaStore 분류와 무관하게 확실히 수집
                allowedDirs.map { File(it) }.filter { it.exists() }.forEach { dir ->
                    ensureActive()
                    dir.walkTopDown()
                        .onEnter { !FileHelper.isRecordingsPath(it.absolutePath) }
                        .filter { it.isFile && it.extension.lowercase() == "mp4" }
                        .forEach { file ->
                            if (seenPaths.add(file.absolutePath)) {
                                addMusicItemIfValid(
                                    file.absolutePath, null, file.name, null,
                                    allowedDirs, items
                                )
                            }
                        }
                }

                items
            }

            _musicList.value = musicItems
            lastScanKey = scanKey
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

    private fun addMusicItemIfValid(
        path: String,
        metaTitle: String?,
        fileName: String?,
        metaArtist: String?,
        allowedDirs: List<String>,
        items: MutableList<MusicItem>
    ) {
        val file = File(path)
        if (file.extension.lowercase() !in AppConstants.SUPPORTED_AUDIO_EXTENSIONS) return
        if (allowedDirs.none { path.startsWith(it) }) return
        if (FileHelper.isRecordingsPath(path)) return

        val title  = if (!metaTitle.isNullOrBlank()) metaTitle
                     else fileName?.substringBeforeLast(".") ?: file.nameWithoutExtension
        val artist = metaArtist ?: "Unknown"

        val hasEffect = try {
            MusicEffectManager.hasEffectFor(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check effect: ${e.message}")
            false
        }

        val size = file.length()
        val modified = file.lastModified()
        metaCache[path]?.takeIf { it.size == size && it.modified == modified }?.let { cached ->
            items.add(
                MusicItem(
                    title        = title,
                    artist       = artist,
                    filePath     = path,
                    albumArtPath = cached.albumArtPath,
                    hasEffect    = hasEffect,
                    duration     = cached.duration
                )
            )
            return
        }

        val retriever = MediaMetadataRetriever()
        var art: String? = null
        var duration = 0L
        try {
            retriever.setDataSource(path)
            art = retriever.embeddedPicture?.let { bytes ->
                val cacheFile = FileHelper.albumArtCacheFile(context, path)
                if (!cacheFile.exists() || cacheFile.length() != bytes.size.toLong()) cacheFile.writeBytes(bytes)
                cacheFile.absolutePath
            }
            duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract metadata: ${e.message}")
        } finally {
            retriever.release()
        }
        metaCache[path] = CachedMeta(size, modified, art, duration)

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

    // 자동모드용 타임라인을 musicFile 기준으로 (재)로드한다. playMusic()/toggleAutoMode()/
    // togglePlayPause() 재개 경로에서 공용으로 쓴다 — 셋 다 "이 곡의 자동 타임라인을 디바이스에
    // 새로 실어야 한다"는 같은 필요에서 나온 호출이라 로직을 하나로 모았다.
    // 파일 해시·EFX 파싱·타임라인 파일 읽기가 들어 있어 IO 스레드에서 실행한다.
    private suspend fun loadTimelineForCurrentTrack(musicFile: File): Unit = withContext(Dispatchers.IO) {
        EffectEngineController.reset()

        if (MusicEffectManager.hasEffectFor(musicFile)) {
            loadEfxUseCase(context, musicFile)
        } else {
            val musicId = MusicIdCache.get(musicFile)
            val ver     = AutoTimelineConfig.VERSION
            val storage = AutoTimelineStorage(version = ver)
            val frames  = storage.load(context, musicId)

            if (!frames.isNullOrEmpty()) {
                EffectEngineController.loadTimelineFromFrames(context, frames)
            } else {
                Log.w(TAG, "자동 타임라인 없음 (v=$ver) → FFT 폴백")
            }
        }
    }

    private var playJob: Job? = null

    fun playMusic(item: MusicItem) {
        _nowPlaying.value      = item
        _isPlaying.value       = true
        _duration.value        = 0
        _currentPosition.value = 0

        playJob?.cancel()
        playJob = viewModelScope.launch {
            val musicFile = File(item.filePath)

            // 타임라인 로드(해시·파일 읽기)는 IO에서 끝낸 뒤 재생을 시작한다 — 메인 스레드 블로킹 방지
            if (_isAutoModeEnabled.value) {
                loadTimelineForCurrentTrack(musicFile)
            } else {
                withContext(Dispatchers.IO) { EffectEngineController.reset() }
            }

            loadSectionMeta(musicFile)
            startPlayback(item)
        }
    }

    // 섹션 메타 비동기 로드 (오버레이용)
    private fun loadSectionMeta(musicFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val ver      = AutoTimelineConfig.VERSION
            val musicId  = MusicIdCache.getOrNull(musicFile) ?: run {
                _currentSections.value = null
                _currentColorFamily.value = null
                return@launch
            }
            val sections = SectionMetaStorage(ver).load(context, musicId)
            _currentSections.value = sections
            // colorFamily는 SectionMeta에 들어있지 않다 — 팔레트 관련 값은 전부
            // EffectMatchingEngine이 관리하므로, musicStyle이 있으면 여기서 직접 물어본다
            // (musicId+musicStyle이 있으면 buildPalette()가 결정론적으로 같은 값을 반환한다).
            _currentColorFamily.value = sections?.firstOrNull()?.musicStyle?.let {
                EffectMatchingEngineRouter.createEngine().buildPalette(musicId, it).colorFamily
            }
        }
    }

    private fun startPlayback(item: MusicItem) {
        // 서비스가 이미 떠 있으면(알림의 다음 곡 등 백그라운드 호출 포함) 알림 갱신만 한다
        ServiceController.updateNotificationProgress(
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
            if (_isAutoModeEnabled.value) {
                // 일시정지 시 off를 보내지 않는다(기기는 마지막 이펙트 상태 유지).
                // 재개 시점의 현재 위치 이펙트 재전송은 resyncEffectsOnResume()에서 처리한다.
                EffectEngineController.pauseEffects(context)
            }
        } else {
            player.play()
            _isPlaying.value = true
            Log.d(TAG, "Resume: begin pos=${player.currentPosition}ms")
            if (_isAutoModeEnabled.value) {
                resyncEffectsOnResume()
            }
        }
        updateNotificationProgress()
    }

    /**
     * 재생 재개 시 이펙트/타임라인 위치를 현재 재생 위치로 재동기화한다.
     * 사용자가 직접 재생 버튼을 눌렀을 때(togglePlayPause)와, 통화 종료 등으로
     * 오디오 포커스를 되찾아 ExoPlayer가 자동으로 재생을 재개했을 때
     * (playerListener의 AUDIO_FOCUS_LOSS reason, playWhenReady=true) 모두 동일하게 필요하다.
     */
    private fun resyncEffectsOnResume() {
        viewModelScope.launch { resyncEffectsOnResumeInternal() }
    }

    private suspend fun resyncEffectsOnResumeInternal() {
        // SDK 업데이트(pendingResyncAfterManualEffect 도입) 이후로는 sendEffect()가
        // 로드된 타임라인을 보존한 채 전송만 억제하고, resumeTimeline() 호출 시 SDK가
        // 내부적으로 현재 프레임을 자동 재전송하므로 앱에서 별도 재동기화가 불필요하다.
        // 그래도 기기가 실제로 타임라인이 없다고 응답하면(연결 재시도 등 다른 경로로
        // 비워진 경우) 재로드한다 — 앱이 자체 추론하지 않고 SDK 상태를 직접 확인한다.
        val needsReload = EffectEngineController.needsTimelineReload(context)
        Log.d(TAG, "Resume: needsTimelineReload=$needsReload")
        if (needsReload) {
            _nowPlaying.value?.let { loadTimelineForCurrentTrack(File(it.filePath)) }
            Log.d(TAG, "Resume: timeline reload (device reports timeline not loaded)")
        }
        // 현재 위치에 해당하는 이펙트가 있으면 새로 보내고, 없으면 resume만 한다.
        // (resume 전에 보내야 SDK가 전송 억제를 풀고 현재 프레임을 재전송한다)
        EffectEngineController.resendActiveEffectBeforeResume(context, player.currentPosition)
        EffectEngineController.resumeEffects(context)
        // 전화 중단 후 재개 등 상황에서 이펙트 재동기화
        // 1. 현재 위치로 Seek 실행 (Timeline 인덱스 초기화)
        val currentPos = player.currentPosition
        try {
            handleSeekUseCase(context, currentPos)
            Log.d(TAG, "Resume: seek to $currentPos")
        } catch (e: Exception) {
            Log.e(TAG, "Resume position sync failed: ${e.message}")
        }
        // 2. 즉시 위치 업데이트 (타임라인을 현재 위치로 강제 동기화)
        try {
            updatePlaybackPositionUseCase(context, currentPos)
            Log.d(TAG, "Resume: sync position at $currentPos")
        } catch (e: Exception) {
            Log.e(TAG, "Resume position update failed: ${e.message}")
        }
    }

    private fun syncTimelineToNewDevice(mac: String) {
        if (!_isAutoModeEnabled.value || _nowPlaying.value == null) return
        viewModelScope.launch {
            // 연결 직후 연결 애니메이션(약 1.25초, 같은 기기에 playTimeline→stopTimeline)이 끝난 뒤 보낸다
            delay(NEW_DEVICE_SYNC_DELAY_MS)
            if (_nowPlaying.value == null) return@launch
            val pos = player.currentPosition
            val playing = player.isPlaying
            withContext(Dispatchers.IO) {
                EffectEngineController.syncTimelineToDevice(context, mac, pos, playing)
            }
        }
    }

    fun toggleSectionOverlay(): Boolean {
        val newState = SectionOverlayPreferences.toggle(getApplication())
        _isSectionOverlayEnabled.value = newState
        return newState
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
                viewModelScope.launch {
                    val musicFile = File(currentMusic.filePath)
                    loadTimelineForCurrentTrack(musicFile)

                    val currentPos = player.currentPosition
                    try {
                        updatePlaybackPositionUseCase(context, currentPos)
                    } catch (e: Exception) {
                        Log.e(TAG, "AUTO ON sync failed: ${e.message}")
                    }
                }
            }
        }

        return newState
    }

    /** 현재 곡의 목록 위치. 재스캔으로 MusicItem 이 새로 만들어져도 찾도록 경로로 비교한다. */
    private fun currentIndex(): Int {
        val path = _nowPlaying.value?.filePath ?: return -1
        return _musicList.value.indexOfFirst { it.filePath == path }
    }

    fun playNext() {
        val index = currentIndex()
        if (index < 0) return  // 현재 곡이 목록에 없으면 첫 곡으로 튀지 않는다
        _musicList.value.getOrNull(index + 1)?.let { playMusic(it) }
    }

    fun playPrevious() {
        val index = currentIndex()
        if (index < 0) return
        _musicList.value.getOrNull(index - 1)?.let { playMusic(it) }
    }

    /**
     * 곡이 끝났을 때. 다음 곡이 있으면 이어서 재생하고, 마지막 곡이면 재생 상태를 끄고
     * 타임라인을 멈춘다(예전에는 isPlaying=true 로 남아 화면/알림이 재생 중으로 보였다).
     */
    private fun onTrackEnded() {
        val index = currentIndex()
        val next = if (index >= 0) _musicList.value.getOrNull(index + 1) else null
        if (next != null) {
            playMusic(next)
            return
        }
        _isPlaying.value = false
        if (_isAutoModeEnabled.value) EffectEngineController.pauseEffects(context)
        player.pause()
        player.seekTo(0)
        _currentPosition.value = 0
        updateNotificationProgress()
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

    /** C.TIME_UNSET(음수) 등 준비 전 값은 0으로 */
    private fun safeDurationMs(): Int = player.duration.let { if (it < 0) 0 else it.toInt() }

    private fun publishPosition() {
        _currentPosition.value = player.currentPosition.toInt()
        _duration.value        = safeDurationMs()
    }

    /**
     * 재생 중에만 돈다. BLE 위치 동기화는 [AppConstants.POSITION_MONITOR_INTERVAL_MS](50ms)마다,
     * 화면용 위치/길이 StateFlow 는 [UI_POSITION_EVERY_TICKS] 틱(200ms)마다 갱신한다.
     * 일시정지/정지 상태에서는 마지막 값을 한 번 반영하고 멈춘다.
     */
    @SuppressLint("MissingPermission")
    private suspend fun monitorPosition() {
        _isPlaying.collectLatest { playing ->
            publishPosition()
            if (!playing) return@collectLatest

            var tick = 0
            while (true) {
                val current = player.currentPosition

                if (player.isPlaying && _isAutoModeEnabled.value) {
                    try { updatePlaybackPositionUseCase(context, current) }
                    catch (e: Exception) {
                        Log.e(TAG, "updatePlaybackPosition() failed: ${e.message}")
                    }
                }

                if (tick % UI_POSITION_EVERY_TICKS == 0) publishPosition()
                tick++

                delay(AppConstants.POSITION_MONITOR_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        MusicPlaybackState.reset()
        player.release()
        // 플레이어가 해제되면 알림 버튼을 받을 곳이 없으므로 알림(포그라운드 서비스)도 내린다.
        // (플레이어를 서비스로 옮기는 구조 변경은 B1 — 별도 작업)
        ServiceController.stopMusicEffectService(context)
        super.onCleared()
    }
}
