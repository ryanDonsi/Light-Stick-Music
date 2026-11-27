package com.lightstick.music.domain.music

import android.content.Context
import com.lightstick.efx.MusicId
import com.lightstick.music.core.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [MusicId.fromFile] 결과 캐시.
 *
 * MusicId 는 파일 앞 4MB 의 SHA-256 이라 곡마다 계산 비용이 크다. 시작 시 Splash 매칭,
 * 타임라인 존재 확인/생성, 음악 목록 스캔, 곡 재생에서 같은 파일을 여러 번 계산하던 것을
 * (경로, 크기, 수정 시각) 키로 한 번만 계산하도록 한다. 파일이 바뀌면(크기/수정 시각 변경) 다시 계산한다.
 *
 * 디스크(filesDir/music_id_cache.txt)에 한 줄씩 덧붙여 저장해 앱 재시작 후에도 재사용한다.
 * [init] 을 호출하지 않으면 메모리 캐시로만 동작한다.
 */
object MusicIdCache {

    private const val TAG = "MusicIdCache"
    private const val FILE_NAME = "music_id_cache.txt"

    private data class Key(val path: String, val size: Long, val modified: Long)

    private val cache = ConcurrentHashMap<Key, Int>()

    @Volatile private var store: File? = null
    @Volatile private var loaded = false
    private val lock = Any()

    /** 디스크 저장 위치 지정. 실제 읽기는 첫 [get] 때(보통 IO 스레드) 한 번만 한다. */
    fun init(context: Context) {
        store = File(context.applicationContext.filesDir, FILE_NAME)
    }

    /** 캐시된 MusicId 를 돌려주고, 없으면 계산해서 저장한다. 실패 시 SDK 예외를 그대로 던진다. */
    fun get(file: File): Int {
        ensureLoaded()
        val key = Key(file.absolutePath, file.length(), file.lastModified())
        cache[key]?.let { return it }

        val id = MusicId.fromFile(file)
        cache[key] = id
        append(key, id)
        return id
    }

    /** 실패하면 null. */
    fun getOrNull(file: File): Int? = runCatching { get(file) }.getOrNull()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = store
            if (f != null && f.exists()) {
                var lines = 0
                runCatching {
                    f.forEachLine { line ->
                        lines++
                        val parts = line.split('\t')
                        if (parts.size == 4) {
                            val size = parts[1].toLongOrNull()
                            val modified = parts[2].toLongOrNull()
                            val id = parts[3].toIntOrNull()
                            if (size != null && modified != null && id != null) {
                                cache[Key(parts[0], size, modified)] = id
                            }
                        }
                    }
                }.onFailure { Log.w(TAG, "load failed: ${it.message}") }
                // 같은 경로의 옛 항목이 쌓였으면 현재 내용으로 다시 쓴다
                if (lines > cache.size * 2 + 50) compact(f)
            }
            loaded = true
        }
    }

    private fun append(key: Key, id: Int) {
        val f = store ?: return
        synchronized(lock) {
            runCatching { f.appendText("${key.path}\t${key.size}\t${key.modified}\t$id\n") }
                .onFailure { Log.w(TAG, "append failed: ${it.message}") }
        }
    }

    private fun compact(f: File) {
        runCatching {
            // 경로별 최신(수정 시각이 가장 큰) 항목만 남긴다
            val latest = cache.entries
                .groupBy { it.key.path }
                .mapValues { (_, entries) -> entries.maxBy { it.key.modified } }
                .values
            cache.clear()
            latest.forEach { cache[it.key] = it.value }
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { w ->
                latest.forEach { (k, id) -> w.write("${k.path}\t${k.size}\t${k.modified}\t$id\n") }
            }
            tmp.renameTo(f)
        }.onFailure { Log.w(TAG, "compact failed: ${it.message}") }
    }
}
