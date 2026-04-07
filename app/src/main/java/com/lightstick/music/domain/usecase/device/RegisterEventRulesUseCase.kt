package com.lightstick.music.domain.usecase.device

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.music.core.util.Log
import com.lightstick.device.Device
import com.lightstick.events.EventAction
import com.lightstick.events.EventFilter
import com.lightstick.events.EventRule
import com.lightstick.events.EventTarget
import com.lightstick.events.EventTrigger
import com.lightstick.events.EventType
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import javax.inject.Inject

/**
 * 디바이스 이벤트 규칙 등록 UseCase
 *
 * 책임:
 * - BLUETOOTH_CONNECT 권한 체크
 * - DevicePreferences에서 call/sms 이벤트 설정 읽기
 * - EventRule 목록 구성
 * - device.registerEventRules(rules) 호출
 *
 * 사용처:
 * - DeviceViewModel.registerDeviceEventRules()
 * - DeviceViewModel.toggleCallEvent() 후 재등록
 * - DeviceViewModel.toggleSmsEvent() 후 재등록
 */
class RegisterEventRulesUseCase @Inject constructor() {

    private val TAG = AppConstants.Feature.UC_REGIST_EVENTRULES

    /**
     * 이벤트 규칙 등록
     *
     * @param context Android Context
     * @param device 이벤트 규칙을 등록할 디바이스
     * @return Result<Unit> 성공 또는 실패
     */
    @SuppressLint("MissingPermission")
    operator fun invoke(
        context: Context,
        device: Device
    ): Result<Unit> {
        return try {
            // ✅ 1. Permission 체크
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                return Result.failure(
                    SecurityException("BLUETOOTH_CONNECT permission required")
                )
            }

            // ✅ 2. Preferences에서 설정 읽기
            val callEventEnabled = DevicePreferences.getCallEventEnabled(device.mac)
            val smsEventEnabled = DevicePreferences.getSmsEventEnabled(device.mac)

            // ✅ 3. EventRule 목록 구성
            val rules = mutableListOf<EventRule>()

            if (callEventEnabled) {
                rules.add(
                    EventRule(
                        id = "call-${device.mac}",
                        trigger = EventTrigger(
                            type = EventType.CALL_RINGING,
                            filter = EventFilter()
                        ),
                        action = EventAction.SendEffectFrame(
                            bytes20 = LSEffectPayload.Effects.blink(
                                period = 10,
                                color = Colors.CYAN,
                                backgroundColor = Colors.BLACK
                            ).toByteArray()
                        ),
                        target = EventTarget.THIS_DEVICE,
                        stopAfterMatch = false
                    )
                )
            }

            if (smsEventEnabled) {
                rules.add(
                    EventRule(
                        id = "sms-${device.mac}",
                        trigger = EventTrigger(
                            type = EventType.SMS_RECEIVED,
                            filter = EventFilter()
                        ),
                        action = EventAction.SendEffectFrame(
                            bytes20 = LSEffectPayload.Effects.blink(
                                period = 10,
                                color = Colors.GREEN,
                                backgroundColor = Colors.BLACK
                            ).toByteArray()
                        ),
                        target = EventTarget.THIS_DEVICE,
                        stopAfterMatch = false
                    )
                )
            }

            // ✅ 4. 규칙 등록
            device.registerEventRules(rules)

            Log.d(TAG, "✅ Event rules registered for ${device.mac}")
            Log.d(TAG, "   ├─ CALL: ${if (callEventEnabled) "enabled" else "disabled"}")
            Log.d(TAG, "   └─ SMS:  ${if (smsEventEnabled) "enabled" else "disabled"}")

            // ✅ 5. 성공 반환
            Result.success(Unit)

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException during registerEventRules: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register event rules: ${e.message}", e)
            Result.failure(e)
        }
    }
}