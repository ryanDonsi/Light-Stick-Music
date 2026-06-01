package com.lightstick.music.domain.effect

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.music.core.util.Log
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.efx.EfxEntry
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.data.model.FrequencyBand
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.state.OtaState
import com.lightstick.types.Color
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import java.io.File

@SuppressLint("MissingPermission")
object EffectEngineController {

    private const val TAG = AppConstants.Feature.EFFECT_ENGINE

    @Volatile private var targetDevice: Device? = null
    @Volatile private var targetAddress: String? = null
    @Volatile private var isTimelineLoaded: Boolean = false
    @Volatile private var loadedEffectSource: TransmissionSource? = null
    @Volatile private var isManualEffectActive: Boolean = false  // PAYLOAD_EFFECT 활성 여부

    @Volatile private var cachedTimeline: List<EfxEntry> = emptyList()
    @Volatile private var lastRecordedEffectIndex: Int = -1

    /** MusicViewModel에서 FFT 차단용으로 사용 */
    fun isTimelineActive(): Boolean = isTimelineLoaded

    /**
     * 앱 기능(Effect Control, Music 등)이 현재 이펙트를 연출 중인지 반환.
     * true 이면 SMS/CALL 이벤트 이펙트를 차단해야 한다.
     */
    fun isEffectActive(): Boolean = isTimelineLoaded || isManualEffectActive

    fun sendEffect(
        context: Context,
        payload: LSEffectPayload,
        source: TransmissionSource,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        if (OtaState.isAnyInProgress.value) {
            Log.d(TAG, "OTA 진행 중 → 이펙트 전송 차단 [$source]")
            return false
        }
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return false
        }

        return try {
            val devices = LSBluetooth.connectedDevices()
            if (devices.isEmpty()) {
                return false
            }

            var success = false

            devices.forEach { device ->
                try {
                    device.sendEffect(payload)

                    val transmissionEvent = BleTransmissionEvent(
                        source = source,
                        deviceMac = device.mac,
                        effectType = payload.effectType,
                        payload = payload,
                        color = payload.color,
                        backgroundColor = payload.backgroundColor,
                        transit = if (payload.effectType == EffectType.ON || payload.effectType == EffectType.OFF) payload.period else null,
                        period = if (payload.effectType != EffectType.ON && payload.effectType != EffectType.OFF) payload.period else null,
                        metadata = metadata
                    )
                    BleTransmissionMonitor.recordTransmission(transmissionEvent)

                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to ${device.mac}: ${e.message}")
                }
            }

            // Effect Control(PAYLOAD_EFFECT) 활성 상태 갱신
            if (success && source == TransmissionSource.PAYLOAD_EFFECT) {
                isManualEffectActive = (payload.effectType != EffectType.OFF)
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Effect send error: ${e.message}")
            false
        }
    }

    fun sendEffectToDevice(
        context: Context,
        deviceMac: String,
        payload: LSEffectPayload,
        source: TransmissionSource,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        if (OtaState.isAnyInProgress.value) {
            Log.d(TAG, "OTA 진행 중 → 이펙트 전송 차단 [$source] → $deviceMac")
            return false
        }
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return false
        }

