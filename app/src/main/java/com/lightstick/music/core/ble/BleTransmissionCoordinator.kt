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

    // ═══════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════

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
    // CopyOnWriteArrayList: 리스너 호출 중 추가/삭제 시 ConcurrentModificationException 방지
    private val controlChangeListeners = CopyOnWriteArrayList<(ControllerState?) -> Unit>()

    // ═══════════════════════════════════════════════════════════
    // Public API - Control Management
    // ═══════════════════════════════════════════════════════════

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
            Log.d(TAG, "Session started: $source (priority=$priority, mode=$mode)")
        }
        return acquired
    }

    /**
     * 세션 종료 (제어권 해제 + 독점 플래그 해제)
     */
    @Synchronized
    fun endSession(source: TransmissionSource) {
        if (activeSession == source) {
            val duration = System.currentTimeMillis() - sessionStartTime
            activeSession = null
            sessionStartTime = 0
            releaseControl(source)
            Log.d(TAG, "Session ended: $source (duration=${duration}ms)")
        } else {
            Log.w(TAG, "End session ignored: $source is not active (active=$activeSession)")
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

        // 1. 현재 제어권 없음 → 즉시 획득
        if (current == null) {
            val newState = ControllerState(source, priority, mode, metadata = metadata)
            _currentController.value = newState
            controllerHistory.add(newState)
            notifyControlChange(newState)
            Log.d(TAG, "Control acquired: $source (priority=$priority, mode=$mode)")
            return true
        }

        // 2. 같은 소스 → 업데이트
        if (current.source == source) {
            val updated = current.copy(
                priority = priority,
                mode = mode,
                metadata = metadata,
                acquiredAt = System.currentTimeMillis()
            )
            _currentController.value = updated
            Log.d(TAG, "Control updated: $source (priority=$priority, mode=$mode)")
            return true
        }

        return when {
            // 3-1. 새 요청이 우선순위 높음 → 강제 획득
            TransmissionPriority.hasHigherPriority(priority, current.priority) -> {
                Log.d(TAG, "⚡ Control forcefully acquired: $source (priority=$priority) " +
                        "overrides ${current.source} (priority=${current.priority})")
                val newState = ControllerState(source, priority, mode, metadata = metadata)
                _currentController.value = newState
                controllerHistory.add(newState)
                if (controllerHistory.size > MAX_HISTORY_SIZE) controllerHistory.removeAt(0)
                notifyControlChange(newState)
                true
            }

            // 3-2. 현재 제어권이 BACKGROUND 모드 → 양보
            current.mode == ControlMode.BACKGROUND -> {
                Log.d(TAG, "Background mode yielded: ${current.source} → $source")
                val newState = ControllerState(source, priority, mode, metadata = metadata)
                _currentController.value = newState
                controllerHistory.add(newState)
                if (controllerHistory.size > MAX_HISTORY_SIZE) controllerHistory.removeAt(0)
                notifyControlChange(newState)
                true
            }

            // 3-3. COOPERATIVE 모드이고 허용된 조합 → 공존
            current.mode == ControlMode.COOPERATIVE && isCompatible(current.source, source) -> {
                Log.d(TAG, "Cooperative mode: ${current.source} + $source")
                true
            }

            // 3-4. 그 외 → 거부
            else -> {
                Log.w(TAG, "Control denied: $source (priority=$priority) " +
                        "blocked by ${current.source} (priority=${current.priority}, mode=${current.mode})")
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
            Log.d(TAG, "Control released: $source")
        } else {
            Log.w(TAG, "Release ignored: $source doesn't have control (current=${current?.source})")
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
        Log.d(TAG, "All controls forcefully released")
    }

    // ═══════════════════════════════════════════════════════════
    // Public API - Transmission
    // ═══════════════════════════════════════════════════════════

    /**
     * 전송 허가 확인
     */
    fun canTransmit(source: TransmissionSource): Boolean {
        // 1. Active Session 체크 (최우선)
        if (activeSession != null) {
            val compatible = activeSession == source || isCompatible(activeSession!!, source)
            if (!compatible) {
                Log.v(TAG, "Transmission blocked by active session: $activeSession blocks $source")
                return false
            }
        }

        // 2. 제어권 체크
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
            Log.w(TAG, "Transmission blocked: ${event.source} (controller=${_currentController.value?.source})")
            return false
        }
        BleTransmissionMonitor.recordTransmission(event)
        Log.d(TAG, "Transmission allowed: ${event.source} → ${event.deviceMac}")
        return true
    }

    // ═══════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // Public API - Listeners
    // ═══════════════════════════════════════════════════════════

    fun addControlChangeListener(listener: (ControllerState?) -> Unit) {
        controlChangeListeners.add(listener)
    }

    fun removeControlChangeListener(listener: (ControllerState?) -> Unit) {
        controlChangeListeners.remove(listener)
    }

    // ═══════════════════════════════════════════════════════════
    // Public API - Statistics
    // ═══════════════════════════════════════════════════════════

    fun getHistory(): List<ControllerState> = controllerHistory.toList()

    fun clearHistory() {
        controllerHistory.clear()
        Log.d(TAG, "History cleared")
    }

    fun getControlCount(source: TransmissionSource): Int =
        controllerHistory.count { it.source == source }

    fun getCurrentControlInfo(): String? {
        val current = _currentController.value ?: return null
        val elapsed = System.currentTimeMillis() - current.acquiredAt
        return "${current.source} (priority=${current.priority}, mode=${current.mode}, elapsed=${elapsed}ms)"
    }

    // ═══════════════════════════════════════════════════════════
    // Debug API
    // ═══════════════════════════════════════════════════════════

    fun printDebugInfo() {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "BLE Transmission Coordinator Status")
        Log.d(TAG, "═══════════════════════════════════════")
        if (activeSession != null) {
            val duration = System.currentTimeMillis() - sessionStartTime
            Log.d(TAG, "Active Session: $activeSession")
            Log.d(TAG, "  Duration: ${duration}ms")
        } else {
            Log.d(TAG, "Active Session: None")
        }
        Log.d(TAG, "")
        val current = _currentController.value
        if (current != null) {
            val elapsed = System.currentTimeMillis() - current.acquiredAt
            Log.d(TAG, "Current Controller: ${current.source}")
            Log.d(TAG, "  Priority: ${current.priority} (${TransmissionPriority.getPriorityName(current.priority)})")
            Log.d(TAG, "  Mode: ${current.mode}")
            Log.d(TAG, "  Elapsed: ${elapsed}ms")
            Log.d(TAG, "  Metadata: ${current.metadata}")
        } else {
            Log.d(TAG, "Current Controller: None")
        }
        Log.d(TAG, "")
        Log.d(TAG, "History: ${controllerHistory.size} entries")
        controllerHistory.takeLast(5).forEach { state ->
            Log.d(TAG, "  - ${state.source} (priority=${state.priority}, mode=${state.mode})")
        }
        Log.d(TAG, "═══════════════════════════════════════")
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