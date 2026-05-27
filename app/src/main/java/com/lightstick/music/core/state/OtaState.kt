package com.lightstick.music.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OTA 업데이트 진행 상태 공유 홀더
 *
 * ViewModel 을 직접 참조할 수 없는 컴포넌트(EffectEngineController, DeviceEventEffectSender 등)가
 * OTA 진행 여부를 확인해 이펙트 전송을 차단할 수 있도록 싱글톤으로 제공합니다.
 *
 * ## 업데이트 주체
 * [DeviceViewModel] 에서 _otaInProgress 맵이 변경될 때마다 갱신합니다.
 *
 * ## 읽기 주체
 * [EffectEngineController] 에서 이펙트 전송 전 OTA 차단 여부 판단에 사용합니다.
 */
object OtaState {

    private val _isAnyInProgress = MutableStateFlow(false)
    val isAnyInProgress: StateFlow<Boolean> = _isAnyInProgress.asStateFlow()

    internal fun update(inProgressMap: Map<String, Boolean>) {
        _isAnyInProgress.value = inProgressMap.values.any { it }
    }
}
