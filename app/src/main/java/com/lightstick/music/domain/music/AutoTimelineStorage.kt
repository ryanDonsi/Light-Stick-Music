package com.lightstick.music.domain.music

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
    private fun dir(context: Context): File =
        File(context.filesDir, "auto_timelines").apply { mkdirs() }

    private fun file(context: Context, musicId: Int): File =
        File(dir(context), "timeline_${musicId}_v$version.bin")

    fun exists(context: Context, musicId: Int): Boolean = file(context, musicId).exists()

    fun save(context: Context, musicId: Int, frames: List<Pair<Long, ByteArray>>) {
        val f = file(context, musicId)
        DataOutputStream(FileOutputStream(f)).use { out ->
            out.writeInt(version)
            out.writeInt(frames.size)
            frames.forEach { (t, payload) ->
                out.writeLong(t)
                out.writeInt(payload.size)
                out.write(payload)
            }
        }
    }

    fun load(context: Context, musicId: Int): List<Pair<Long, ByteArray>>? {
        val f = file(context, musicId)
        if (!f.exists()) return null

        return DataInputStream(FileInputStream(f)).use { input ->
            val v = input.readInt()
            if (v != version) return@use null

            val count = input.readInt()
            val frames = ArrayList<Pair<Long, ByteArray>>(count)

            repeat(count) {
                val t = input.readLong()
                val len = input.readInt()
                val payload = ByteArray(len)
                input.readFully(payload)
                frames.add(t to payload)
            }
            frames
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