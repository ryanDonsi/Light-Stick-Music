package com.dongsitech.lightstickmusicdemo.effect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dongsitech.lightstickmusicdemo.model.FrequencyBand
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.types.Color
import com.lightstick.types.LSEffectPayload

/**
 * Controls FFT/timeline-driven LED effects for a SINGLE Light Stick.
 *
 * - Single target device (fallback to first actually-connected if target is null/disconnected)
 * - SDK-first .efx timeline loading (via reflection), app loader fallback
 * - Every BLE call is guarded by runtime permission check
 * - Lint-friendly: @SuppressLint("MissingPermission") on methods that perform BLE I/O
 */
class EffectEngineController(
    private val ledColorMapper: LedColorMapper? = null,
    private val processMode: FftProcessMode = FftProcessMode.DEFAULT_AND_CUSTOM
) {
    // ---------- Single-target management ----------
    @Volatile private var targetAddress: String? = null
    @Volatile private var targetDevice: Device? = null

    /** Set/clear the current target device MAC address. */
    fun setTargetAddress(address: String?) {
        targetAddress = address
        targetDevice = null // 디바이스 객체는 다시 찾아야 함
    }

    /** Read the current target address (may be null). */
    fun getTargetAddress(): String? = targetAddress

    /**
     * Resolve a usable send target Device:
     * - If targetAddress is set AND connected → use it.
     * - Else → use first of LSBluetooth.connectedDevices(), or null if none.
     * Guarded by BLUETOOTH_CONNECT permission check.
     */
    @SuppressLint("MissingPermission")
    private fun resolveTarget(context: Context): Device? {
        if (!hasBleConnectPermission(context)) return null

        try {
            val addr = targetAddress

            // 캐시된 타겟이 있고 연결되어 있으면 사용
            if (addr != null && targetDevice != null && targetDevice?.isConnected() == true) {
                return targetDevice
            }

            // 타겟 주소가 설정되어 있으면 해당 디바이스 찾기
            if (addr != null) {
                val devices = LSBluetooth.connectedDevices()
                targetDevice = devices.find { it.mac == addr && it.isConnected() }
                if (targetDevice != null) return targetDevice
            }

            // 타겟이 없으면 첫 번째 연결된 디바이스 사용
            val firstConnected = LSBluetooth.connectedDevices().firstOrNull()
            if (firstConnected != null) {
                targetDevice = firstConnected
                targetAddress = firstConnected.mac
            }
            return targetDevice

        } catch (t: Throwable) {
            Log.e("EffectEngineController", "resolveTarget() failed: ${t.message}")
            return null
        }
    }

    // ---------- Timeline storage ----------
    /** SDK timeline (list of LSEffectPayload or similar) if SDK loader exists. */
    private var sdkTimeline: List<Any>? = null

    /** App timeline (legacy) — kept non-null for null-safety. */
    private var loadedEffects: List<MusicEffect> = emptyList()

    private var isEffectFileMode = false
    private var lastEffectIndex = -1

    /**
     * Load per-track effects (.efx) by [musicId] — SDK first, then app fallback.
     * Prefer calling this overload (needs context) so that SDK loader를 시도할 수 있음.
     */
    fun loadEffectsFor(musicId: Int, context: Context) {
        // 1) Try SDK loader via reflection (현재는 신 SDK에 해당 기능 없음)
        // 나중에 SDK에 추가되면 활성화
        sdkTimeline = null // tryLoadTimelineViaSdk(context, musicId)

        if (sdkTimeline.isNullOrEmpty()) {
            // 2) Fallback: app loader
            loadedEffects = try {
                MusicEffectManager.loadEffects(musicId) ?: emptyList()
            } catch (t: Throwable) {
                Log.e("EffectEngineController", "App timeline load failed: ${t.message}")
                emptyList()
            }
            Log.d("EffectEngineController", "App timeline items: ${loadedEffects.size}")
        } else {
            loadedEffects = emptyList()
            Log.d("EffectEngineController", "SDK timeline items: ${sdkTimeline?.size}")
        }

        isEffectFileMode = !sdkTimeline.isNullOrEmpty() || loadedEffects.isNotEmpty()
        lastEffectIndex = -1
    }

    /** Backward-compatible overload: app loader only (no SDK attempt). */
    fun loadEffectsFor(musicId: Int) {
        sdkTimeline = null
        loadedEffects = try {
            MusicEffectManager.loadEffects(musicId) ?: emptyList()
        } catch (t: Throwable) {
            Log.e("EffectEngineController", "App timeline load failed: ${t.message}")
            emptyList()
        }
        isEffectFileMode = loadedEffects.isNotEmpty()
        lastEffectIndex = -1
    }

    /** Reset internal state, clearing any loaded timeline effects. */
    fun reset() {
        sdkTimeline = null
        loadedEffects = emptyList()
        isEffectFileMode = false
        lastEffectIndex = -1
        targetDevice = null
    }

    /**
     * Apply FFT-based effect for a single frame (called from the audio pipeline).
     * If a timeline is active, this is a no-op.
     */
    @SuppressLint("MissingPermission")
    fun processFftEffect(band: FrequencyBand, context: Context) {
        if (isEffectFileMode) return
        if (!hasBleConnectPermission(context)) return

        try {
            when (processMode) {
                FftProcessMode.DEFAULT_ONLY -> sendDefaultColor(context, band)
                FftProcessMode.CUSTOM_ONLY -> {
                    ledColorMapper?.let { mapper ->
                        val c = mapper.mapColor(band)
                        sendColorToTarget(context, c.r, c.g, c.b, c.transit)
                    }
                }
                FftProcessMode.DEFAULT_AND_CUSTOM -> {
                    sendDefaultColor(context, band)
                    ledColorMapper?.let { mapper ->
                        val c = mapper.mapColor(band)
                        sendColorToTarget(context, c.r, c.g, c.b, c.transit)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("EffectEngineController", "BLE send failed (processFftEffect): ${e.message}")
        }
    }

    /**
     * Drive timeline (.efx) effects by playback position (ms).
     * Sends each effect once when its start time is reached.
     */
    @SuppressLint("MissingPermission")
    fun processPosition(context: Context, currentPositionMs: Int) {
        if (!hasBleConnectPermission(context)) return

        // App timeline path (SDK timeline은 현재 미지원)
        if (loadedEffects.isNotEmpty()
            && lastEffectIndex + 1 < loadedEffects.size
            && loadedEffects[lastEffectIndex + 1].startTimeMs <= currentPositionMs
        ) {
            val device = resolveTarget(context) ?: return
            lastEffectIndex++
            val payload = loadedEffects[lastEffectIndex].payload

            try {
                // 신 SDK 방식: Device.sendEffect()
                sendEffectToDevice(device, payload)
            } catch (e: SecurityException) {
                Log.e("EffectEngineController", "BLE send failed (processPosition): ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private fun hasBleConnectPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    /** Map FFT bands to a basic RGB and send to the single target device. */
    private fun sendDefaultColor(context: Context, band: FrequencyBand) {
        val total = (band.bass + band.mid + band.treble).let { if (it <= 0f) 1e-6f else it }
        val r = ((band.bass   / total) * 255f).toInt().coerceIn(0, 255).toByte()
        val g = ((band.mid    / total) * 255f).toInt().coerceIn(0, 255).toByte()
        val b = ((band.treble / total) * 255f).toInt().coerceIn(0, 255).toByte()
        val transit: Byte = 5
        sendColorToTarget(context, r, g, b, transit)
    }

    /**
     * Send a color command to the single active target.
     * 신 SDK 방식: Device.sendColor() 사용
     */
    @SuppressLint("MissingPermission")
    private fun sendColorToTarget(
        context: Context,
        r: Byte, g: Byte, b: Byte,
        transit: Byte
    ) {
        if (!hasBleConnectPermission(context)) return
        val device = resolveTarget(context) ?: return

        try {
            val color = Color(
                r.toInt() and 0xFF,
                g.toInt() and 0xFF,
                b.toInt() and 0xFF
            )
            device.sendColor(color, transit.toInt() and 0xFF)
        } catch (se: SecurityException) {
            Log.e("EffectEngineController", "sendColor SecurityException: ${se.message}")
        } catch (t: Throwable) {
            Log.e("EffectEngineController", "sendColor failed: ${t.message}")
        }
    }

    /**
     * Send effect payload to device.
     * 신 SDK 방식: Device.sendEffect() 사용
     */
    @SuppressLint("MissingPermission")
    private fun sendEffectToDevice(device: Device, payload: ByteArray) {
        try {
            // ByteArray를 LSEffectPayload로 변환
            val effectPayload = LSEffectPayload.fromByteArray(payload)
            device.sendEffect(effectPayload)
        } catch (t: Throwable) {
            Log.e("EffectEngineController", "sendEffect failed: ${t.message}")
        }
    }
}

/** Processing mode for FFT → LED color pipeline. */
enum class FftProcessMode { DEFAULT_ONLY, CUSTOM_ONLY, DEFAULT_AND_CUSTOM }

/** Implement to customize FFT → LED color mapping. */
interface LedColorMapper { fun mapColor(band: FrequencyBand): LedColor }

/** Simple RGB+transition container used by [LedColorMapper]. */
data class LedColor(val r: Byte, val g: Byte, val b: Byte, val transit: Byte)