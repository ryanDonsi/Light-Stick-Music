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

    /** 펌웨어/타임라인 진단 로그(50ms 주기로 문자열 생성). 디버깅할 때만 true. */
    private const val DIAGNOSTIC_LOGS = false

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

    /**
     * 캔드 프레임 시퀀스 재생 (EFFECT LIST용).
     *
     * playJob 계열(자체 시계, 원샷, pause 불가) — `playEffects()`/`stopEffects()`가 정식 페어.
     * [stopPlayFrames] 참고.
     */
    fun playFrames(context: Context, frames: List<Pair<Long, ByteArray>>): Device? {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return null
        }

        // 연결된 기기 전체에 보낸다(B6). 예전에는 첫 기기 하나에만 갔다.
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) {
            Log.w(TAG, "No target device")
            return null
        }

        var first: Device? = null
        devices.forEach { device ->
            try {
                device.playEffects(frames)
                if (first == null) first = device
            } catch (e: Exception) {
                Log.e(TAG, "Play frames error [${device.mac}]: ${e.message}")
            }
        }
        return first
    }

    /** [playFrames]로 재생 중인 캔드 시퀀스를 즉시 중단(연결된 모든 기기의 playEffects 정지). */
    fun stopPlayFrames(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return

        resolveAllDevices(context).forEach { device ->
            try {
                device.stopEffects()
            } catch (e: Exception) {
                Log.e(TAG, "Stop play frames error [${device.mac}]: ${e.message}")
            }
        }
    }

    fun sendColor(context: Context, color: Color, transit: Int, source: TransmissionSource): Boolean {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission required")
            return false
        }

        if (OtaState.isAnyInProgress.value) return false

        // 연결된 기기 전체에 보낸다(B6). 예전에는 첫 기기 하나에만 갔다.
        val devices = resolveAllDevices(context)
        if (devices.isEmpty()) {
            Log.w(TAG, "No target device")
            return false
        }

        var success = false
        devices.forEach { device ->
            try {
                device.sendColor(color, transit)
                BleTransmissionMonitor.recordTransmission(
                    BleTransmissionEvent(
                        source = source,
                        deviceMac = device.mac,
                        effectType = null,
                        payload = LSEffectPayload.Effects.on(color, transit = transit),
                        color = color,
                        transit = transit,
                        metadata = mapOf("type" to "color")
                    )
                )
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Send color error [${device.mac}]: ${e.message}")
            }
        }
        return success
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
            devices.forEach { it.playTimeline(frames) }
            Log.d(TAG, "[load] playTimeline 완료 devices=${devices.size}")
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

            cachedTimeline = loadedEffects.sortedBy { it.timestampMs }
            lastRecordedEffectIndex = -1

            val frames = loadedEffects.map { entry -> entry.timestampMs to entry.payload.toByteArray() }
            devices.forEach { it.playTimeline(frames) }

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
            devices.forEach { it.updatePlaybackPosition(currentPositionMs) }
            recordCurrentTimelineEffect(devices.first().mac, currentPositionMs)
            if (DIAGNOSTIC_LOGS) logNextFrameGap(currentPositionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Update playback failed: ${e.message}")
        }
    }

    // 진단용 — 재개 직후 이펙트가 1~2초 늦게 나오는 현상의 원인 확인용.
    // "다음 예정 프레임이 실제로 그만큼 떨어져 있어서(타임라인 자체가 성긴 구간)"인지,
    // "바로 다음인데 전송이 늦는 것"인지 구분하려고 캐시된 타임라인에서 이 위치 이후
    // 첫 프레임까지의 거리를 로그로 남긴다. 동작에는 영향 없음 — SDK가 실제로 그 프레임을
    // 언제 전송하는지는 앱에서 관측할 수 없어서(closed SDK), 이건 "몇 ms 뒤에 프레임이
    // 예정돼 있다"는 타임라인 데이터 자체만 보여준다.
    private fun logNextFrameGap(currentPositionMs: Long) {
        val timeline = cachedTimeline
        val next = timeline.getOrNull(indexAtOrBefore(timeline, currentPositionMs - 1) + 1)
        if (next == null) {
            Log.d(TAG, "[gap-check] posMs=$currentPositionMs 이후 예정 프레임 없음 (타임라인 끝)")
        } else {
            Log.d(TAG, "[gap-check] posMs=$currentPositionMs nextFrameT=${next.timestampMs}ms " +
                "gap=${next.timestampMs - currentPositionMs}ms type=${next.payload.effectType} color=${next.payload.color}")
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

    /**
     * [positionMs] 시점에 유효한 타임라인 이펙트를 반환한다. 없으면 null.
     * - 마지막으로 시작된(timestampMs <= positionMs) 프레임을 대상으로 한다.
     * - SDK 타임라인 전송은 프레임의 duration을 항상 0(다음 프레임까지 유지)으로 덮어쓰므로
     *   duration 만료 판단은 하지 않는다. 재전송 때도 같은 규칙을 맞춘다([resendActiveEffectBeforeResume]).
     */
    fun activeTimelineEffectAt(positionMs: Long): LSEffectPayload? {
        val timeline = cachedTimeline
        return timeline.getOrNull(indexAtOrBefore(timeline, positionMs))?.payload
    }

    /**
     * timestampMs 오름차순 [timeline] 에서 timestampMs <= [positionMs] 인 마지막 인덱스(없으면 -1).
     * 50ms마다 불리므로 선형 탐색 대신 이진 탐색.
     */
    private fun indexAtOrBefore(timeline: List<EfxEntry>, positionMs: Long): Int {
        var lo = 0
        var hi = timeline.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (timeline[mid].timestampMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * 재개 직전에 호출한다. 현재 위치에 해당하는 이펙트가 있으면 새로 전송하고, 없으면 아무것도 하지 않는다.
     * 반드시 [resumeEffects]보다 먼저 호출해야 한다 — sendEffect()는 SDK 내부에서 타임라인 전송을
     * 억제하는데, resumeTimeline()이 그 억제를 해제하고 현재 프레임을 재전송하기 때문이다.
     */
    fun resendActiveEffectBeforeResume(context: Context, positionMs: Long): Boolean {
        val payload = activeTimelineEffectAt(positionMs) ?: run {
            Log.d(TAG, "[resume] posMs=$positionMs 해당 이펙트 없음 → resume만")
            return false
        }
        Log.d(TAG, "[resume] posMs=$positionMs 해당 이펙트 재전송 type=${payload.effectType}")
        // SDK 타임라인 프레임과 동일하게 duration=0으로 보낸다(sendEffect는 duration을 덮어쓰지 않음).
        return sendEffect(context, payload.copy(duration = 0), TransmissionSource.TIMELINE_EFFECT)
    }

    /**
     * 곡 재생 중 새로 연결된 기기에 현재 타임라인을 실어 다른 기기와 맞춘다.
     * 예전에는 타임라인을 로드한 시점에 연결돼 있던 기기에만 보냈다.
     * @return 보냈으면 true
     */
    fun syncTimelineToDevice(context: Context, mac: String, positionMs: Long, playing: Boolean): Boolean {
        if (!isTimelineLoaded) return false
        val timeline = cachedTimeline
        if (timeline.isEmpty()) return false
        val device = resolveAllDevices(context).find { it.mac == mac } ?: return false
        return try {
            val frames = timeline.map { it.timestampMs to it.payload.toByteArray() }
            device.playTimeline(frames)
            device.updatePlaybackPosition(positionMs)
            if (!playing) device.pauseTimeline()
            Log.d(TAG, "[sync] 새 연결 기기 $mac 에 타임라인 ${frames.size}개 전송 (pos=$positionMs, playing=$playing)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncTimelineToDevice failed $mac: ${e.message}")
            false
        }
    }

    fun pauseEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        resolveAllDevices(context).forEach {
            try { it.pauseTimeline() } catch (e: Exception) { Log.e(TAG, "Pause failed ${it.mac}: ${e.message}") }
        }
    }

    fun resumeEffects(context: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(context)) return
        resolveAllDevices(context).forEach {
            try { it.resumeTimeline() } catch (e: Exception) { Log.e(TAG, "Resume failed ${it.mac}: ${e.message}") }
        }
    }

    @Synchronized
    fun reset() {
        isTimelineLoaded = false
        isManualEffectActive = false
        loadedEffectSource = null
        cachedTimeline = emptyList()
        lastRecordedEffectIndex = -1
        lastFftLevel = -1
        synchronized(fftMeter) { fftMeter.reset() }
        try { LSBluetooth.connectedDevices().forEach { it.stopTimeline() } } catch (_: Exception) {}
        targetDevice = null
    }

    // FFT 연출: 에너지를 이퀄라이저처럼 OFF + 7단계 색으로 바꿔 일정 간격으로 보낸다([FftLevelMeter]).
    // 예전에는 저/중/고음 비율을 그대로 RGB 로 써서 색이 거의 섞인 흰색에 가까웠고, transit 5(약 0.5초)로
    // 천천히 바뀌어 음악에 반응하는 느낌이 약했다.
    /** 단계 색 전송 간격 하한(ms). SDK 명령 간격보다 짧으면 그 값을 쓴다 */
    private const val FFT_EQ_SEND_INTERVAL_MS = 80L
    /** 단계 색 전환 시간 (transit 단위, 1 = 약 100ms). 0 이면 즉시 바뀌어 깜빡임처럼 보일 수 있다 */
    private const val FFT_EQ_TRANSIT = 1

    private val fftMeter = FftLevelMeter()
    @Volatile private var lastFftSentAt = 0L
    @Volatile private var lastFftLevel = -1

    private fun fftMinIntervalMs(): Long =
        runCatching { LSBluetooth.getCommandIntervalMs() }.getOrDefault(50L)
            .coerceAtLeast(FFT_EQ_SEND_INTERVAL_MS)

    /** FFT는 Timeline이 없을 때만 전송 */
    fun processFFT(context: Context, band: FrequencyBand) {
        if (isTimelineLoaded) return

        val now = android.os.SystemClock.elapsedRealtime()
        // 단계 계산은 FFT 창마다(약 46ms) 해서 감쇠·피크 홀드가 매끄럽게 움직이게 하고, 전송만 간격을 둔다
        val level = synchronized(fftMeter) { fftMeter.update(band, now) }

        if (now - lastFftSentAt < fftMinIntervalMs()) return
        if (level == lastFftLevel) return // 같은 단계면 다시 보내지 않는다

        val color = fftMeter.colorFor(level)
        if (sendColor(context, color, transit = FFT_EQ_TRANSIT, source = TransmissionSource.FFT_EFFECT)) {
            lastFftSentAt = now
            lastFftLevel = level
        }
    }

    /**
     * 재생 재개 시 타임라인을 다시 로드해야 하는지 SDK에 직접 물어본다(device.isTimelineLoaded()).
     * SDK 업데이트(suspendBackgroundProducers 도입) 이후로는 sendEffect()가
     * 로드된 타임라인 데이터를 보존한 채 전송만 잠깐 억제하므로, 연결된 기기가 전부
     * 타임라인을 갖고 있으면 resumeTimeline()만으로 충분하다. 연결된 기기 중 하나라도
     * 타임라인이 없다고 응답하면(레거시 play() 취소 등 다른 경로로 비워진 경우) 재로드가 필요하다.
     */
    fun needsTimelineReload(context: Context): Boolean =
        resolveAllDevices(context).any { device ->
            // 조회 자체가 실패하면 상태를 모르는 것이므로, 모른 채로 넘어가 이펙트가
            // 조용히 안 나가는 것보다는 안전하게 재로드하는 쪽을 택한다.
            try { !device.isTimelineLoaded() } catch (e: Exception) { true }
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


    @Synchronized
    private fun recordCurrentTimelineEffect(deviceMac: String, currentPositionMs: Long) {
        val timeline = cachedTimeline
        if (timeline.isEmpty()) return

        try {
            val latestTransmission = BleTransmissionMonitor.latestTransmission.value
            if (latestTransmission != null) {
                val dt = System.currentTimeMillis() - latestTransmission.timestamp
                if (latestTransmission.source == TransmissionSource.PAYLOAD_EFFECT && dt < 500) return
            }

            val currentEffectIndex = indexAtOrBefore(timeline, currentPositionMs)
            if (currentEffectIndex < 0) return
            if (currentEffectIndex == lastRecordedEffectIndex) return

            // 인덱스가 2 이상 건너뛰면 경고 (SDK가 중간 프레임을 스킵했을 가능성)
            val skipped = currentEffectIndex - lastRecordedEffectIndex - 1
            if (DIAGNOSTIC_LOGS && lastRecordedEffectIndex >= 0 && skipped > 0) {
                Log.w(TAG, "[record] effectIndex 건너뜀: ${lastRecordedEffectIndex} → ${currentEffectIndex} (${skipped}개 스킵) posMs=${currentPositionMs}ms")
                for (si in (lastRecordedEffectIndex + 1) until currentEffectIndex) {
                    val skippedEntry = timeline[si]
                    val p = skippedEntry.payload
                    Log.w(TAG, "[record]   스킵된 frame[$si] t=${skippedEntry.timestampMs}ms type=${p.effectType} color=${p.color}")
                }
            }

            val currentEntry = timeline[currentEffectIndex]
            val currentPayload = currentEntry.payload

            // [firmware] 이전 프레임과의 간격 및 연속 동일 color 패턴 감지
            if (DIAGNOSTIC_LOGS && lastRecordedEffectIndex >= 0) {
                val prevEntry = timeline[lastRecordedEffectIndex]
                val intervalMs = currentEntry.timestampMs - prevEntry.timestampMs
                val prevColor = prevEntry.payload.color
                val currColor = currentPayload.color
                val sameColor = prevColor.r == currColor.r && prevColor.g == currColor.g && prevColor.b == currColor.b
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

            // [firmware] raw bytes 로그 (frame index 3-5 집중 확인 — SDK effectIndex 필드는 Mode 용도, 기본값=1)
            if (DIAGNOSTIC_LOGS && currentEffectIndex in 3..5) {
                val bytes = runCatching { currentEntry.payload.toByteArray() }.getOrNull()
                Log.d(TAG, "[firmware] raw frame[$currentEffectIndex] bytes=${bytes?.joinToString(",") { "%02X".format(it) }}")
            }

            Log.d(TAG) { "[record] effectIndex=${currentEffectIndex} posMs=${currentPositionMs}ms t=${currentEntry.timestampMs}ms" }
            lastRecordedEffectIndex = currentEffectIndex
            val currentEffect = timeline[currentEffectIndex]

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
