package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.domain.music.AutoTimelineConfig
import com.lightstick.music.domain.music.AutoTimelineStorage
import com.lightstick.music.domain.music.MusicIdCache
import com.lightstick.music.domain.music.AutoTimelineGenerator
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat
import com.lightstick.music.domain.music.AutoTimelineGeneratorWithSections
import com.lightstick.music.domain.music.sectiondetector.meta.SectionMetaStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * 초기화 시 자동 타임라인 생성 UseCase
 *
 *  핵심 보장:
 * - AutoTimelineConfig.VERSION 기준으로 "해당 버전(vX)" 파일만 생성/저장
 * - 기존 파일이 있으면 스킵 (테스트 강제 재생성 옵션 제외)
 * - 테스트 시에는 해당 버전(vX) 파일만 삭제 후 재생성 가능
 *
 * [수정] onProgress 파라미터에 currentFileName 추가
 * - Splash UI에서 현재 처리 중인 파일명을 실시간 표시
 */
class PrecomputeAutoTimelinesUseCase @Inject constructor() {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        /**
         *  테스트 시: true면 해당 버전(vX) 파일을 전부 삭제 후 재생성
         */
        private const val TEST_FORCE_REGENERATE = false

        /**
         * 동시 처리할 최대 파일 수. MediaCodec 인스턴스 수와 CPU 부하를 고려한 상한.
         * 실제 값은 [parallelCountFor]로 기기 힙 크기에 맞춰 줄인다.
         */
        private const val PARALLEL_COUNT = 3

        /**
         * 곡 하나 분석에 PCM 만으로 최대 약 106MB(10분, 44.1kHz 모노 float)를 쓴다.
         * 힙이 작은 기기에서 3곡을 동시에 돌리면 OOM 이 날 수 있어 힙 크기로 병렬 수를 정한다.
         */
        private fun parallelCountFor(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val heapMb = am?.memoryClass ?: 256  // largeHeap 미사용이므로 memoryClass 기준
            return when {
                heapMb < 256 -> 1
                heapMb < 512 -> 2
                else         -> PARALLEL_COUNT
            }
        }
    }

    /**
     * [수정] onProgress: currentFileName 추가
     * - processed: 처리 완료 수
     * - total: 전체 파일 수
     * - currentFileName: 현재 처리 중인 파일명 (확장자 제외)
     */
    suspend operator fun invoke(
        context: Context,
        musicFiles: List<File>,
        /** 동시 처리 수 상한. 실제 값은 이 값과 기기 힙 기준 값 중 작은 쪽 */
        maxParallel: Int = PARALLEL_COUNT,
        /** 곡 하나가 끝날 때마다(성공 여부와 함께) 호출 — 재생 중인 곡이면 바로 적용하기 위함 */
        onFileDone: (file: File, created: Boolean) -> Unit = { _, _ -> },
        /** 실제로 만들 곡 목록이 정해지면 한 번 호출 (이미 있는 곡은 빠진 목록) */
        onQueue: (files: List<File>) -> Unit = {},
        /** 곡 하나의 생성을 시작할 때 호출 */
        onFileStart: (file: File) -> Unit = {},
        onProgress: (processed: Int, total: Int, currentFileName: String) -> Unit
    ) {
        val version = AutoTimelineConfig.VERSION
        val paletteSize = AutoTimelineConfig.PALETTE_SIZE

        val storage = AutoTimelineStorage(version = version)

        if (TEST_FORCE_REGENERATE) {
            runCatching {
                storage.clearAll(context)
            }.onFailure {
                Log.e(TAG, "Failed to clear timelines: ${it.message}")
            }
        }

        // 통합 생성기 (AutoTimelineGeneratorBeat)는 AutoTimelineConfig 설정으로 모든 버전 지원
        val generator: AutoTimelineGenerator = AutoTimelineGeneratorBeat()
        val sectionStorage = if (generator is AutoTimelineGeneratorWithSections) SectionMetaStorage(version) else null

        Log.d(TAG, "생성기: AutoTimelineGeneratorBeat (effectRule=${AutoTimelineConfig.EFFECT_RULE_VERSION})")

        // 이미 생성된 파일은 제외한 실제 처리 대상만 추려 정확한 total 확보
        val filesToProcess = if (TEST_FORCE_REGENERATE) {
            musicFiles
        } else {
            musicFiles.filter { file ->
                val musicId = MusicIdCache.getOrNull(file)
                    ?: return@filter true  // musicId 실패 시 처리 목록에 포함(→ failed 처리)
                !storage.exists(context, musicId)
            }
        }

        val total     = filesToProcess.size
        onQueue(filesToProcess)
        onProgress(0, total, "")  // 정확한 total을 UI에 즉시 전달

        if (filesToProcess.isEmpty()) return

        val processed = AtomicInteger(0)
        val created   = AtomicInteger(0)
        val failed    = AtomicInteger(0)

        val parallel  = minOf(parallelCountFor(context), maxParallel.coerceAtLeast(1))
        Log.d(TAG, "parallel=$parallel")
        val semaphore = Semaphore(parallel)

        coroutineScope {
            filesToProcess.map { file ->
                async {
                    semaphore.withPermit {
                        val p = processed.incrementAndGet()
                        onProgress(p, total, file.nameWithoutExtension)
                        onFileStart(file)

                        val musicId = MusicIdCache.getOrNull(file)
                        if (musicId == null) {
                            failed.incrementAndGet()
                            onFileDone(file, false)
                            return@withPermit
                        }

                        var ok = false
                        try {
                            val (frames, sections) = if (generator is AutoTimelineGeneratorWithSections) {
                                generator.generateWithSections(file.absolutePath, musicId, paletteSize)
                            } else {
                                generator.generate(file.absolutePath, musicId, paletteSize) to emptyList()
                            }

                            if (frames.isEmpty()) {
                                failed.incrementAndGet()
                                Log.w(TAG, "empty frames -> skip save file=${file.name} musicId=$musicId")
                            } else {
                                storage.save(context, musicId, frames)
                                if (sections.isNotEmpty()) {
                                    sectionStorage?.save(context, musicId, sections)
                                }
                                created.incrementAndGet()
                                ok = true
                            }
                        } catch (t: Throwable) {
                            failed.incrementAndGet()
                            Log.e(TAG, "failed v$version file=${file.name} err=${t.message}")
                        } finally {
                            // 각 파일 처리 완료 후 즉시 GC: V3의 메모리 누수 방지
                            Runtime.getRuntime().gc()
                        }
                        onFileDone(file, ok)
                    }
                }
            }.awaitAll()
        }

    }
}
