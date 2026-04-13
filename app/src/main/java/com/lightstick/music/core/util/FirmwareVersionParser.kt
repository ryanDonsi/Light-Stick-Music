package com.lightstick.music.core.util

import android.util.Log
import java.util.zip.ZipInputStream

/**
 * 펌웨어 바이너리에서 버전 정보 추출 유틸
 *
 * [테스트 모드]
 *   파일에서 버전 파싱 불가 시 → 디바이스 현재 버전의 minor version +1 을 시뮬레이션 버전으로 사용
 *
 * [TODO] 최종 구현 시 서버 기반 OTA로 전환:
 *   - 서버 API로 최신 펌웨어 버전 조회
 *   - 서버 응답의 버전 문자열을 직접 사용 (파일 파싱 불필요)
 *   - 서버에서 다운로드 URL 수신 → 파일 다운로드 → startOta() 호출
 */
object FirmwareVersionParser {

    private const val TAG = "FirmwareVersionParser"

    // ZIP 파일 매직 바이트 (Nordic DFU 패키지 감지용)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * 펌웨어 바이너리에서 버전 문자열 추출 시도
     *
     * 지원 포맷:
     *  1. Nordic DFU ZIP 패키지 → manifest.json의 "version" 필드
     *  2. Raw binary → X.Y.Z 형식 ASCII 문자열 탐색
     *
     * @return 파싱된 버전 문자열, 실패 시 null
     */
    fun parseFromBytes(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        return try {
            if (isDfuZip(bytes)) parseFromDfuZip(bytes) else findVersionInBinary(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "버전 파싱 실패: ${e.message}")
            null
        }
    }

    /**
     * [테스트용] 현재 버전의 minor version +1 버전 생성
     *
     * 예) "1.2.3" → "1.3.3"
     *     "1.2"   → "1.3.0"
     *     "2"     → "2.1.0"
     */
    fun simulateTestVersion(currentVersion: String): String {
        return try {
            val parts = currentVersion.trim().split(".").toMutableList()
            while (parts.size < 3) parts.add("0")
            val minor = (parts[1].toIntOrNull() ?: 0) + 1
            "${parts[0]}.$minor.${parts[2]}"
        } catch (e: Exception) {
            "$currentVersion-new"
        }
    }

    /**
     * fileVersion이 deviceVersion보다 최신인지 여부
     */
    fun isNewerVersion(fileVersion: String, deviceVersion: String): Boolean =
        compareVersions(fileVersion, deviceVersion) > 0

    /**
     * 시맨틱 버전 비교 (X.Y.Z)
     * @return 양수: v1 > v2 / 0: 동일 / 음수: v1 < v2
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(parts1.size, parts2.size)
        for (i in 0 until len) {
            val diff = parts1.getOrElse(i) { 0 } - parts2.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun isDfuZip(bytes: ByteArray): Boolean =
        bytes[0] == ZIP_MAGIC[0] && bytes[1] == ZIP_MAGIC[1] &&
        bytes[2] == ZIP_MAGIC[2] && bytes[3] == ZIP_MAGIC[3]

    /** Nordic DFU ZIP 패키지의 manifest.json에서 "version" 필드 추출 */
    private fun parseFromDfuZip(bytes: ByteArray): String? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(content)
                    return match?.groupValues?.get(1)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** Raw binary에서 X.Y.Z 형식 버전 문자열 탐색 */
    private fun findVersionInBinary(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        return Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""").find(text)?.value
    }
}
