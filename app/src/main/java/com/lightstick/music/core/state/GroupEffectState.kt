package com.lightstick.music.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 그룹 Effect(그룹 배정, groupMask 지정 Effect) 허용 여부 공유 홀더
 *
 * ViewModel 을 직접 참조할 수 없는 컴포넌트(PlayEffectListUseCase 등)가 FF06(Device Mode) 기준
 * 그룹 Effect 허용 여부를 확인해 groupMask 지정 Effect 전송을 차단할 수 있도록 싱글톤으로 제공합니다.
 *
 * ## 업데이트 주체
 * [DeviceViewModel] 에서 연결된 기기들의 FF06(Device Mode)을 읽어 갱신합니다.
 *
 * ## 읽기 주체
 * [PlayEffectListUseCase], [EffectViewModel] 등에서 그룹 지정 Effect 전송 전 차단 여부 판단에 사용합니다.
 */
object GroupEffectState {

    private val _isAllowed = MutableStateFlow(true)
    val isAllowed: StateFlow<Boolean> = _isAllowed.asStateFlow()

    internal fun update(allowed: Boolean) {
        _isAllowed.value = allowed
    }
}
