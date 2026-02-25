package com.lightstick.music.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel 간 음악 재생 상태 공유 홀더
 *
 * ## 사용 목적
 * EffectViewModel 이 MusicViewModel 을 직접 참조하지 않고도
 * "음악 재생 중 + AUTO 모드 활성화" 여부를 알 수 있도록 싱글톤으로 제공합니다.
 *
 * ## 업데이트 주체
 * [MusicViewModel] 에서 isPlaying + isAutoModeEnabled 상태가 변경될 때마다 갱신합니다.
 *
 * ## 읽기 주체
 * [EffectViewModel] 에서 EXCLUSIVE 모드의 Effect 잠금 여부를 판단할 때 사용합니다.
 */
object MusicPlaybackState {

    // ── 음악 재생 중 + AUTO 모드 동시 활성화 여부 ──────────────────────
    private val _isPlayingWithAutoMode = MutableStateFlow(false)
    val isPlayingWithAutoMode: StateFlow<Boolean> = _isPlayingWithAutoMode.asStateFlow()

    internal fun update(isPlaying: Boolean, isAutoMode: Boolean) {
        _isPlayingWithAutoMode.value = isPlaying && isAutoMode
    }

    // 앱 종료 / 테스트 등에서 명시적 초기화용
    internal fun reset() {
        _isPlayingWithAutoMode.value = false
    }
}