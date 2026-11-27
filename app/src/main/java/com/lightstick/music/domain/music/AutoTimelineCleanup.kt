package com.lightstick.music.domain.music

import android.content.Context
import com.lightstick.music.core.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 자동 연출 파일(filesDir/auto_timelines) 정리.
 *
 * 파일 이름은 곡 내용 해시(musicId) 기준이라, 곡 파일이 지워져도 남는다. 같은 파일을 다시 넣으면
 * 그대로 재사용되므로 바로 지우지 않고 유예 기간을 둔다.
 * - 현재 버전([AutoTimelineConfig.VERSION])이 아닌 파일: 바로 삭제
 * - 지금 목록에 있는 곡의 파일: 수정 시각을 지금으로 갱신해 "마지막으로 목록에 있던 때"로 쓴다
 * - 목록에 없는 곡의 파일: 마지막으로 목록에 있던 때로부터 [RETENTION_DAYS] 일이 지나면 삭제
 *
 * 목록 곡들의 musicId 는 이미 계산돼 캐시에 있는 상태(자동 연출 생성 직후)에서 부른다.
 */
object AutoTimelineCleanup {

    private const val TAG = "AutoTimelineCleanup"

    /** 목록에서 빠진 곡의 자동 연출을 남겨 두는 기간 */
    const val RETENTION_DAYS = 30L

    /** timeline_<id>_v<ver>.bin / sections_<id>_v<ver>.bin */
    private val NAME = Regex("""^(timeline|sections)_(-?\d+)_v(\d+)\.bin$""")

    fun run(context: Context, musicFiles: List<File>) {
        val dir = File(context.filesDir, "auto_timelines")
        if (!dir.isDirectory) return

        val currentIds = musicFiles.mapNotNullTo(HashSet()) { MusicIdCache.getOrNull(it) }
        val version = AutoTimelineConfig.VERSION
        val now = System.currentTimeMillis()
        val expireBefore = now - TimeUnit.DAYS.toMillis(RETENTION_DAYS)

        var oldVersion = 0
        var expired = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val m = NAME.matchEntire(f.name) ?: return@forEach   // 모르는 파일은 건드리지 않는다
            val id = m.groupValues[2].toIntOrNull() ?: return@forEach
            val ver = m.groupValues[3].toIntOrNull() ?: return@forEach
            when {
                ver != version -> if (f.delete()) oldVersion++
                id in currentIds -> f.setLastModified(now)
                // 목록이 비었으면(권한 없음·스캔 실패 등) 목록 기준 삭제는 하지 않는다
                currentIds.isNotEmpty() && f.lastModified() < expireBefore -> if (f.delete()) expired++
            }
        }
        if (oldVersion > 0 || expired > 0) {
            Log.d(TAG, "deleted oldVersion=$oldVersion expired=$expired (keep ${RETENTION_DAYS}d)")
        }

        MusicIdCache.pruneStale()
    }
}
