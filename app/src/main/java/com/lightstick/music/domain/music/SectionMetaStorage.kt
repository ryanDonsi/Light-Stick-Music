package com.lightstick.music.domain.music

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * SectionMeta 바이너리 저장소.
 *
 * 저장 경로:
 *   /data/data/<pkg>/files/auto_timelines/sections_<musicId>_v<version>.bin
 *
 * 바이너리 포맷 (DataOutputStream):
 *   Int:   version
 *   Int:   count
 *   For each SectionMeta:
 *     Long:  startMs
 *     Long:  endMs
 *     Int:   type.ordinal
 *     Int:   changeStrength.ordinal
 *     Long:  beatMs
 *     Float: beatConfidence
 */
class SectionMetaStorage(private val version: Int) {

    private fun dir(context: Context): File =
        File(context.filesDir, "auto_timelines").apply { mkdirs() }

    private fun file(context: Context, musicId: Int): File =
        File(dir(context), "sections_${musicId}_v$version.bin")

    fun exists(context: Context, musicId: Int): Boolean = file(context, musicId).exists()

    fun save(context: Context, musicId: Int, sections: List<SectionMeta>) {
        val f = file(context, musicId)
        try {
            DataOutputStream(FileOutputStream(f)).use { out ->
                out.writeInt(version)
                out.writeInt(sections.size)
                sections.forEach { s ->
                    out.writeLong(s.startMs)
                    out.writeLong(s.endMs)
                    out.writeInt(s.type.ordinal)
                    out.writeInt(s.changeStrength.ordinal)
                    out.writeLong(s.beatMs)
                    out.writeFloat(s.beatConfidence)
                }
            }
        } catch (t: Throwable) {
            runCatching { f.delete() }
        }
    }

    fun load(context: Context, musicId: Int): List<SectionMeta>? {
        val f = file(context, musicId)
        if (!f.exists()) return null

        return try {
            DataInputStream(FileInputStream(f)).use { input ->
                val v = input.readInt()
                if (v != version) return@use null

                val count = input.readInt()
                val sections = ArrayList<SectionMeta>(count)

                val sectionTypes     = SectionDetector.SectionType.values()
                val changeStrengths  = SectionDetector.ChangeStrength.values()

                repeat(count) {
                    val startMs         = input.readLong()
                    val endMs           = input.readLong()
                    val typeOrdinal     = input.readInt()
                    val strengthOrdinal = input.readInt()
                    val beatMs          = input.readLong()
                    val beatConfidence  = input.readFloat()

                    sections.add(
                        SectionMeta(
                            startMs        = startMs,
                            endMs          = endMs,
                            type           = sectionTypes.getOrElse(typeOrdinal) { SectionDetector.SectionType.VERSE },
                            changeStrength = changeStrengths.getOrElse(strengthOrdinal) { SectionDetector.ChangeStrength.NONE },
                            beatMs         = beatMs,
                            beatConfidence = beatConfidence
                        )
                    )
                }
                sections
            }
        } catch (t: Throwable) {
            null
        }
    }
}
