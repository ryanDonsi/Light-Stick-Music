package com.dongsitech.lightstickmusicdemo.util

/**
 * 시간 포맷 유틸리티
 */
object TimeFormatter {
    /**
     * 밀리초를 MM:SS 형식으로 변환
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
}