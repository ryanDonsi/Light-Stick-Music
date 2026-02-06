package com.lightstick.music.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.lightstick.music.core.constants.AppConstants
import java.io.File

/**
 * 파일 처리 유틸리티
 *
 * 파일명, 확장자, 경로 등 파일 관련 공통 기능 제공
 */
object FileHelper {

    private const val TAG = "FileHelper"

    /**
     * 파일이 EFX 파일인지 확인
     *
     * @param file 확인할 파일
     * @return true if EFX file
     */
    fun File.isEfxFile(): Boolean {
        return extension.equals(AppConstants.EFX_FILE_EXTENSION, ignoreCase = true)
    }

    /**
     * 파일이 지원하는 음악 파일인지 확인
     *
     * @param file 확인할 파일
     * @return true if supported audio file
     */
    fun File.isSupportedAudioFile(): Boolean {
        return AppConstants.SUPPORTED_AUDIO_EXTENSIONS.contains(
            extension.lowercase()
        )
    }

    /**
     * URI에서 파일명 추출 (확장자 포함)
     *
     * @param context Context
     * @param uri 파일 URI
     * @return 파일명 또는 null
     */
    fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file name: ${e.message}")
            null
        }
    }

    /**
     * URI에서 파일명 추출 (확장자 제외)
     *
     * @param context Context
     * @param uri 파일 URI
     * @return 확장자를 제외한 파일명 또는 null
     */
    fun getFileNameWithoutExtension(context: Context, uri: Uri): String? {
        return getFileName(context, uri)?.substringBeforeLast(".")
    }

    /**
     * URI에서 파일 크기 가져오기
     *
     * @param context Context
     * @param uri 파일 URI
     * @return 파일 크기 (bytes) 또는 0
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        cursor.getLong(sizeIndex)
                    } else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size: ${e.message}")
            0L
        }
    }

    /**
     * 파일 크기를 읽기 쉬운 형식으로 변환
     *
     * @param bytes 파일 크기 (bytes)
     * @return 형식화된 문자열 (예: "1.5 MB", "345 KB")
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * 파일 확장자 가져오기 (소문자)
     *
     * @param fileName 파일명
     * @return 확장자 (예: "mp3", "efx") 또는 빈 문자열
     */
    fun getExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex >= 0) {
            fileName.substring(lastDotIndex + 1).lowercase()
        } else {
            ""
        }
    }

    /**
     * 안전한 파일명 생성 (특수문자 제거)
     *
     * @param fileName 원본 파일명
     * @return 안전한 파일명
     */
    fun sanitizeFileName(fileName: String): String {
        // 파일 시스템에서 허용되지 않는 문자 제거
        return fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(255) // 파일명 최대 길이 제한
    }

    /**
     * 임시 파일 생성
     *
     * @param context Context
     * @param prefix 파일명 접두사
     * @param extension 확장자 (예: "tmp", "cache")
     * @return 생성된 임시 파일
     */
    fun createTempFile(
        context: Context,
        prefix: String = "temp",
        extension: String = "tmp"
    ): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "${prefix}_${timestamp}.$extension"
        return File(context.cacheDir, fileName)
    }

    /**
     * 캐시 디렉토리 정리
     *
     * @param context Context
     * @param maxAgeMillis 최대 보관 시간 (기본: 7일)
     */
    fun clearOldCacheFiles(
        context: Context,
        maxAgeMillis: Long = 7 * 24 * 60 * 60 * 1000L // 7일
    ) {
        try {
            val currentTime = System.currentTimeMillis()
            val cacheDir = context.cacheDir

            val deletedCount = cacheDir.listFiles()?.count { file ->
                val age = currentTime - file.lastModified()
                if (age > maxAgeMillis) {
                    file.delete()
                    true
                } else {
                    false
                }
            } ?: 0

            if (deletedCount > 0) {
                Log.d(TAG, "Cleared $deletedCount old cache files")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear old cache files: ${e.message}")
        }
    }

    /**
     * 디렉토리 크기 계산
     *
     * @param directory 디렉토리
     * @return 총 크기 (bytes)
     */
    fun getDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory) return 0L

        return try {
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate directory size: ${e.message}")
            0L
        }
    }

    /**
     * 파일 존재 여부 확인 (안전)
     *
     * @param filePath 파일 경로
     * @return true if exists
     */
    fun fileExists(filePath: String?): Boolean {
        if (filePath.isNullOrEmpty()) return false

        return try {
            File(filePath).exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 파일 복사
     *
     * @param source 원본 파일
     * @param destination 대상 파일
     * @return true if successful
     */
    fun copyFile(source: File, destination: File): Boolean {
        return try {
            source.copyTo(destination, overwrite = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file: ${e.message}")
            false
        }
    }

    /**
     * 파일 이동
     *
     * @param source 원본 파일
     * @param destination 대상 파일
     * @return true if successful
     */
    fun moveFile(source: File, destination: File): Boolean {
        return try {
            if (copyFile(source, destination)) {
                source.delete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move file: ${e.message}")
            false
        }
    }

    /**
     * 고유 파일명 생성 (중복 방지)
     *
     * @param directory 디렉토리
     * @param baseName 기본 파일명
     * @param extension 확장자
     * @return 고유한 파일
     */
    fun createUniqueFile(
        directory: File,
        baseName: String,
        extension: String
    ): File {
        var counter = 1
        var file = File(directory, "$baseName.$extension")

        while (file.exists()) {
            file = File(directory, "${baseName}_$counter.$extension")
            counter++
        }

        return file
    }
}