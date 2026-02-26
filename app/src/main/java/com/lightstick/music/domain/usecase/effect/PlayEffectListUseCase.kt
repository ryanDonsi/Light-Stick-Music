package com.lightstick.music.domain.usecase.effect

import android.content.Context
import com.lightstick.music.core.util.Log
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.effect.EffectEngineController
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import com.lightstick.types.Color as LightStickColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * EffectList 재생 UseCase
 *
 * 책임:
 * - EffectList 시퀀스 생성
 * - EffectEngineController.playFrames() 호출
 * - Monitor 기록 (프레임별 타이밍 추적)
 * - Job 반환 (취소 가능)
 *
 * 사용:
 * - EffectViewModel.playEffect() (EffectList 타입)
 */
class PlayEffectListUseCase {

    companion object {
        private const val TAG = AppConstants.Feature.UC_PLAY_EFFECT_LIST
    }

    /**
     * EffectList 재생
     *
     * @param context          Android Context
     * @param effectListNumber EffectList 번호 (1~6)
     * @param coroutineScope   재생을 실행할 CoroutineScope
     * @return Result<Job> 성공 시 재생 Job, 실패 시 Exception
     */
    operator fun invoke(
        context: Context,
        effectListNumber: Int,
        coroutineScope: CoroutineScope
    ): Result<Job> {
        return try {
            // 1. Frames 생성
            val frames = createEffectListSequence(effectListNumber)

            // 2. EffectEngineController.playFrames() 호출
            val device = EffectEngineController.playFrames(context, frames)
                ?: return Result.failure(Exception("No target device"))

            // 3. 반복 재생 + Monitor 기록
            val job = coroutineScope.launch {
                try {
                    while (isActive) {
                        val startTime = System.currentTimeMillis()

                        EffectEngineController.playFrames(context, frames)

                        // 별도 코루틴에서 Monitor 기록
                        launch {
                            recordEffectListPlayback(device.mac, frames, startTime)
                        }

                        // ✅ [수정] 500L 매직 넘버 → 그대로 유지 (재생 사이 여백, Monitor 주기와 별개)
                        val maxTimestamp = frames.maxOfOrNull { it.first } ?: 0L
                        delay(maxTimestamp + 500L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EffectList playback error: ${e.message}")
                }
            }

            Result.success(job)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // EffectList 시퀀스 정의
    // ═══════════════════════════════════════════════════════════

    private fun createEffectListSequence(number: Int): List<Pair<Long, ByteArray>> {
        return when (number) {
            1 -> listOf(  // 발라드
                0L    to LSEffectPayload.Effects.on(Colors.RED, transit = 40).toByteArray(),
                2000L to LSEffectPayload.Effects.on(LightStickColor(255, 192, 203), transit = 50).toByteArray(),
                4000L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 30).toByteArray(),
                6000L to LSEffectPayload.Effects.on(LightStickColor(255, 165, 0), transit = 45).toByteArray()
            )
            2 -> listOf(  // 댄스
                0L    to LSEffectPayload.Effects.strobe(5, Colors.MAGENTA, Colors.BLACK).toByteArray(),
                1000L to LSEffectPayload.Effects.blink(8, Colors.CYAN, Colors.BLACK).toByteArray(),
                2000L to LSEffectPayload.Effects.strobe(3, Colors.YELLOW, Colors.BLACK).toByteArray(),
                3000L to LSEffectPayload.Effects.strobe(6, Colors.GREEN, Colors.BLACK).toByteArray()
            )
            3 -> listOf(  // 록
                0L    to LSEffectPayload.Effects.strobe(10, Colors.RED, Colors.BLACK).toByteArray(),
                1500L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 10).toByteArray(),
                2500L to LSEffectPayload.Effects.strobe(8, LightStickColor(255, 165, 0), Colors.BLACK).toByteArray(),
                4000L to LSEffectPayload.Effects.blink(15, Colors.YELLOW, Colors.BLACK).toByteArray()
            )
            4 -> listOf(  // 힙합
                0L    to LSEffectPayload.Effects.blink(12, Colors.YELLOW, Colors.BLACK).toByteArray(),
                1200L to LSEffectPayload.Effects.strobe(7, LightStickColor(128, 0, 128), Colors.BLACK).toByteArray(),
                2400L to LSEffectPayload.Effects.blink(10, Colors.CYAN, Colors.BLACK).toByteArray(),
                3600L to LSEffectPayload.Effects.on(Colors.GREEN, transit = 15).toByteArray()
            )
            5 -> listOf(  // 재즈
                0L    to LSEffectPayload.Effects.breath(50, Colors.CYAN, Colors.BLUE).toByteArray(),
                2500L to LSEffectPayload.Effects.on(LightStickColor(135, 206, 235), transit = 40).toByteArray(),
                5000L to LSEffectPayload.Effects.breath(60, Colors.BLUE, Colors.BLACK).toByteArray(),
                7500L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 50).toByteArray()
            )
            6 -> listOf(  // 클래식
                0L    to LSEffectPayload.Effects.breath(60, Colors.WHITE, Colors.BLACK).toByteArray(),
                3000L to LSEffectPayload.Effects.on(LightStickColor(255, 192, 203), transit = 50).toByteArray(),
                6000L to LSEffectPayload.Effects.breath(70, LightStickColor(135, 206, 235), Colors.BLACK).toByteArray(),
                9000L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 60).toByteArray()
            )
            else -> listOf(
                0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Monitor 기록 (프레임별 타이밍 추적)
    // ═══════════════════════════════════════════════════════════

    private suspend fun recordEffectListPlayback(
        deviceMac: String,
        frames: List<Pair<Long, ByteArray>>,
        startTime: Long
    ) {
        try {
            val sortedFrames = frames.sortedBy { it.first }
            var lastRecordedIndex = -1

            // 첫 프레임 즉시 기록 (delay 없이)
            if (sortedFrames.isNotEmpty()) {
                val (timestamp, frameBytes) = sortedFrames[0]
                lastRecordedIndex = 0
                recordFrame(deviceMac, timestamp, frameBytes, 0)
                Log.d(TAG, "📋 EffectList first frame recorded immediately")
            }

            // ✅ [수정] 이후 프레임 추적 주기: delay(50) → AppConstants.TRANSMISSION_MONITOR_UPDATE_INTERVAL_MS
            while (true) {
                delay(AppConstants.TRANSMISSION_MONITOR_UPDATE_INTERVAL_MS)

                val elapsed = System.currentTimeMillis() - startTime
                val currentIndex = sortedFrames.indexOfLast { (timestamp, _) -> timestamp <= elapsed }

                if (currentIndex >= 0 && currentIndex != lastRecordedIndex) {
                    lastRecordedIndex = currentIndex
                    val (timestamp, frameBytes) = sortedFrames[currentIndex]
                    recordFrame(deviceMac, timestamp, frameBytes, currentIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record EffectList playback: ${e.message}")
        }
    }

    private fun recordFrame(deviceMac: String, timestamp: Long, frameBytes: ByteArray, index: Int) {
        val payload = LSEffectPayload.fromByteArray(frameBytes)
        val isOnOff = payload.effectType == EffectType.ON || payload.effectType == EffectType.OFF

        BleTransmissionMonitor.recordTransmission(
            BleTransmissionEvent(
                source = TransmissionSource.PAYLOAD_EFFECT,
                deviceMac = deviceMac,
                effectType = payload.effectType,
                payload = payload,
                color = payload.color,
                backgroundColor = payload.backgroundColor,
                transit = if (isOnOff) payload.period else null,
                period = if (!isOnOff) payload.period else null,
                metadata = mapOf(
                    "type" to "effect_list",
                    "timestamp" to timestamp,
                    "frameIndex" to index
                )
            )
        )
    }
}