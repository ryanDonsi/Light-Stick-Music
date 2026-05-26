package com.lightstick.music.core.manager

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.util.Log
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 앱 레벨 이벤트(전화/SMS) 감지 시 연결된 디바이스에 이펙트를 전송합니다.
 *
 * - 이벤트 감지: EventNotificationListenerService (전화/메시지)
 * - 이펙트 전송: 이 클래스가 담당 (SDK의 sendEffect API 사용)
 * - 디바이스별 이벤트 활성화 여부를 DevicePreferences에서 읽어 필터링합니다.
 */
object DeviceEventEffectSender {

    private val TAG = AppConstants.Feature.DEVICE_EVENT_SENDER
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // SMS blink 3회 지속 시간 (period=10 기준 약 3초)
    private const val SMS_BLINK_DURATION_MS = 3000L

    // 현재 진행 중인 SMS blink Job (중복 알림 debounce용)
    private var smsBlinkJob: Job? = null

    @SuppressLint("MissingPermission")
    fun sendCallEffect(context: Context) {
        val payload = LSEffectPayload.Effects.blink(
            period = 10,
            color = Colors.CYAN,
            backgroundColor = Colors.BLACK
        )
        sendToEnabledDevices(
            context = context,
            eventTag = "CALL",
            payload = payload,
            isEnabled = { mac -> DevicePreferences.getCallEventEnabled(mac) }
        )
    }

    @SuppressLint("MissingPermission")
    fun sendCallEndEffect(context: Context) {
        val payload = LSEffectPayload.Effects.off()
        sendToEnabledDevices(
            context = context,
            eventTag = "CALL_END",
            payload = payload,
            isEnabled = { mac -> DevicePreferences.getCallEventEnabled(mac) }
        )
    }

    @SuppressLint("MissingPermission")
    fun sendSmsEffect(context: Context) {
        // blink가 이미 진행 중이면 중복 알림 무시
        if (smsBlinkJob?.isActive == true) {
            Log.d(TAG, "[SMS] blink 진행 중 → 중복 알림 무시")
            return
        }

        val payload = LSEffectPayload.Effects.blink(
            period = 10,
            color = Colors.GREEN,
            backgroundColor = Colors.BLACK
        )
        sendToEnabledDevices(
            context = context,
            eventTag = "SMS",
            payload = payload,
            isEnabled = { mac -> DevicePreferences.getSmsEventEnabled(mac) }
        )

        val appContext = context.applicationContext
        smsBlinkJob = scope.launch {
            delay(SMS_BLINK_DURATION_MS)
            val offPayload = LSEffectPayload.Effects.off()
            sendToEnabledDevices(
                context = appContext,
                eventTag = "SMS_END",
                payload = offPayload,
                isEnabled = { mac -> DevicePreferences.getSmsEventEnabled(mac) }
            )
        }
    }

    private fun sendToEnabledDevices(
        context: Context,
        eventTag: String,
        payload: LSEffectPayload,
        isEnabled: (String) -> Boolean
    ) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "[$eventTag] BLUETOOTH_CONNECT 권한 없음")
            return
        }

        val devices = try {
            LSBluetooth.connectedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "[$eventTag] 연결 디바이스 조회 실패: ${e.message}")
            return
        }

        if (devices.isEmpty()) {
            Log.d(TAG, "[$eventTag] 연결된 디바이스 없음")
            return
        }

        devices.forEach { device ->
            if (isEnabled(device.mac)) {
                val ok = EffectEngineController.sendEffectToDevice(
                    context = context,
                    deviceMac = device.mac,
                    payload = payload,
                    source = TransmissionSource.EVENT_EFFECT,
                    metadata = mapOf("eventType" to eventTag)
                )
                Log.i(TAG, "[$eventTag] 이펙트 전송 → ${device.mac}: $ok")
            } else {
                Log.d(TAG, "[$eventTag] 비활성화됨 → ${device.mac}")
            }
        }
    }
}
