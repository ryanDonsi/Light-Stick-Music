package com.lightstick.music

import com.lightstick.music.domain.music.BeatDetectorRouter
import java.io.File

/**
 * V1 vs V3 정확도 비교 테스트
 *
 * 데이터 기반으로 V1과 V3의 성능을 정량적으로 비교합니다.
 * - BPM 정확도 (madmom과의 오차율)
 * - F-measure (위상 정확도)
 */
class BeatDetectorV1vsV3ComparisonTest {

    data class ComparisonResult(
        val songName: String,
        val madmomBpm: Long,
        val v1Bpm: Long,
        val v3Bpm: Long,
        val v1BpmError: Float,      // %
        val v3BpmError: Float,      // %
        val v1FMeasure: Float,      // 0-1
        val v3FMeasure: Float,      // 0-1
        val v1Better: Boolean       // true if V1 is more accurate
    )

    fun compareV1andV3(
        audioFilePath: String,
        madmomBpm: Long,
        madmomBeats: List<Long> = emptyList()
    ): ComparisonResult {
        val router = BeatDetectorRouter()

        // V1 감지
        val v1Result = router.detect(audioFilePath, version = 1)
        val v1Bpm = v1Result.beatMs.let { if (it > 0) 60_000 / it else 0L }
        val v1BpmError = calculateBpmError(v1Bpm, madmomBpm)
        val v1FMeasure = if (madmomBeats.isNotEmpty()) {
            calculateFMeasure(v1Result.beats.map { it.timeMs }, madmomBeats)
        } else {
            0f
        }

        // V3 감지
        val v3Result = router.detect(audioFilePath, version = 3)
        val v3Bpm = v3Result.beatMs.let { if (it > 0) 60_000 / it else 0L }
        val v3BpmError = calculateBpmError(v3Bpm, madmomBpm)
        val v3FMeasure = if (madmomBeats.isNotEmpty()) {
            calculateFMeasure(v3Result.beats.map { it.timeMs }, madmomBeats)
        } else {
            0f
        }

        val songName = File(audioFilePath).nameWithoutExtension
        val v1Better = v1BpmError < v3BpmError || (v1BpmError == v3BpmError && v1FMeasure > v3FMeasure)

        return ComparisonResult(
            songName = songName,
            madmomBpm = madmomBpm,
            v1Bpm = v1Bpm,
            v3Bpm = v3Bpm,
            v1BpmError = v1BpmError,
            v3BpmError = v3BpmError,
            v1FMeasure = v1FMeasure,
            v3FMeasure = v3FMeasure,
            v1Better = v1Better
        )
    }

    private fun calculateBpmError(detected: Long, madmom: Long): Float {
        if (madmom <= 0) return 0f
        return kotlin.math.abs(detected - madmom).toFloat() / madmom * 100f
    }

    private fun calculateFMeasure(
        detectedBeats: List<Long>,
        madmomBeats: List<Long>,
        toleranceMs: Long = 70L
    ): Float {
        if (detectedBeats.isEmpty() || madmomBeats.isEmpty()) return 0f

        var truePositives = 0
        for (detected in detectedBeats) {
            if (madmomBeats.any { kotlin.math.abs(it - detected) <= toleranceMs }) {
                truePositives++
            }
        }

        val precision = truePositives.toFloat() / detectedBeats.size
        val recall = truePositives.toFloat() / madmomBeats.size
        val fMeasure = if (precision + recall > 0) {
            2 * precision * recall / (precision + recall)
        } else {
            0f
        }

        return fMeasure
    }

    fun printComparisonTable(results: List<ComparisonResult>) {
        println("\n" + "=".repeat(120))
        println("V1 vs V3 정확도 비교 (데이터 기반)")
        println("=".repeat(120))
        println(
            "%-30s | %6s | %6s | %6s | %7s | %7s | %7s | %7s | %-6s".format(
                "Song",
                "Madmom",
                "V1 BPM",
                "V3 BPM",
                "V1 Err%",
                "V3 Err%",
                "V1 F1",
                "V3 F1",
                "Better"
            )
        )
        println("-".repeat(120))

        var v1WinCount = 0
        var v3WinCount = 0

        for (result in results) {
            println(
                "%-30s | %6d | %6d | %6d | %6.2f%% | %6.2f%% | %6.3f | %6.3f | %-6s".format(
                    result.songName.take(28),
                    result.madmomBpm,
                    result.v1Bpm,
                    result.v3Bpm,
                    result.v1BpmError,
                    result.v3BpmError,
                    result.v1FMeasure,
                    result.v3FMeasure,
                    if (result.v1Better) "V1 ✓" else "V3 ✓"
                )
            )
            if (result.v1Better) v1WinCount++ else v3WinCount++
        }

        println("-".repeat(120))
        println(
            "%-30s | %6s | %6s | %6s | V1: %d곡 WIN | V3: %d곡 WIN".format(
                "TOTAL",
                "", "", "",
                v1WinCount, v3WinCount
            )
        )
        println("=".repeat(120) + "\n")
    }
}
