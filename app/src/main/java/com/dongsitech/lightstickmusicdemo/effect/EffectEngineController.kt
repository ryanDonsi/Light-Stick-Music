package com.dongsitech.lightstickmusicdemo.effect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.dongsitech.lightstickmusicdemo.model.FrequencyBand
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.efx.EfxEntry
import com.lightstick.types.Color
import java.io.File

/**
 * Controls FFT/timeline-driven LED effects for a SINGLE Light Stick.
 *
 * ✅ 개선 사항:
 * - SDK의 Color 타입 직접 사용 (Byte 변환 제거)
 * - EfxEntry.payload 직접 사용 (LSEffectPayload 변환 불필요)
 * - LedColor → LedColorWithTransit로 명확화
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
        targetDevice = null
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

            if (addr != null && targetDevice != null && targetDevice?.isConnected() == true) {
                return targetDevice
            }

            if (addr != null) {
                val devices = LSBluetooth.connectedDevices()
                targetDevice = devices.find { it.mac == addr && it.isConnected() }
                if (targetDevice != null) return targetDevice
            }

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

    /** ✅ 변경: EfxEntry 타입 사용 (SDK 타입) */
    private var loadedEffects: List<EfxEntry> = emptyList()

    private var isEffectFileMode = false
    private var lastEffectIndex = -1

    /**
     * ✅ SDK 활용: EfxEntry 리스트 로드
     */
    fun loadEffectsFor(musicFile: File, context: Context) {
        sdkTimeline = null

        if (sdkTimeline.isNullOrEmpty()) {
            loadedEffects = try {
                MusicEffectManager.loadEffects(musicFile) ?: emptyList()
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

    fun loadEffectsFor(musicUri: Uri, context: Context) {
        sdkTimeline = null

        if (sdkTimeline.isNullOrEmpty()) {
            loadedEffects = try {
                MusicEffectManager.loadEffects(context, musicUri) ?: emptyList()
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

    @Deprecated(
        message = "Use loadEffectsFor(File, Context) or loadEffectsFor(Uri, Context) instead",
        replaceWith = ReplaceWith("loadEffectsFor(musicFile, context)")
    )
    fun loadEffectsFor(musicId: Int, context: Context) {
        sdkTimeline = null

        if (sdkTimeline.isNullOrEmpty()) {
            loadedEffects = try {
                @Suppress("DEPRECATION")
                MusicEffectManager.loadEffectsByMusicId(musicId) ?: emptyList()
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

    @Deprecated(
        message = "Use loadEffectsFor(File, Context) or loadEffectsFor(Uri, Context) instead",
        replaceWith = ReplaceWith("loadEffectsFor(musicFile, context)")
    )
    fun loadEffectsFor(musicId: Int) {
        sdkTimeline = null
        loadedEffects = try {
            @Suppress("DEPRECATION")
            MusicEffectManager.loadEffectsByMusicId(musicId) ?: emptyList()
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
     * ✅ 개선: Color 타입 직접 사용
     */
    @SuppressLint("MissingPermission")
    fun processFftEffect(band: FrequencyBand, context: Context) {
        if (!hasBleConnectPermission(context)) return
        if (loadedEffects.isNotEmpty()) return

        try {
            when (processMode) {
                FftProcessMode.DEFAULT_ONLY -> sendDefaultColor(context, band)
                FftProcessMode.CUSTOM_ONLY -> {
                    ledColorMapper?.let { mapper ->
                        val result = mapper.mapColor(band)
                        sendColorToTarget(context, result.color, result.transit)
                    }
                }
                FftProcessMode.DEFAULT_AND_CUSTOM -> {
                    sendDefaultColor(context, band)
                    ledColorMapper?.let { mapper ->
                        val result = mapper.mapColor(band)
                        sendColorToTarget(context, result.color, result.transit)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("EffectEngineController", "BLE send failed (processFftEffect): ${e.message}")
        }
    }

    /**
     * ✅ 개선: EfxEntry.payload는 이미 LSEffectPayload 타입 - 변환 불필요
     */
    @SuppressLint("MissingPermission")
    fun processPosition(context: Context, currentPositionMs: Int) {
        if (!hasBleConnectPermission(context)) return

        if (loadedEffects.isNotEmpty()
            && lastEffectIndex + 1 < loadedEffects.size
            && loadedEffects[lastEffectIndex + 1].timestampMs <= currentPositionMs
        ) {
            val device = resolveTarget(context) ?: return
            lastEffectIndex++

            // ✅ EfxEntry.payload는 이미 LSEffectPayload 타입
            val payload = loadedEffects[lastEffectIndex].payload

            try {
                device.sendEffect(payload)
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

    /**
     * ✅ 개선: 직접 Color 객체 생성 (Byte 변환 불필요)
     */
    @SuppressLint("MissingPermission")
    private fun sendDefaultColor(context: Context, band: FrequencyBand) {
        val total = (band.bass + band.mid + band.treble).let { if (it <= 0f) 1e-6f else it }

        // ✅ 직접 Color 생성 (Int 값 사용)
        val color = Color(
            r = ((band.bass / total) * 255f).toInt().coerceIn(0, 255),
            g = ((band.mid / total) * 255f).toInt().coerceIn(0, 255),
            b = ((band.treble / total) * 255f).toInt().coerceIn(0, 255)
        )

        sendColorToTarget(context, color, transit = 5)
    }

    /**
     * ✅ 개선: Color 타입 직접 사용 (Byte 변환 제거)
     */
    @SuppressLint("MissingPermission")
    private fun sendColorToTarget(
        context: Context,
        color: Color,
        transit: Int
    ) {
        if (!hasBleConnectPermission(context)) return
        val device = resolveTarget(context) ?: return

        try {
            device.sendColor(color = color, transition = transit)
        } catch (se: SecurityException) {
            Log.e("EffectEngineController", "sendColor SecurityException: ${se.message}")
        } catch (t: Throwable) {
            Log.e("EffectEngineController", "sendColor failed: ${t.message}")
        }
    }
}

/** Processing mode for FFT → LED color pipeline. */
enum class FftProcessMode { DEFAULT_ONLY, CUSTOM_ONLY, DEFAULT_AND_CUSTOM }

/**
 * ✅ 개선: SDK의 Color 타입 사용
 * Implement to customize FFT → LED color mapping.
 */
interface LedColorMapper {
    fun mapColor(band: FrequencyBand): LedColorWithTransit
}

/**
 * ✅ 개선: SDK Color + transit 조합
 * Simple Color+transition container used by [LedColorMapper].
 */
data class LedColorWithTransit(val color: Color, val transit: Int)