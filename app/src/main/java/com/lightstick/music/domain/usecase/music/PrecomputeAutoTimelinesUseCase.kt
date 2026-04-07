package com.lightstick.music.domain.usecase.music

import android.content.Context
import com.lightstick.efx.MusicId
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.domain.music.AutoTimelineConfig
import com.lightstick.music.domain.music.AutoTimelineStorage
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v1
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v2
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v3
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v4
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v5
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v6
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v7
import com.lightstick.music.domain.music.AutoTimelineGeneratorBeat_v8
import kotlinx.coroutines.yield
import java.io.File
import javax.inject.Inject

/**
 * 초기화 시 자동 타임라인 생성 UseCase
 *
 * ✅ 핵심 보장:
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
         * ✅ 테스트 시: true면 해당 버전(vX) 파일을 전부 삭제 후 재생성
         */
        private const val TEST_FORCE_REGENERATE = false

        /**
         * ✅ 디코딩/분석이 길 때 UI 갱신 부드럽게 하기 위한 yield 주기
         */
        private const val YIELD_EVERY_N = 2
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
                Log.d(TAG, "🧹 Cleared all timelines for version=$version")
            }.onFailure {
                Log.e(TAG, "Failed to clear timelines: ${it.message}")
            }
        }

        // 현재 설정 버전에 해당하는 제너레이터만 생성
        val generator = when (version) {
            1 -> AutoTimelineGeneratorBeat_v1()
            2 -> AutoTimelineGeneratorBeat_v2()
            3 -> AutoTimelineGeneratorBeat_v3()
            4 -> AutoTimelineGeneratorBeat_v4()
            5 -> AutoTimelineGeneratorBeat_v5()
            6 -> AutoTimelineGeneratorBeat_v6()
            7 -> AutoTimelineGeneratorBeat_v7()
            8 -> AutoTimelineGeneratorBeat_v8()
            else -> throw IllegalArgumentException("Unsupported version: $version")
        }

        val total = musicFiles.size
        var processed = 0
        var created = 0
        var skipped = 0
        var failed = 0

        Log.d(TAG, "🚀 Precompute start: total=$total version=$version paletteSize=$paletteSize")

        for ((idx, file) in musicFiles.withIndex()) {
            processed++
            onProgress(processed, total, file.nameWithoutExtension)

            val musicId = runCatching { MusicId.fromFile(file) }.getOrNull()
            if (musicId == null) {
                failed++
                if (idx % YIELD_EVERY_N == 0) yield()
                continue
            }

            if (!TEST_FORCE_REGENERATE && storage.exists(context, musicId)) {
                skipped++
                if (idx % YIELD_EVERY_N == 0) yield()
                continue
            }

            try {
                val frames = generator.generate(file.absolutePath, musicId, paletteSize = paletteSize)

                if (frames.isEmpty()) {
                    failed++
                    Log.w(TAG, "⚠️ empty frames -> skip save file=${file.name} musicId=$musicId")
                    if (idx % YIELD_EVERY_N == 0) yield()
                    continue
                }

                storage.save(context, musicId, frames)
                created++
                Log.d(TAG, "✅ saved v$version musicId=$musicId frames=${frames.size} file=${file.name}")

            } catch (t: Throwable) {
                failed++
                Log.e(TAG, "❌ failed v$version file=${file.name} err=${t.message}")
            }

            if (idx % YIELD_EVERY_N == 0) yield()
        }

        Log.d(TAG, "🏁 Precompute done: v$version created=$created skipped=$skipped failed=$failed")
    }
}