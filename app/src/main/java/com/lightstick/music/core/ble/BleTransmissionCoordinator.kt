package com.lightstick.music.core.ble

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.ble.TransmissionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE 전송 중앙 제어 시스템
 *
 * 모든 BLE 전송의 제어권을 관리하고 충돌을 방지합니다.
 * - 우선순위 기반 제어권 관리
 * - EXCLUSIVE/COOPERATIVE/BACKGROUND 모드 지원
 * - 소스 간 호환성 체크
 */
object BleTransmissionCoordinator {

    @Suppress("unused")
    private const val TAG = AppConstants.Feature.BLE_COORDINATOR

    private val _currentController = MutableStateFlow<ControllerState?>(null)
    val currentController: StateFlow<ControllerState?> = _currentController.asStateFlow()

    /**
     * 현재 활성 세션
     *
     * 세션이 활성화되면 해당 소스가 독점적으로 BLE를 제어합니다.
     * - Manual Effect 시작 → activeSession = MANUAL_EFFECT
     * - 이 동안 FFT는 전송 불가 (깜빡임 방지)
     * - Manual Effect 종료 → activeSession = null
     */
    private var activeSession: TransmissionSource? = null

    /**
     * 세션 시작 (제어권 획득 + 독점 플래그 설정)
     */
    @Synchronized
    fun startSession(
        source: TransmissionSource,
        priority: Int,
        mode: ControlMode = ControlMode.EXCLUSIVE,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        val acquired = requestControl(source, priority, mode, metadata)
        if (acquired) {
            activeSession = source
        }
        return acquired
    }

    /**
     * 세션 종료 (제어권 해제 + 독점 플래그 해제)
     */
    @Synchronized
    fun endSession(source: TransmissionSource) {
        if (activeSession == source) {
            activeSession = null
            releaseControl(source)
        }
    }

    fun getActiveSession(): TransmissionSource? = activeSession
    fun isSessionActive(source: TransmissionSource): Boolean = activeSession == source
    fun hasActiveSession(): Boolean = activeSession != null

    /**
     * 제어권 요청 (단발성 전송용)
     */
    @Synchronized
    fun requestControl(
        source: TransmissionSource,
        priority: Int,
        mode: ControlMode = ControlMode.EXCLUSIVE,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        val current = _currentController.value

        if (current == null) {
            _currentController.value = ControllerState(source, priority, mode, metadata = metadata)
            return true
        }

        if (current.source == source) {
            _currentController.value = current.copy(
                priority = priority,
                mode = mode,
                metadata = metadata,
                acquiredAt = System.currentTimeMillis()
            )
            return true
        }

        return when {
            TransmissionPriority.hasHigherPriority(priority, current.priority) -> {
                _currentController.value = ControllerState(source, priority, mode, metadata = metadata)
                true
            }

            current.mode == ControlMode.BACKGROUND -> {
                _currentController.value = ControllerState(source, priority, mode, metadata = metadata)
                true
            }

            current.mode == ControlMode.COOPERATIVE && isCompatible(current.source, source) -> true

            else -> false
        }
    }

    /**
     * 제어권 해제
     */
    @Synchronized
    fun releaseControl(source: TransmissionSource) {
        if (_currentController.value?.source == source) {
            _currentController.value = null
        }
    }

    /**
     * 모든 제어권 강제 해제
     */
    @Synchronized
    fun forceReleaseAll() {
        activeSession = null
        _currentController.value = null
    }

    /**
     * 전송 허가 확인
     */
    fun canTransmit(source: TransmissionSource): Boolean {
        if (activeSession != null) {
            val compatible = activeSession == source || isCompatible(activeSession!!, source)
            if (!compatible) return false
        }

        val current = _currentController.value
        return when {
            current == null -> true
            current.source == source -> true
            current.mode == ControlMode.COOPERATIVE && isCompatible(current.source, source) -> true
            else -> false
        }
    }

    /**
     * 전송 (제어권 체크 후 모니터 기록)
     */
    fun sendEffect(event: BleTransmissionEvent): Boolean {
        if (!canTransmit(event.source)) return false
        BleTransmissionMonitor.recordTransmission(event)
        return true
    }

    private fun isCompatible(source1: TransmissionSource, source2: TransmissionSource): Boolean {
        val compatiblePairs = setOf(
            setOf(TransmissionSource.EFX_EFFECT, TransmissionSource.FFT_EFFECT),
            setOf(TransmissionSource.TIMELINE_EFFECT, TransmissionSource.FFT_EFFECT)
        )
        return compatiblePairs.any { it.contains(source1) && it.contains(source2) }
    }
}

/**
 * 제어권 상태
 */
data class ControllerState(
    val source: TransmissionSource,
    val priority: Int,
    val mode: ControlMode,
    val acquiredAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
)
