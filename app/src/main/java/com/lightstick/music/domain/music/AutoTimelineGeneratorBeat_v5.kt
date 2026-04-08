package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * AutoTimelineGeneratorBeat_v5 (BLINK-ONLY, SINGLE PAYLOAD)
 *
 * ✅ 목표(사용자 스펙):
 * - Bass(저역)에서 beat(피크)만 추출
 * - BLINK는 "1개의 payload"로만 생성
 * - period로 박자(beat interval)를 맞춤
 *
 * ✅ 펌웨어 스펙:
 * - BLINK period tick: 1 = 10ms
 * - BLINK 연출: FG = period/2, BG = period/2
 *   => FG가 다시 나오는 간격 = period * 10ms
 *
 * ✅ 결론:
 * - 추정한 1박(beat interval ms)에 맞춰 periodTicks를 계산:
 *   periodTicks = round(beatIntervalMs / 10)
 * - 타임라인에는 (t=0) BLINK 프레임 1개만 넣는다.
 *
 * ⚠️ 참고:
 * - 기존 v5 문서의 "색 5초 유지"는 beat마다 BLINK를 다시 보내는 방식에서만 의미가 있었음.
 *   "payload 1개" 요구사항을 지키려면 색은 고정(초기 1회)이어야 한다.
 */
