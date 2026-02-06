package com.lightstick.music.domain.effect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightstick.music.data.model.FrequencyBand
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.efx.EfxEntry
import com.lightstick.types.Color
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 중앙 집중식 Effect 제어 Singleton
 *
 * - Manual Effect (Effect 탭에서 수동 실행)
 * - Timeline Effect (음악 재생 중 타임라인 동기화)
 * - FFT Effect (음악 주파수 분석)
 *
 * 모든 Effect 전송은 이 Object를 통해서만 수행됨
 *
 * ✅ SDK 타임라인 API 사용으로 정확한 음악-이펙트 동기화 구현
 */
@SuppressLint("MissingPermission")
object EffectEngineController {
    private const val TAG = "EffectEngineController"

    // =========== 설정 가능한 속성 ===========
    @Volatile var ledColorMapper: LedColorMapper? = null
    @Volatile var processMode: FftProcessMode = FftProcessMode.DEFAULT_AND_CUSTOM

    // =========== Single-target management ===========
    @Volatile private var targetAddress: String? = null
    @Volatile private var targetDevice: Device? = null

    // =========== Manual Effect (EffectViewModel용) ===========
    @Volatile private var manualEffectJob: Job? = null

    // =========== Timeline Effect (MusicPlayerViewModel용) ===========
    @Volatile private var isTimelineLoaded = false

    // =========== Public API ===========

    /** Set/clear the current target device MAC address. */
    fun setTargetAddress(address: String?) {
        targetAddress = address
        targetDevice = null
    }

    /** Read the current target address (may be null). */
    fun getTargetAddress(): String? = targetAddress

