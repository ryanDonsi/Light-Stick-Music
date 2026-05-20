package com.lightstick.music.domain.music

import android.content.Context
import android.net.Uri
import com.lightstick.music.core.util.Log
import com.lightstick.efx.Efx
import com.lightstick.efx.EfxEntry
import com.lightstick.efx.MusicId
import com.lightstick.music.data.local.storage.EffectPathPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 *  SAF 지원 추가
 * - File 기반 초기화 (하위 호환)
 * - SAF 기반 초기화 (권장)
 */
object MusicEffectManager {

    private val TAG = "MusicEffectManager"

    private val effectFileMap = ConcurrentHashMap<Int, File>()

    /**
     *  SAF 기반 초기화 (권장)
     *  SAF URI가 없는 경우(자동 설정된 파일 경로) File 기반으로 폴백
     */
    fun initializeFromSAF(context: Context) {
        val dirUri = EffectPathPreferences.getSavedDirectoryUri(context)
        Log.d(TAG, "initializeFromSAF: SAF URI = $dirUri")

        if (dirUri == null) {
            val path = EffectPathPreferences.getSavedDirectoryPath(context)
            Log.d(TAG, "initializeFromSAF: no SAF URI → file path = $path")
            if (path != null) {
                initialize(File(path))
            } else {
                effectFileMap.clear()
                Log.w(TAG, "initializeFromSAF: no directory configured at all")
            }
            return
        }

        effectFileMap.clear()

        val effectFiles = EffectPathPreferences.listEffectFiles(context)
        Log.d(TAG, "initializeFromSAF: SAF found ${effectFiles.size} EFX file(s)")

        if (effectFiles.isEmpty()) {
            Log.w(TAG, "initializeFromSAF: no EFX files in SAF directory ($dirUri)")
            return
        }

        effectFiles.forEach { docFile ->
            try {
                val tempFile = EffectPathPreferences.copyToTempFile(context, docFile)
                if (tempFile == null) {
                    Log.w(TAG, "initializeFromSAF: copyToTempFile failed for ${docFile.name}")
                    return@forEach
                }

                val efx = Efx.Companion.read(tempFile)
                val musicId = efx.header.musicId
                effectFileMap[musicId] = tempFile
                Log.d(TAG, "initializeFromSAF: loaded ${docFile.name} → musicId=$musicId (0x${musicId.toUInt().toString(16)})")

            } catch (e: Exception) {
                Log.e(TAG, "initializeFromSAF: failed to read ${docFile.name}: ${e.message}")
            }
        }

        Log.i(TAG, "initializeFromSAF: done — ${effectFileMap.size} EFX file(s) in map")
    }

    /**
     *  File 기반 초기화 (하위 호환)
     */
    fun initialize(effectDir: File) {
        Log.d(TAG, "initialize(File): path=${effectDir.absolutePath}, exists=${effectDir.exists()}, canRead=${effectDir.canRead()}")

        if (!effectDir.exists()) {
            Log.w(TAG, "initialize(File): directory does not exist → ${effectDir.absolutePath}")
            return
        }

        effectFileMap.clear()

        val listed = effectDir.listFiles { file -> file.extension.equals("efx", ignoreCase = true) }
        Log.d(TAG, "initialize(File): listFiles result = ${listed?.size ?: "null (permission denied?)"}")

        listed?.forEach { efxFile ->
            try {
                val efx = Efx.Companion.read(efxFile)
                val musicId = efx.header.musicId
                effectFileMap[musicId] = efxFile
                Log.d(TAG, "initialize(File): loaded ${efxFile.name} → musicId=$musicId (0x${musicId.toUInt().toString(16)})")
            } catch (e: Exception) {
                Log.e(TAG, "initialize(File): failed to read ${efxFile.name}: ${e.message}")
            }
        }

        Log.i(TAG, "initialize(File): done — ${effectFileMap.size} EFX file(s) in map")
    }

    /**
     *  SDK 활용: MusicId.fromFile()
     */
    fun hasEffectFor(musicFile: File): Boolean {
        return try {
            val musicId = MusicId.fromFile(musicFile)
            val found = effectFileMap.containsKey(musicId)
            if (found) {
                Log.d(TAG, "hasEffectFor: HIT  ${musicFile.name} → musicId=$musicId")
            } else {
                Log.d(TAG, "hasEffectFor: MISS ${musicFile.name} → musicId=$musicId, map keys=${effectFileMap.keys.toList()}")
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "hasEffectFor: exception for ${musicFile.name}: ${e.message}")
            false
        }
    }

    /**
     *  SDK 활용: MusicId.fromUri()
     */
    fun hasEffectFor(context: Context, musicUri: Uri): Boolean {
        return try {
            val musicId = MusicId.fromUri(context, musicUri)
            effectFileMap.containsKey(musicId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute musicId: ${e.message}")
            false
        }
    }

    /**
     *  SDK 활용: Efx.read()로 파일을 읽어 EfxEntry 리스트 반환
     */
    fun loadEffects(musicFile: File): List<EfxEntry>? {
        return try {
            val musicId = MusicId.fromFile(musicFile)
            loadEffectsByMusicId(musicId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load effects: ${e.message}")
            null
        }
    }

    /**
     *  SDK 활용: Efx.read()
     */
    fun loadEffects(context: Context, musicUri: Uri): List<EfxEntry>? {
        return try {
            val musicId = MusicId.fromUri(context, musicUri)
            loadEffectsByMusicId(musicId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load effects: ${e.message}")
            null
        }
    }

    /**
     *  SDK 활용: Efx.read()로 파싱
     */
    fun loadEffectsByMusicId(musicId: Int): List<EfxEntry>? {
        val efxFile = effectFileMap[musicId] ?: return null

        return try {
            val efx = Efx.Companion.read(efxFile)
            val entries = efx.body.entries

            entries

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EFX file: ${e.message}")
            null
        }
    }

    /**
     * 현재 로드된 EFX 파일 수
     */
    fun getLoadedEffectCount(): Int = effectFileMap.size
}