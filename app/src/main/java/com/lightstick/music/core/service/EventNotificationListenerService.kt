package com.lightstick.music.core.service

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale
import com.lightstick.LSBluetooth
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.manager.DeviceEventEffectSender
import com.lightstick.music.core.manager.DeviceStatusNotificationManager
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 전화 수신 및 메시지 알림을 감지하여 이펙트를 전송합니다.
 *
 * READ_PHONE_STATE / RECEIVE_SMS 대신 사용하는 방식 — Google Play 정책 준수.
 * 설정 > 앱 > 특별 앱 접근 > 알림 접근에서 사용자가 직접 허용해야 합니다.
 *
 * CATEGORY_CALL  + fullScreenIntent → 수신 전화(RINGING)
 * CATEGORY_CALL  알림 제거          → 전화 종료(IDLE)
 * CATEGORY_MESSAGE                  → 메시지 수신
 *
 * bonded 디바이스가 있으면 RECONNECT_INTERVAL_MS 주기로 미연결 상태를 확인하고 자동 재연결합니다.
 */
class EventNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = AppConstants.Feature.NOTIFICATION_LISTENER

        // 첫 재연결 시도까지 대기 (부팅 직후 시스템 안정화)
        private const val INITIAL_DELAY_MS = 10_000L

        // 주기적 재연결 체크 간격 (3분)
        private const val RECONNECT_INTERVAL_MS = 3 * 60 * 1000L

        // 스캔 지속 시간
        private const val SCAN_DURATION_SEC = 3
    }

    // 스캔 중 연결 시도한 MAC 주소 추적 (중복 connect 방지)
    private val connectAttempted = ConcurrentHashMap.newKeySet<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "알림 접근 서비스 연결됨 → 상태 알림 + 주기적 자동 재연결 시작")
        DeviceStatusNotificationManager.start(applicationContext)
        scope.launch { periodicReconnectLoop() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val category = sbn.notification.category
        val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
        Log.d(TAG, "[notification posted] pkg=${sbn.packageName} / category=$category / title='$title'")

        when (category) {
            Notification.CATEGORY_CALL -> {
                Log.i(TAG, "CATEGORY_CALL 알림 수신 [${sbn.packageName}]")
                if (isIncomingCall(sbn.notification)) {
                    Log.i(TAG, "→ 최종 판정: 수신 전화 → 이펙트 전송")
                    DeviceEventEffectSender.sendCallEffect(applicationContext)
                } else {
                    Log.i(TAG, "→ 최종 판정: 발신/진행중 전화 → 무시")
                }
            }
            Notification.CATEGORY_MESSAGE -> {
                Log.i(TAG, "메시지 알림 감지 [${sbn.packageName}] → 이펙트 전송")
                DeviceEventEffectSender.sendSmsEffect(applicationContext)
            }
            else -> {
                Log.d(TAG, "다른 카테고리: $category [${sbn.packageName}] → 무시")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            Log.i(TAG, "[notification removed] CATEGORY_CALL 제거됨 [${sbn.packageName}] → 전화 종료 → 이펙트 정지")
            DeviceEventEffectSender.sendCallEndEffect(applicationContext)
        }
    }

    /**
     * 수신 전화 여부 판단. Samsung incallui는 android.callType을 설정하지 않고
     * fullScreenIntent도 null인 경우가 있어 다단계 fallback으로 처리.
     *
     * 판정 순서:
     * 1. android.callType (API 31+)
     * 2. 전통 방식 (fullScreenIntent, FLAG_INSISTENT, 액션 텍스트)
     */
    private fun isIncomingCall(notification: Notification): Boolean {
        Log.d(TAG, "=== isIncomingCall 판단 시작 ===")

        // 1단계: android.callType (API 31+) - Samsung에서는 신뢰 불가능하므로 1만 사용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callType = notification.extras?.getInt("android.callType", 0) ?: 0
            Log.d(TAG, "  [1] android.callType = $callType")
            if (callType == 1) {
                Log.d(TAG, "  → 수신 (CALL_TYPE_INCOMING)")
                return true
            } else if (callType in listOf(2, 3)) {
                Log.d(TAG, "  → callType=$callType (Samsung에서는 발신/진행중/수신 모두 2로 보고) → 전통 방식으로 재검증")
            }
        }

        // 2단계: 발신 확실 여부 먼저 체크 (fullScreenIntent보다 우선 — Samsung은 발신에도 fullScreenIntent 있음)
        val definitelyOutgoing = isDefinitelyOutgoing(notification)
        Log.d(TAG, "  [2] 발신 확실(종료만 있음): $definitelyOutgoing")
        if (definitelyOutgoing) {
            Log.d(TAG, "  → 발신 (종료 액션만 있음)")
            return false
        }

        // 3단계: 전통 방식 (모든 API 레벨)
        Log.d(TAG, "  [3] 전통 방식 판단")
        val fullScreenIntent = notification.fullScreenIntent
        Log.d(TAG, "    - fullScreenIntent 있음: ${fullScreenIntent != null}")
        if (fullScreenIntent != null) {
            Log.d(TAG, "    → 수신 (fullScreenIntent 존재)")
            return true
        }

        val insistent = (notification.flags and Notification.FLAG_INSISTENT) != 0
        Log.d(TAG, "    - FLAG_INSISTENT: $insistent")
        if (insistent) {
            Log.d(TAG, "    → 수신 (FLAG_INSISTENT)")
            return true
        }

        val hasAnswer = hasAnswerAction(notification)
        Log.d(TAG, "    - 답하기 액션 있음: $hasAnswer")
        if (hasAnswer) {
            Log.d(TAG, "    → 수신 (답하기 액션)")
            return true
        }

        // 판단 불가 → 수신으로 간주
        Log.d(TAG, "  [최종] 판단 불가 → 수신으로 간주 (보수적 판단)")
        return true
    }

    private fun hasAnswerAction(notification: Notification): Boolean {
        val actions = notification.actions
        if (actions == null) {
            Log.d(TAG, "    hasAnswerAction: 액션 없음")
            return false
        }

        val answerKeywords = listOf("answer", "accept", "받기", "수신", "응답")
        Log.d(TAG, "    hasAnswerAction: ${actions.size}개 액션 검사 - 액션들=[${actions.joinToString(", ") { it.title?.toString() ?: "null" }}]")

        val found = actions.any { action ->
            val title = action.title?.toString()?.lowercase(Locale.getDefault()) ?: return@any false
            val matched = answerKeywords.any { it in title }
            if (matched) {
                Log.d(TAG, "      ✓ 답하기 액션 매칭: '$title'")
            }
            matched
        }
        Log.d(TAG, "    → 답하기 액션 결과: $found")
        return found
    }

    private fun isDefinitelyOutgoing(notification: Notification): Boolean {
        val actions = notification.actions
        if (actions == null) {
            Log.d(TAG, "    isDefinitelyOutgoing: 액션 없음")
            return false
        }

        val endKeywords = listOf("종료", "end call", "hang up", "끊기", "cancel")
        val answerKeywords = listOf("answer", "accept", "받기", "수신", "응답")

        Log.d(TAG, "    isDefinitelyOutgoing: ${actions.size}개 액션 검사 - 액션들=[${actions.joinToString(", ") { it.title?.toString() ?: "null" }}]")

        val hasEnd = actions.any { a ->
            val t = a.title?.toString()?.lowercase(Locale.getDefault()) ?: return@any false
            val matched = endKeywords.any { it in t }
            if (matched) {
                Log.d(TAG, "      ✓ 종료 액션 매칭: '$t'")
            }
            matched
        }

        val hasAnswer = actions.any { a ->
            val t = a.title?.toString()?.lowercase(Locale.getDefault()) ?: return@any false
            val matched = answerKeywords.any { it in t }
            if (matched) {
                Log.d(TAG, "      ✓ 답하기 액션 매칭: '$t'")
            }
            matched
        }

        val isOutgoing = hasEnd && !hasAnswer
        Log.d(TAG, "    → 판정: hasEnd=$hasEnd / hasAnswer=$hasAnswer → 발신확실=$isOutgoing")
        return isOutgoing
    }

    /**
     * bonded 디바이스가 있고 연결된 디바이스가 없을 때 주기적으로 재연결을 시도합니다.
     * 부팅, 앱 프로세스 재시작, 범위 이탈 후 복귀 등 모든 단절 상황을 대응합니다.
     */
    private suspend fun periodicReconnectLoop() {
        delay(INITIAL_DELAY_MS)
        while (true) {
            tryAutoReconnect()
            delay(RECONNECT_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryAutoReconnect() {
        if (!PermissionManager.hasBluetoothConnectPermission(this)) {
            Log.w(TAG, "자동 재연결: BLUETOOTH_CONNECT 권한 없음")
            return
        }

        if (LSBluetooth.connectedCount() > 0) {
            Log.d(TAG, "자동 재연결: 이미 연결됨 (${LSBluetooth.connectedCount()}개) → 스킵")
            DeviceStatusNotificationManager.ensureNotificationVisible(this)
            return
        }

        val bonded = try {
            LSBluetooth.bondedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "자동 재연결: bonded 디바이스 조회 실패 - ${e.message}")
            return
        }

        if (bonded.isEmpty()) {
            Log.d(TAG, "자동 재연결: 페어링된 디바이스 없음 → 스킵")
            return
        }

        val bondedMacs = bonded.map { it.mac }.toSet()
        connectAttempted.clear()

        Log.i(TAG, "자동 재연결: 스캔 시작 (bonded ${bonded.size}개 대상)")

        LSBluetooth.startScan(scanTimeSeconds = SCAN_DURATION_SEC) { device ->
            if (device.mac in bondedMacs && connectAttempted.add(device.mac)) {
                Log.i(TAG, "자동 재연결: ${device.name} (${device.mac}) 발견 → connect 시도")
                device.connect(
                    onConnected = {
                        Log.i(TAG, "자동 재연결 성공: ${device.name} (${device.mac})")
                    },
                    onFailed = { err ->
                        Log.w(TAG, "자동 재연결 실패: ${device.mac} - ${err.message}")
                    }
                )
            }
        }
    }
}
