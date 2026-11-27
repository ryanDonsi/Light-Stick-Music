package com.lightstick.music.domain.music.sectiondetector.meta

import android.content.Context
import com.lightstick.music.domain.music.genredetector.GenreDetector
import com.lightstick.music.domain.music.sectiondetector.SectionDetector
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.IOException
import com.lightstick.music.domain.music.SafeFileWriter
import java.io.File
import java.io.FileInputStream

/**
 * SectionMeta 바이너리 저장소.
 *
 * 저장 경로:
 *   /data/data/<pkg>/files/auto_timelines/sections_<musicId>_v<version>.bin
 *
 * 바이너리 포맷 (DataOutputStream):
 *   Int:   version
 *   Int:   musicStyle.ordinal (-1 = null)
 *   Int:   count
 *   For each SectionMeta:
 *     Long:  startMs
 *     Long:  endMs
 *     Int:   type.ordinal
 *     Int:   changeStrength.ordinal
 *     Long:  beatMs
 *     Float: beatConfidence
 *     Float: energy
 *     Float: peakEnergy
 *     Float: lowRatio
 *     Float: midRatio
 *     Float: highRatio
 *     Float: onsetDensity
 *     Float: periodicity
 *     Int:   beatTimesMs.size
 *     Long[]: beatTimesMs
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
            // 임시 파일에 다 쓴 뒤 이름을 바꾼다 — 강제 종료돼도 반쯤 쓴 파일이 남지 않는다
            SafeFileWriter.write(f) { out ->
                out.writeInt(version)
                out.writeInt(sections.firstOrNull()?.musicStyle?.ordinal ?: -1)
                out.writeInt(sections.size)
                sections.forEach { s ->
                    out.writeLong(s.startMs)
                    out.writeLong(s.endMs)
                    out.writeInt(s.type.ordinal)
                    out.writeInt(s.changeStrength.ordinal)
                    out.writeLong(s.beatMs)
                    out.writeFloat(s.beatConfidence)
                    out.writeFloat(s.energy)
                    out.writeFloat(s.peakEnergy)
                    out.writeFloat(s.lowRatio)
                    out.writeFloat(s.midRatio)
                    out.writeFloat(s.highRatio)
                    out.writeFloat(s.onsetDensity)
                    out.writeFloat(s.periodicity)
                    out.writeInt(s.beatTimesMs.size)
                    s.beatTimesMs.forEach { out.writeLong(it) }
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
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { input ->
                val v = input.readInt()
                if (v != version) return@use null

                val musicStyleOrdinal = input.readInt()
                val musicStyles       = GenreDetector.MusicStyle.values()
                val songStyle         = musicStyles.getOrNull(musicStyleOrdinal)

                val count = input.readInt()
                if (count < 0 || count > 100_000) throw IOException("bad section count $count")
                val sections = ArrayList<SectionMeta>(count)

                val sectionTypes     = SectionDetector.SectionType.values()
                val changeStrengths  = SectionDetector.ChangeStrength.values()

                repeat(count) { idx ->
                    val startMs         = input.readLong()
                    val endMs           = input.readLong()
                    val typeOrdinal     = input.readInt()
                    val strengthOrdinal = input.readInt()
                    val beatMs          = input.readLong()
                    val beatConfidence  = input.readFloat()
                    val energy          = input.readFloat()
                    val peakEnergy      = input.readFloat()
                    val lowRatio        = input.readFloat()
                    val midRatio        = input.readFloat()
                    val highRatio       = input.readFloat()
                    val onsetDensity    = input.readFloat()
                    val periodicity     = input.readFloat()
                    val beatCount       = input.readInt()
                    if (beatCount < 0 || beatCount > 1_000_000) throw IOException("bad beat count $beatCount")
                    val beatTimesMs     = LongArray(beatCount) { input.readLong() }.toList()

                    sections.add(
                        SectionMeta(
                            startMs        = startMs,
                            endMs          = endMs,
                            type           = sectionTypes.getOrElse(typeOrdinal) { SectionDetector.SectionType.VERSE },
                            changeStrength = changeStrengths.getOrElse(strengthOrdinal) { SectionDetector.ChangeStrength.NONE },
                            beatMs         = beatMs,
                            beatConfidence = beatConfidence,
                            energy         = energy,
                            peakEnergy     = peakEnergy,
                            lowRatio       = lowRatio,
                            midRatio       = midRatio,
                            highRatio      = highRatio,
                            onsetDensity   = onsetDensity,
                            periodicity    = periodicity,
                            musicStyle     = if (idx == 0) songStyle else null,
                            beatTimesMs    = beatTimesMs
                        )
                    )
                }
                sections
            }
        } catch (t: Throwable) {
            // 깨진 파일은 지운다(다음 생성 때 다시 만들어진다)
            runCatching { f.delete() }
            null
        }
    }
}