        return try {
            val devices = LSBluetooth.connectedDevices()
            val target = devices.find { it.mac == deviceMac }
            if (target == null) {
                Log.w(TAG, "Device $deviceMac not found or not connected")
                return false
            }

            target.sendEffect(payload)

            val transmissionEvent = BleTransmissionEvent(
                source = source,
                deviceMac = deviceMac,
                effectType = payload.effectType,
                payload = payload,
                color = payload.color,
                backgroundColor = payload.backgroundColor,
                transit = if (payload.effectType == EffectType.ON || payload.effectType == EffectType.OFF) payload.period else null,
                period = if (payload.effectType != EffectType.ON && payload.effectType != EffectType.OFF) payload.period else null,
                metadata = metadata
            )
            BleTransmissionMonitor.recordTransmission(transmissionEvent)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Effect send error: ${e.message}")
            false
        }
    }

    fun playFrames(context: Context, frames: List<Pair<Long, ByteArray>>): Device? {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return null
        }

        val device = resolveTarget(context) ?: run {
            Log.w(TAG, "No target device")
            return null
        }

        return try {
            device.play(frames)
            device
        } catch (e: Exception) {
            Log.e(TAG, "Play frames error: ${e.message}")
            null
        }
    }

    fun sendColor(context: Context, color: Color, transit: Int, source: TransmissionSource): Boolean {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return false
        }

        val device = resolveTarget(context) ?: run {
            Log.w(TAG, "No target device")
            return false
        }

        return try {
            device.sendColor(color, transit)

            val transmissionEvent = BleTransmissionEvent(
                source = source,
                deviceMac = device.mac,
                effectType = null,
                payload = LSEffectPayload.Effects.on(color, transit = transit),
                color = color,
                transit = transit,
                metadata = mapOf("type" to "color")
            )
            BleTransmissionMonitor.recordTransmission(transmissionEvent)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Send color error: ${e.message}")
            false
        }
    }

    /** 자동 타임라인(frames) 로드 — 연결된 모든 기기에 전송 */
    @Synchronized
    fun loadTimelineFromFrames(context: Context, frames: List<Pair<Long, ByteArray>>) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) { Log.w(TAG, "No connected devices for auto timeline"); return }

        try {
            Log.d(TAG, "[load] loadTimeline: total=${frames.size}개 frames → SDK 전달")
            frames.take(10).forEachIndexed { i, (t, bytes) ->
                val payload = runCatching { LSEffectPayload.fromByteArray(bytes) }.getOrNull()
                Log.d(TAG, "[load]   frame[$i] t=${t}ms type=${payload?.effectType} color=${payload?.color}")
            }
            devices.forEach { it.loadTimeline(frames) }
            Log.d(TAG, "[load] loadTimeline 완료 devices=${devices.size}")
            isTimelineLoaded = true
            loadedEffectSource = TransmissionSource.TIMELINE_EFFECT
            lastRecordedEffectIndex = -1

            cachedTimeline = frames.mapNotNull { (timestampMs, bytes) ->
                try {
                    EfxEntry(timestampMs, LSEffectPayload.fromByteArray(bytes))
                } catch (e: Exception) {
                    Log.w(TAG, "Frame parse failed at ${timestampMs}ms: ${e.message}")
                    null
                }
            }.sortedBy { it.timestampMs }

        } catch (e: Exception) {
            isTimelineLoaded = false
            loadedEffectSource = null
            Log.e(TAG, "Auto timeline load failed: ${e.message}")
        }
    }

    /** EFX 파일 기반 이펙트 로드 — 연결된 모든 기기에 전송 */
    @Synchronized
    fun loadEffectsFor(context: Context, musicFile: File) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) { Log.w(TAG, "No connected devices for EFX"); return }

        try {
            val loadedEffects = MusicEffectManager.loadEffects(musicFile)
            if (loadedEffects.isNullOrEmpty()) {
                isTimelineLoaded = false
                loadedEffectSource = null
                return
            }

            cachedTimeline = loadedEffects
            lastRecordedEffectIndex = -1

            val frames = loadedEffects.map { entry -> entry.timestampMs to entry.payload.toByteArray() }
            devices.forEach { it.loadTimeline(frames) }

            isTimelineLoaded = true
            loadedEffectSource = TransmissionSource.EFX_EFFECT
        } catch (e: Exception) {
            isTimelineLoaded = false
            loadedEffectSource = null
            Log.e(TAG, "EFX load failed: ${e.message}")
        }
    }

    fun updatePlaybackPosition(context: Context, currentPositionMs: Long) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) return

        try {
            // 이 posMs 기준으로 "이미 지난" 프레임 중 아직 안 보낸 것 목록
            val due = cachedTimeline.filter { it.timestampMs <= currentPositionMs }
            val lastSent = lastRecordedEffectIndex
            if (due.size > lastSent + 2) {
                Log.w(TAG, "[sdk] 위험: posMs=${currentPositionMs}ms 기준 due=${due.size}개, lastSent=$lastSent → ${due.size - lastSent - 1}개 한번에 처리 가능")
            }
            Log.d(TAG, "[sdk] updatePlaybackPosition posMs=${currentPositionMs}ms due=${due.size} lastSentIdx=$lastSent")
            devices.forEach { it.updatePlaybackPosition(currentPositionMs) }
            recordCurrentTimelineEffect(devices.first().mac, currentPositionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Update playback failed: ${e.message}")
        }
    }

    fun handleSeek(context: Context, newPositionMs: Long) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) return

        try {
            lastRecordedEffectIndex = -1
            devices.forEach { it.updatePlaybackPosition(newPositionMs) }
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed: ${e.message}")
        }
    }

    fun pauseEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        resolveAllDevices(context).forEach {
            try { it.pauseEffects() } catch (e: Exception) { Log.e(TAG, "Pause failed ${it.mac}: ${e.message}") }
        }
    }

    fun resumeEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        resolveAllDevices(context).forEach {
            try { it.resumeEffects() } catch (e: Exception) { Log.e(TAG, "Resume failed ${it.mac}: ${e.message}") }
        }
    }

    @Synchronized
    fun reset() {
        isTimelineLoaded = false
        isManualEffectActive = false
        loadedEffectSource = null
        cachedTimeline = emptyList()
        lastRecordedEffectIndex = -1
        try { LSBluetooth.connectedDevices().forEach { it.stopTimeline() } } catch (_: Exception) {}
        targetDevice = null
    }

    /** FFT는 Timeline이 없을 때만 전송 */
    fun processFFT(context: Context, band: FrequencyBand) {
        if (isTimelineLoaded) return

        val total = (band.bass + band.mid + band.treble).let { if (it <= 0f) 1e-6f else it }

        val color = Color(
            r = ((band.bass / total) * 255f).toInt().coerceIn(0, 255),
            g = ((band.mid / total) * 255f).toInt().coerceIn(0, 255),
            b = ((band.treble / total) * 255f).toInt().coerceIn(0, 255)
        )

        sendColor(context, color, transit = 5, source = TransmissionSource.FFT_EFFECT)
    }

    /** 타임라인/재생 제어 대상: 연결된 모든 기기 반환 */
    private fun resolveAllDevices(context: Context): List<Device> {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return emptyList()
        return try {
            LSBluetooth.connectedDevices()
        } catch (t: Throwable) {
            Log.e(TAG, "resolveAllDevices() failed: ${t.message}")
            emptyList()
        }
    }

    fun setTargetAddress(address: String?) {
        targetAddress = address
        targetDevice = null
    }

    fun getTargetAddress(): String? = targetAddress

    fun getLoadedEffectCount(): Int = cachedTimeline.size

    private fun resolveTarget(context: Context): Device? {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return null

        return try {
            val addr = targetAddress

            if (addr != null && targetDevice != null && targetDevice?.isConnected() == true) {
                return targetDevice
            }

            if (addr != null) {
                val devices = LSBluetooth.connectedDevices()
                targetDevice = devices.find { it.mac == addr && it.isConnected() }
                if (targetDevice != null) return targetDevice
            }

            val first = LSBluetooth.connectedDevices().firstOrNull()
            if (first != null) {
                targetDevice = first
                targetAddress = first.mac
            }
            targetDevice
        } catch (t: Throwable) {
            Log.e(TAG, "resolveTarget() failed: ${t.message}")
            null
        }
    }

    @Synchronized
    private fun recordCurrentTimelineEffect(deviceMac: String, currentPositionMs: Long) {
        if (cachedTimeline.isEmpty()) return

        try {
            val latestTransmission = BleTransmissionMonitor.latestTransmission.value
            if (latestTransmission != null) {
                val dt = System.currentTimeMillis() - latestTransmission.timestamp
                if (latestTransmission.source == TransmissionSource.PAYLOAD_EFFECT && dt < 500) return
            }

            val currentEffectIndex = cachedTimeline.indexOfLast { it.timestampMs <= currentPositionMs }
            if (currentEffectIndex < 0) return
            if (currentEffectIndex == lastRecordedEffectIndex) return

            // 인덱스가 2 이상 건너뛰면 경고 (SDK가 중간 프레임을 스킵했을 가능성)
            val skipped = currentEffectIndex - lastRecordedEffectIndex - 1
            if (lastRecordedEffectIndex >= 0 && skipped > 0) {
                Log.w(TAG, "[record] effectIndex 건너뜀: ${lastRecordedEffectIndex} → ${currentEffectIndex} (${skipped}개 스킵) posMs=${currentPositionMs}ms")
                for (si in (lastRecordedEffectIndex + 1) until currentEffectIndex) {
                    val skippedEntry = cachedTimeline[si]
                    val p = skippedEntry.payload
                    Log.w(TAG, "[record]   스킵된 frame[$si] t=${skippedEntry.timestampMs}ms type=${p.effectType} color=${p.color}")
                }
            }

            val currentEntry = cachedTimeline[currentEffectIndex]
            val currentPayload = currentEntry.payload

            // [firmware] 이전 프레임과의 간격 및 연속 동일 color 패턴 감지
            if (lastRecordedEffectIndex >= 0) {
                val prevEntry = cachedTimeline[lastRecordedEffectIndex]
                val intervalMs = currentEntry.timestampMs - prevEntry.timestampMs
                val prevColor = prevEntry.payload.color
                val currColor = currentPayload.color
                val sameColor = (prevColor != null && currColor != null &&
                        prevColor.r == currColor.r && prevColor.g == currColor.g && prevColor.b == currColor.b)
                Log.d(TAG, "[firmware] idx=${currentEffectIndex}(1-idx=${currentEffectIndex + 1}) " +
                        "t=${currentEntry.timestampMs}ms interval=${intervalMs}ms " +
                        "type=${currentPayload.effectType} color=${currColor} " +
                        "sameColorAsPrev=$sameColor")
                if (sameColor) {
                    Log.w(TAG, "[firmware] ★ 연속 동일 color 감지! idx=${lastRecordedEffectIndex}→${currentEffectIndex} " +
                            "t=${prevEntry.timestampMs}→${currentEntry.timestampMs}ms color=${currColor} " +
                            "→ 펌웨어가 무시할 가능성 있음")
                }
            }

            // [firmware] raw bytes 로그 (effectIndex 4 = LedControl 1-indexed 5 주변 집중 확인)
            if (currentEffectIndex in 3..5) {
                val bytes = runCatching { currentEntry.payload.toByteArray() }.getOrNull()
                Log.d(TAG, "[firmware] raw frame[$currentEffectIndex] bytes=${bytes?.joinToString(",") { "%02X".format(it) }}")
            }

            Log.d(TAG, "[record] effectIndex=${currentEffectIndex} posMs=${currentPositionMs}ms t=${currentEntry.timestampMs}ms")
            lastRecordedEffectIndex = currentEffectIndex
            val currentEffect = cachedTimeline[currentEffectIndex]

            val source = loadedEffectSource ?: TransmissionSource.TIMELINE_EFFECT
            val transmissionEvent = BleTransmissionEvent(
                source = source,
                deviceMac = deviceMac,
                effectType = currentEffect.payload.effectType,
                payload = currentEffect.payload,
                color = currentEffect.payload.color,
                backgroundColor = currentEffect.payload.backgroundColor,
                transit = if (currentEffect.payload.effectType == EffectType.ON || currentEffect.payload.effectType == EffectType.OFF) currentEffect.payload.period else null,
                period = if (currentEffect.payload.effectType != EffectType.ON && currentEffect.payload.effectType != EffectType.OFF) currentEffect.payload.period else null,
                metadata = mapOf(
                    "type" to if (source == TransmissionSource.EFX_EFFECT) "efx_effect" else "timeline_effect",
                    "timestamp" to currentEffect.timestampMs,
                    "effectIndex" to currentEffectIndex
                )
            )

            BleTransmissionMonitor.recordTransmission(transmissionEvent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to record effect: ${e.message}")
        }
    }
}
