package com.lightstick.music.core.manager

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
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

    // CALL 이펙트를 실제로 받은 기기 목록 (CALL_END 시, 설정이 그 사이 꺼졌어도 반드시 off를 보내기 위함)
    private var activeCallEffectMacs: Set<String> = emptySet()

    @SuppressLint("MissingPermission")
    fun sendCallEffect(context: Context) {
        if (EffectEngineController.isEffectActive()) {
            Log.d(TAG, "[CALL] 이펙트 연출 중 → 이벤트 이펙트 차단")
            return
        }
        val payload = LSEffectPayload.Effects.blink(
            period = 10,
            color = Colors.CYAN,
            backgroundColor = Colors.BLACK
        )
        activeCallEffectMacs = sendToEnabledDevices(
            context = context,
            eventTag = "CALL",
            payload = payload,
            isEnabled = { mac -> DevicePreferences.getCallEventEnabled(mac) }
        )
    }

    @SuppressLint("MissingPermission")
    fun sendCallEndEffect(context: Context) {
        val targetMacs = activeCallEffectMacs
        activeCallEffectMacs = emptySet()
        if (targetMacs.isEmpty()) return
        // 켜놓은 기기에는 그 사이 설정이 꺼졌어도(isEnabled 재확인 없이) 반드시 off를 보내
        // "블링크가 기기에 영구히 남는" 상태를 방지한다.
        val payload = LSEffectPayload.Effects.off()
        sendToDevices(context = context, eventTag = "CALL_END", payload = payload, targetMacs = targetMacs)
    }

    @SuppressLint("MissingPermission")
    fun sendSmsEffect(context: Context) {
        if (EffectEngineController.isEffectActive()) {
            Log.d(TAG, "[SMS] 이펙트 연출 중 → 이벤트 이펙트 차단")
            return
        }
        // blink가 이미 진행 중이면 중복 알림 무시
        if (smsBlinkJob?.isActive == true) {
            Log.d(TAG, "[SMS] blink 진행 중 → 중복 알림 무시")
            return
        }

        val appContext = context.applicationContext
        val wasTimelineActive = EffectEngineController.isTimelineActive()

        if (wasTimelineActive) {
            EffectEngineController.pauseEffects(appContext)
            Log.d(TAG, "[SMS] 자동 타임라인 일시정지")
        }

        val payload = LSEffectPayload.Effects.blink(
            period = 10,
            color = Colors.GREEN,
            backgroundColor = Colors.BLACK
        )
        val targetMacs = sendToEnabledDevices(
            context = context,
            eventTag = "SMS",
            payload = payload,
            isEnabled = { mac -> DevicePreferences.getSmsEventEnabled(mac) }
        )

        smsBlinkJob = scope.launch {
            delay(SMS_BLINK_DURATION_MS)
            if (targetMacs.isNotEmpty()) {
                // 켜놓은 기기에는 그 사이 설정이 꺼졌어도(isEnabled 재확인 없이) 반드시 off를
                // 보내 "블링크가 기기에 영구히 남는" 상태를 방지한다.
                val offPayload = LSEffectPayload.Effects.off()
                sendToDevices(context = appContext, eventTag = "SMS_END", payload = offPayload, targetMacs = targetMacs)
            }
            if (wasTimelineActive) {
                EffectEngineController.resumeEffects(appContext)
                Log.d(TAG, "[SMS] 자동 타임라인 재개")
            }
        }
    }

    /** [eventTag]에 해당하는 이펙트를 [isEnabled]가 true인 연결된 기기에만 전송하고, 실제로 전송된 기기의 mac 목록을 반환한다. */
    @SuppressLint("MissingPermission")
    private fun sendToEnabledDevices(
        context: Context,
        eventTag: String,
        payload: LSEffectPayload,
        isEnabled: (String) -> Boolean
    ): Set<String> {
        val devices = resolveConnectedDevices(context, eventTag) ?: return emptySet()

        val sentMacs = mutableSetOf<String>()
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
                if (ok) sentMacs.add(device.mac)
            } else {
                Log.d(TAG, "[$eventTag] 비활성화됨 → ${device.mac}")
            }
        }
        return sentMacs
    }

    /** [targetMacs]에 해당하는 연결된 기기에 [isEnabled] 재확인 없이 무조건 전송한다 (정리/off 용). */
    @SuppressLint("MissingPermission")
    private fun sendToDevices(
        context: Context,
        eventTag: String,
        payload: LSEffectPayload,
        targetMacs: Set<String>
    ) {
        val devices = resolveConnectedDevices(context, eventTag) ?: return

        devices.filter { it.mac in targetMacs }.forEach { device ->
            val ok = EffectEngineController.sendEffectToDevice(
                context = context,
                deviceMac = device.mac,
                payload = payload,
                source = TransmissionSource.EVENT_EFFECT,
                metadata = mapOf("eventType" to eventTag)
            )
            Log.i(TAG, "[$eventTag] 이펙트 전송 → ${device.mac}: $ok")
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveConnectedDevices(context: Context, eventTag: String): List<Device>? {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "[$eventTag] BLUETOOTH_CONNECT 권한 없음")
            return null
        }

        val devices = try {
            LSBluetooth.connectedDevices()
        } catch (e: Exception) {
            Log.e(TAG, "[$eventTag] 연결 디바이스 조회 실패: ${e.message}")
            return null
        }

        if (devices.isEmpty()) {
            Log.d(TAG, "[$eventTag] 연결된 디바이스 없음")
            return null
        }
        return devices
    }
}
