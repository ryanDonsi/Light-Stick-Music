package com.lightstick.music.data.local.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/**
 * Effects 디렉토리 관리 (자동 경로 지원)
 *
 * Music/Effects 폴더를 자동으로 사용
 * - Android 10 이하: File API 직접 사용
 * - Android 11+: 제한적 접근 (SAF 필요시 폴백)
 */
object EffectPathPreferences {

    private const val TAG = "EffectDirManager"
    private const val PREFS_NAME = "effect_directory"
    private const val KEY_DIRECTORY_PATH = "directory_path"
    private const val KEY_DIRECTORY_URI = "directory_uri"
    private const val KEY_AUTO_CONFIGURED = "auto_configured"

    /**
     * ✅ 자동으로 Music/Effects 폴더 설정
     */
    fun autoConfigureEffectsDirectory(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 이미 설정되어 있으면 스킵
        if (prefs.getBoolean(KEY_AUTO_CONFIGURED, false)) {
            Log.d(TAG, "✓ Already auto-configured")
            return true
        }

        return try {
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val effectsDir = File(musicDir, "Effects")

            if (!effectsDir.exists()) {
                effectsDir.mkdirs()
                Log.d(TAG, "✅ Created Effects directory: ${effectsDir.absolutePath}")
            }

            prefs.edit()
                .putString(KEY_DIRECTORY_PATH, effectsDir.absolutePath)
                .putBoolean(KEY_AUTO_CONFIGURED, true)
                .apply()

            Log.d(TAG, "✅ Auto-configured: ${effectsDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Auto-configuration failed: ${e.message}")
            false
        }
    }

    /**
     * 저장된 Effects 디렉토리 경로 가져오기
     */
    fun getSavedDirectoryPath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DIRECTORY_PATH, null)
    }

    /**
     * 저장된 Effects 디렉토리 URI 가져오기 (SAF용)
     */
    fun getSavedDirectoryUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_DIRECTORY_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    /**
     * Effects 디렉토리 URI 저장 (수동 선택)
     */
    fun saveDirectoryUri(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DIRECTORY_URI, uri.toString())
            .putBoolean(KEY_AUTO_CONFIGURED, false) // 수동 설정
            .apply()

        // 영구 권한 부여
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Could not take persistable permission: ${e.message}")
        }

        Log.d(TAG, "✅ Saved directory URI: $uri")
    }

    /**
     * Effects 디렉토리가 설정되었는지 확인
     */
    fun isDirectoryConfigured(context: Context): Boolean {
        // 먼저 자동 설정 경로 확인
        val path = getSavedDirectoryPath(context)
        if (path != null) {
            val file = File(path)
            if (file.exists()) return true
        }

        // SAF URI 확인
        val uri = getSavedDirectoryUri(context) ?: return false

        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.exists() == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking directory: ${e.message}")
            false
        }
    }

    /**
     * 디렉토리 선택 Intent 생성 (수동 선택용)
     */
    fun createDirectoryPickerIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }

        // ✅ Music 폴더를 초기 위치로 힌트 제공
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val musicUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
            intent.putExtra("android.provider.extra.INITIAL_URI", musicUri)
        }

        return intent
    }

    /**
     * Effects 디렉토리의 모든 EFX 파일 목록 가져오기
     */
    fun listEffectFiles(context: Context): List<DocumentFile> {
        // 먼저 File 경로 시도
//        val path = getSavedDirectoryPath(context)
//        if (path != null) {
//            val dir = File(path)
//            if (dir.exists()) {
//                val files = dir.listFiles()
//                files?.forEach { file ->
//                    Log.d(TAG, "checking: ${file.name}")
//                }
//                return files?.filter {
//                    it.isFile && it.extension.equals("efx", ignoreCase = true)
//                }?.map { file ->
//                    DocumentFile.fromFile(file)
//                } ?: emptyList()
//            }
//        }

        // SAF URI 시도
        val uri = getSavedDirectoryUri(context) ?: return emptyList()

        return try {
            val directory = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()

            directory.listFiles()
                .filter { it.isFile && it.name?.endsWith(".efx", ignoreCase = true) == true }
                .also { files ->
                    Log.d(TAG, "📂 Found ${files.size} EFX files")
                    files.forEach { file ->
                        Log.d(TAG, "  - ${file.name}")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files: ${e.message}")
            emptyList()
        }
    }

    /**
     * DocumentFile을 InputStream으로 열기
     */
    fun openInputStream(context: Context, documentFile: DocumentFile): InputStream? {
        return try {
            context.contentResolver.openInputStream(documentFile.uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${e.message}")
            null
        }
    }

    /**
     * DocumentFile의 내용을 임시 File로 복사
     */
    fun copyToTempFile(context: Context, documentFile: DocumentFile): File? {
        return try {
            val tempFile = File(context.cacheDir, documentFile.name ?: "temp.efx")

            openInputStream(context, documentFile)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "✅ Copied to temp: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to temp: ${e.message}")
            null
        }
    }

    /**
     * 디렉토리 설정 초기화
     */
    fun clearDirectory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_DIRECTORY_PATH)
            .remove(KEY_DIRECTORY_URI)
            .remove(KEY_AUTO_CONFIGURED)
            .apply()
        Log.d(TAG, "🗑️ Cleared directory configuration")
    }
}