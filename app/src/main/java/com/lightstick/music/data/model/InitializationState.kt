package com.lightstick.music.data.model

/**
 * 초기화 진행 상태
 */
sealed class InitializationState {
    object Idle : InitializationState()

    data class CheckingPermissions(val progress: Int) : InitializationState()
    data class PermissionDenied(val permission: String) : InitializationState()

    data class ScanningMusic(val scanned: Int, val total: Int) : InitializationState()
    data class CalculatingMusicIds(val calculated: Int, val total: Int) : InitializationState()

    object ConfiguringEffectsDirectory : InitializationState()
    data class ScanningEffects(val scanned: Int, val total: Int) : InitializationState()

    data class MatchingEffects(val matched: Int, val total: Int) : InitializationState()

    data class Completed(
        val musicCount: Int,
        val effectCount: Int,
        val matchedCount: Int
    ) : InitializationState()

    data class Error(val message: String) : InitializationState()
}

/**
 * 초기화 결과
 */
data class InitializationResult(
    val musicList: List<MusicItem>,
    val effectCount: Int,
    val matchedCount: Int,
    val duration: Long
)