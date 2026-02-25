package com.lightstick.music.domain.music

import android.content.Context
import android.net.Uri
import com.lightstick.music.core.util.Log
import com.lightstick.efx.Efx
import com.lightstick.efx.EfxEntry
import com.lightstick.efx.MusicId
import com.lightstick.music.data.local.storage.EffectPathPreferences
import java.io.File

/**
 * ✅ SAF 지원 추가
 * - File 기반 초기화 (하위 호환)
 * - SAF 기반 초기화 (권장)
 */
object MusicEffectManager {

    private val TAG = "MusicEffectManager"

    // musicId -> EFX 파일 매핑
    private val effectFileMap = mutableMapOf<Int, File>()

    /**
     * ✅ SAF 기반 초기화 (권장)
     */
    fun initializeFromSAF(context: Context) {
        effectFileMap.clear()

        val effectFiles = EffectPathPreferences.listEffectFiles(context)

        if (effectFiles.isEmpty()) {
            Log.w(TAG, "⚠️ No EFX files found in configured directory")
            return
        }

        effectFiles.forEach { docFile ->
            try {
                // DocumentFile을 임시 File로 복사
                val tempFile = EffectPathPreferences.copyToTempFile(context, docFile)
                    ?: return@forEach

                // EFX 파일 읽기
                val efx = Efx.Companion.read(tempFile)
                val musicId = efx.header.musicId

                effectFileMap[musicId] = tempFile
                Log.d(TAG, "✅ Loaded: ${docFile.name} -> musicId=0x${musicId.toUInt().toString(16).uppercase()}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to read EFX file ${docFile.name}: ${e.message}")
            }
        }

        Log.i(TAG, "📦 Initialized with ${effectFileMap.size} EFX files")
    }

    /**
     * ✅ File 기반 초기화 (하위 호환)
     */
    fun initialize(effectDir: File) {
        if (!effectDir.exists()) {
            Log.w(TAG, "Effect directory does not exist: ${effectDir.absolutePath}")
            return
        }

        effectFileMap.clear()

        effectDir.listFiles { file ->
            file.extension.equals("efx", ignoreCase = true)
        }?.forEach { efxFile ->
            try {
                val efx = Efx.Companion.read(efxFile)
                val musicId = efx.header.musicId

                effectFileMap[musicId] = efxFile
                Log.d(TAG, "Loaded: ${efxFile.name} -> musicId=0x${musicId.toUInt().toString(16).uppercase()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read EFX file ${efxFile.name}: ${e.message}")
            }
        }

        Log.i(TAG, "Initialized with ${effectFileMap.size} EFX files")
    }

    /**
     * ✅ SDK 활용: MusicId.fromFile()
     */
    fun hasEffectFor(musicFile: File): Boolean {
        return try {
            val musicId = MusicId.fromFile(musicFile)
            effectFileMap.containsKey(musicId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute musicId: ${e.message}")
            false
        }
    }

    /**
     * ✅ SDK 활용: MusicId.fromUri()
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
     * ✅ SDK 활용: Efx.read()로 파일을 읽어 EfxEntry 리스트 반환
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
     * ✅ SDK 활용: Efx.read()
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
     * ✅ SDK 활용: Efx.read()로 파싱
     */
    fun loadEffectsByMusicId(musicId: Int): List<EfxEntry>? {
        val efxFile = effectFileMap[musicId] ?: return null

        return try {
            val efx = Efx.Companion.read(efxFile)
            val entries = efx.body.entries

            Log.d(TAG, "Loaded ${entries.size} entries from ${efxFile.name}")
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