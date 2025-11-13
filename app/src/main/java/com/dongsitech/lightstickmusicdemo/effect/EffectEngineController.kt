package com.dongsitech.lightstickmusicdemo.effect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dongsitech.lightstickmusicdemo.model.FrequencyBand
import io.lightstick.sdk.ble.BleSdk

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

    /** Set/clear the current target device MAC address. */
    fun setTargetAddress(address: String?) { targetAddress = address }

    /** Read the current target address (may be null). */
    fun getTargetAddress(): String? = targetAddress

    /**
     * Resolve a usable send target:
     * - If targetAddress is set AND connected → use it.
     * - Else → use first of BleSdk.gattManager.getConnectedList(), or null if none.
     * Guarded by BLUETOOTH_CONNECT permission check.
     */
    @SuppressLint("MissingPermission")
    private fun resolveTarget(context: Context): String? {
        if (!hasBleConnectPermission(context)) return null
        val addr = targetAddress
        return try {
            when {
                addr != null && BleSdk.gattManager.isConnected(addr) -> addr
                else -> BleSdk.gattManager.getConnectedList().firstOrNull()
            }
        } catch (t: Throwable) {
            Log.e("EffectEngineController", "resolveTarget() failed: ${t.message}")
            null
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
        // 1) Try SDK loader via reflection: BleSdk.ledControlManager.loadEfxTimeline(context, musicId)
        sdkTimeline = tryLoadTimelineViaSdk(context, musicId)

        if (sdkTimeline.isNullOrEmpty()) {
            // 2) Fallback: app loader (nullable → emptyList()로 병합)
            loadedEffects = try {
                MusicEffectManager.loadEffects(musicId) ?: emptyList()
            } catch (t: Throwable) {
                Log.e("EffectEngineController", "App timeline load failed: ${t.message}")
                emptyList()
            }
            Log.d("EffectEngineController", "App timeline items: ${loadedEffects.size}")
        } else {
            loadedEffects = emptyList() // ensure app list empty when SDK list is present
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
    }

    /**
     * Apply FFT-based effect for a single frame (called from the audio pipeline).
     * If a timeline is active, this is a no-op.
     *
     * Lint notes:
     * - We check permission at the start
     * - We add @SuppressLint("MissingPermission") to silence Lint after the check
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
     *
     * Lint notes:
     * - We check permission at the start
     * - We add @SuppressLint("MissingPermission") to silence Lint after the check
     */
    @SuppressLint("MissingPermission")
    fun processPosition(context: Context, currentPositionMs: Int) {
        if (!hasBleConnectPermission(context)) return

        // 1) SDK timeline path
        sdkTimeline?.let { list ->
            val addr = resolveTarget(context) ?: return@let
            val next = list.getOrNull(lastEffectIndex + 1) ?: return@let
            val nextStart = reflectStartTimeMs(next) ?: return@let
            if (nextStart <= currentPositionMs) {
                lastEffectIndex++
                if (!reflectSendSdkPayload(addr, next)) {
                    // Fallback: extract raw bytes if possible
                    val bytes = reflectBytes(next)
                    if (bytes != null) {
                        try { BleSdk.ledControlManager.sendLedEffect(addr, bytes) }
                        catch (t: Throwable) { Log.e("EffectEngineController", "sendLedEffect(bytes) failed: ${t.message}") }
                    } else {
                        Log.w("EffectEngineController", "No compatible SDK send nor bytes extractor.")
                    }
                }
            }
            return
        }

        // 2) App timeline path
        if (loadedEffects.isNotEmpty()
            && lastEffectIndex + 1 < loadedEffects.size
            && loadedEffects[lastEffectIndex + 1].startTimeMs <= currentPositionMs
        ) {
            val addr = resolveTarget(context) ?: return
            lastEffectIndex++
            val payload = loadedEffects[lastEffectIndex].payload
            try {
                BleSdk.ledControlManager.sendLedEffect(addr, payload)
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
     * If target is null/disconnected, uses the first actually-connected address.
     *
     * Lint notes:
     * - Caller already checked permission
     * - We keep @SuppressLint here to make Lint happy regardless of call graph inlining
     */
    @SuppressLint("MissingPermission")
    private fun sendColorToTarget(
        context: Context,
        r: Byte, g: Byte, b: Byte,
        transit: Byte
    ) {
        if (!hasBleConnectPermission(context)) return
        val addr: String = resolveTarget(context) ?: return
        try {
            BleSdk.ledControlManager.sendLedColor(addr, r, g, b, transit)
        } catch (se: SecurityException) {
            Log.e("EffectEngineController", "sendLedColor SecurityException ($addr): ${se.message}")
        } catch (t: Throwable) {
            Log.e("EffectEngineController", "sendLedColor failed ($addr): ${t.message}")
        }
    }

    // ----------------- Reflection helpers (SDK timeline) -----------------

    /** Try calling BleSdk.ledControlManager.loadEfxTimeline(context, musicId) via reflection. */
    private fun tryLoadTimelineViaSdk(context: Context, musicId: Int): List<Any>? {
        return try {
            val mgr = BleSdk.ledControlManager
            val m = mgr::class.java.methods.firstOrNull { method ->
                method.name == "loadEfxTimeline" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == Context::class.java &&
                        (method.parameterTypes[1] == Int::class.java || method.parameterTypes[1] == Integer.TYPE)
            } ?: return null
            val res = m.invoke(mgr, context, musicId) as? List<*>
            @Suppress("UNCHECKED_CAST")
            (res?.filterNotNull())
        } catch (_: Throwable) {
            null
        }
    }

    /** Reflectively get startTimeMs from an SDK LSEffectPayload-like object. */
    private fun reflectStartTimeMs(obj: Any): Int? {
        return try {
            // Prefer getter
            obj.javaClass.methods.firstOrNull { it.name == "getStartTimeMs" && it.parameterCount == 0 }
                ?.let { (it.invoke(obj) as? Number)?.toInt() }
                ?: run {
                    // Try field
                    val f = obj.javaClass.getDeclaredField("startTimeMs")
                    f.isAccessible = true
                    (f.get(obj) as? Number)?.toInt()
                }
        } catch (_: Throwable) {
            null
        }
    }

    /** Reflectively call BleSdk.ledControlManager.sendLedEffect(addr, payloadObj) if such overload exists. */
    private fun reflectSendSdkPayload(addr: String, payloadObj: Any): Boolean {
        return try {
            val mgr = BleSdk.ledControlManager
            val method = mgr::class.java.methods.firstOrNull { m ->
                m.name == "sendLedEffect" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == String::class.java &&
                        // second param class name contains LSEffectPayload
                        m.parameterTypes[1].name.contains("LSEffectPayload")
            } ?: return false
            method.invoke(mgr, addr, payloadObj)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Try to extract raw bytes from an SDK payload via common names: bytes / payload / data. */
    private fun reflectBytes(obj: Any): ByteArray? {
        // getters
        obj.javaClass.methods.firstOrNull { it.name == "getBytes" && it.parameterCount == 0 }?.let {
            return it.invoke(obj) as? ByteArray
        }
        obj.javaClass.methods.firstOrNull { it.name == "getPayload" && it.parameterCount == 0 }?.let {
            return it.invoke(obj) as? ByteArray
        }
        obj.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }?.let {
            return it.invoke(obj) as? ByteArray
        }
        // fields
        runCatching { obj.javaClass.getDeclaredField("bytes") }.getOrNull()?.let {
            it.isAccessible = true; return it.get(obj) as? ByteArray
        }
        runCatching { obj.javaClass.getDeclaredField("payload") }.getOrNull()?.let {
            it.isAccessible = true; return it.get(obj) as? ByteArray
        }
        runCatching { obj.javaClass.getDeclaredField("data") }.getOrNull()?.let {
            it.isAccessible = true; return it.get(obj) as? ByteArray
        }
        return null
    }
}

/** Processing mode for FFT → LED color pipeline. */
enum class FftProcessMode { DEFAULT_ONLY, CUSTOM_ONLY, DEFAULT_AND_CUSTOM }

/** Implement to customize FFT → LED color mapping. */
interface LedColorMapper { fun mapColor(band: FrequencyBand): LedColor }

/** Simple RGB+transition container used by [LedColorMapper]. */
data class LedColor(val r: Byte, val g: Byte, val b: Byte, val transit: Byte)
