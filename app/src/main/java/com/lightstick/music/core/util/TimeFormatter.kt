package com.lightstick.music.core.util

/**
 * 시간 포맷 유틸리티
 *
 * 다양한 시간 형식 변환 기능 제공:
 * - MM:SS (일반 재생 시간)
 * - HH:MM:SS (1시간 이상)
 * - MM:SS.mmm (밀리초 포함, EFX 타임라인용)
 */
object TimeFormatter {

    /**
     * 밀리초를 MM:SS 형식으로 변환
     *
     * @param millis 변환할 밀리초 값
     * @return "M:SS" 형식의 문자열 (예: "3:45", "12:03")
     *
     * @sample
     * ```
     * formatTime(0)       // "0:00"
     * formatTime(45000)   // "0:45"
     * formatTime(125000)  // "2:05"
     * formatTime(3665000) // "61:05"
     * ```
     */
    fun formatTime(millis: Long): String {
        if (millis <= 0) return "0:00"

        val seconds = (millis / 1000).toInt()
        val minutes = seconds / 60
        val secs = seconds % 60

        return String.format("%d:%02d", minutes, secs)
    }

    /**
     * 밀리초를 HH:MM:SS 형식으로 변환 (1시간 이상)
     *
     * @param millis 변환할 밀리초 값
     * @return "H:MM:SS" 또는 "M:SS" 형식의 문자열
     *
     * @sample
     * ```
     * formatTimeLong(0)        // "0:00"
     * formatTimeLong(45000)    // "0:45"
     * formatTimeLong(3665000)  // "1:01:05"
     * formatTimeLong(36005000) // "10:00:05"
     * ```
     */
    fun formatTimeLong(millis: Long): String {
        if (millis <= 0) return "0:00"

        val seconds = (millis / 1000).toInt()
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    /**
     * 밀리초를 MM:SS.mmm 형식으로 변환 (밀리초 포함)
     *
     * EFX 타임라인 편집 등 정밀한 시간 표시가 필요한 곳에서 사용
     *
     * @param timestampMs 변환할 밀리초 값
     * @return "MM:SS.mmm" 형식의 문자열 (예: "02:03.450")
     *
     * @sample
     * ```
     * formatTimeWithMillis(0)      // "00:00.000"
     * formatTimeWithMillis(1500)   // "00:01.500"
     * formatTimeWithMillis(65432)  // "01:05.432"
     * formatTimeWithMillis(125678) // "02:05.678"
     * ```
     */
    fun formatTimeWithMillis(timestampMs: Long): String {
        if (timestampMs < 0) return "00:00.000"

        val minutes = (timestampMs / 60000).toInt()
        val seconds = ((timestampMs % 60000) / 1000).toInt()
        val millis = (timestampMs % 1000).toInt()

        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    /**
     * MM:SS.mmm 형식의 문자열을 밀리초로 변환
     *
     * @param timeString "MM:SS.mmm" 형식의 문자열
     * @return 밀리초 값, 파싱 실패 시 0
     *
     * @sample
     * ```
     * parseTimeWithMillis("00:00.000") // 0
     * parseTimeWithMillis("00:01.500") // 1500
     * parseTimeWithMillis("01:05.432") // 65432
     * parseTimeWithMillis("02:05.678") // 125678
     * ```
     */
    fun parseTimeWithMillis(timeString: String): Long {
        return try {
            // "02:05.678" 형식 파싱
            val parts = timeString.split(":", ".")
            if (parts.size != 3) return 0

            val minutes = parts[0].toIntOrNull() ?: 0
            val seconds = parts[1].toIntOrNull() ?: 0
            val millis = parts[2].toIntOrNull() ?: 0

            (minutes * 60000L) + (seconds * 1000L) + millis
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 두 시간 사이의 차이를 계산
     *
     * @param startMillis 시작 시간 (밀리초)
     * @param endMillis 종료 시간 (밀리초)
     * @return 차이 (밀리초), 음수일 경우 0 반환
     */
    fun getDuration(startMillis: Long, endMillis: Long): Long {
        return (endMillis - startMillis).coerceAtLeast(0)
    }

    /**
     * 진행률 계산 (0.0 ~ 1.0)
     *
     * @param currentMillis 현재 위치 (밀리초)
     * @param totalMillis 전체 길이 (밀리초)
     * @return 진행률 (0.0 ~ 1.0)
     */
    fun getProgress(currentMillis: Long, totalMillis: Long): Float {
        if (totalMillis <= 0) return 0f
        return (currentMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
    }
}