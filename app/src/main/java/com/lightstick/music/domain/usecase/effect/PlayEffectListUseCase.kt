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
                //
                // groupMask는 대상 필터일 뿐 — 마스크에서 빠진 그룹이 자동으로 꺼지지 않는다(SDK 그룹
                // 컨트롤 특성상 OFF는 반드시 별도 명령으로 보내야 함). 그래서 각 스텝에서 직전 이동
                // 위치를 명시적으로 OFF한다. 같은 타임스탬프에 OFF/ON을 함께 보내면 BLE 쓰기 큐가
                // 같은 coalesceKey("LCS:PAYLOAD")의 대기 중인 OFF를 ON으로 대체해버려 OFF가 실제로는
                // 전송되지 않으므로(replaceIfSameKey), offGapMs만큼 타임스탬프를 떼어 각각 별도로 전송한다.
                val groupWaveCount = 10
                val stepMs = 500L
                val holdMs = 1500L
                val offTransit = 50
                val offGapMs = 200L

                val frames = mutableListOf<Pair<Long, ByteArray>>()
                var landedMask = 0L
                var stepIndex = 0
                for (target in groupWaveCount downTo 1) {
                    for (moving in 1..target) {
                        val timestamp = stepIndex * stepMs
                        if (moving > 1) {
                            val prevMovingMask = 1L shl (moving - 2)
                            frames.add(timestamp to LSEffectPayload.Effects.off(transit = 15, groupMask = prevMovingMask).toByteArray())
                        }
                        val mask = landedMask or (1L shl (moving - 1))
                        frames.add((timestamp + offGapMs) to LSEffectPayload.Effects.on(Colors.WHITE, transit = 15, groupMask = mask).toByteArray())
                        stepIndex++
                    }
                    landedMask = landedMask or (1L shl (target - 1))
                }

                val allGroupsMask = (1L shl groupWaveCount) - 1L
                val offTimestamp = stepIndex * stepMs + holdMs
                frames.add(offTimestamp to LSEffectPayload.Effects.off(transit = offTransit, groupMask = allGroupsMask).toByteArray())

                frames
            }
            6 -> {
                // 그룹 스캐너: 그룹1~10을 한 줄로 뒀을 때 단일 점이 좌(1)→우(10)→좌(1)로 왕복.
                // 매 스텝마다 직전 그룹은 서서히 OFF, 다음 그룹은 서서히 ON — 스냅 전환 대신
                // 페이드로 자연스럽게 넘어가도록 크로스페이드.
                //
                // OFF/ON을 같은 타임스탬프로 보내면 BLE 쓰기 큐가 같은 coalesceKey("LCS:PAYLOAD")의
                // 대기 중인 OFF를 ON으로 대체해버려 OFF가 실제로 전송되지 않는다(replaceIfSameKey).
                // offGapMs만큼 떼어 각각 별도 프레임으로 전송한다.
                val groupWaveCount = 10
                // offGapMs만큼 OFF/ON을 떼고도 다음 스텝의 OFF까지 200ms 이상 남도록
                // stepMs를 offGapMs의 2배로 잡는다(그렇지 않으면 이번 ON과 다음 OFF가
                // 다시 같은/근접 타임스탬프로 몰려 동일한 coalescing 드롭이 재발한다).
                val offGapMs = 200L
                val stepMs = offGapMs * 2
                // transit 값이 실제로 몇 ms 페이드에 대응하는지는 SDK 코드에 없는 펌웨어 고유 스케일이라
                // 확인 불가 — 실기기로 보면서 체감상 자연스러운 값으로 보정 필요.
                val crossfadeTransit = 10

                val path = (1..groupWaveCount).toList() + (groupWaveCount - 1 downTo 2).toList()

                val frames = mutableListOf<Pair<Long, ByteArray>>()
                frames.add(0L to LSEffectPayload.Effects.on(Colors.WHITE, transit = crossfadeTransit, groupMask = 1L shl (path[0] - 1)).toByteArray())

                for (i in 1 until path.size) {
                    val timestamp = i * stepMs
                    val prevMask = 1L shl (path[i - 1] - 1)
                    val currentMask = 1L shl (path[i] - 1)
                    frames.add(timestamp to LSEffectPayload.Effects.off(transit = crossfadeTransit, groupMask = prevMask).toByteArray())
                    frames.add((timestamp + offGapMs) to LSEffectPayload.Effects.on(Colors.WHITE, transit = crossfadeTransit, groupMask = currentMask).toByteArray())
                }

                val lastMask = 1L shl (path.last() - 1)
                frames.add(path.size * stepMs to LSEffectPayload.Effects.off(transit = crossfadeTransit, groupMask = lastMask).toByteArray())

                frames
            }
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