    /**
     * Manual Effect 시작 (Effect 탭용)
     * - 기존 Manual Effect가 있으면 자동 중단 후 새로 시작
     */
    fun startManualEffect(
        payload: LSEffectPayload,
        context: Context,
        scope: CoroutineScope
    ) {
        // 기존 Manual Effect 중단
        stopManualEffect(context)

        manualEffectJob = scope.launch {
            try {
                while (isActive) {
                    sendEffect(payload, context)
                    delay(1000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual effect error: ${e.message}")
            }
        }

        Log.d(TAG, "Manual effect started")
    }

    /**
     * Manual Effect 중단
     */
    fun stopManualEffect(context: Context) {
        manualEffectJob?.cancel()
        manualEffectJob = null
        sendOffToAll(context)
        Log.d(TAG, "Manual effect stopped")
    }

    /**
     * ✅ Timeline Effect 로드 (음악 재생 시)
     * - 기존 Manual Effect가 있으면 자동 중단
     * - SDK의 device.loadTimeline() API 사용
     * - 타임라인 로드 후 즉시 0ms 위치로 초기화
     */
    fun loadEffectsFor(musicFile: File, context: Context) {
        // Manual Effect 중단
        stopManualEffect(context)

        try {
            // EFX 파일 로드
            val loadedEffects = MusicEffectManager.loadEffects(musicFile)

            if (loadedEffects.isNullOrEmpty()) {
                Log.d(TAG, "No EFX file found for: ${musicFile.name}")
                isTimelineLoaded = false
                return
            }

            // EfxEntry 리스트를 SDK 타임라인 프레임 형식으로 변환
            val frames = loadedEffects.map { entry ->
                entry.timestampMs to entry.payload.toByteArray()
            }

            // 타겟 디바이스에 타임라인 로드
            val device = resolveTarget(context)
            if (device != null && device.isConnected()) {
                val success = device.loadTimeline(frames)
                if (success) {
                    isTimelineLoaded = true

                    // ✅ 타임라인 로드 후 즉시 0ms 위치로 초기화
                    // 이렇게 하면 음악 재생 시작 전에 SDK가 준비 완료 상태가 됨
                    device.updatePlaybackPosition(0L)

                    Log.d(TAG, "✅ Timeline loaded: ${frames.size} frames for ${musicFile.name}")
                    Log.d(TAG, "📍 Timeline initialized at 0ms")
                } else {
                    isTimelineLoaded = false
                    Log.w(TAG, "⚠️ Failed to load timeline")
                }
            } else {
                isTimelineLoaded = false
                Log.w(TAG, "⚠️ No connected device to load timeline")
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Effect load failed: ${t.message}")
            isTimelineLoaded = false
        }
    }

    /**
     * ✅ Timeline Effect 로드 (URI 버전)
     */
    fun loadEffectsFor(musicUri: Uri, context: Context) {
        stopManualEffect(context)

        try {
            val loadedEffects = MusicEffectManager.loadEffects(context, musicUri)

            if (loadedEffects.isNullOrEmpty()) {
                Log.d(TAG, "No EFX file found for: $musicUri")
                isTimelineLoaded = false
                return
            }

            val frames = loadedEffects.map { entry ->
                entry.timestampMs to entry.payload.toByteArray()
            }

            val device = resolveTarget(context)
            if (device != null && device.isConnected()) {
                val success = device.loadTimeline(frames)
                if (success) {
                    isTimelineLoaded = true

                    // ✅ 타임라인 로드 후 즉시 0ms 위치로 초기화
                    device.updatePlaybackPosition(0L)

                    Log.d(TAG, "✅ Timeline loaded: ${frames.size} frames from URI")
                    Log.d(TAG, "📍 Timeline initialized at 0ms")
                } else {
                    isTimelineLoaded = false
                    Log.w(TAG, "⚠️ Failed to load timeline")
                }
            } else {
                isTimelineLoaded = false
                Log.w(TAG, "⚠️ No connected device to load timeline")
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Effect load failed: ${t.message}")
            isTimelineLoaded = false
        }
    }

    @Deprecated(
        message = "Use loadEffectsFor(File, Context) or loadEffectsFor(Uri, Context) instead",
        replaceWith = ReplaceWith("loadEffectsFor(musicFile, context)")
    )
    fun loadEffectsFor(musicId: Int, context: Context) {
        stopManualEffect(context)

        try {
            @Suppress("DEPRECATION")
            val loadedEffects = MusicEffectManager.loadEffectsByMusicId(musicId)

            if (loadedEffects.isNullOrEmpty()) {
                Log.d(TAG, "No EFX file found for musicId: $musicId")
                isTimelineLoaded = false
                return
            }

            val frames = loadedEffects.map { entry ->
                entry.timestampMs to entry.payload.toByteArray()
            }

            val device = resolveTarget(context)
            if (device != null && device.isConnected()) {
                val success = device.loadTimeline(frames)
                if (success) {
                    isTimelineLoaded = true

                    // ✅ 타임라인 로드 후 즉시 0ms 위치로 초기화
                    device.updatePlaybackPosition(0L)

                    Log.d(TAG, "✅ Timeline loaded: ${frames.size} frames for musicId $musicId")
                    Log.d(TAG, "📍 Timeline initialized at 0ms")
                } else {
                    isTimelineLoaded = false
                    Log.w(TAG, "⚠️ Failed to load timeline")
                }
            } else {
                isTimelineLoaded = false
                Log.w(TAG, "⚠️ No connected device to load timeline")
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Effect load failed: ${t.message}")
            isTimelineLoaded = false
        }
    }

    @Deprecated(
        message = "Use loadEffectsFor(File, Context) or loadEffectsFor(Uri, Context) instead",
        replaceWith = ReplaceWith("loadEffectsFor(musicFile, context)")
    )
    fun loadEffectsFor(musicId: Int) {
        Log.w(TAG, "Deprecated loadEffectsFor(musicId) called without context")
        isTimelineLoaded = false
    }

    /** Reset timeline effects */
    fun reset() {
        isTimelineLoaded = false
        targetDevice = null

        // 타임라인 중지
        try {
            targetDevice?.stopTimeline()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to stop timeline: ${e.message}")
        }

        Log.d(TAG, "Timeline reset")
    }

    /**
     * FFT Effect 처리 (음악 재생 중)
     * - 타임라인이 로드되어 있으면 FFT 효과는 처리하지 않음
     */
    fun processFftEffect(band: FrequencyBand, context: Context) {
        if (!hasBleConnectPermission(context)) return
        if (isTimelineLoaded) return  // 타임라인 재생 중에는 FFT 효과 비활성화

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
            Log.e(TAG, "FFT effect send failed: ${e.message}")
        }
    }

    /**
     * ✅ Timeline Position 업데이트 (음악 재생 중)
     * - SDK의 device.updatePlaybackPosition() 사용
     * - 100ms마다 호출하여 정확한 동기화 유지
     */
    fun updatePlaybackPosition(context: Context, currentPositionMs: Long) {
        if (!hasBleConnectPermission(context)) return
        if (!isTimelineLoaded) return

        try {
            val device = resolveTarget(context)
            if (device != null && device.isConnected()) {
                device.updatePlaybackPosition(currentPositionMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updatePlaybackPosition failed: ${e.message}")
        }
    }

    /**
     * ✅ Seek 처리 (음악 위치 변경 시)
     * - SDK가 자동으로 Seek를 감지하므로 특별한 처리 불필요
     * - 단순히 새 위치로 updatePlaybackPosition() 호출
     */
    fun handleSeek(context: Context, newPositionMs: Long) {
        if (!hasBleConnectPermission(context)) return
        if (!isTimelineLoaded) return

        try {
            val device = resolveTarget(context)
            if (device != null && device.isConnected()) {
                device.updatePlaybackPosition(newPositionMs)
                Log.d(TAG, "Seek to ${newPositionMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleSeek failed: ${e.message}")
        }
    }

    // =========== Internal Helpers ===========

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
            Log.e(TAG, "resolveTarget() failed: ${t.message}")
            return null
        }
    }

    private fun sendEffect(payload: LSEffectPayload, context: Context) {
        if (!hasBleConnectPermission(context)) return

        try {
            val devices = LSBluetooth.connectedDevices()
            if (devices.isEmpty()) {
                Log.d(TAG, "No connected devices")
                return
            }

            devices.forEach { device ->
                try {
                    device.sendEffect(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to ${device.mac}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Effect send error: ${e.message}")
        }
    }

    private fun sendOffToAll(context: Context) {
        if (!hasBleConnectPermission(context)) return

        try {
            val offPayload = LSEffectPayload.Effects.off()
            LSBluetooth.broadcastEffect(offPayload)
            Log.d(TAG, "Sent OFF to all devices")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending OFF: ${e.message}")
        }
    }

    private fun hasBleConnectPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun sendDefaultColor(context: Context, band: FrequencyBand) {
        val total = (band.bass + band.mid + band.treble).let { if (it <= 0f) 1e-6f else it }

        val color = Color(
            r = ((band.bass / total) * 255f).toInt().coerceIn(0, 255),
            g = ((band.mid / total) * 255f).toInt().coerceIn(0, 255),
            b = ((band.treble / total) * 255f).toInt().coerceIn(0, 255)
        )

        sendColorToTarget(context, color, transit = 5)
    }

    private fun sendColorToTarget(context: Context, color: Color, transit: Int) {
        if (!hasBleConnectPermission(context)) return
        val device = resolveTarget(context) ?: return

        try {
            device.sendColor(color = color, transition = transit)
        } catch (se: SecurityException) {
            Log.e(TAG, "sendColor SecurityException: ${se.message}")
        } catch (t: Throwable) {
            Log.e(TAG, "sendColor failed: ${t.message}")
        }
    }
}

/** Processing mode for FFT → LED color pipeline. */
enum class FftProcessMode { DEFAULT_ONLY, CUSTOM_ONLY, DEFAULT_AND_CUSTOM }

/**
 * Implement to customize FFT → LED color mapping.
 */
interface LedColorMapper {
    fun mapColor(band: FrequencyBand): LedColorWithTransit
}

/**
 * Simple Color+transition container used by [LedColorMapper].
 */
data class LedColorWithTransit(val color: Color, val transit: Int)