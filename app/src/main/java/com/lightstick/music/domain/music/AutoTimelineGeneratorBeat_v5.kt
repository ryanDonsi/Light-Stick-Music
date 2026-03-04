package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AutoTimelineGeneratorBeat_v5 (BLINK-ONLY)
 *
 * ✅ 요구사항:
 * - Bass(저역)에서 beat(피크)만 추출
 * - 해당 beat timestamp에 BLINK만 생성 (ON/OFF/STROBE/BREATH 사용 금지)
 * - FG: 팔레트 3색(c1,c2,c3) + WHITE 중 랜덤 (5초 유지)
 * - BG: 팔레트 1색(우선 c4, 없으면 c3) + BLACK 중 랜덤 (5초 유지)
 * - paletteSize(3~5) / 팔레트 생성 방식은 기존 유지
 */
class AutoTimelineGeneratorBeat_v5 {

    companion object {
        private const val TAG = "AutoTLBeat_v5"

        private const val HOP_MS = 50L
        private const val COLOR_HOLD_MS = 5_000L
        private const val MIN_BEAT_GAP_MS = 200L

        private const val BLINK_PERIOD = 10

        private const val NOVELTY_THRESHOLD_PERCENTILE = 0.80f
        private const val BASS_LPF_ALPHA = 0.04
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

    fun generate(musicPath: String, musicId: Int, paletteSize: Int = 4): List<Pair<Long, ByteArray>> {
        val palette = buildPalette(musicId, paletteSize.coerceIn(3, 5))

        // 1) Bass envelope 추출
        val bassEnv = decodeBassEnvelope(musicPath, hopMs = HOP_MS.toInt())
        if (bassEnv.isEmpty()) {
            Log.w(TAG, "Bass envelope empty -> fallback")
            return fallback(musicId, palette)
        }

        val durationMs = bassEnv.size.toLong() * HOP_MS

        // 2) novelty + peak picking
        val novelty = computeNovelty(bassEnv)
        val beatTimes = pickBeatsFromNovelty(novelty, hopMs = HOP_MS, durationMs = durationMs)

        // 3) beat마다 BLINK-only 프레임 생성 (색은 5초 유지)
        val frames = ArrayList<Pair<Long, ByteArray>>(beatTimes.size)
        var lastBeat = Long.MIN_VALUE

        for (t in beatTimes) {
            if (t - lastBeat < MIN_BEAT_GAP_MS) continue
            lastBeat = t

            val hold = colorsForHold(musicId, palette, t)

            // ✅ BLINK ONLY
            val payload = LSEffectPayload.Effects.blink(
                period = BLINK_PERIOD,
                color = hold.fg,
                backgroundColor = hold.bg
            ).toByteArray()

            frames.add(t to payload)
        }

        Log.d(TAG, "paletteSize=$paletteSize beats=${beatTimes.size} frames=${frames.size} durationMs=$durationMs")
        return frames
    }

    // ─────────────────────────────────────────────
    // Color: 5초 유지 + 결정적 랜덤
    // ─────────────────────────────────────────────

    private fun colorsForHold(musicId: Int, p: ThemePalette, tMs: Long): HoldColors {
        val segment = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + segment * 97)

        // ✅ FG: 팔레트 3색(c1,c2,c3) + WHITE
        val fgChoices = listOf(p.c1, p.c2, p.c3, p.white)

        // ✅ BG: 팔레트 1색 + BLACK
        // - paletteSize>=4면 c4를 "배경 테마색"으로 사용
        // - 아니면 c3를 배경 테마색으로 사용
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

    private fun fallback(musicId: Int, palette: ThemePalette, durationMs: Long = 180_000L): List<Pair<Long, ByteArray>> {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        var t = 0L
        while (t < durationMs) {
            // ✅ fallback도 BLINK ONLY
            frames.add(t to LSEffectPayload.Effects.blink(BLINK_PERIOD, palette.c1, palette.black).toByteArray())
            t += 500L
        }
        return frames
    }
}