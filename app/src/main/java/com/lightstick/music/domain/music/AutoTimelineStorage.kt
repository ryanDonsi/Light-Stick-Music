package com.lightstick.music.domain.music

import android.content.Context
import com.lightstick.music.core.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Auto Timeline 저장소
 *
 * - version 별로 파일명을 분리해서(v1/v2/v3 비교용) 서로 덮어쓰지 않게 함
 * - clearAll(): 해당 version 파일만 전부 삭제 (테스트용)
 *
 * 저장 경로:
 *   /data/data/<pkg>/files/auto_timelines/timeline_<musicId>_v<version>.bin
 */
class AutoTimelineStorage(
    private val version: Int = 1
) {
    companion object {
        private const val TAG = "AutoTimelineStorage"
        /** 깨진 파일의 엉터리 개수로 큰 배열을 만들지 않게 하는 상한 */
        private const val MAX_FRAMES = 1_000_000
        private const val MAX_PAYLOAD = 64 * 1024
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "auto_timelines").apply { mkdirs() }

    private fun file(context: Context, musicId: Int): File =
        File(dir(context), "timeline_${musicId}_v$version.bin")

    fun exists(context: Context, musicId: Int): Boolean = file(context, musicId).exists()

    /** 임시 파일에 다 쓴 뒤 이름을 바꾼다 — 강제 종료돼도 반쯤 쓴 파일이 남지 않는다 */
    fun save(context: Context, musicId: Int, frames: List<Pair<Long, ByteArray>>) {
        SafeFileWriter.write(file(context, musicId)) { out ->
            out.writeInt(version)
            out.writeInt(frames.size)
            frames.forEach { (t, payload) ->
                out.writeLong(t)
                out.writeInt(payload.size)
                out.write(payload)
            }
        }
    }

    /**
     * 없으면 null. 읽다가 실패하면(예전 버전에서 쓰다 만 파일 등) 그 파일을 지우고 null 을 돌려준다.
     * 지워진 곡은 다음 자동 연출 생성 때 다시 만들어진다.
     */
    fun load(context: Context, musicId: Int): List<Pair<Long, ByteArray>>? {
        val f = file(context, musicId)
        if (!f.exists()) return null

        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { input ->
                val v = input.readInt()
                if (v != version) return@use null

                val count = input.readInt()
                if (count < 0 || count > MAX_FRAMES) throw IOException("bad frame count $count")
                val frames = ArrayList<Pair<Long, ByteArray>>(count)

                repeat(count) {
                    val t = input.readLong()
                    val len = input.readInt()
                    if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad payload size $len")
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    frames.add(t to payload)
                }
                frames
            }
        } catch (t: Throwable) {
            Log.w(TAG, "corrupt timeline ${f.name} → delete (${t.message})")
            runCatching { f.delete() }
            null
        }
    }

    /**
     *  테스트용: 현재 version(vX)에 해당하는 모든 타임라인 파일 삭제
     * - 예: version=2면 "*_v2.bin" 파일만 삭제
     */
    fun clearAll(context: Context) {
        val d = dir(context)
        if (!d.exists()) return

        d.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith("_v$version.bin")) {
                runCatching { f.delete() }
            }
        }
    }

    /**
     *  특정 곡(musicId)만 삭제 (테스트/디버그용)
     */
    fun clearOne(context: Context, musicId: Int) {
        runCatching { file(context, musicId).delete() }
    }
}