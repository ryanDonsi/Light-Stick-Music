package com.lightstick.music.core.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * MediaMetadataRetriever 래퍼
 *
 * 음악 파일의 메타데이터(앨범아트, 재생시간, 제목, 아티스트 등)를 추출하는
 * 중복 로직을 통합 관리
 *
 * 주요 기능:
 * - 앨범아트 추출 및 캐싱
 * - 재생 시간 추출
 * - 안전한 리소스 해제
 */
object MediaMetadataExtractor {

    private const val TAG = "MediaMetadataExtractor"

    /**
     * 음악 메타데이터 결과
     */
    data class MusicMetadata(
        val albumArtPath: String? = null,
        val duration: Long = 0L,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null
    )

    /**
     * File에서 메타데이터 추출
     *
     * @param context Context
     * @param musicFile 음악 파일
     * @param cacheAlbumArt 앨범아트를 캐시할지 여부 (기본: true)
     * @return MusicMetadata 또는 실패 시 빈 메타데이터
     */
    fun extractMetadata(
        context: Context,
        musicFile: File,
        cacheAlbumArt: Boolean = true
    ): MusicMetadata {
        if (!musicFile.exists()) {
            Log.w(TAG, "Music file does not exist: ${musicFile.absolutePath}")
            return MusicMetadata()
        }

        return extractMetadataInternal(
            path = musicFile.absolutePath,
            cacheKey = musicFile.name,
            context = context,
            cacheAlbumArt = cacheAlbumArt
        )
    }

    /**
     * URI에서 메타데이터 추출
     *
     * @param context Context
     * @param musicUri 음악 URI
     * @param cacheKey 캐시 키 (일반적으로 파일명)
     * @param cacheAlbumArt 앨범아트를 캐시할지 여부 (기본: true)
     * @return MusicMetadata 또는 실패 시 빈 메타데이터
     */
    fun extractMetadata(
        context: Context,
        musicUri: Uri,
        cacheKey: String,
        cacheAlbumArt: Boolean = true
    ): MusicMetadata {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, musicUri)

            val albumArtPath = if (cacheAlbumArt) {
                extractAlbumArt(retriever, context, cacheKey)
            } else null

            val metadata = MusicMetadata(
                albumArtPath = albumArtPath,
                duration = extractDuration(retriever),
                title = extractString(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = extractString(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = extractString(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM)
            )

            retriever.release()
            metadata

        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract metadata from URI: ${e.message}")
            MusicMetadata()
        }
    }

    /**
     * Path에서 메타데이터 추출 (내부용)
     */
    private fun extractMetadataInternal(
        path: String,
        cacheKey: String,
        context: Context,
        cacheAlbumArt: Boolean
    ): MusicMetadata {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(path)

            val albumArtPath = if (cacheAlbumArt) {
                extractAlbumArt(retriever, context, cacheKey)
            } else null

            MusicMetadata(
                albumArtPath = albumArtPath,
                duration = extractDuration(retriever),
                title = extractString(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = extractString(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = extractString(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract metadata from path: ${e.message}")
            MusicMetadata()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release retriever: ${e.message}")
            }
        }
    }

    /**
     * 앨범아트 추출 및 캐싱
     */
    private fun extractAlbumArt(
        retriever: MediaMetadataRetriever,
        context: Context,
        cacheKey: String
    ): String? {
        return try {
            val artBytes = retriever.embeddedPicture ?: return null

            // 캐시 파일명 생성 (해시 기반)
            val cacheFileName = "${cacheKey.hashCode()}.jpg"
            val cacheFile = File(context.cacheDir, cacheFileName)

            // 이미 캐시 파일이 있으면 재사용
            if (cacheFile.exists()) {
                return cacheFile.absolutePath
            }

            // 캐시 파일 저장
            cacheFile.writeBytes(artBytes)
            cacheFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract album art: ${e.message}")
            null
        }
    }

    /**
     * 재생 시간 추출
     */
    private fun extractDuration(retriever: MediaMetadataRetriever): Long {
        return try {
            val durationStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract duration: ${e.message}")
            0L
        }
    }

    /**
     * 문자열 메타데이터 추출
     */
    private fun extractString(
        retriever: MediaMetadataRetriever,
        key: Int
    ): String? {
        return try {
            retriever.extractMetadata(key)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 앨범아트만 추출 (빠른 추출용)
     *
     * @param context Context
     * @param musicFile 음악 파일
     * @return 앨범아트 경로 또는 null
     */
    fun extractAlbumArtOnly(context: Context, musicFile: File): String? {
        if (!musicFile.exists()) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(musicFile.absolutePath)
            extractAlbumArt(retriever, context, musicFile.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract album art: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release retriever: ${e.message}")
            }
        }
    }

    /**
     * 재생 시간만 추출 (빠른 추출용)
     *
     * @param context Context
     * @param musicUri 음악 URI
     * @return 재생 시간 (밀리초) 또는 0
     */
    fun extractDurationOnly(context: Context, musicUri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, musicUri)
            extractDuration(retriever)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract duration: ${e.message}")
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release retriever: ${e.message}")
            }
        }
    }

    /**
     * 캐시된 앨범아트 삭제
     *
     * @param context Context
     * @param maxCacheSize 최대 캐시 개수 (기본: 100)
     */
    fun clearAlbumArtCache(context: Context, maxCacheSize: Int = 100) {
        try {
            val cacheDir = context.cacheDir
            val albumArtFiles = cacheDir.listFiles { file ->
                file.extension == "jpg" && file.name.contains(".jpg")
            } ?: return

            // 오래된 파일부터 삭제
            if (albumArtFiles.size > maxCacheSize) {
                albumArtFiles
                    .sortedBy { it.lastModified() }
                    .take(albumArtFiles.size - maxCacheSize)
                    .forEach { it.delete() }

                Log.d(TAG, "Cleared ${albumArtFiles.size - maxCacheSize} old album art files")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear album art cache: ${e.message}")
        }
    }
}