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
import com.lightstick.music.data.local.preferences.DevicePreferences
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
        Log.d(TAG, "[notification posted] id=${sbn.id} / pkg=${sbn.packageName} / category=$category / title='$title'")

        when (category) {
            Notification.CATEGORY_CALL -> {
                Log.i(TAG, "CATEGORY_CALL 알림 수신 [${sbn.packageName}]")
                if (!hasAnyEnabledDevice { DevicePreferences.getCallEventEnabled(it) }) {
                    Log.d(TAG, "→ Call Event 모두 OFF → 스킵")
                    return
                }
                if (isIncomingCall(sbn.notification)) {
                    Log.i(TAG, "→ 최종 판정: 수신 전화 → 이펙트 전송")
                    DeviceEventEffectSender.sendCallEffect(applicationContext)
                } else {
                    Log.i(TAG, "→ 최종 판정: 발신/진행중 전화 → 무시")
                }
            }
            Notification.CATEGORY_MESSAGE -> {
                Log.i(TAG, "메시지 알림 감지 [${sbn.packageName}]")
                if (!hasAnyEnabledDevice { DevicePreferences.getSmsEventEnabled(it) }) {
                    Log.d(TAG, "→ SMS Event 모두 OFF → 스킵")
                    return
                }
                Log.i(TAG, "→ 이펙트 전송")
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
            if (!hasAnyEnabledDevice { DevicePreferences.getCallEventEnabled(it) }) return
            DeviceEventEffectSender.sendCallEndEffect(applicationContext)
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasAnyEnabledDevice(isEnabled: (String) -> Boolean): Boolean {
        if (!PermissionManager.hasBluetoothConnectPermission(this)) return false
        return try {
            LSBluetooth.connectedDevices().any { isEnabled(it.mac) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 수신 전화 여부 판단.
     *
     * Samsung incallui(com.samsung.android.incallui)는 android.callType을 수신/발신 모두 2로 보고.
     * 수신 벨소리 알림에는 android.text = "수신전화"가 설정되므로 이를 1차 판정에 사용.
     *
     * 판정 순서:
     * 1. android.callType == 1 (표준 수신)
     * 2. FLAG_INSISTENT (수신 벨소리 지속)
     * 3. android.text 수신 키워드 ("수신" / "incoming") — Samsung 주 판정 기준
     * 4. 발신 확실 여부 (종료 액션만 존재)
     * 5. fullScreenIntent (기타 dialer fallback)
     * 6. 답하기 액션
     */
    private fun isIncomingCall(notification: Notification): Boolean {
        Log.d(TAG, "=== isIncomingCall 판단 시작 ===")

        // 판단에 사용 가능한 모든 값을 먼저 출력
        @Suppress("InlinedApi")
        val callType = notification.extras?.getInt(Notification.EXTRA_CALL_TYPE, -1) ?: -1
        val hasFullScreenIntent = notification.fullScreenIntent != null
        val isInsistent = (notification.flags and Notification.FLAG_INSISTENT) != 0
        val actionTitles = notification.actions?.map { it.title?.toString() ?: "null" } ?: emptyList()
        Log.d(TAG, "  [info] callType=$callType | channelId=${notification.channelId} | fullScreenIntent=$hasFullScreenIntent | FLAG_INSISTENT=$isInsistent | flags=0x${notification.flags.toString(16)}")
        Log.d(TAG, "  [info] actions(${actionTitles.size}개)=$actionTitles")
        notification.extras?.keySet()?.forEach { key ->
            try {
                val v = notification.extras.get(key)
                if (v != null && v !is android.graphics.Bitmap && v !is android.widget.RemoteViews && v !is android.app.PendingIntent) {
                    Log.d(TAG, "  [extra] $key = $v")
                } else if (v is android.app.PendingIntent) {
                    Log.d(TAG, "  [extra] $key = <PendingIntent>")
                }
            } catch (_: Exception) {}
        }

        // 1단계: Notification.EXTRA_CALL_TYPE 기반 분기
        Log.d(TAG, "  [1] EXTRA_CALL_TYPE = $callType")
        when (callType) {
            1 -> {
                // forIncomingCall() → CALL_TYPE_INCOMING
                Log.d(TAG, "  → 수신 (CALL_TYPE_INCOMING)")
                return true
            }
            3 -> {
                // forScreeningCall() → CALL_TYPE_SCREENING (발신 또는 스크리닝 상태)
                Log.d(TAG, "  → 발신/스크리닝 (CALL_TYPE_SCREENING)")
                return false
            }
            2 -> {
                // forOngoingCall() → CALL_TYPE_ONGOING
                // Samsung은 수신/발신 모두 이 값을 사용하므로 아래 단계에서 계속 검증
                Log.d(TAG, "  → CALL_TYPE_ONGOING (Samsung 오용 또는 진행 중) → 계속 검증")
            }
            else -> {
                // callType Extra 없음 (-1) — 구버전 앱 또는 비표준 구현 → 2순위 판별로 넘어감
                Log.d(TAG, "  → EXTRA_CALL_TYPE 없음 (구버전/비표준) → 계속 검증")
            }
        }

        // 2단계: FLAG_INSISTENT — 수신 벨소리 지속 플래그
        Log.d(TAG, "  [2] FLAG_INSISTENT: $isInsistent")
        if (isInsistent) {
            Log.d(TAG, "  → 수신 (FLAG_INSISTENT)")
            return true
        }

        // 3단계: android.text 수신 키워드 — Samsung CallStyle 수신 알림에 "수신전화" 세팅됨
        //        발신은 "전화를 거는 중…" 등 다른 텍스트이므로 안전하게 구분 가능
        val callText = notification.extras?.getCharSequence("android.text")
            ?.toString()?.lowercase(Locale.getDefault()) ?: ""
        Log.d(TAG, "  [3] android.text: '$callText'")
        if (callText.contains("수신") || callText.contains("incoming")) {
            Log.d(TAG, "  → 수신 (android.text 수신 키워드)")
            return true
        }

        // 4단계: 발신 확실 여부 (종료 액션만 존재)
        val definitelyOutgoing = isDefinitelyOutgoing(notification)
        Log.d(TAG, "  [4] 발신 확실(종료만 있음): $definitelyOutgoing")
        if (definitelyOutgoing) {
            Log.d(TAG, "  → 발신 (종료 액션만 있음)")
            return false
        }

        // 5단계: fullScreenIntent (표준 Android dialer fallback)
        Log.d(TAG, "  [5] fullScreenIntent 있음: $hasFullScreenIntent")
        if (hasFullScreenIntent) {
            Log.d(TAG, "  → 수신 (fullScreenIntent 존재)")
            return true
        }

        // 6단계: 답하기 액션
        val hasAnswer = hasAnswerAction(notification)
        Log.d(TAG, "  [6] 답하기 액션 있음: $hasAnswer")
        if (hasAnswer) {
            Log.d(TAG, "  → 수신 (답하기 액션)")
            return true
        }

        Log.d(TAG, "  [최종] 판단 불가 → 수신으로 간주")
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

        val anyEventEnabled = bonded.any { device ->
            DevicePreferences.getCallEventEnabled(device.mac) || DevicePreferences.getSmsEventEnabled(device.mac)
        }
        if (!anyEventEnabled) {
            Log.d(TAG, "자동 재연결: 모든 디바이스의 Call/SMS Event가 OFF → scan/connect 스킵")
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
