package com.lightstick.music.core.ble

import android.util.Log
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
 *
 * 주요 기능:
 * - 우선순위 기반 제어권 관리
 * - EXCLUSIVE/COOPERATIVE/BACKGROUND 모드 지원
 * - 소스 간 호환성 체크
 * - 자동 모니터 기록
 *
 * 사용 예시:
 * ```
 * // 제어권 요청
 * val acquired = BleTransmissionCoordinator.requestControl(
 *     source = TransmissionSource.MANUAL_EFFECT,
 *     priority = TransmissionPriority.MANUAL_EFFECT,
 *     mode = ControlMode.EXCLUSIVE
 * )
 *
 * if (acquired) {
 *     // 전송 수행
 *     BleTransmissionCoordinator.sendEffect(event)
 * }
 *
 * // 제어권 해제
 * BleTransmissionCoordinator.releaseControl(TransmissionSource.MANUAL_EFFECT)
 * ```
 */
object BleTransmissionCoordinator {

    private const val TAG = "BleCoordinator"

    // ═══════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════

    /** 현재 제어권 보유자 */
    private val _currentController = MutableStateFlow<ControllerState?>(null)
    val currentController: StateFlow<ControllerState?> = _currentController.asStateFlow()

    /**
     * 현재 활성 세션
     *
     * 제어권을 획득하고 실제로 전송 중인 소스를 나타냅니다.
     * 세션이 활성화되면 해당 소스가 독점적으로 BLE를 제어합니다.
     *
     * 예시:
     * - Manual Effect 시작 → activeSession = MANUAL_EFFECT
     * - 이 동안 FFT는 전송 불가 (깜빡임 방지)
     * - Manual Effect 종료 → activeSession = null
     * - FFT 다시 전송 가능
     */
    private var activeSession: TransmissionSource? = null

    /** 세션 시작 시각 */
    private var sessionStartTime: Long = 0

    /** 제어권 히스토리 */
    private val controllerHistory = mutableListOf<ControllerState>()

    /** 제어권 변경 리스너 */
    private val controlChangeListeners = mutableListOf<(ControllerState?) -> Unit>()

    // ═══════════════════════════════════════════════════════════
    // Public API - Control Management
    // ═══════════════════════════════════════════════════════════

    /**
     * 세션 시작 (제어권 획득 + 독점 플래그 설정)
     *
     * 세션을 시작하면 해당 소스가 독점적으로 BLE를 제어합니다.
     * 세션 중에는 다른 소스의 전송이 차단됩니다 (깜빡임 방지).
     *
     * @param source 전송 소스
     * @param priority 우선순위
     * @param mode 제어 모드
     * @param metadata 추가 메타데이터
     * @return true if session started
     *
     * @sample
     * ```kotlin
     * // Manual Effect 시작
     * if (startSession(MANUAL_EFFECT, MANUAL_EFFECT_PRIORITY, EXCLUSIVE)) {
     *     while (isActive) {
     *         sendEffect(payload)
     *         delay(1000)
     *     }
     *     endSession(MANUAL_EFFECT)
     * }
     * ```
     */
    @Synchronized
    fun startSession(
        source: TransmissionSource,
        priority: Int,
        mode: ControlMode = ControlMode.EXCLUSIVE,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        // 제어권 획득
        val acquired = requestControl(source, priority, mode, metadata)

        if (acquired) {
            activeSession = source
            sessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "🟢 Session started: $source (priority=$priority, mode=$mode)")
        }

