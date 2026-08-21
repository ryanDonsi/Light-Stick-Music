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
import javax.inject.Inject

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
class PlayEffectListUseCase @Inject constructor() {

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
            val frames = createEffectListSequence(effectListNumber)

            val device = EffectEngineController.playFrames(context, frames)
                ?: return Result.failure(Exception("No target device"))

            val job = coroutineScope.launch {
                try {
                    while (isActive) {
                        val startTime = System.currentTimeMillis()

                        EffectEngineController.playFrames(context, frames)

                        launch {
                            recordEffectListPlayback(device.mac, frames, startTime)
                        }

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

    private fun createEffectListSequence(number: Int): List<Pair<Long, ByteArray>> {
        return when (number) {
            1 -> listOf(
                0L    to LSEffectPayload.Effects.on(Colors.RED, transit = 40).toByteArray(),
                2000L to LSEffectPayload.Effects.on(LightStickColor(255, 192, 203), transit = 50).toByteArray(),
                4000L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 30).toByteArray(),
                6000L to LSEffectPayload.Effects.on(LightStickColor(255, 165, 0), transit = 45).toByteArray()
            )
            2 -> listOf(
                0L    to LSEffectPayload.Effects.strobe(5, Colors.MAGENTA, Colors.BLACK).toByteArray(),
                1000L to LSEffectPayload.Effects.blink(8, Colors.CYAN, Colors.BLACK).toByteArray(),
                2000L to LSEffectPayload.Effects.strobe(3, Colors.YELLOW, Colors.BLACK).toByteArray(),
                3000L to LSEffectPayload.Effects.strobe(6, Colors.GREEN, Colors.BLACK).toByteArray()
            )
            3 -> listOf(
                0L    to LSEffectPayload.Effects.strobe(10, Colors.RED, Colors.BLACK).toByteArray(),
                1500L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 10).toByteArray(),
                2500L to LSEffectPayload.Effects.strobe(8, LightStickColor(255, 165, 0), Colors.BLACK).toByteArray(),
                4000L to LSEffectPayload.Effects.blink(15, Colors.YELLOW, Colors.BLACK).toByteArray()
            )
            4 -> {
                // 그룹 물결: 그룹1부터 그룹10까지 화이트로 한 그룹씩 누적 점등 후,
                // 켜진 순서(그룹1→10) 그대로 한 그룹씩 소등. groupMask=0은 "전체 제어"를
                // 의미하므로(프로토콜 규약) 마지막 소등도 반드시 그룹10 개별 마스크로 전송.
                val groupWaveCount = 10
                val stepMs = 500L
                val holdMs = 1500L

                val onFrames = (1..groupWaveCount).map { step ->
                    val timestamp = (step - 1) * stepMs
                    val cumulativeMask = (1L shl step) - 1L
                    timestamp to LSEffectPayload.Effects.on(Colors.WHITE, transit = 15, groupMask = cumulativeMask).toByteArray()
                }
                val offStart = (groupWaveCount - 1) * stepMs + holdMs
                val offFrames = (1..groupWaveCount).map { group ->
                    val timestamp = offStart + (group - 1) * stepMs
                    val singleGroupMask = 1L shl (group - 1)
                    timestamp to LSEffectPayload.Effects.off(transit = 15, groupMask = singleGroupMask).toByteArray()
                }
                onFrames + offFrames
            }
            5 -> {
                // 그룹 쌓기: 그룹1에서 출발한 이동 점이 라운드마다 한 칸씩 더 멀리 이동하며,
                // 도착 지점은 그룹10→그룹1 순으로 쌓여 그대로 켜진 채 남는다(이전 라운드 도착지는
                // 다음 라운드에서도 계속 점등). 전부 쌓이면 대기 후 그룹1~10 전체를 transit 페이드로 OFF.
                val groupWaveCount = 10
                val stepMs = 500L
                val holdMs = 1500L
                val offTransit = 50

                val frames = mutableListOf<Pair<Long, ByteArray>>()
                var landedMask = 0L
                var stepIndex = 0
                for (target in groupWaveCount downTo 1) {
                    for (moving in 1..target) {
                        val timestamp = stepIndex * stepMs
                        val mask = landedMask or (1L shl (moving - 1))
                        frames.add(timestamp to LSEffectPayload.Effects.on(Colors.WHITE, transit = 15, groupMask = mask).toByteArray())
                        stepIndex++
                    }
                    landedMask = landedMask or (1L shl (target - 1))
                }

                val allGroupsMask = (1L shl groupWaveCount) - 1L
                val offTimestamp = stepIndex * stepMs + holdMs
                frames.add(offTimestamp to LSEffectPayload.Effects.off(transit = offTransit, groupMask = allGroupsMask).toByteArray())

                frames
            }
            6 -> listOf(
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

    private suspend fun recordEffectListPlayback(
        deviceMac: String,
        frames: List<Pair<Long, ByteArray>>,
        startTime: Long
    ) {
        try {
            val sortedFrames = frames.sortedBy { it.first }
            var lastRecordedIndex = -1

            if (sortedFrames.isNotEmpty()) {
                val (timestamp, frameBytes) = sortedFrames[0]
                lastRecordedIndex = 0
                recordFrame(deviceMac, timestamp, frameBytes, 0)
            }

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
