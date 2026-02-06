package com.lightstick.music.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/**
 * Storage Access Framework (SAF) 유틸리티
 *
 * Android 11+ 에서 scoped storage에 대응하기 위한 SAF 관련 기능 제공:
 * - DocumentFile 처리
 * - URI 권한 관리
 * - 파일 복사/읽기
 */
object SafHelper {

    private const val TAG = "SafHelper"

    /**
     * 디렉토리 선택 Intent 생성
     *
     * @param initialUri 초기 위치 URI (선택사항)
     * @return ACTION_OPEN_DOCUMENT_TREE Intent
     */
    fun createDirectoryPickerIntent(initialUri: Uri? = null): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }

        // 초기 위치 힌트 제공 (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
            intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)
        }

        return intent
    }

    /**
     * Music 폴더를 초기 위치로 하는 디렉토리 선택 Intent
     */
    fun createMusicDirectoryPickerIntent(): Intent {
        val musicUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        )
        return createDirectoryPickerIntent(musicUri)
    }

    /**
     * URI에 대한 영구 권한 요청
     *
     * @param context Context
     * @param uri 권한을 요청할 URI
     * @return true if successful
     */
    fun takePersistableUriPermission(context: Context, uri: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Log.d(TAG, "✅ Persistable permission granted: $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to take persistable permission: ${e.message}")
            false
        }
    }

    /**
     * URI에 대한 영구 권한 해제
     *
     * @param context Context
     * @param uri 권한을 해제할 URI
     */
    fun releasePersistableUriPermission(context: Context, uri: Uri) {
        try {
            val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            context.contentResolver.releasePersistableUriPermission(uri, releaseFlags)
            Log.d(TAG, "🔓 Persistable permission released: $uri")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release persistable permission: ${e.message}")
        }
    }

    /**
     * DocumentFile을 InputStream으로 열기
     *
     * @param context Context
     * @param documentFile DocumentFile
     * @return InputStream 또는 null
     */
    fun openInputStream(context: Context, documentFile: DocumentFile): InputStream? {
        return try {
            context.contentResolver.openInputStream(documentFile.uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open InputStream: ${e.message}")
            null
        }
    }

    /**
     * DocumentFile을 임시 File로 복사
     *
     * @param context Context
     * @param documentFile DocumentFile
     * @param targetName 대상 파일명 (선택사항, 기본값: DocumentFile의 이름)
     * @return 복사된 File 또는 null
     */
    fun copyToTempFile(
        context: Context,
        documentFile: DocumentFile,
        targetName: String? = null
    ): File? {
        return try {
            val fileName = targetName ?: documentFile.name ?: "temp_${System.currentTimeMillis()}"
            val tempFile = File(context.cacheDir, fileName)

            openInputStream(context, documentFile)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "✅ Copied to temp: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy to temp: ${e.message}")
            null
        }
    }

    /**
     * URI에서 DocumentFile로 변환
     *
     * @param context Context
     * @param uri 변환할 URI
     * @return DocumentFile 또는 null
     */
    fun fromUri(context: Context, uri: Uri): DocumentFile? {
        return try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DocumentFile from URI: ${e.message}")
            null
        }
    }

    /**
     * 디렉토리의 모든 파일 목록 가져오기
     *
     * @param context Context
     * @param directoryUri 디렉토리 URI
     * @param extension 필터링할 확장자 (선택사항, 예: "efx")
     * @return 파일 목록
     */
    fun listFiles(
        context: Context,
        directoryUri: Uri,
        extension: String? = null
    ): List<DocumentFile> {
        return try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: return emptyList()

            val allFiles = directory.listFiles()

            if (extension == null) {
                allFiles.filter { it.isFile }
            } else {
                allFiles.filter {
                    it.isFile && it.name?.endsWith(".$extension", ignoreCase = true) == true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list files: ${e.message}")
            emptyList()
        }
    }

    /**
     * URI가 유효한지 확인
     *
     * @param context Context
     * @param uri 확인할 URI
     * @return true if valid and accessible
     */
    fun isUriValid(context: Context, uri: Uri): Boolean {
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.exists() == true
        } catch (e: Exception) {
            Log.e(TAG, "URI validation failed: ${e.message}")
            false
        }
    }

    /**
     * URI에 쓰기 권한이 있는지 확인
     *
     * @param context Context
     * @param uri 확인할 URI
     * @return true if writable
     */
    fun isUriWritable(context: Context, uri: Uri): Boolean {
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.canWrite() == true
        } catch (e: Exception) {
            Log.e(TAG, "Writable check failed: ${e.message}")
            false
        }
    }

    /**
     * URI에 읽기 권한이 있는지 확인
     *
     * @param context Context
     * @param uri 확인할 URI
     * @return true if readable
     */
    fun isUriReadable(context: Context, uri: Uri): Boolean {
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.canRead() == true
        } catch (e: Exception) {
            Log.e(TAG, "Readable check failed: ${e.message}")
            false
        }
    }

    /**
     * 파일 크기 가져오기
     *
     * @param documentFile DocumentFile
     * @return 파일 크기 (bytes) 또는 0
     */
    fun getFileSize(documentFile: DocumentFile): Long {
        return try {
            documentFile.length()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size: ${e.message}")
            0L
        }
    }

    /**
     * 파일 생성
     *
     * @param context Context
     * @param directoryUri 디렉토리 URI
     * @param mimeType MIME 타입
     * @param displayName 파일명
     * @return 생성된 DocumentFile 또는 null
     */
    fun createFile(
        context: Context,
        directoryUri: Uri,
        mimeType: String,
        displayName: String
    ): DocumentFile? {
        return try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: return null

            directory.createFile(mimeType, displayName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file: ${e.message}")
            null
        }
    }

    /**
     * 파일 삭제
     *
     * @param documentFile 삭제할 DocumentFile
     * @return true if deleted
     */
    fun deleteFile(documentFile: DocumentFile): Boolean {
        return try {
            documentFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: ${e.message}")
            false
        }
    }
}