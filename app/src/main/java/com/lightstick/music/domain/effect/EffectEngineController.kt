package com.lightstick.music.domain.effect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
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

/**
 * ✅ Effect Engine Controller - BLE 전송의 핵심 엔진 (Single Entry Point)
 *
 * 책임:
 * - 기본적인 BLE 이펙트 전송 (sendEffect, playFrames, sendColor)
 * - 모든 전송에 대한 자동 Monitor 기록
 * - Timeline 관리 (load, update, seek)
 * - Target Device 관리
 *
 * 사용 규칙:
 * - ViewModel에서 직접 호출 금지 → UseCase를 통해서만 호출
 * - 모든 공개 API는 자동으로 Monitor에 기록됨
 *
 * 사용하는 곳:
 * - UseCase 계층 (PlayManualEffectUseCase, LoadMusicTimelineUseCase 등)
 */
@SuppressLint("MissingPermission")
object EffectEngineController {

    private const val TAG = "EffectEngineCtrl"

    // ═══════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════

    @Volatile private var targetDevice: Device? = null
    @Volatile private var targetAddress: String? = null
    @Volatile private var isTimelineLoaded: Boolean = false

    /** Timeline 캐싱 (App 레벨에서 관리) */
    @Volatile private var cachedTimeline: List<EfxEntry> = emptyList()
    @Volatile private var lastRecordedEffectIndex: Int = -1

    // ═══════════════════════════════════════════════════════════
    // ✅ Core API - Effect 전송
    // ═══════════════════════════════════════════════════════════

    /**
     * Effect 전송 (모든 연결된 디바이스)
     *
     * 모든 연결된 디바이스에 동일한 이펙트를 전송하고
     * 자동으로 Monitor에 기록합니다.
     *
     * @param context Android Context
     * @param payload LSEffectPayload
     * @param source 전송 소스 (MANUAL_EFFECT, CONNECTION_EFFECT 등)
     * @param metadata 추가 메타데이터 (Optional)
     * @return 성공 여부 (하나 이상의 디바이스에 전송 성공 시 true)
     */
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

