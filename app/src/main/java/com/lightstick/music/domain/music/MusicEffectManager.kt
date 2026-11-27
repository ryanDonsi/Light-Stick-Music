package com.lightstick.music.domain.music

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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

    /** 마지막으로 읽은 SAF 폴더 내용(이름·크기·수정 시각) — 그대로면 다시 복사/파싱하지 않는다. */
    @Volatile private var lastSafSignature: String? = null

    /**
     *  SAF 기반 초기화 (권장)
     *  SAF URI가 없는 경우(자동 설정된 파일 경로) File 기반으로 폴백
     */
    @Synchronized
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

        val effectFiles = EffectPathPreferences.listEffectFiles(context)
        Log.d(TAG, "initializeFromSAF: SAF found ${effectFiles.size} EFX file(s)")

        val signature = dirUri.toString() + "\n" + effectFiles
            .map { "${it.name}|${it.length()}|${it.lastModified()}" }
            .sorted()
            .joinToString("\n")
        if (signature == lastSafSignature && effectFileMap.values.all { it.exists() }) {
            Log.d(TAG, "initializeFromSAF: unchanged → skip (${effectFileMap.size} in map)")
            return
        }

        lastSafSignature = null

        if (effectFiles.isEmpty()) {
            effectFileMap.clear()
            Log.w(TAG, "initializeFromSAF: no EFX files in SAF directory ($dirUri)")
            return
        }

        // 새 맵을 다 만든 뒤 한 번에 바꾼다 — 읽는 도중 다른 스레드(loadMusic)가 빈 맵을 보지 않게
        val fresh = HashMap<Int, File>()
        effectFiles.forEach { docFile ->
            try {
                val tempFile = copyToLocal(context, docFile)
                if (tempFile == null) {
                    Log.w(TAG, "initializeFromSAF: copy failed for ${docFile.name}")
                    return@forEach
                }

                val efx = Efx.Companion.read(tempFile)
                val musicId = efx.header.musicId
                fresh[musicId] = tempFile
                Log.d(TAG, "initializeFromSAF: loaded ${docFile.name} → musicId=$musicId (0x${musicId.toUInt().toString(16)})")

            } catch (e: Exception) {
                Log.e(TAG, "initializeFromSAF: failed to read ${docFile.name}: ${e.message}")
            }
        }

        replaceMap(fresh)
        lastSafSignature = signature

        // SAF 폴더에서 지워진 EFX 의 로컬 사본 정리
        val keep = effectFiles.mapNotNull { it.name }.toSet()
        File(context.filesDir, "efx").listFiles()?.forEach { if (it.name !in keep) it.delete() }
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

        val fresh = HashMap<Int, File>()

        val listed = effectDir.listFiles { file -> file.extension.equals("efx", ignoreCase = true) }
        Log.d(TAG, "initialize(File): listFiles result = ${listed?.size ?: "null (permission denied?)"}")

        listed?.forEach { efxFile ->
            try {
                val efx = Efx.Companion.read(efxFile)
                val musicId = efx.header.musicId
                fresh[musicId] = efxFile
                Log.d(TAG, "initialize(File): loaded ${efxFile.name} → musicId=$musicId (0x${musicId.toUInt().toString(16)})")
            } catch (e: Exception) {
                Log.e(TAG, "initialize(File): failed to read ${efxFile.name}: ${e.message}")
            }
        }

        replaceMap(fresh)
        Log.i(TAG, "initialize(File): done — ${effectFileMap.size} EFX file(s) in map")
    }

    private fun replaceMap(fresh: Map<Int, File>) {
        effectFileMap.keys.retainAll(fresh.keys)
        effectFileMap.putAll(fresh)
        mapVersion++
    }

    /** EFX 맵이 바뀔 때마다 증가 — 음악 목록이 hasEffect 를 다시 계산해야 하는지 판단용 */
    @Volatile var mapVersion: Int = 0
        private set

    /**
     * SAF 문서를 filesDir/efx 로 복사한다. cacheDir 은 시스템이 앱 실행 중에도 비울 수 있어
     * 그 경우 EFX 가 조용히 빠지므로 filesDir 를 쓴다. 크기·수정 시각이 같으면 다시 복사하지 않는다.
     */
    private fun copyToLocal(context: Context, docFile: DocumentFile): File? {
        val name = docFile.name ?: return null
        val dir = File(context.filesDir, "efx").apply { mkdirs() }
        val target = File(dir, name)
        val srcModified = docFile.lastModified()
        if (target.exists() && target.length() == docFile.length() &&
            srcModified > 0 && target.lastModified() == srcModified
        ) return target

        return try {
            val tmp = File(dir, "$name.part")
            val ok = context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!ok) return null
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return null
            }
            if (srcModified > 0) target.setLastModified(srcModified)
            target
        } catch (e: Exception) {
            Log.e(TAG, "copyToLocal failed for $name: ${e.message}")
            null
        }
    }

    fun hasEffectFor(musicFile: File): Boolean {
        return try {
            val musicId = MusicIdCache.get(musicFile)
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
            val musicId = MusicIdCache.get(musicFile)
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