package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v7 목표
 * - low + mid + full band 모두 계산
 * - 곡을 임시 segment(기본 20초)로 나눠서 구간별로 beat source 선택
 * - beat empty면 fallback 하지 않고 source 전환 재시도
 * - 끝까지 실패하면 empty 반환
 *
 * 현재 단계는 "정확한 beat 추출"이 목적이므로
 * payload는 단순 BLINK 기반으로 유지
 */
class AutoTimelineGeneratorBeat_v7 {

    companion object {
        private const val TAG = "AutoTimeline"

        private const val HOP_MS = 50L

        private const val SEGMENT_MS = 20_000L
        private const val MIN_SEGMENT_MS = 15_000L
        private const val MAX_SEGMENT_MS = 30_000L

        private const val MIN_BEAT_MS = 280L     // ~214 BPM
        private const val MAX_BEAT_MS = 900L     // ~67 BPM
        private const val MIN_PEAK_GAP_MS = 180L

        private const val MIN_BEATS_PER_SEGMENT = 4

        private const val COLOR_HOLD_MS = 5_000L
        private const val BLINK_PERIOD = 10

        // 디코딩용 필터 대역
        private const val LOW_CUTOFF_HZ = 180.0
        private const val MID_LOW_CUTOFF_HZ = 180.0
        private const val MID_HIGH_CUTOFF_HZ = 2200.0

        private const val EPS = 1e-6f
    }

    enum class BeatSource {
        LOW,
        MID,
        FULL,
        LOW_MID,
        MID_FULL,
        LOW_FULL
    }

    data class ThemePalette(
        val black: LSColor,
        val white: LSColor,
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor? = null,
        val c5: LSColor? = null
    )

    data class HoldColors(
        val fg: LSColor,
        val bg: LSColor
    )

    data class BandEnvelopes(
        val durationMs: Long,
        val hopMs: Long,
        val low: FloatArray,
        val mid: FloatArray,
        val full: FloatArray
    )

    data class SegmentWindow(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val startFrame: Int,
        val endFrame: Int
    )

    data class SourceTryResult(
        val source: BeatSource,
        val beatTimesMs: List<Long>,
        val beatMs: Long,
        val score: Float,
        val rawPeakCount: Int,
        val snappedCount: Int,
        val onsetMean: Float,
        val onsetStd: Float,
        val onsetMax: Float,
        val acPeak: Float,
        val snapRatio: Float,
        val reason: String
    )

    fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int = 4
    ): List<Pair<Long, ByteArray>> {
        val pSize = paletteSize.coerceIn(3, 5)
        val palette = buildPalette(musicId, pSize)

        Log.d(TAG, "generate() start file=$musicPath musicId=$musicId paletteSize=$pSize")

        val env = decodeBandEnvelopes(musicPath, hopMs = HOP_MS.toInt())
        if (env.low.isEmpty() || env.mid.isEmpty() || env.full.isEmpty()) {
            Log.w(TAG, "env empty -> return empty")
            return emptyList()
        }

        val frameCount = min(env.low.size, min(env.mid.size, env.full.size))
        val durationMs = min(env.durationMs, frameCount * HOP_MS)

        Log.d(
            TAG,
            "envSize low=${env.low.size} mid=${env.mid.size} full=${env.full.size} durationMs=$durationMs hopMs=${env.hopMs}"
        )

        val segments = buildSegments(durationMs, HOP_MS, frameCount)
        Log.d(TAG, "segments=${segments.size} segmentMs=$SEGMENT_MS")

        val allBeats = mutableListOf<Long>()

        for (seg in segments) {
            val result = detectBestBeatsForSegment(env, seg)

            Log.d(
                TAG,
                "SEG[${seg.index}] ${seg.startMs}-${seg.endMs} " +
                        "best=${result?.source} beats=${result?.beatTimesMs?.size ?: 0} beatMs=${result?.beatMs ?: 0} " +
                        "score=${result?.score ?: 0f} reason=${result?.reason ?: "all failed"}"
            )

            if (result == null || result.beatTimesMs.isEmpty()) {
                Log.w(TAG, "SEG[${seg.index}] FAIL -> skip segment")
                continue
            }

            allBeats += result.beatTimesMs
        }

        val finalBeats = dedupeBeats(allBeats.sorted(), minGapMs = MIN_PEAK_GAP_MS)

        if (finalBeats.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return emptyList()
        }

        val estimatedBeatMs = estimateBeatIntervalMs(finalBeats).coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        val frames = ArrayList<Pair<Long, ByteArray>>(finalBeats.size)

        for (t in finalBeats) {
            val hold = colorsForHold(musicId, palette, pSize, t)
            val payload = LSEffectPayload.Effects.blink(
                period = BLINK_PERIOD,
                color = hold.fg,
                backgroundColor = hold.bg
            ).toByteArray()

            frames.add(t to payload)
        }

        Log.d(
            TAG,
            "frames(final)=${frames.size} beats=${finalBeats.size} beatIntervalMs=$estimatedBeatMs"
        )

        return frames
    }

    // =========================================================
    // Segment
    // =========================================================

    private fun buildSegments(
        durationMs: Long,
        hopMs: Long,
        frameCount: Int
    ): List<SegmentWindow> {
        if (durationMs <= 0L || frameCount <= 0) return emptyList()

        val out = mutableListOf<SegmentWindow>()
        var startMs = 0L
        var idx = 0

        while (startMs < durationMs) {
            var endMs = min(startMs + SEGMENT_MS, durationMs)

            val remain = durationMs - endMs
            if (remain in 1 until MIN_SEGMENT_MS) {
                endMs = durationMs
            }

            val segLen = endMs - startMs
            if (segLen < MIN_SEGMENT_MS && out.isNotEmpty()) {
                val last = out.removeLast()
                out += last.copy(
                    endMs = durationMs,
                    endFrame = ((durationMs / hopMs).toInt()).coerceAtMost(frameCount)
                )
                break
            }

            val clampedLen = segLen.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
            val realEndMs = (startMs + clampedLen).coerceAtMost(durationMs)

            val startFrame = (startMs / hopMs).toInt().coerceIn(0, frameCount)
            val endFrame = (realEndMs / hopMs).toInt().coerceIn(0, frameCount)

            out += SegmentWindow(
                index = idx,
                startMs = startMs,
                endMs = realEndMs,
                startFrame = startFrame,
                endFrame = endFrame
            )

            idx++
            startMs = realEndMs
        }

        return out
    }

    // =========================================================
    // Beat Detect - per segment
    // =========================================================

    private fun detectBestBeatsForSegment(
        env: BandEnvelopes,
        seg: SegmentWindow
    ): SourceTryResult? {
        val lowSeg = env.low.copyOfRange(seg.startFrame, seg.endFrame)
        val midSeg = env.mid.copyOfRange(seg.startFrame, seg.endFrame)
        val fullSeg = env.full.copyOfRange(seg.startFrame, seg.endFrame)

        val lowVar = variance(lowSeg)
        val midVar = variance(midSeg)
        val fullVar = variance(fullSeg)

        val lowPeak = maxOfOrZero(lowSeg)
        val midPeak = maxOfOrZero(midSeg)
        val fullPeak = maxOfOrZero(fullSeg)

        val order = chooseSourceOrder(
            lowVar = lowVar,
            midVar = midVar,
            fullVar = fullVar,
            lowPeak = lowPeak,
            midPeak = midPeak,
            fullPeak = fullPeak
        )

        Log.d(
            TAG,
            "SEG[${seg.index}] srcOrder=$order " +
                    "lowVar=${fmt(lowVar)} midVar=${fmt(midVar)} fullVar=${fmt(fullVar)} " +
                    "lowPeak=${fmt(lowPeak)} midPeak=${fmt(midPeak)} fullPeak=${fmt(fullPeak)}"
        )

        var best: SourceTryResult? = null

        for (source in order) {
            val result = detectWithSource(
                env = env,
                seg = seg,
                source = source
            )

            Log.d(
                TAG,
                "SEG[${seg.index}] try=$source beats=${result.beatTimesMs.size} beatMs=${result.beatMs} " +
                        "score=${fmt(result.score)} rawPeak=${result.rawPeakCount} snapped=${result.snappedCount} " +
                        "onset(mean/std/max)=${fmt(result.onsetMean)}/${fmt(result.onsetStd)}/${fmt(result.onsetMax)} " +
                        "acPeak=${fmt(result.acPeak)} snapRatio=${fmt(result.snapRatio)} reason=${result.reason}"
            )

            if (best == null || result.score > best!!.score) {
                best = result
            }

            if (result.beatTimesMs.size >= MIN_BEATS_PER_SEGMENT && result.score >= 0.45f) {
                return result
            }
        }

        return best?.takeIf { it.beatTimesMs.isNotEmpty() }
    }

    private fun chooseSourceOrder(
        lowVar: Float,
        midVar: Float,
        fullVar: Float,
        lowPeak: Float,
        midPeak: Float,
        fullPeak: Float
    ): List<BeatSource> {
        val lowStrength = lowVar * 0.7f + lowPeak * 0.3f
        val midStrength = midVar * 0.7f + midPeak * 0.3f
        val fullStrength = fullVar * 0.7f + fullPeak * 0.3f

        return when {
            midStrength > lowStrength * 1.15f -> listOf(
                BeatSource.MID,
                BeatSource.MID_FULL,
                BeatSource.LOW_MID,
                BeatSource.FULL,
                BeatSource.LOW_FULL,
                BeatSource.LOW
            )

            lowStrength > midStrength * 1.15f -> listOf(
                BeatSource.LOW,
                BeatSource.LOW_MID,
                BeatSource.LOW_FULL,
                BeatSource.FULL,
                BeatSource.MID_FULL,
                BeatSource.MID
            )

            fullStrength > max(lowStrength, midStrength) * 1.05f -> listOf(
                BeatSource.FULL,
                BeatSource.LOW_FULL,
                BeatSource.MID_FULL,
                BeatSource.LOW_MID,
                BeatSource.LOW,
                BeatSource.MID
            )

            else -> listOf(
                BeatSource.LOW_MID,
                BeatSource.MID_FULL,
                BeatSource.LOW,
                BeatSource.MID,
                BeatSource.FULL,
                BeatSource.LOW_FULL
            )
        }
    }

    private fun detectWithSource(
        env: BandEnvelopes,
        seg: SegmentWindow,
        source: BeatSource
    ): SourceTryResult {
        val lowSeg = env.low.copyOfRange(seg.startFrame, seg.endFrame)
        val midSeg = env.mid.copyOfRange(seg.startFrame, seg.endFrame)
        val fullSeg = env.full.copyOfRange(seg.startFrame, seg.endFrame)

        val mixed = when (source) {
            BeatSource.LOW -> lowSeg
            BeatSource.MID -> midSeg
            BeatSource.FULL -> fullSeg
            BeatSource.LOW_MID -> mix(lowSeg, midSeg, 0.58f, 0.42f)
            BeatSource.MID_FULL -> mix(midSeg, fullSeg, 0.65f, 0.35f)
            BeatSource.LOW_FULL -> mix(lowSeg, fullSeg, 0.65f, 0.35f)
        }

        val onset = computeOnsetFlux(mixed)
        val onsetMean = mean(onset)
        val onsetStd = std(onset, onsetMean)
        val onsetMax = maxOfOrZero(onset)

        val rawPeaks = pickCandidatePeaks(onset)
        val rawPeakTimesMs = rawPeaks.map { seg.startMs + it * HOP_MS }

        if (rawPeakTimesMs.isEmpty()) {
            return SourceTryResult(
                source = source,
                beatTimesMs = emptyList(),
                beatMs = 0L,
                score = 0f,
                rawPeakCount = 0,
                snappedCount = 0,
                onsetMean = onsetMean,
                onsetStd = onsetStd,
                onsetMax = onsetMax,
                acPeak = 0f,
                snapRatio = 0f,
                reason = "no raw peak"
            )
        }

        val ac = estimateBeatFromAutocorr(onset)
        if (ac.beatMs <= 0L) {
            return SourceTryResult(
                source = source,
                beatTimesMs = emptyList(),
                beatMs = 0L,
                score = 0.08f,
                rawPeakCount = rawPeakTimesMs.size,
                snappedCount = 0,
                onsetMean = onsetMean,
                onsetStd = onsetStd,
                onsetMax = onsetMax,
                acPeak = ac.peakValue,
                snapRatio = 0f,
                reason = "autocorr weak"
            )
        }

        val snapped = snapPeaksToGrid(
            candidateTimesMs = rawPeakTimesMs,
            beatMs = ac.beatMs,
            segStartMs = seg.startMs,
            segEndMs = seg.endMs
        )

        val deduped = dedupeBeats(snapped, MIN_PEAK_GAP_MS)

        val snapRatio = if (rawPeakTimesMs.isEmpty()) 0f else deduped.size.toFloat() / rawPeakTimesMs.size.toFloat()

        val score = (
                ac.peakValue * 0.40f +
                        snapRatio * 0.30f +
                        (deduped.size.coerceAtMost(16) / 16f) * 0.20f +
                        onsetMax * 0.10f
                ).coerceIn(0f, 1f)

        return SourceTryResult(
            source = source,
            beatTimesMs = deduped,
            beatMs = ac.beatMs,
            score = score,
            rawPeakCount = rawPeakTimesMs.size,
            snappedCount = deduped.size,
            onsetMean = onsetMean,
            onsetStd = onsetStd,
            onsetMax = onsetMax,
            acPeak = ac.peakValue,
            snapRatio = snapRatio,
            reason = when {
                deduped.isEmpty() -> "snap empty"
                deduped.size < MIN_BEATS_PER_SEGMENT -> "too few beats"
                else -> "ok"
            }
        )
    }

    data class AutoCorrResult(
        val beatMs: Long,
        val peakValue: Float
    )

    private fun estimateBeatFromAutocorr(onset: FloatArray): AutoCorrResult {
        if (onset.size < 16) return AutoCorrResult(0L, 0f)

        val minLag = (MIN_BEAT_MS / HOP_MS).toInt().coerceAtLeast(1)
        val maxLag = (MAX_BEAT_MS / HOP_MS).toInt().coerceAtMost(onset.size / 2)
        if (maxLag <= minLag) return AutoCorrResult(0L, 0f)

        var bestLag = -1
        var best = 0f

        val centered = onset.copyOf()
        val m = mean(centered)
        for (i in centered.indices) centered[i] -= m

        for (lag in minLag..maxLag) {
            var s = 0f
            var c = 0
            for (i in 0 until centered.size - lag) {
                s += centered[i] * centered[i + lag]
                c++
            }
            if (c <= 0) continue
            val v = s / c
            if (v > best) {
                best = v
                bestLag = lag
            }
        }

        if (bestLag <= 0 || best <= 0.01f) {
            return AutoCorrResult(0L, best)
        }

        return AutoCorrResult(
            beatMs = bestLag * HOP_MS,
            peakValue = best
        )
    }

    private fun snapPeaksToGrid(
        candidateTimesMs: List<Long>,
        beatMs: Long,
        segStartMs: Long,
        segEndMs: Long
    ): List<Long> {
        if (candidateTimesMs.isEmpty() || beatMs <= 0L) return emptyList()

        val offsets = mutableMapOf<Long, Int>()
        for (t in candidateTimesMs) {
            val off = ((t - segStartMs) % beatMs + beatMs) % beatMs
            offsets[off] = (offsets[off] ?: 0) + 1
        }

        val bestOffset = offsets.maxByOrNull { it.value }?.key ?: 0L
        val snapWindow = min(90L, beatMs / 5)

        val grid = mutableListOf<Long>()
        var t = segStartMs + bestOffset
        while (t < segEndMs + beatMs) {
            grid += t
            t += beatMs
        }

        val snapped = mutableListOf<Long>()

        for (g in grid) {
            var bestCandidate: Long? = null
            var bestDist = Long.MAX_VALUE
            for (c in candidateTimesMs) {
                val d = abs(c - g)
                if (d <= snapWindow && d < bestDist) {
                    bestDist = d
                    bestCandidate = c
                }
            }
            if (bestCandidate != null && bestCandidate in segStartMs..segEndMs) {
                snapped += bestCandidate
            }
        }

        return snapped.sorted()
    }

    private fun pickCandidatePeaks(onset: FloatArray): List<Long> {
        if (onset.size < 8) return emptyList()

        val m = mean(onset)
        val sd = std(onset, m)
        val dyn = maxOfOrZero(onset)

        // 너무 빡빡하지 않게, 하지만 평평한 구간은 거르기
        val threshold = max(
            m + sd * 0.75f,
            dyn * 0.22f
        )

        val minGapFrames = (MIN_PEAK_GAP_MS / HOP_MS).toInt().coerceAtLeast(1)

        val peaks = mutableListOf<Long>()
        var lastPeak = -10_000

        for (i in 2 until onset.size - 2) {
            val v = onset[i]
            val isPeak =
                v >= threshold &&
                        v >= onset[i - 1] &&
                        v >= onset[i + 1] &&
                        v >= onset[i - 2] &&
                        v >= onset[i + 2]

            if (!isPeak) continue
            if (i - lastPeak < minGapFrames) continue

            peaks += i.toLong()
            lastPeak = i
        }

        return peaks
    }

    private fun computeOnsetFlux(env: FloatArray): FloatArray {
        if (env.isEmpty()) return FloatArray(0)

        val smooth = env.copyOf()
        smoothInPlace(smooth, win = 2)

        val flux = FloatArray(smooth.size)
        for (i in 1 until smooth.size) {
            val d = smooth[i] - smooth[i - 1]
            flux[i] = if (d > 0f) d else 0f
        }

        normalize01InPlace(flux)
        smoothInPlace(flux, win = 1)
        return flux
    }

    // =========================================================
    // Decode low / mid / full
    // =========================================================

    private fun decodeBandEnvelopes(path: String, hopMs: Int): BandEnvelopes {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(path)

            var audioTrack = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }

            if (audioTrack < 0 || format == null) {
                return BandEnvelopes(0L, HOP_MS, FloatArray(0), FloatArray(0), FloatArray(0))
            }

            extractor.selectTrack(audioTrack)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val hopSamplesTarget = max(1, sampleRate * hopMs / 1000)

            val lowEnv = ArrayList<Float>()
            val midEnv = ArrayList<Float>()
            val fullEnv = ArrayList<Float>()

            val bufferInfo = MediaCodec.BufferInfo()

            var inputEos = false
            var outputEos = false

            val lowAlpha = alphaForCutoff(LOW_CUTOFF_HZ, sampleRate.toDouble())
            val midLowAlpha = alphaForCutoff(MID_LOW_CUTOFF_HZ, sampleRate.toDouble())
            val midHighAlpha = alphaForCutoff(MID_HIGH_CUTOFF_HZ, sampleRate.toDouble())

            var lowLp = 0.0
            var midLowLp = 0.0
            var midBandLp = 0.0

            var lowAcc = 0.0
            var midAcc = 0.0
            var fullAcc = 0.0
            var hopCount = 0

            var lastPtsUs = 0L

            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                lastPtsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, size, lastPtsUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            val bytes = ByteArray(bufferInfo.size)
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outBuffer.get(bytes)

                            var i = 0
                            while (i + 1 < bytes.size) {
                                var mono = 0.0
                                var readCh = 0

                                repeat(channelCount) {
                                    if (i + 1 >= bytes.size) return@repeat
                                    val sample = (
                                            (bytes[i + 1].toInt() shl 8) or
                                                    (bytes[i].toInt() and 0xFF)
                                            ).toShort()
                                    mono += sample / 32768.0
                                    readCh++
                                    i += 2
                                }

                                if (readCh <= 0) continue
                                val x = mono / readCh.toDouble()

                                // low
                                lowLp += lowAlpha * (x - lowLp)
                                val low = lowLp

                                // mid = HP(180) 후 LP(2200) 느낌의 단순 근사
                                midLowLp += midLowAlpha * (x - midLowLp)
                                val hp180 = x - midLowLp
                                midBandLp += midHighAlpha * (hp180 - midBandLp)
                                val mid = midBandLp

                                val full = x

                                lowAcc += low * low
                                midAcc += mid * mid
                                fullAcc += full * full
                                hopCount++

                                if (hopCount >= hopSamplesTarget) {
                                    lowEnv += sqrt(lowAcc / hopCount).toFloat()
                                    midEnv += sqrt(midAcc / hopCount).toFloat()
                                    fullEnv += sqrt(fullAcc / hopCount).toFloat()

                                    lowAcc = 0.0
                                    midAcc = 0.0
                                    fullAcc = 0.0
                                    hopCount = 0
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val low = lowEnv.toFloatArray()
            val mid = midEnv.toFloatArray()
            val full = fullEnv.toFloatArray()

            normalize01InPlace(low)
            normalize01InPlace(mid)
            normalize01InPlace(full)

            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else {
                max(low.size, max(mid.size, full.size)).toLong() * HOP_MS
            }

            return BandEnvelopes(
                durationMs = durationMs,
                hopMs = HOP_MS,
                low = low,
                mid = mid,
                full = full
            )
        } catch (t: Throwable) {
            runCatching { extractor.release() }
            Log.e(TAG, "decodeBandEnvelopes failed: ${t.message}")
            return BandEnvelopes(0L, HOP_MS, FloatArray(0), FloatArray(0), FloatArray(0))
        }
    }

    private fun alphaForCutoff(cutoffHz: Double, sampleRate: Double): Double {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
        return dt / (rc + dt)
    }

    // =========================================================
    // Color
    // =========================================================

    private fun colorsForHold(
        musicId: Int,
        p: ThemePalette,
        paletteSize: Int,
        tMs: Long
    ): HoldColors {
        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + seg * 97)

        val fgChoices = if (paletteSize >= 5) {
            listOfNotNull(p.c1, p.c2, p.c3, p.c5, p.white)
        } else {
            listOf(p.c1, p.c2, p.c3, p.white)
        }

        val bgChoices = if (paletteSize >= 4) {
            listOfNotNull(p.c4, p.black)
        } else {
            listOf(p.black)
        }

        val fg = fgChoices[rnd.nextInt(fgChoices.size)]
        val bg = bgChoices[rnd.nextInt(bgChoices.size)]

        return HoldColors(fg = fg, bg = bg)
    }

    private fun buildPalette(musicId: Int, paletteSize: Int): ThemePalette {
        val baseHue = ((musicId * 53) % 360).toFloat()

        val c1 = hsvToRgb(baseHue, 0.85f, 1.0f)
        val c2 = hsvToRgb(wrap360(baseHue + 18f), 0.75f, 1.0f)
        val c3 = hsvToRgb(wrap360(baseHue - 18f), 0.85f, 1.0f)

        val c4 = if (paletteSize >= 4) hsvToRgb(wrap360(baseHue + 36f), 0.70f, 1.0f) else null
        val c5 = if (paletteSize >= 5) hsvToRgb(wrap360(baseHue - 36f), 0.70f, 1.0f) else null

        return ThemePalette(
            black = Colors.BLACK,
            white = Colors.WHITE,
            c1 = c1,
            c2 = c2,
            c3 = c3,
            c4 = c4,
            c5 = c5
        )
    }

    private fun wrap360(h: Float): Float = (h % 360 + 360) % 360

    private fun hsvToRgb(h: Float, s: Float, v: Float): LSColor {
        val hh = wrap360(h)
        val c = v * s
        val x = c * (1 - abs((hh / 60f) % 2 - 1))
        val m = v - c

        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)

        return LSColor(r, g, b)
    }

    // =========================================================
    // Utils
    // =========================================================

    private fun mix(a: FloatArray, b: FloatArray, wa: Float, wb: Float): FloatArray {
        val n = min(a.size, b.size)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = a[i] * wa + b[i] * wb
        }
        normalize01InPlace(out)
        return out
    }

    private fun dedupeBeats(beats: List<Long>, minGapMs: Long): List<Long> {
        if (beats.isEmpty()) return emptyList()
        val out = mutableListOf<Long>()
        var last = Long.MIN_VALUE
        for (b in beats.sorted()) {
            if (b - last >= minGapMs) {
                out += b
                last = b
            }
        }
        return out
    }

    private fun estimateBeatIntervalMs(beats: List<Long>): Long {
        if (beats.size < 2) return 500L
        val diffs = mutableListOf<Long>()
        for (i in 1 until beats.size) {
            val d = beats[i] - beats[i - 1]
            if (d in MIN_BEAT_MS..MAX_BEAT_MS) diffs += d
        }
        if (diffs.isEmpty()) return 500L
        return diffs.sorted()[diffs.size / 2]
    }

    private fun normalize01InPlace(x: FloatArray) {
        if (x.isEmpty()) return
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= EPS) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.isEmpty() || win <= 0) return
        val copy = x.copyOf()
        for (i in x.indices) {
            var s = 0f
            var c = 0
            val a = (i - win).coerceAtLeast(0)
            val b = (i + win).coerceAtMost(x.lastIndex)
            for (j in a..b) {
                s += copy[j]
                c++
            }
            x[i] = s / max(1, c)
        }
    }

    private fun mean(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0f
        for (v in x) s += v
        return s / x.size
    }

    private fun std(x: FloatArray, mean: Float): Float {
        if (x.isEmpty()) return 0f
        var s = 0f
        for (v in x) {
            val d = v - mean
            s += d * d
        }
        return sqrt(s / x.size)
    }

    private fun variance(x: FloatArray): Float {
        val m = mean(x)
        val sd = std(x, m)
        return sd * sd
    }

    private fun maxOfOrZero(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var mx = x[0]
        for (i in 1 until x.size) {
            if (x[i] > mx) mx = x[i]
        }
        return mx
    }

    private fun fmt(v: Float): String = String.format("%.3f", v)
}