        return acquired
    }

    /**
     * 세션 종료 (제어권 해제 + 독점 플래그 해제)
     *
     * @param source 전송 소스
     */
    @Synchronized
    fun endSession(source: TransmissionSource) {
        if (activeSession == source) {
            val duration = System.currentTimeMillis() - sessionStartTime
            activeSession = null
            sessionStartTime = 0
            releaseControl(source)
            Log.d(TAG, "🔴 Session ended: $source (duration=${duration}ms)")
        } else {
            Log.w(TAG, "⚠️ End session ignored: $source is not active (active=$activeSession)")
        }
    }

    /**
     * 현재 활성 세션 확인
     *
     * @return 활성 세션 소스 또는 null
     */
    fun getActiveSession(): TransmissionSource? = activeSession

    /**
     * 특정 소스의 세션이 활성화되어 있는지 확인
     *
     * @param source 전송 소스
     * @return true if session is active
     */
    fun isSessionActive(source: TransmissionSource): Boolean {
        return activeSession == source
    }

    /**
     * 아무 세션이라도 활성화되어 있는지 확인
     *
     * @return true if any session is active
     */
    fun hasActiveSession(): Boolean {
        return activeSession != null
    }

    /**
     * 제어권 요청 (세션 없이)
     *
     * 단발성 전송에 사용합니다.
     * 지속적인 전송은 startSession()을 사용하세요.
     *
     * @param source 전송 소스
     * @param priority 우선순위 (TransmissionPriority 상수 사용)
     * @param mode 제어 모드
     * @param metadata 추가 메타데이터
     * @return true if control acquired
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

            Log.d(TAG, "✅ Control acquired: $source (priority=$priority, mode=$mode)")
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

            Log.d(TAG, "🔄 Control updated: $source (priority=$priority, mode=$mode)")
            return true
        }

        // 3. 우선순위 비교 및 모드별 처리
        return when {
            // 3-1. 새 요청이 우선순위 높음 → 강제 획득
            TransmissionPriority.hasHigherPriority(priority, current.priority) -> {
                Log.d(TAG, "⚡ Control forcefully acquired: $source (priority=$priority) " +
                        "overrides ${current.source} (priority=${current.priority})")

                val newState = ControllerState(source, priority, mode, metadata = metadata)
                _currentController.value = newState
                controllerHistory.add(newState)
                notifyControlChange(newState)
                true
            }

            // 3-2. 현재 제어권이 BACKGROUND 모드 → 양보
            current.mode == ControlMode.BACKGROUND -> {
                Log.d(TAG, "🤝 Background mode yielded: ${current.source} → $source")

                val newState = ControllerState(source, priority, mode, metadata = metadata)
                _currentController.value = newState
                controllerHistory.add(newState)
                notifyControlChange(newState)
                true
            }

            // 3-3. COOPERATIVE 모드이고 허용된 조합 → 공존
            current.mode == ControlMode.COOPERATIVE && isCompatible(current.source, source) -> {
                Log.d(TAG, "🤝 Cooperative mode: ${current.source} + $source")
                // Cooperative는 제어권 유지하되 양쪽 모두 전송 허용
                true
            }

            // 3-4. 그 외 → 거부
            else -> {
                Log.w(TAG, "❌ Control denied: $source (priority=$priority) " +
                        "blocked by ${current.source} (priority=${current.priority}, mode=${current.mode})")
                false
            }
        }
    }

    /**
     * 제어권 해제
     *
     * @param source 제어권을 해제할 소스
     */
    @Synchronized
    fun releaseControl(source: TransmissionSource) {
        val current = _currentController.value

        if (current?.source == source) {
            _currentController.value = null
            notifyControlChange(null)
            Log.d(TAG, "🔓 Control released: $source")
        } else {
            Log.w(TAG, "⚠️ Release ignored: $source doesn't have control (current=${current?.source})")
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
        Log.d(TAG, "🔒 All controls forcefully released")
    }

    // ═══════════════════════════════════════════════════════════
    // Public API - Transmission
    // ═══════════════════════════════════════════════════════════

    /**
     * 전송 허가 확인
     *
     * Active Session이 있으면 해당 소스만 전송 가능합니다.
     * 이를 통해 지속적인 전송 중 다른 소스의 개입을 방지합니다.
     *
     * @param source 전송하려는 소스
     * @return true if transmission is allowed
     */
    fun canTransmit(source: TransmissionSource): Boolean {
        // 1. Active Session 체크 (최우선)
        if (activeSession != null) {
            // 세션 소스이거나 호환되는 소스만 전송 가능
            val compatible = activeSession == source || isCompatible(activeSession!!, source)

            if (!compatible) {
                // 디버그 로그 (빈번하므로 VERBOSE만)
                if (AppConstants.VERBOSE_LOGGING) {
                    Log.v(TAG, "🚫 Transmission blocked by active session: $activeSession blocks $source")
                }
                return false
            }
        }

        // 2. 제어권 체크
        val current = _currentController.value

        return when {
            // 제어권 없음 → 허용
            current == null -> true

            // 같은 소스 → 허용
            current.source == source -> true

            // COOPERATIVE 모드이고 호환됨 → 허용
            current.mode == ControlMode.COOPERATIVE && isCompatible(current.source, source) -> true

            // 그 외 → 거부
            else -> false
        }
    }

    /**
     * 전송 (제어권 체크 후 모니터 기록)
     *
     * @param event BLE 전송 이벤트
     * @return true if transmitted, false if blocked
     */
    fun sendEffect(event: BleTransmissionEvent): Boolean {
        if (!canTransmit(event.source)) {
            Log.w(TAG, "🚫 Transmission blocked: ${event.source} (controller=${_currentController.value?.source})")
            return false
        }

        // 모니터 기록
        BleTransmissionMonitor.recordTransmission(event)

        Log.d(TAG, "📤 Transmission allowed: ${event.source} → ${event.deviceMac}")
        return true
    }

    // ═══════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * 소스 호환성 체크 (COOPERATIVE 모드용)
     *
     * @param source1 첫 번째 소스
     * @param source2 두 번째 소스
     * @return true if compatible
     */
    private fun isCompatible(source1: TransmissionSource, source2: TransmissionSource): Boolean {
        // 허용된 조합 정의
        val compatiblePairs = setOf(
            // Timeline과 FFT는 협력 가능 (Timeline 없을 때 FFT 작동)
            setOf(TransmissionSource.TIMELINE_EFFECT, TransmissionSource.FFT_EFFECT)

            // 추후 다른 조합 추가 가능
            // setOf(TransmissionSource.XXX, TransmissionSource.YYY)
        )

        return compatiblePairs.any { it.contains(source1) && it.contains(source2) }
    }

    /**
     * 제어권 변경 알림
     */
    private fun notifyControlChange(state: ControllerState?) {
        controlChangeListeners.forEach { listener ->
            try {
                listener(state)
            } catch (e: Exception) {
                Log.e(TAG, "Control change listener error: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Public API - Listeners
    // ═══════════════════════════════════════════════════════════

    /**
     * 제어권 변경 리스너 등록
     *
     * @param listener 콜백 함수
     */
    fun addControlChangeListener(listener: (ControllerState?) -> Unit) {
        controlChangeListeners.add(listener)
    }

    /**
     * 제어권 변경 리스너 제거
     *
     * @param listener 콜백 함수
     */
    fun removeControlChangeListener(listener: (ControllerState?) -> Unit) {
        controlChangeListeners.remove(listener)
    }

    // ═══════════════════════════════════════════════════════════
    // Public API - Statistics
    // ═══════════════════════════════════════════════════════════

    /**
     * 제어 히스토리 조회
     *
     * @return 제어 히스토리 복사본
     */
    fun getHistory(): List<ControllerState> = controllerHistory.toList()

    /**
     * 히스토리 초기화
     */
    fun clearHistory() {
        controllerHistory.clear()
        Log.d(TAG, "🗑️ History cleared")
    }

    /**
     * 특정 소스의 제어 횟수 조회
     *
     * @param source 전송 소스
     * @return 제어 획득 횟수
     */
    fun getControlCount(source: TransmissionSource): Int {
        return controllerHistory.count { it.source == source }
    }

    /**
     * 현재 제어권 정보 조회
     *
     * @return 현재 제어권 상태 또는 null
     */
    fun getCurrentControlInfo(): String? {
        val current = _currentController.value ?: return null

        val elapsed = System.currentTimeMillis() - current.acquiredAt
        return "${current.source} (priority=${current.priority}, mode=${current.mode}, elapsed=${elapsed}ms)"
    }

    // ═══════════════════════════════════════════════════════════
    // Debug API
    // ═══════════════════════════════════════════════════════════

    /**
     * 디버그 정보 출력
     */
    fun printDebugInfo() {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "📊 BLE Transmission Coordinator Status")
        Log.d(TAG, "═══════════════════════════════════════")

        // Active Session
        if (activeSession != null) {
            val duration = System.currentTimeMillis() - sessionStartTime
            Log.d(TAG, "Active Session: $activeSession")
            Log.d(TAG, "  Duration: ${duration}ms")
        } else {
            Log.d(TAG, "Active Session: None")
        }

        Log.d(TAG, "")

        // Current Controller
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
 *
 * @property source 전송 소스
 * @property priority 우선순위
 * @property mode 제어 모드
 * @property acquiredAt 제어권 획득 시각
 * @property isActive 활발히 전송 중인지 여부 (CRITICAL)
 * @property metadata 추가 메타데이터
 */
data class ControllerState(
    val source: TransmissionSource,
    val priority: Int,
    val mode: ControlMode,
    val acquiredAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * 제어 상태를 읽기 쉬운 형태로 변환
     */
    fun getStateDescription(): String {
        return when {
            isActive && mode == ControlMode.EXCLUSIVE -> "🔒 독점 전송 중 (완전 잠금)"
            isActive && mode == ControlMode.COOPERATIVE -> "🔄 협력 전송 중"
            isActive && mode == ControlMode.BACKGROUND -> "🎵 백그라운드 전송 중"
            else -> "⏸️ 제어권 보유 (대기 중)"
        }
    }
}