package com.lightstick.music.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.core.constants.PrefsKeys
import com.lightstick.music.core.util.FileHelper
import com.lightstick.music.core.util.Log
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.data.model.InitializationResult
import com.lightstick.music.data.model.InitializationState
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.data.model.SplashState
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.domain.usecase.music.PrecomputeAutoTimelinesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

@HiltViewModel
class SplashViewModel @Inject constructor(
    application: Application,
    private val precomputeAutoTimelinesUseCase: PrecomputeAutoTimelinesUseCase
) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _splashState = MutableStateFlow<SplashState>(SplashState.ShowLogo)
    val splashState: StateFlow<SplashState> = _splashState.asStateFlow()

    private val _state = MutableStateFlow<InitializationState>(InitializationState.Idle)
    val state: StateFlow<InitializationState> = _state.asStateFlow()

    private val _result = MutableStateFlow<InitializationResult?>(null)
    val result: StateFlow<InitializationResult?> = _result.asStateFlow()

    /**
     * 로고 화면 표시 완료 → 권한 안내 화면으로 전환
     */
    fun onLogoTimeout() {
        _splashState.value = SplashState.ShowPermissionGuide
    }

    /**
     * 권한 안내 확인 버튼 클릭 → Activity에서 시스템 권한 요청 수행
     * (시스템 권한 요청은 Activity에서 처리)
     */
    fun onPermissionGuideConfirmed() {
    }

    /**
     * 권한 허용 → 앱 초기화 시작
     */
    fun onPermissionAllowed() {
        _splashState.value = SplashState.Initializing(InitializationState.Idle)
    }

    /**
     * 권한 거부 → 앱 종료 처리
     */
    fun onPermissionDenied() {
    }

    /**
     * 전체 초기화 시작
     */
    fun startInitialization() {
        viewModelScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                triggerMediaStoreScan()

                val musicList = scanMusicFiles()

                _state.value = InitializationState.ConfiguringEffectsDirectory
                _splashState.value = SplashState.Initializing(InitializationState.ConfiguringEffectsDirectory)

                val configured = EffectPathPreferences.autoConfigureEffectsDirectory(context)
                if (!configured) {
                    Log.w("InitVM", "Auto-configuration failed, but continuing...")
                }

                val effectCount = scanEffectFiles()

                val matchedList = matchEffects(musicList)

                val musicFiles = matchedList.map { File(it.filePath) }

                withContext(Dispatchers.Main.immediate) {
                    val st = InitializationState.PrecomputingTimelines(0, 0)
                    _state.value = st
                    _splashState.value = SplashState.Initializing(st)
                }

                withContext(Dispatchers.IO) {
                    precomputeAutoTimelinesUseCase(
                        context = context,
                        musicFiles = musicFiles,
                        onProgress = { processed, total2, fileName ->
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                val st = InitializationState.PrecomputingTimelines(processed, total2, fileName)
                                _state.value = st
                                _splashState.value = SplashState.Initializing(st)
                            }
                        }
                    )
                }

                val duration = System.currentTimeMillis() - startTime

                _result.value = InitializationResult(
                    musicList = matchedList,
                    effectCount = effectCount,
                    matchedCount = matchedList.count { it.hasEffect },
                    duration = duration
                )

                val completedState = InitializationState.Completed(
                    musicCount = matchedList.size,
                    effectCount = effectCount,
                    matchedCount = matchedList.count { it.hasEffect }
                )

                _state.value = completedState
                _splashState.value = SplashState.Initializing(completedState)

            } catch (e: Exception) {
                Log.e("InitVM", "Initialization failed: ${e.message}", e)
                val errorState = InitializationState.Error(e.message ?: "Unknown error")
                _state.value = errorState
                _splashState.value = SplashState.Initializing(errorState)
            }
        }
    }

    /**
     * 0단계: 파일시스템 직접 탐색 후 MediaStore 강제 인덱싱
     * - MediaStore 자동 스캔이 누락한 파일을 초기화 시점에 한 번 등록
     */
    private suspend fun triggerMediaStoreScan() = withContext(Dispatchers.IO) {
        val audioExtensions = setOf("mp3", "m4a", "flac", "aac", "ogg", "wav", "wma", "opus")
        val scanDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
        ).filter { it != null && it.exists() }

        val audioFiles = scanDirs
            .flatMap { dir ->
                dir.walkTopDown()
                    .onEnter { !FileHelper.isRecordingsPath(it.absolutePath) }
                    .filter { it.isFile && it.extension.lowercase() in audioExtensions }
                    .map { it.absolutePath }
                    .toList()
            }
            .distinct()

        if (audioFiles.isEmpty()) return@withContext

        suspendCancellableCoroutine { cont ->
            val remaining = AtomicInteger(audioFiles.size)
            MediaScannerConnection.scanFile(
                context,
                audioFiles.toTypedArray(),
                null
            ) { _, _ ->
                if (remaining.decrementAndGet() == 0) {
                    cont.resumeWith(Result.success(Unit))
                }
            }
        }
    }

    /**
     * 1단계: 음악 파일 스캔 및 Music ID 계산
     */
    private suspend fun scanMusicFiles(): List<MusicItem> = withContext(Dispatchers.IO) {
        _state.value = InitializationState.ScanningMusic(0, 0)
        _splashState.value = SplashState.Initializing(InitializationState.ScanningMusic(0, 0))

        val resolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val audioExtensions = setOf("mp3", "m4a", "flac", "aac", "ogg", "wav", "wma", "opus")
        val allowedDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
        ).mapNotNull { if (it != null && it.exists()) it.canonicalPath else null }

        // 1단계: 필터를 적용해 유효한 경로만 수집 → 정확한 total 확보
        data class RawEntry(val path: String, val title: String, val artist: String)

        val dataOnlyProjection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA
        )
        val validEntries = mutableListOf<RawEntry>()
        resolver.query(uri, dataOnlyProjection, selection, null, sort)?.use { cursor ->
            val titleCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val nameCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                if (File(path).extension.lowercase() !in audioExtensions) continue
                if (allowedDirs.none { path.startsWith(it) }) continue
                if (FileHelper.isRecordingsPath(path)) continue
                val metaTitle = cursor.getString(titleCol)
                val fileName  = cursor.getString(nameCol) ?: "Unknown"
                val title     = if (!metaTitle.isNullOrBlank()) metaTitle else fileName.substringBeforeLast(".")
                validEntries.add(RawEntry(path, title, cursor.getString(artistCol) ?: "Unknown"))
            }
        }

        val total = validEntries.size
        _state.value = InitializationState.ScanningMusic(total, total)
        _splashState.value = SplashState.Initializing(InitializationState.ScanningMusic(total, total))

        // 2단계: 필터된 파일만 메타데이터 추출
        val musicItems = mutableListOf<MusicItem>()
        val retriever = MediaMetadataRetriever()
        try {
            validEntries.forEachIndexed { index, entry ->
                val calcState = InitializationState.CalculatingMusicIds(index + 1, total)
                _state.value = calcState
                _splashState.value = SplashState.Initializing(calcState)

                var art: String? = null
                var duration: Long = 0L
                try {
                    retriever.setDataSource(entry.path)
                    art = retriever.embeddedPicture?.let {
                        val file = File(context.cacheDir, "${entry.title.hashCode()}.jpg")
                        file.writeBytes(it)
                        file.absolutePath
                    }
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = durationStr?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    Log.e("InitVM", "Failed to extract metadata: ${e.message}")
                }

                musicItems.add(
                    MusicItem(
                        title        = entry.title,
                        artist       = entry.artist,
                        filePath     = entry.path,
                        albumArtPath = art,
                        hasEffect    = false,
                        duration     = duration
                    )
                )
            }
        } finally {
            retriever.release()
        }

        musicItems
    }

    /**
     * 2단계: Effect 파일 스캔
     */
    private suspend fun scanEffectFiles(): Int = withContext(Dispatchers.IO) {
        val scanState = InitializationState.ScanningEffects(0, 0)
        _state.value = scanState
        _splashState.value = SplashState.Initializing(scanState)

        MusicEffectManager.initializeFromSAF(context)
        val effectCount = MusicEffectManager.getLoadedEffectCount()

        val completedScanState = InitializationState.ScanningEffects(effectCount, effectCount)
        _state.value = completedScanState
        _splashState.value = SplashState.Initializing(completedScanState)

        effectCount
    }

    /**
     * 3단계: 음악-이펙트 매칭
     */
    private suspend fun matchEffects(musicList: List<MusicItem>): List<MusicItem> =
        withContext(Dispatchers.IO) {
            val matchState = InitializationState.MatchingEffects(0, musicList.size)
            _state.value = matchState
            _splashState.value = SplashState.Initializing(matchState)

            val matchedList = musicList.mapIndexed { index, item ->
                val hasEffect = try {
                    val musicFile = File(item.filePath)
                    MusicEffectManager.hasEffectFor(musicFile)
                } catch (e: Exception) {
                    Log.e("InitVM", "Failed to check effect for ${item.title}: ${e.message}")
                    false
                }

                val updatedMatchState = InitializationState.MatchingEffects(index + 1, musicList.size)
                _state.value = updatedMatchState
                _splashState.value = SplashState.Initializing(updatedMatchState)

                item.copy(hasEffect = hasEffect)
            }

            matchedList
        }

    /**
     * 초기화 결과를 SharedPreferences에 캐싱
     */
    fun saveInitializationResult() {
        val result = _result.value ?: return

        context.getSharedPreferences(PrefsKeys.PREFS_APP_STATE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PrefsKeys.KEY_IS_INITIALIZED, true)
            .putInt(PrefsKeys.KEY_MUSIC_COUNT, result.musicList.size)
            .putInt(PrefsKeys.KEY_EFFECT_COUNT, result.effectCount)
            .putInt(PrefsKeys.KEY_MATCHED_COUNT, result.matchedCount)
            .putLong(PrefsKeys.KEY_LAST_INIT_TIME, System.currentTimeMillis())
            .apply()

    }
}
