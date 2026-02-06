package com.lightstick.music.core.extensions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.lightstick.music.core.util.SafHelper
import java.io.File
import java.io.InputStream

/**
 * Uri 확장 함수
 *
 * Uri 처리를 간편하게 하는 확장 함수 모음
 */

private const val TAG = "UriExtensions"

/**
 * URI에서 파일명 가져오기
 *
 * @return 파일명 또는 null
 */
fun Uri.getDisplayName(context: Context): String? {
    return try {
        context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            } else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get display name: ${e.message}")
        null
    }
}

/**
 * URI에서 파일명 가져오기 (확장자 제외)
 *
 * @return 확장자를 제외한 파일명 또는 null
 */
fun Uri.getDisplayNameWithoutExtension(context: Context): String? {
    return getDisplayName(context)?.substringBeforeLast(".")
}

/**
 * URI에서 파일 크기 가져오기
 *
 * @return 파일 크기 (bytes) 또는 null
 */
fun Uri.getFileSize(context: Context): Long? {
    return try {
        context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) {
                    cursor.getLong(sizeIndex)
                } else null
            } else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get file size: ${e.message}")
        null
    }
}

/**
 * URI를 InputStream으로 열기
 *
 * @return InputStream 또는 null
 */
fun Uri.openInputStream(context: Context): InputStream? {
    return try {
        context.contentResolver.openInputStream(this)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open input stream: ${e.message}")
        null
    }
}

/**
 * URI를 캐시 파일로 복사
 *
 * @param context Context
 * @param targetName 대상 파일명 (선택사항)
 * @return 복사된 File 또는 null
 */
fun Uri.copyToCache(context: Context, targetName: String? = null): File? {
    return try {
        val fileName = targetName ?: getDisplayName(context) ?: "temp_${System.currentTimeMillis()}"
        val cacheFile = File(context.cacheDir, fileName)

        openInputStream(context)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Log.d(TAG, "✅ Copied to cache: ${cacheFile.absolutePath}")
        cacheFile
    } catch (e: Exception) {
        Log.e(TAG, "❌ Failed to copy to cache: ${e.message}")
        null
    }
}

/**
 * URI가 유효한지 확인
 *
 * @param context Context
 * @return true if valid
 */
fun Uri.isValid(context: Context): Boolean {
    return try {
        context.contentResolver.query(this, null, null, null, null)?.use {
            it.count > 0
        } ?: false
    } catch (e: Exception) {
        false
    }
}

/**
 * URI를 DocumentFile로 변환
 *
 * @param context Context
 * @return DocumentFile 또는 null
 */
fun Uri.toDocumentFile(context: Context): DocumentFile? {
    return try {
        DocumentFile.fromTreeUri(context, this)
            ?: DocumentFile.fromSingleUri(context, this)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to convert to DocumentFile: ${e.message}")
        null
    }
}

/**
 * URI에 대한 영구 권한 요청
 *
 * @param context Context
 * @return true if successful
 */
fun Uri.takePersistablePermission(context: Context): Boolean {
    return SafHelper.takePersistableUriPermission(context, this)
}

/**
 * URI가 content:// 스킴인지 확인
 *
 * @return true if content URI
 */
fun Uri.isContentUri(): Boolean {
    return scheme == "content"
}

/**
 * URI가 file:// 스킴인지 확인
 *
 * @return true if file URI
 */
fun Uri.isFileUri(): Boolean {
    return scheme == "file"
}

/**
 * URI의 MIME 타입 가져오기
 *
 * @param context Context
 * @return MIME 타입 또는 null
 */
fun Uri.getMimeType(context: Context): String? {
    return try {
        context.contentResolver.getType(this)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get MIME type: ${e.message}")
        null
    }
}

/**
 * URI가 읽기 가능한지 확인
 *
 * @param context Context
 * @return true if readable
 */
fun Uri.isReadable(context: Context): Boolean {
    return try {
        toDocumentFile(context)?.canRead() == true
    } catch (e: Exception) {
        false
    }
}

/**
 * URI가 쓰기 가능한지 확인
 *
 * @param context Context
 * @return true if writable
 */
fun Uri.isWritable(context: Context): Boolean {
    return try {
        toDocumentFile(context)?.canWrite() == true
    } catch (e: Exception) {
        false
    }
}