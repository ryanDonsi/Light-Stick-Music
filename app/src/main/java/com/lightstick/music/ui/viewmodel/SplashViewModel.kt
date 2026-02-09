package com.lightstick.music.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.domain.effect.MusicEffectManager
import com.lightstick.music.data.model.InitializationResult
import com.lightstick.music.data.model.InitializationState
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.data.model.SplashState
import com.lightstick.music.data.local.storage.EffectPathPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SplashViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // ✅ Splash 화면 전체 상태 관리 (로고 → 권한 안내 → 권한 요청 → 초기화)
    private val _splashState = MutableStateFlow<SplashState>(SplashState.ShowLogo)
    val splashState: StateFlow<SplashState> = _splashState.asStateFlow()

    // 기존 초기화 상태 (내부적으로만 사용)
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
        // Activity에서 시스템 권한 요청을 수행하도록 신호만 보냄
        // 실제 권한 요청은 SplashActivity의 requestAllPermissions()에서 처리
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
        // Activity에서 처리
    }

    /**
     * 전체 초기화 시작
     */
    fun startInitialization() {
        viewModelScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // 1단계: 음악 파일 스캔 및 Music ID 계산
                val musicList = scanMusicFiles()

                // 2단계: Effects 폴더 자동 설정
                _state.value = InitializationState.ConfiguringEffectsDirectory
                _splashState.value = SplashState.Initializing(InitializationState.ConfiguringEffectsDirectory)

                val configured = EffectPathPreferences.autoConfigureEffectsDirectory(context)

                if (!configured) {
                    Log.w("InitVM", "⚠️ Auto-configuration failed, but continuing...")
                }

                // 3단계: Effects 스캔
                val effectCount = scanEffectFiles()

                // 4단계: 매칭
                val matchedList = matchEffects(musicList)

                val duration = System.currentTimeMillis() - startTime

                // 완료
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

                Log.d("InitVM", "✅ Initialization completed in ${duration}ms")

            } catch (e: Exception) {
                Log.e("InitVM", "❌ Initialization failed: ${e.message}", e)
                val errorState = InitializationState.Error(e.message ?: "Unknown error")
                _state.value = errorState
                _splashState.value = SplashState.Initializing(errorState)
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

        val musicItems = mutableListOf<MusicItem>()
        val totalFiles = mutableListOf<String>()

        // 먼저 전체 파일 수 확인
        resolver.query(uri, projection, selection, null, sort)?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                totalFiles.add(cursor.getString(dataCol))
            }
        }

        Log.d("InitVM", "📀 Found ${totalFiles.size} music files")
        _state.value = InitializationState.ScanningMusic(0, totalFiles.size)
        _splashState.value = SplashState.Initializing(InitializationState.ScanningMusic(0, totalFiles.size))

        // Music ID 계산하면서 스캔
        resolver.query(uri, projection, selection, null, sort)?.use { cursor ->
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            var index = 0
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol)
                val metaTitle = cursor.getString(titleCol)
                val fileName = cursor.getString(nameCol) ?: "Unknown"
                val title = if (!metaTitle.isNullOrBlank()) {
                    metaTitle
                } else {
                    fileName.substringBeforeLast(".")
                }
                val artist = cursor.getString(artistCol) ?: "Unknown"

                // 진행 상황 업데이트
                index++
                val calcState = InitializationState.CalculatingMusicIds(index, totalFiles.size)
                _state.value = calcState
                _splashState.value = SplashState.Initializing(calcState)

                val retriever = MediaMetadataRetriever()
                var art: String? = null
                var duration: Long = 0L

                try {
                    retriever.setDataSource(path)

                    // 앨범아트 추출
                    art = retriever.embeddedPicture?.let {
                        val file = File(context.cacheDir, "${title.hashCode()}.jpg")
                        file.writeBytes(it)
                        file.absolutePath
                    }

                    // Duration 추출
                    val durationStr =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = durationStr?.toLongOrNull() ?: 0L

                } catch (e: Exception) {
                    Log.e("InitVM", "Failed to extract metadata: ${e.message}")
                } finally {
                    retriever.release()
                }

                musicItems.add(
                    MusicItem(
                        title = title,
                        artist = artist,
                        filePath = path,
                        albumArtPath = art,
                        hasEffect = false,
                        duration = duration
                    )
                )

                // UI 업데이트를 위한 짧은 지연
                if (index % 10 == 0) {
                    delay(10)
                }
            }
        }

        Log.d("InitVM", "✅ Scanned ${musicItems.size} music files")
        musicItems
    }

    /**
     * 2단계: Effect 파일 스캔
     */
    private suspend fun scanEffectFiles(): Int = withContext(Dispatchers.IO) {
        val scanState = InitializationState.ScanningEffects(0, 0)
        _state.value = scanState
        _splashState.value = SplashState.Initializing(scanState)

        // SAF를 통해 초기화
        MusicEffectManager.initializeFromSAF(context)
        val effectCount = MusicEffectManager.getLoadedEffectCount()

        val completedScanState = InitializationState.ScanningEffects(effectCount, effectCount)
        _state.value = completedScanState
        _splashState.value = SplashState.Initializing(completedScanState)

        Log.d("InitVM", "✅ Scanned $effectCount effect files")
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

                // UI 업데이트를 위한 짧은 지연
                if (index % 5 == 0) {
                    delay(10)
                }

                item.copy(hasEffect = hasEffect)
            }

            val matchedCount = matchedList.count { it.hasEffect }
            Log.d("InitVM", "✅ Matched $matchedCount / ${musicList.size} files")

            matchedList
        }

    /**
     * 초기화 결과를 SharedPreferences에 캐싱
     */
    fun saveInitializationResult() {
        val result = _result.value ?: return

        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_initialized", true)
            .putInt("music_count", result.musicList.size)
            .putInt("effect_count", result.effectCount)
            .putInt("matched_count", result.matchedCount)
            .putLong("last_init_time", System.currentTimeMillis())
            .apply()

        Log.d("InitVM", "💾 Saved initialization result")
    }
}