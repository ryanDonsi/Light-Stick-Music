package com.lightstick.music.domain.music

import android.content.Context
import com.lightstick.music.core.util.Log
import com.lightstick.music.domain.usecase.music.PrecomputeAutoTimelinesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 자동 타임라인을 앱 전역 범위에서 생성한다.
 *
 * 예전에는 Splash 가 모든 곡의 생성이 끝날 때까지 다음 화면으로 넘어가지 않았다. 이제 Splash 는
 * 매칭까지만 하고 [start] 로 넘긴 뒤 바로 메인 화면으로 간다. 타임라인이 아직 없는 곡은 FFT 연출로
 * 재생되고, 생성이 끝나면 [completed] 로 알려 재생 중인 곡이면 바로 타임라인으로 바꾼다.
 *
 * 프로세스가 살아 있는 동안 계속 돌고, 프로세스가 끝나면 다음 실행 때 남은 곡부터 이어서 만든다
 * (이미 만든 곡은 [PrecomputeAutoTimelinesUseCase] 가 건너뛴다).
 */
object BackgroundTimelineGenerator {

    private const val TAG = "BgTimelineGen"

    /** 재생과 동시에 돌기 때문에 디코드 병렬 수를 낮게 잡는다(오디오 끊김·BLE 지연 방지) */
    private const val MAX_PARALLEL = 1

    data class Progress(
        val running: Boolean = false,
        val processed: Int = 0,
        val total: Int = 0,
        val currentName: String = "",
        /** 아직 차례가 오지 않은 곡의 절대 경로 */
        val waiting: Set<String> = emptySet(),
        /** 지금 만들고 있는 곡의 절대 경로 */
        val active: Set<String> = emptySet()
    ) {
        /** [path] 곡의 상태. 생성 대상이 아니거나 끝났으면 null */
        fun stateOf(path: String): FileState? =
            when (path) {
                in active  -> FileState.Generating
                in waiting -> FileState.Waiting
                else       -> null
            }
    }

    /** 곡 하나의 생성 상태 (목록 카드 표시용) */
    sealed interface FileState {
        data object Waiting : FileState
        data object Generating : FileState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _completed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** 타임라인 생성에 성공한 곡의 절대 경로 */
    val completed: SharedFlow<String> = _completed.asSharedFlow()

    /** 생성을 시작한다. 이미 돌고 있으면 무시한다(남은 곡은 다음 [start] 때 다시 확인된다). */
    @Synchronized
    fun start(context: Context, musicFiles: List<File>) {
        if (job?.isActive == true) {
            Log.d(TAG, "already running → skip")
            return
        }
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                PrecomputeAutoTimelinesUseCase().invoke(
                    context     = appContext,
                    musicFiles  = musicFiles,
                    maxParallel = MAX_PARALLEL,
                    onQueue     = { files ->
                        _progress.update { it.copy(waiting = files.mapTo(HashSet()) { f -> f.absolutePath }) }
                    },
                    onFileStart = { file ->
                        val path = file.absolutePath
                        _progress.update { it.copy(waiting = it.waiting - path, active = it.active + path) }
                    },
                    onFileDone  = { file, created ->
                        val path = file.absolutePath
                        _progress.update { it.copy(waiting = it.waiting - path, active = it.active - path) }
                        if (created) _completed.tryEmit(path)
                    },
                    onProgress  = { processed, total, name ->
                        _progress.update {
                            it.copy(running = true, processed = processed, total = total, currentName = name)
                        }
                    }
                )
                // 생성이 끝나 목록 곡들의 musicId 가 모두 캐시에 있으니 여기서 정리한다
                AutoTimelineCleanup.run(appContext, musicFiles)
            } catch (t: Throwable) {
                Log.e(TAG, "generation failed: ${t.message}")
            } finally {
                _progress.update {
                    it.copy(running = false, currentName = "", waiting = emptySet(), active = emptySet())
                }
            }
        }
    }
}