        try {
            val devices = LSBluetooth.connectedDevices()
            if (devices.isEmpty()) {
                Log.d(TAG, "No connected devices")
                return false
            }

            var success = false

            devices.forEach { device ->
                try {
                    // ✅ 1. 실제 BLE 전송
                    device.sendEffect(payload)

                    // ✅ 2. Monitor 자동 기록
                    val transmissionEvent = BleTransmissionEvent(
                        source = source,
                        deviceMac = device.mac,
                        effectType = payload.effectType,
                        payload = payload,
                        color = payload.color,
                        backgroundColor = payload.backgroundColor,
                        transit = if (payload.effectType == EffectType.ON ||
                            payload.effectType == EffectType.OFF) {
                            payload.period
                        } else null,
                        period = if (payload.effectType != EffectType.ON &&
                            payload.effectType != EffectType.OFF) {
                            payload.period
                        } else null,
                        metadata = metadata
                    )
                    BleTransmissionMonitor.recordTransmission(transmissionEvent)

                    success = true
                    Log.d(TAG, "✅ Effect sent: ${payload.effectType} to ${device.mac}")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to ${device.mac}: ${e.message}")
                }
            }

            return success

        } catch (e: Exception) {
            Log.e(TAG, "Effect send error: ${e.message}")
            return false
        }
    }

    /**
     * Effect 전송 (특정 디바이스)
     *
     * 특정 MAC 주소의 디바이스에만 이펙트를 전송하고
     * 자동으로 Monitor에 기록합니다.
     *
     * @param context Android Context
     * @param deviceMac 대상 디바이스 MAC 주소
     * @param payload LSEffectPayload
     * @param source 전송 소스
     * @param metadata 추가 메타데이터 (Optional)
     * @return 성공 여부
     */
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

        try {
            val devices = LSBluetooth.connectedDevices()
            val targetDevice = devices.find { it.mac == deviceMac }

            if (targetDevice == null) {
                Log.w(TAG, "Device $deviceMac not found or not connected")
                return false
            }

            // ✅ 1. 실제 BLE 전송
            targetDevice.sendEffect(payload)

            // ✅ 2. Monitor 자동 기록
            val transmissionEvent = BleTransmissionEvent(
                source = source,
                deviceMac = deviceMac,
                effectType = payload.effectType,
                payload = payload,
                color = payload.color,
                backgroundColor = payload.backgroundColor,
                transit = if (payload.effectType == EffectType.ON ||
                    payload.effectType == EffectType.OFF) {
                    payload.period
                } else null,
                period = if (payload.effectType != EffectType.ON &&
                    payload.effectType != EffectType.OFF) {
                    payload.period
                } else null,
                metadata = metadata
            )
            BleTransmissionMonitor.recordTransmission(transmissionEvent)

            Log.d(TAG, "✅ Effect sent: ${payload.effectType} to $deviceMac")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Effect send error: ${e.message}")
            return false
        }
    }

    /**
     * Frames 재생 (device.play() 사용)
     *
     * Target 디바이스에 timestamped frames를 재생합니다.
     * EffectList나 짧은 시퀀스 재생에 사용됩니다.
     *
     * ⚠️ 주의: Monitor 기록은 UseCase에서 별도로 수행해야 합니다.
     * (프레임별 타이밍 추적이 필요하기 때문)
     *
     * @param context Android Context
     * @param frames 재생할 프레임 리스트 [(timestampMs, ByteArray), ...]
     * @return Target Device (재생 중인 디바이스, 실패 시 null)
     */
    fun playFrames(
        context: Context,
        frames: List<Pair<Long, ByteArray>>
    ): Device? {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return null
        }

        val device = resolveTarget(context)
        if (device == null) {
            Log.w(TAG, "No target device")
            return null
        }

        try {
            // ✅ SDK에 재생 시작
            device.play(frames)
            Log.d(TAG, "✅ Frames playback started on ${device.mac}")
            return device

        } catch (e: Exception) {
            Log.e(TAG, "Play frames error: ${e.message}")
            return null
        }
    }

    /**
     * Color 전송 (sendColor 사용)
     *
     * Target 디바이스에 색상을 전송하고
     * 자동으로 Monitor에 기록합니다.
     *
     * @param context Android Context
     * @param color Color
     * @param transit Transit 값 (색상 전환 속도)
     * @param source 전송 소스
     * @return 성공 여부
     */
    fun sendColor(
        context: Context,
        color: Color,
        transit: Int,
        source: TransmissionSource
    ): Boolean {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return false
        }

        val device = resolveTarget(context)
        if (device == null) {
            Log.w(TAG, "No target device")
            return false
        }

        try {
            // ✅ 1. 실제 BLE 전송
            device.sendColor(color, transit)

            // ✅ 2. Monitor 자동 기록
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

            Log.d(TAG, "✅ Color sent to ${device.mac}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Send color error: ${e.message}")
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ Timeline API (기존 유지)
    // ═══════════════════════════════════════════════════════════

    /**
     * 음악 파일의 타임라인 로드
     *
     * EFX 파일을 파싱하여 Target 디바이스에 타임라인을 로드합니다.
     *
     * @param context Android Context
     * @param musicFile 음악 파일 (동일 경로에 .efx 파일 필요)
     */
    fun loadEffectsFor(context: Context, musicFile: File) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return

        val device = resolveTarget(context) ?: return

        try {
            val loadedEffects = MusicEffectManager.loadEffects(musicFile)

            if (loadedEffects.isNullOrEmpty()) {
                Log.d(TAG, "No EFX file found for: ${musicFile.name}")
                isTimelineLoaded = false
                return
            }

            cachedTimeline = loadedEffects
            lastRecordedEffectIndex = -1

            val frames = loadedEffects.map { entry ->
                entry.timestampMs to entry.payload.toByteArray()
            }

            device.loadTimeline(frames)
            isTimelineLoaded = true

            Log.d(TAG, "✅ Timeline loaded: ${frames.size} effects")
        } catch (e: Exception) {
            Log.e(TAG, "Timeline load failed: ${e.message}")
            isTimelineLoaded = false
        }
    }

    /**
     * 재생 위치 업데이트
     *
     * 현재 음악 재생 위치를 SDK에 전달하고
     * 해당 시점의 이펙트를 Monitor에 기록합니다.
     *
     * @param context Android Context
     * @param currentPositionMs 현재 재생 위치 (밀리초)
     */
    fun updatePlaybackPosition(context: Context, currentPositionMs: Long) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return

        val device = resolveTarget(context) ?: return

        try {
            // ✅ 1. SDK 전송
            device.updatePlaybackPosition(currentPositionMs)

            // ✅ 2. Monitor 기록
            recordCurrentTimelineEffect(device.mac, currentPositionMs)

        } catch (e: Exception) {
            Log.e(TAG, "Update playback failed: ${e.message}")
        }
    }

    /**
     * Seek 처리
     *
     * 사용자가 재생 위치를 변경했을 때 호출합니다.
     * Timeline 인덱스를 리셋하고 새 위치로 이동합니다.
     *
     * @param context Android Context
     * @param newPositionMs 새로운 재생 위치 (밀리초)
     */
    fun handleSeek(context: Context, newPositionMs: Long) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return

        val device = resolveTarget(context) ?: return

        try {
            lastRecordedEffectIndex = -1
            device.updatePlaybackPosition(newPositionMs)
            Log.d(TAG, "✅ Seek handled: ${newPositionMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed: ${e.message}")
        }
    }

    /**
     * 이펙트 일시정지
     *
     * Timeline 추적은 유지하되 BLE 전송만 중단합니다.
     *
     * @param context Android Context
     */
    fun pauseEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return

        val device = resolveTarget(context) ?: return
        try {
            device.pauseEffects()
            Log.d(TAG, "⏸ Timeline paused")
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed: ${e.message}")
        }
    }

    /**
     * 이펙트 재개
     *
     * syncIndex를 증가시켜 재동기화하고 전송을 재개합니다.
     *
     * @param context Android Context
     */
    fun resumeEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return

        val device = resolveTarget(context) ?: return
        try {
            device.resumeEffects()
            Log.d(TAG, "▶️ Timeline resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Resume failed: ${e.message}")
        }
    }

    /**
     * Controller 리셋
     *
     * Timeline과 캐시를 모두 클리어합니다.
     */
    fun reset() {
        isTimelineLoaded = false
        cachedTimeline = emptyList()
        lastRecordedEffectIndex = -1
        targetDevice?.stopTimeline()
        Log.d(TAG, "♻️ Controller reset")
    }

    /**
     * FFT 데이터 처리
     *
     * Timeline이 없을 때 주파수 분석 데이터를 색상으로 변환하여 전송합니다.
     *
     * @param context Android Context
     * @param band FrequencyBand (bass, mid, treble)
     */
    fun processFFT(context: Context, band: FrequencyBand) {
        if (!isTimelineLoaded) {
            val total = (band.bass + band.mid + band.treble).let { if (it <= 0f) 1e-6f else it }

            val color = Color(
                r = ((band.bass / total) * 255f).toInt().coerceIn(0, 255),
                g = ((band.mid / total) * 255f).toInt().coerceIn(0, 255),
                b = ((band.treble / total) * 255f).toInt().coerceIn(0, 255)
            )

            sendColor(context, color, transit = 5, source = TransmissionSource.FFT_EFFECT)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ Target Device 관리
    // ═══════════════════════════════════════════════════════════

    /**
     * Target Device MAC 주소 설정
     *
     * @param address 대상 디바이스 MAC 주소 (null이면 자동 선택)
     */
    fun setTargetAddress(address: String?) {
        targetAddress = address
        targetDevice = null
    }

    /**
     * Target Device MAC 주소 조회
     *
     * @return 현재 Target MAC 주소 (null 가능)
     */
    fun getTargetAddress(): String? = targetAddress

    // ═══════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Target Device 해결
     *
     * 캐시된 디바이스를 확인하거나 첫 번째 연결된 디바이스를 반환합니다.
     */
    private fun resolveTarget(context: Context): Device? {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return null

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

    /**
     * ✅ Timeline Effect Monitor 기록 (우선순위 체크 포함)
     *
     * 현재 재생 위치의 이펙트를 Monitor에 기록합니다.
     * 최근 Manual Effect가 전송되었으면 스킵합니다.
     */
    private fun recordCurrentTimelineEffect(deviceMac: String, currentPositionMs: Long) {
        if (cachedTimeline.isEmpty()) return

        try {
            // ✅ 우선순위 체크: 최근 Manual Effect가 있으면 스킵
            val latestTransmission = BleTransmissionMonitor.latestTransmission.value
            if (latestTransmission != null) {
                val timeSinceLastTransmission = System.currentTimeMillis() - latestTransmission.timestamp

                if (latestTransmission.source == TransmissionSource.MANUAL_EFFECT &&
                    timeSinceLastTransmission < 500) {
                    return
                }
            }

            val currentEffectIndex = cachedTimeline.indexOfLast { entry ->
                entry.timestampMs <= currentPositionMs
            }

            if (currentEffectIndex == lastRecordedEffectIndex || currentEffectIndex < 0) {
                return
            }

            lastRecordedEffectIndex = currentEffectIndex
            val currentEffect = cachedTimeline[currentEffectIndex]

            val transmissionEvent = BleTransmissionEvent(
                source = TransmissionSource.TIMELINE_EFFECT,
                deviceMac = deviceMac,
                effectType = currentEffect.payload.effectType,
                payload = currentEffect.payload,
                color = currentEffect.payload.color,
                backgroundColor = currentEffect.payload.backgroundColor,
                transit = if (currentEffect.payload.effectType == EffectType.ON ||
                    currentEffect.payload.effectType == EffectType.OFF) {
                    currentEffect.payload.period
                } else null,
                period = if (currentEffect.payload.effectType != EffectType.ON &&
                    currentEffect.payload.effectType != EffectType.OFF) {
                    currentEffect.payload.period
                } else null,
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