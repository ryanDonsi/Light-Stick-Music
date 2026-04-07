package com.lightstick.music.domain.effect

import android.annotation.SuppressLint
import android.content.Context
import com.lightstick.music.core.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.efx.EfxEntry
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.data.model.FrequencyBand
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.types.Color
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import java.io.File

@SuppressLint("MissingPermission")
object EffectEngineController {

    private const val TAG = "EffectEngineCtrl"

    @Volatile private var targetDevice: Device? = null
    @Volatile private var targetAddress: String? = null
    @Volatile private var isTimelineLoaded: Boolean = false

    @Volatile private var cachedTimeline: List<EfxEntry> = emptyList()
    @Volatile private var lastRecordedEffectIndex: Int = -1

    /** ✅ MusicViewModel에서 FFT 차단용으로 사용 */
    fun isTimelineActive(): Boolean = isTimelineLoaded

    // ─────────────────────────────────────────────
    // Core send
    // ─────────────────────────────────────────────

    fun sendEffect(
        context: Context,
        payload: LSEffectPayload,
        source: TransmissionSource,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return false
        }

        return try {
            val devices = LSBluetooth.connectedDevices()
            if (devices.isEmpty()) {
                Log.d(TAG, "No connected devices")
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

    // ─────────────────────────────────────────────
    // Timeline
    // ─────────────────────────────────────────────

    /** 자동 타임라인(frames) 로드 — 연결된 모든 기기에 전송 */
    fun loadTimelineFromFrames(context: Context, frames: List<Pair<Long, ByteArray>>) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) { Log.w(TAG, "No connected devices for timeline"); return }

        try {
            devices.forEach { it.loadTimeline(frames) }
            isTimelineLoaded = true
            lastRecordedEffectIndex = -1

            cachedTimeline = frames.mapNotNull { (timestampMs, bytes) ->
                try {
                    EfxEntry(timestampMs, LSEffectPayload.fromByteArray(bytes))
                } catch (e: Exception) {
                    Log.w(TAG, "Frame parse failed at ${timestampMs}ms: ${e.message}")
                    null
                }
            }.sortedBy { it.timestampMs }

            Log.d(TAG, "✅ Precomputed timeline loaded to ${devices.size} device(s): ${frames.size} frames")
        } catch (e: Exception) {
            isTimelineLoaded = false
            Log.e(TAG, "Precomputed timeline load failed: ${e.message}")
        }
    }

    /** EFX 기반 타임라인 로드 — 연결된 모든 기기에 전송 */
    fun loadEffectsFor(context: Context, musicFile: File) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) { Log.w(TAG, "No connected devices for EFX timeline"); return }

        try {
            val loadedEffects = MusicEffectManager.loadEffects(musicFile)
            if (loadedEffects.isNullOrEmpty()) {
                Log.d(TAG, "No EFX file found for: ${musicFile.name}")
                isTimelineLoaded = false
                return
            }

            cachedTimeline = loadedEffects
            lastRecordedEffectIndex = -1

            val frames = loadedEffects.map { entry -> entry.timestampMs to entry.payload.toByteArray() }
            devices.forEach { it.loadTimeline(frames) }

            isTimelineLoaded = true
            Log.d(TAG, "✅ EFX timeline loaded to ${devices.size} device(s): ${frames.size} effects")
        } catch (e: Exception) {
            isTimelineLoaded = false
            Log.e(TAG, "Timeline load failed: ${e.message}")
        }
    }

    fun updatePlaybackPosition(context: Context, currentPositionMs: Long) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) return

        try {
            devices.forEach { it.updatePlaybackPosition(currentPositionMs) }
            // 첫 번째 기기 기준으로 UI 모니터 기록
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
            Log.d(TAG, "✅ Seek handled on ${devices.size} device(s): ${newPositionMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed: ${e.message}")
        }
    }

    fun pauseEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        resolveAllDevices(context).forEach {
            try { it.pauseEffects() } catch (e: Exception) { Log.e(TAG, "Pause failed ${it.mac}: ${e.message}") }
        }
        Log.d(TAG, "⏸ Timeline paused")
    }

    fun resumeEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        resolveAllDevices(context).forEach {
            try { it.resumeEffects() } catch (e: Exception) { Log.e(TAG, "Resume failed ${it.mac}: ${e.message}") }
        }
        Log.d(TAG, "▶️ Timeline resumed")
    }

    fun reset() {
        isTimelineLoaded = false
        cachedTimeline = emptyList()
        lastRecordedEffectIndex = -1
        try { LSBluetooth.connectedDevices().forEach { it.stopTimeline() } } catch (_: Exception) {}
        targetDevice = null
        Log.d(TAG, "♻️ Controller reset")
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

    // ─────────────────────────────────────────────
    // Target
    // ─────────────────────────────────────────────

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

    private fun recordCurrentTimelineEffect(deviceMac: String, currentPositionMs: Long) {
        if (cachedTimeline.isEmpty()) return

        try {
            val latestTransmission = BleTransmissionMonitor.latestTransmission.value
            if (latestTransmission != null) {
                val dt = System.currentTimeMillis() - latestTransmission.timestamp
                if (latestTransmission.source == TransmissionSource.PAYLOAD_EFFECT && dt < 500) return
            }

            val currentEffectIndex = cachedTimeline.indexOfLast { it.timestampMs <= currentPositionMs }
            if (currentEffectIndex == lastRecordedEffectIndex || currentEffectIndex < 0) return

            lastRecordedEffectIndex = currentEffectIndex
            val currentEffect = cachedTimeline[currentEffectIndex]

            val transmissionEvent = BleTransmissionEvent(
                source = TransmissionSource.TIMELINE_EFFECT,
                deviceMac = deviceMac,
                effectType = currentEffect.payload.effectType,
                payload = currentEffect.payload,
                color = currentEffect.payload.color,
                backgroundColor = currentEffect.payload.backgroundColor,
                transit = if (currentEffect.payload.effectType == EffectType.ON || currentEffect.payload.effectType == EffectType.OFF) currentEffect.payload.period else null,
                period = if (currentEffect.payload.effectType != EffectType.ON && currentEffect.payload.effectType != EffectType.OFF) currentEffect.payload.period else null,
                metadata = mapOf(
                    "type" to "timeline_effect",
                    "timestamp" to currentEffect.timestampMs,
                    "effectIndex" to currentEffectIndex
                )
            )

            BleTransmissionMonitor.recordTransmission(transmissionEvent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to record timeline effect: ${e.message}")
        }
    }
}