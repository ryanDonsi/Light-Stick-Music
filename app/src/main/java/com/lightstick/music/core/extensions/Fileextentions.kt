package com.lightstick.music.core.extensions

import com.lightstick.music.core.constants.AppConstants
import java.io.File

/**
 * File 확장 함수
 *
 * File 처리를 간편하게 하는 확장 함수 모음
 */

/**
 * 파일이 EFX 파일인지 확인
 *
 * @return true if EFX file
 */
fun File.isEfxFile(): Boolean {
    return extension.equals(AppConstants.EFX_FILE_EXTENSION, ignoreCase = true)
}

/**
 * 파일이 지원하는 음악 파일인지 확인
 *
 * @return true if supported audio file
 */
fun File.isSupportedAudioFile(): Boolean {
    return AppConstants.SUPPORTED_AUDIO_EXTENSIONS.contains(extension.lowercase())
}

/**
 * 파일이 MP3 파일인지 확인
 *
 * @return true if MP3 file
 */
fun File.isMp3File(): Boolean {
    return extension.equals(AppConstants.MP3_FILE_EXTENSION, ignoreCase = true)
}

/**
 * 파일이 WAV 파일인지 확인
 *
 * @return true if WAV file
 */
fun File.isWavFile(): Boolean {
    return extension.equals(AppConstants.WAV_FILE_EXTENSION, ignoreCase = true)
}

/**
 * 파일이 FLAC 파일인지 확인
 *
 * @return true if FLAC file
 */
fun File.isFlacFile(): Boolean {
    return extension.equals(AppConstants.FLAC_FILE_EXTENSION, ignoreCase = true)
}

/**
 * 확장자를 제외한 파일명 가져오기
 *
 * @return 확장자를 제외한 파일명
 */
fun File.nameWithoutExtension(): String {
    return nameWithoutExtension
}

/**
 * 파일 크기를 읽기 쉬운 형식으로 변환
 *
 * @return 형식화된 문자열 (예: "1.5 MB", "345 KB")
 */
fun File.getReadableSize(): String {
    val bytes = length()
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * 파일이 비어있는지 확인
 *
 * @return true if empty (size = 0)
 */
fun File.isEmpty(): Boolean {
    return length() == 0L
}

/**
 * 파일이 비어있지 않은지 확인
 *
 * @return true if not empty
 */
fun File.isNotEmpty(): Boolean {
    return length() > 0L
}

/**
 * 파일 크기가 제한보다 작은지 확인
 *
 * @param maxSizeBytes 최대 크기 (bytes)
 * @return true if within size limit
 */
fun File.isWithinSizeLimit(maxSizeBytes: Long): Boolean {
    return length() <= maxSizeBytes
}

/**
 * 파일이 너무 오래되었는지 확인
 *
 * @param maxAgeMillis 최대 보관 시간 (밀리초)
 * @return true if older than maxAge
 */
fun File.isOlderThan(maxAgeMillis: Long): Boolean {
    val age = System.currentTimeMillis() - lastModified()
    return age > maxAgeMillis
}

/**
 * 파일이 최근 생성되었는지 확인
 *
 * @param maxAgeMillis 최근 기준 시간 (밀리초)
 * @return true if created within maxAge
 */
fun File.isNewerThan(maxAgeMillis: Long): Boolean {
    val age = System.currentTimeMillis() - lastModified()
    return age < maxAgeMillis
}

/**
 * 파일을 안전하게 삭제
 *
 * 파일이 존재하지 않아도 예외를 발생시키지 않음
 *
 * @return true if deleted or doesn't exist
 */
fun File.safeDelete(): Boolean {
    return if (exists()) {
        delete()
    } else {
        true
    }
}

/**
 * 파일을 다른 디렉토리로 이동
 *
 * @param destinationDir 대상 디렉토리
 * @param newName 새 파일명 (선택사항)
 * @return 이동된 파일 또는 null
 */
fun File.moveTo(destinationDir: File, newName: String? = null): File? {
    if (!exists()) return null
    if (!destinationDir.exists()) destinationDir.mkdirs()

    val targetName = newName ?: name
    val targetFile = File(destinationDir, targetName)

    return if (renameTo(targetFile)) {
        targetFile
    } else {
        // renameTo 실패 시 복사 후 삭제
        try {
            copyTo(targetFile, overwrite = true)
            if (delete()) targetFile else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 디렉토리의 모든 EFX 파일 가져오기
 *
 * @return EFX 파일 목록
 */
fun File.listEfxFiles(): List<File> {
    if (!isDirectory) return emptyList()
    return listFiles { file -> file.isEfxFile() }?.toList() ?: emptyList()
}

/**
 * 디렉토리의 모든 음악 파일 가져오기
 *
 * @return 음악 파일 목록
 */
fun File.listAudioFiles(): List<File> {
    if (!isDirectory) return emptyList()
    return listFiles { file -> file.isSupportedAudioFile() }?.toList() ?: emptyList()
}

/**
 * 디렉토리의 총 크기 계산
 *
 * @return 총 크기 (bytes)
 */
fun File.getTotalSize(): Long {
    if (!exists()) return 0L
    if (isFile) return length()

    return walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * 디렉토리 내 파일 개수 계산
 *
 * @param recursive 하위 디렉토리 포함 여부
 * @return 파일 개수
 */
fun File.countFiles(recursive: Boolean = false): Int {
    if (!isDirectory) return 0

    return if (recursive) {
        walkTopDown().count { it.isFile }
    } else {
        listFiles()?.count { it.isFile } ?: 0
    }
}

/**
 * 빈 디렉토리인지 확인
 *
 * @return true if directory is empty
 */
fun File.isEmptyDirectory(): Boolean {
    if (!isDirectory) return false
    return listFiles()?.isEmpty() ?: true
}

/**
 * 파일 확장자 변경
 *
 * @param newExtension 새 확장자 (점 제외)
 * @return 확장자가 변경된 새 File 객체
 */
fun File.withExtension(newExtension: String): File {
    val nameWithoutExt = nameWithoutExtension
    return File(parent, "$nameWithoutExt.$newExtension")
}

/**
 * 파일명에 접미사 추가
 *
 * @param suffix 추가할 접미사
 * @return 접미사가 추가된 새 File 객체
 */
fun File.withSuffix(suffix: String): File {
    val nameWithoutExt = nameWithoutExtension
    val ext = extension
    return if (ext.isNotEmpty()) {
        File(parent, "${nameWithoutExt}${suffix}.$ext")
    } else {
        File(parent, "${nameWithoutExt}${suffix}")
    }
}