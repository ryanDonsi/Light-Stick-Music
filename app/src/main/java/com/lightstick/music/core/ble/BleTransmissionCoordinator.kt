package com.lightstick.music.core.ble

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.music.domain.ble.BleTransmissionEvent
import java.util.concurrent.CopyOnWriteArrayList
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.ble.TransmissionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE 전송 중앙 제어 시스템
 *
 * 모든 BLE 전송의 제어권을 관리하고 충돌을 방지합니다.
 *
 * 주요 기능:
 * - 우선순위 기반 제어권 관리
 * - EXCLUSIVE/COOPERATIVE/BACKGROUND 모드 지원
 * - 소스 간 호환성 체크
 * - 자동 모니터 기록
 *
 * 수정: AppConstants.VERBOSE_LOGGING 제거됨에 따라
 *        → BuildConfig.DEBUG 로 교체 (릴리즈 빌드에서 자동 제거)
 */
object BleTransmissionCoordinator {

    private const val TAG = AppConstants.Feature.BLE_COORDINATOR
    private const val MAX_HISTORY_SIZE = 100

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
    private var sessionStartTime: Long = 0
    private val controllerHistory = mutableListOf<ControllerState>()
    private val controlChangeListeners = CopyOnWriteArrayList<(ControllerState?) -> Unit>()

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
            sessionStartTime = System.currentTimeMillis()
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
            sessionStartTime = 0
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
            val newState = ControllerState(source, priority, mode, metadata = metadata)
            _currentController.value = newState
            controllerHistory.add(newState)
            notifyControlChange(newState)
            return true
        }

        if (current.source == source) {
            val updated = current.copy(
                priority = priority,
                mode = mode,
                metadata = metadata,
                acquiredAt = System.currentTimeMillis()
            )
            _currentController.value = updated
            return true
        }

        return when {
            TransmissionPriority.hasHigherPriority(priority, current.priority) -> {
                val newState = ControllerState(source, priority, mode, metadata = metadata)
                _currentController.value = newState
                controllerHistory.add(newState)
                if (controllerHistory.size > MAX_HISTORY_SIZE) controllerHistory.removeAt(0)
                notifyControlChange(newState)
                true
            }

            current.mode == ControlMode.BACKGROUND -> {
                val newState = ControllerState(source, priority, mode, metadata = metadata)
                _currentController.value = newState
                controllerHistory.add(newState)
                if (controllerHistory.size > MAX_HISTORY_SIZE) controllerHistory.removeAt(0)
                notifyControlChange(newState)
                true
            }

            current.mode == ControlMode.COOPERATIVE && isCompatible(current.source, source) -> {
                true
            }

            else -> {
                false
            }
        }
    }

    /**
     * 제어권 해제
     */
    @Synchronized
    fun releaseControl(source: TransmissionSource) {
        val current = _currentController.value
        if (current?.source == source) {
            _currentController.value = null
            notifyControlChange(null)
        }
    }

    /**
     * 모든 제어권 강제 해제
     */
    @Synchronized
    fun forceReleaseAll() {
        activeSession = null
        sessionStartTime = 0
        _currentController.value = null
        notifyControlChange(null)
    }

    /**
     * 전송 허가 확인
     */
    fun canTransmit(source: TransmissionSource): Boolean {
        if (activeSession != null) {
            val compatible = activeSession == source || isCompatible(activeSession!!, source)
            if (!compatible) {
                return false
            }
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
        if (!canTransmit(event.source)) {
            return false
        }
        BleTransmissionMonitor.recordTransmission(event)
        return true
    }

    private fun isCompatible(source1: TransmissionSource, source2: TransmissionSource): Boolean {
        val compatiblePairs = setOf(
            setOf(TransmissionSource.TIMELINE_EFFECT, TransmissionSource.FFT_EFFECT)
        )
        return compatiblePairs.any { it.contains(source1) && it.contains(source2) }
    }

    private fun notifyControlChange(state: ControllerState?) {
        controlChangeListeners.forEach { listener ->
            try { listener(state) } catch (e: Exception) {
                Log.e(TAG, "Control change listener error: ${e.message}")
            }
        }
    }

    fun addControlChangeListener(listener: (ControllerState?) -> Unit) {
        controlChangeListeners.add(listener)
    }

    fun removeControlChangeListener(listener: (ControllerState?) -> Unit) {
        controlChangeListeners.remove(listener)
    }

    fun getHistory(): List<ControllerState> = controllerHistory.toList()

    fun clearHistory() {
        controllerHistory.clear()
    }

    fun getControlCount(source: TransmissionSource): Int =
        controllerHistory.count { it.source == source }

    fun getCurrentControlInfo(): String? {
        val current = _currentController.value ?: return null
        val elapsed = System.currentTimeMillis() - current.acquiredAt
        return "${current.source} (priority=${current.priority}, mode=${current.mode}, elapsed=${elapsed}ms)"
    }

    fun printDebugInfo() {
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
    val isActive: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun getStateDescription(): String = when {
        isActive && mode == ControlMode.EXCLUSIVE   -> "독점 전송 중 (완전 잠금)"
        isActive && mode == ControlMode.COOPERATIVE -> "협력 전송 중"
        isActive && mode == ControlMode.BACKGROUND  -> "백그라운드 전송 중"
        else -> "제어권 보유 (대기 중)"
    }
}
