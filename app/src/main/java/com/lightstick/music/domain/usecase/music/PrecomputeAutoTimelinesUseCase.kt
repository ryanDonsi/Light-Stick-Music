package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.efx.MusicId
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.domain.music.AutoTimelineConfig
import com.lightstick.music.domain.music.AutoTimelineStorage
import com.lightstick.music.domain.music.AutoTimelineGenerator
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v0
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v3
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v4
import com.lightstick.music.domain.music.SectionAwareGenerator
import com.lightstick.music.domain.music.SectionMetaStorage
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
         * 동시 처리할 파일 수. MediaCodec 인스턴스 수와 CPU 부하를 고려해 3으로 고정.
         */
        private const val PARALLEL_COUNT = 3
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

        val generator: AutoTimelineGenerator = when (AutoTimelineConfig.GENERATOR_VERSION) {
            0  -> AutoTimelineGeneratorBeat_v0()
            3  -> AutoTimelineGeneratorBeat_v3()
            4  -> AutoTimelineGeneratorBeat_v4()
            else -> throw IllegalArgumentException("Unsupported generator version: ${AutoTimelineConfig.GENERATOR_VERSION} (supported: 0, 3, 4)")
        }
        val sectionStorage = if (generator is SectionAwareGenerator) SectionMetaStorage(version) else null

        // 이미 생성된 파일은 제외한 실제 처리 대상만 추려 정확한 total 확보
        val filesToProcess = if (TEST_FORCE_REGENERATE) {
            musicFiles
        } else {
            musicFiles.filter { file ->
                val musicId = runCatching { MusicId.fromFile(file) }.getOrNull()
                    ?: return@filter true  // musicId 실패 시 처리 목록에 포함(→ failed 처리)
                !storage.exists(context, musicId)
            }
        }

        val total     = filesToProcess.size
        onProgress(0, total, "")  // 정확한 total을 UI에 즉시 전달

        if (filesToProcess.isEmpty()) return

        val processed = AtomicInteger(0)
        val created   = AtomicInteger(0)
        val failed    = AtomicInteger(0)

        val semaphore = Semaphore(PARALLEL_COUNT)

        coroutineScope {
            filesToProcess.map { file ->
                async {
                    semaphore.withPermit {
                        val p = processed.incrementAndGet()
                        onProgress(p, total, file.nameWithoutExtension)

                        val musicId = runCatching { MusicId.fromFile(file) }.getOrNull()
                        if (musicId == null) {
                            failed.incrementAndGet()
                            return@withPermit
                        }

                        try {
                            val (frames, sections) = if (generator is SectionAwareGenerator) {
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
                            }
                        } catch (t: Throwable) {
                            failed.incrementAndGet()
                            Log.e(TAG, "failed v$version file=${file.name} err=${t.message}")
                        }
                    }
                }
            }.awaitAll()
        }

    }
}