class AutoTimelineGeneratorBeat_v5 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val HOP_MS = 50L
        private const val MIN_BEAT_GAP_MS = 200L

        /** BLINK period tick = 10ms (firmware spec) */
        private const val BLINK_TICK_MS = 100L

        private const val NOVELTY_THRESHOLD_PERCENTILE = 0.80f
        private const val BASS_LPF_ALPHA = 0.04

        /** beats가 부족할 때 사용하는 기본값(대략 120 BPM) => 500ms */
        private const val DEFAULT_BEAT_INTERVAL_MS = 500L
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

    override fun generate(musicPath: String, musicId: Int, paletteSize: Int): List<Pair<Long, ByteArray>> {
        val palette = buildPalette(musicId, paletteSize.coerceIn(3, 5))

        // 1) Bass envelope 추출
        val bassEnv = decodeBassEnvelope(musicPath, hopMs = HOP_MS.toInt())
        if (bassEnv.isEmpty()) {
            Log.w(TAG, "Bass envelope empty -> fallback")
            return fallbackSingleBlink(musicId, palette)
        }

        val durationMs = bassEnv.size.toLong() * HOP_MS

        // 2) novelty + peak picking -> beat 후보 타임스탬프 얻기 (BPM/interval 추정용)
        val novelty = computeNovelty(bassEnv)
        val beatTimes = pickBeatsFromNovelty(novelty, hopMs = HOP_MS, durationMs = durationMs)

        // 3) beat interval 추정 -> BLINK periodTicks 계산
        val beatIntervalMs = estimateBeatIntervalMs(beatTimes)
        val periodTicks = computeBlinkPeriodTicksFromBeatInterval(beatIntervalMs)

        // 4) ✅ BLINK payload 1개만 생성 (t=0)
        val hold = colorsForHold(musicId, palette, tMs = 0L)

        val payloadBytes = LSEffectPayload.Effects.blink(
            period = periodTicks,
            color = hold.fg,
            backgroundColor = hold.bg
        ).toByteArray()

        Log.d(
            TAG,
            "paletteSize=$paletteSize beats=${beatTimes.size} " +
                    "beatIntervalMs=$beatIntervalMs periodTicks=$periodTicks durationMs=$durationMs"
        )

        return listOf(0L to payloadBytes)
    }

    // ─────────────────────────────────────────────
    // Beat interval 추정
    // ─────────────────────────────────────────────

    /**
     * beatTimes로부터 1박 간격(ms)을 추정한다.
     * - outlier를 줄이기 위해 diff들의 median을 사용
     * - beats가 부족하면 DEFAULT_BEAT_INTERVAL_MS 사용
     */
    private fun estimateBeatIntervalMs(beatTimes: LongArray): Long {
        if (beatTimes.size < 2) return DEFAULT_BEAT_INTERVAL_MS

        val diffs = ArrayList<Long>(beatTimes.size - 1)
        var prev = beatTimes[0]
        for (i in 1 until beatTimes.size) {
            val cur = beatTimes[i]
            val d = cur - prev
            prev = cur
            if (d >= MIN_BEAT_GAP_MS) diffs.add(d)
        }
        if (diffs.isEmpty()) return DEFAULT_BEAT_INTERVAL_MS

        diffs.sort()
        val mid = diffs.size / 2
        return if (diffs.size % 2 == 1) {
            diffs[mid]
        } else {
            ((diffs[mid - 1] + diffs[mid]) / 2L)
        }
    }

    /**
     * 펌웨어 tick=10ms, FG/BG = period/2.
     * "쿵(FG)"이 1박마다 나오게 하려면:
     *   periodTicks * 10ms = beatIntervalMs
     */
    private fun computeBlinkPeriodTicksFromBeatInterval(beatIntervalMs: Long): Int {
        val ticks = (beatIntervalMs.toDouble() / BLINK_TICK_MS.toDouble()).roundToInt()
        return ticks.coerceAtLeast(1)
    }

    // ─────────────────────────────────────────────
    // Color (기존 팔레트 생성/결정적 랜덤 유지)
    // ─────────────────────────────────────────────

    private fun colorsForHold(musicId: Int, p: ThemePalette, tMs: Long): HoldColors {
        // v5는 payload 1개이므로 사실상 tMs=0만 의미 있음
        val segment = 0 // (tMs / COLOR_HOLD_MS) 같은 개념은 v5에선 사용하지 않음
        val rnd = Random(musicId * 1_000_003 + segment * 97)

        // FG: 팔레트 3색(c1,c2,c3) + WHITE
        val fgChoices = listOf(p.c1, p.c2, p.c3, p.white)

        // BG: 팔레트 1색 + BLACK
        val bgTheme = p.c4 ?: p.c3
        val bgChoices = listOf(bgTheme, p.black)

        val fg = fgChoices[rnd.nextInt(fgChoices.size)]
        val bg = bgChoices[rnd.nextInt(bgChoices.size)]

        return HoldColors(fg = fg, bg = bg)
    }

    // ─────────────────────────────────────────────
    // Beat 추출: novelty(상승분) + peak picking
    // ─────────────────────────────────────────────

    private fun computeNovelty(env: FloatArray): FloatArray {
        val nov = FloatArray(env.size)
        for (i in 1 until env.size) {
            val d = env[i] - env[i - 1]
            nov[i] = if (d > 0f) d else 0f
        }
        normalize01InPlace(nov)
        smoothInPlace(nov, win = 2)
        return nov
    }

    private fun pickBeatsFromNovelty(novelty: FloatArray, hopMs: Long, durationMs: Long): LongArray {
        val sorted = novelty.toList().sorted()
        val thIndex = (sorted.size * NOVELTY_THRESHOLD_PERCENTILE).toInt().coerceIn(0, sorted.lastIndex)
        val th = sorted[thIndex]

        val beats = ArrayList<Long>()
        var lastBeatMs = Long.MIN_VALUE

        for (i in 2 until novelty.size - 2) {
            val v = novelty[i]
            if (v < th) continue

            val isPeak =
                v >= novelty[i - 1] && v >= novelty[i + 1] &&
                        v >= novelty[i - 2] && v >= novelty[i + 2]

            if (!isPeak) continue

            val tMs = i.toLong() * hopMs
            if (tMs < 0 || tMs > durationMs) continue
            if (tMs - lastBeatMs < MIN_BEAT_GAP_MS) continue

            beats.add(tMs)
            lastBeatMs = tMs
        }

        return beats.toLongArray()
    }

    // ─────────────────────────────────────────────
    // Bass envelope: PCM -> 1pole LPF -> hop energy
    // ─────────────────────────────────────────────

    private fun decodeBassEnvelope(path: String, hopMs: Int): FloatArray {
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
            if (audioTrack < 0 || format == null) return FloatArray(0)
            extractor.selectTrack(audioTrack)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val hopSamplesTarget = max(1, sampleRate * hopMs / 1000)

            val env = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            var lp = 0.0
            val alpha = BASS_LPF_ALPHA

            var hopEnergy = 0.0
            var hopSamples = 0

            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                            continue
                        }

                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer == null) {
                            codec.releaseOutputBuffer(outIndex, false)
                            continue
                        }

                        val bytes = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outBuffer.get(bytes)

                        // PCM 16bit LE
                        var i = 0
                        while (i + 1 < bytes.size) {
                            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                            val x = sample / 32768.0

                            lp += alpha * (x - lp)
                            hopEnergy += lp * lp
                            hopSamples++

                            if (hopSamples >= hopSamplesTarget) {
                                val v = sqrt(hopEnergy / max(1, hopSamples)).toFloat().coerceIn(0f, 1f)
                                env.add(v)
                                hopEnergy = 0.0
                                hopSamples = 0
                            }

                            i += 2
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val arr = env.toFloatArray()
            normalize01InPlace(arr)
            return arr

        } catch (t: Throwable) {
            runCatching { extractor.release() }
            Log.e(TAG, "decodeBassEnvelope failed: ${t.message}")
            return FloatArray(0)
        }
    }

    // ─────────────────────────────────────────────
    // Utils
    // ─────────────────────────────────────────────

    private fun normalize01InPlace(x: FloatArray) {
        var mx = 0f
        for (v in x) mx = max(mx, v)
        if (mx <= 1e-6f) return
        for (i in x.indices) x[i] = (x[i] / mx).coerceIn(0f, 1f)
    }

    private fun smoothInPlace(x: FloatArray, win: Int) {
        if (x.size < win + 2) return
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

    // ─────────────────────────────────────────────
    // Palette (3~5 유지)
    // ─────────────────────────────────────────────

    private fun buildPalette(musicId: Int, paletteSize: Int): ThemePalette {
        val baseHue = ((musicId * 53) % 360).toFloat()

        val c1 = hsvToRgb(baseHue, 0.85f, 0.95f)
        val c2 = hsvToRgb(wrap360(baseHue + 18f), 0.60f, 1.00f)
        val c3 = hsvToRgb(wrap360(baseHue - 18f), 0.85f, 0.80f)

        val c4 = if (paletteSize >= 4) hsvToRgb(wrap360(baseHue + 30f), 0.75f, 0.90f) else null
        val c5 = if (paletteSize >= 5) hsvToRgb(wrap360(baseHue - 30f), 0.70f, 0.90f) else null

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
        val x = c * (1 - kotlin.math.abs((hh / 60f) % 2 - 1))
        val m = v - c

        val (r1, g1, b1) = when {
            hh < 60f  -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else      -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return LSColor(r, g, b)
    }

    // ─────────────────────────────────────────────
    // Fallback: BLINK 1개만
    // ─────────────────────────────────────────────

    private fun fallbackSingleBlink(musicId: Int, palette: ThemePalette): List<Pair<Long, ByteArray>> {
        val hold = colorsForHold(musicId, palette, 0L)
        val periodTicks = computeBlinkPeriodTicksFromBeatInterval(DEFAULT_BEAT_INTERVAL_MS)
        val payload = LSEffectPayload.Effects.blink(
            period = periodTicks,
            color = hold.fg,
            backgroundColor = hold.bg
        ).toByteArray()
        return listOf(0L to payload)
    }
}