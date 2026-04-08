package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AutoTimelineGeneratorBeat_v6
 *
 * 목표:
 * - beat(박자)가 반드시 필요
 * - peak-picking이 실패(beatTimes=0)해도, 오디오 기반(=novelty)으로 tempo+phase를 추정해
 *   grid beat를 생성해서 항상 beatTimes를 만들어낸다.
 *
 * BG는 항상 BLACK (가독성/박자 대비 목적)
 */
class AutoTimelineGeneratorBeat_v6 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val HOP_MS = 50L
        private const val COLOR_HOLD_MS = 5_000L
        private const val MIN_BEAT_GAP_MS = 200L

        /** tick: 1 = 10ms */
        private const val EFFECT_TICK_MS = 10L

        /** FG:BG = 3:7 */
        private const val FG_UNITS = 3
        private const val BG_UNITS = 7
        private const val TOTAL_UNITS = FG_UNITS + BG_UNITS // 10

        /** ON transit */
        private const val ON_TRANSIT = 3

        private const val NOVELTY_THRESHOLD_PERCENTILE = 0.80f
        private const val BASS_LPF_ALPHA = 0.04

        /** beat interval 기본값(대략 120bpm) */
        private const val DEFAULT_BEAT_INTERVAL_MS = 500L

        /**
         * tempo 탐색 범위 (beatMs)
         * - 필요하면 좁혀도 됨 (예: 300~650ms)
         */
        private const val MIN_BEAT_MS = 250L   // bpm 240
        private const val MAX_BEAT_MS = 750L   // bpm 80
    }

    enum class FgMode { ON_PULSE, BLINK, STROBE, BREATH }

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

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        val palette = buildPalette(musicId, paletteSize.coerceIn(3, 5))

        // 1) Bass envelope 추출
        val bassEnv = decodeBassEnvelope(musicPath, hopMs = HOP_MS.toInt())
        if (bassEnv.isEmpty()) {
            Log.w(TAG, "Bass envelope empty -> fallback")
            return fallback(musicId, palette)
        }

        val durationMs = bassEnv.size.toLong() * HOP_MS

        // ✅ (요청) 로그 1: 입력 요약
        Log.d(TAG, "bassEnv=${bassEnv.size} durationMs=$durationMs hopMs=$HOP_MS")

        // 2) novelty + beat 후보(peak picking)
        val novelty = computeNovelty(bassEnv)
        var beatTimes = pickBeatsFromNovelty(novelty, hopMs = HOP_MS, durationMs = durationMs)

        // ✅ (요청) 로그 2: peak picking 결과
        Log.d(
            TAG,
            "beats(peak)=${beatTimes.size} first=${beatTimes.firstOrNull()} last=${beatTimes.lastOrNull()}"
        )

        /**
         * 3) beatTimes가 0이면:
         *    - novelty 기반 autocorrelation으로 tempo(beatMs) 추정
         *    - phase offset을 grid score로 맞춘 뒤
         *    - grid beatTimes 생성 (오디오 기반이므로 '진짜 beat'를 강제)
         */
        if (beatTimes.isEmpty()) {
            val beatMs = estimateBeatMsByAutocorr(
                novelty = novelty,
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = MAX_BEAT_MS
            )

            val offsetMs = estimatePhaseOffsetMs(
                novelty = novelty,
                hopMs = HOP_MS,
                beatMs = beatMs
            )

            beatTimes = buildGridBeats(
                durationMs = durationMs,
                beatMs = beatMs,
                offsetMs = offsetMs
            )

            Log.w(
                TAG,
                "peak beats=0 -> autocorr beatMs=$beatMs offsetMs=$offsetMs gridBeats=${beatTimes.size}"
            )
        }

        // 4) beatInterval을 v6 리듬 분할(3/7)로 변환
        val beatIntervalMs = normalizeBeatInterval(estimateBeatIntervalMs(beatTimes))
        val unitMs = (beatIntervalMs / TOTAL_UNITS.toLong()).coerceAtLeast(1L)
        val fgMs = unitMs * FG_UNITS

        val frames = ArrayList<Pair<Long, ByteArray>>(max(1, beatTimes.size) * 2)

        for (t in beatTimes) {
            val hold = colorsForHold(musicId, palette, t) // BG=BLACK 고정
            val fgMode = fgModeForHold(musicId, t)

            val fgPayload = buildFgPayload(
                mode = fgMode,
                fg = hold.fg,
                bgAlwaysBlack = Colors.BLACK,
                unitMs = unitMs,
                fgMs = fgMs
            )

            val bgPayload = LSEffectPayload.Effects.on(
                color = Colors.BLACK,
                transit = ON_TRANSIT
            ).toByteArray()

            frames.add(t to fgPayload)
            frames.add((t + fgMs) to bgPayload)
        }

        // ✅ (요청) 로그 3: 최종 프레임 수
        Log.d(
            TAG,
            "frames(final)=${frames.size} beats=${beatTimes.size} beatIntervalMs=$beatIntervalMs unitMs=$unitMs fgMs=$fgMs"
        )

        return frames.sortedBy { it.first }
    }

    // ─────────────────────────────────────────────
    // ✅ beat 강제(tempo/phase) 로직
    // ─────────────────────────────────────────────

    /**
     * Autocorrelation으로 beatMs(템포)를 추정한다.
     * novelty 기반이라, peak가 부족해도 주기를 잡는 데 강함.
     *
     * hopMs=50ms 이므로 lag는 (minBeatMs/hopMs)~(maxBeatMs/hopMs) 범위 index로 탐색.
     */
    private fun estimateBeatMsByAutocorr(
        novelty: FloatArray,
        hopMs: Long,
        minBeatMs: Long,
        maxBeatMs: Long
    ): Long {
        val minLag = max(1, (minBeatMs / hopMs).toInt())
        val maxLag = max(minLag + 1, (maxBeatMs / hopMs).toInt().coerceAtMost(novelty.size - 1))

        var bestLag = (DEFAULT_BEAT_INTERVAL_MS / hopMs).toInt().coerceIn(minLag, maxLag)
        var bestScore = Double.NEGATIVE_INFINITY

        // 간단한 autocorr: sum(n[i] * n[i-lag])
        // (정규화까지 하면 더 좋지만, 여기선 선택용으로 충분)
        for (lag in minLag..maxLag) {
            var s = 0.0
            var i = lag
            while (i < novelty.size) {
                s += (novelty[i] * novelty[i - lag]).toDouble()
                i++
            }
            if (s > bestScore) {
                bestScore = s
                bestLag = lag
            }
        }

        val beatMs = bestLag.toLong() * hopMs
        return beatMs.coerceIn(minBeatMs, maxBeatMs)
    }

    /**
     * Phase(시작 offset)를 추정한다.
     * offset 후보(0..beatMs) 중 novelty가 가장 많이 쌓이는 offset을 선택.
     */
    private fun estimatePhaseOffsetMs(
        novelty: FloatArray,
        hopMs: Long,
        beatMs: Long
    ): Long {
        val lag = max(1, (beatMs / hopMs).toInt())
        if (lag <= 1) return 0L

        var bestOffsetIdx = 0
        var bestScore = Double.NEGATIVE_INFINITY

        // offsetIndex를 0..lag-1 범위에서 탐색
        for (offsetIdx in 0 until lag) {
            var s = 0.0
            var i = offsetIdx
            while (i < novelty.size) {
                s += novelty[i].toDouble()
                i += lag
            }
            if (s > bestScore) {
                bestScore = s
                bestOffsetIdx = offsetIdx
            }
        }

        return bestOffsetIdx.toLong() * hopMs
    }

    /**
     * beatMs 간격의 grid beats 생성.
     * offsetMs부터 시작해서 durationMs까지 beat 찍음.
     */
    private fun buildGridBeats(durationMs: Long, beatMs: Long, offsetMs: Long): LongArray {
        val b = beatMs.coerceAtLeast(MIN_BEAT_GAP_MS)
        val start = offsetMs.coerceIn(0L, b - 1)
        val list = ArrayList<Long>()
        var t = start
        while (t <= durationMs) {
            list.add(t)
            t += b
        }
        return list.toLongArray()
    }

    // ─────────────────────────────────────────────
    // FG/BG 조합
    // ─────────────────────────────────────────────

    private fun buildFgPayload(
        mode: FgMode,
        fg: LSColor,
        bgAlwaysBlack: LSColor,
        unitMs: Long,
        fgMs: Long
    ): ByteArray {
        fun msToTicks(ms: Long): Int =
            (ms.toDouble() / EFFECT_TICK_MS.toDouble()).roundToInt().coerceAtLeast(1)

        return when (mode) {
            FgMode.ON_PULSE -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgMode.BLINK -> {
                LSEffectPayload.Effects.blink(
                    period = msToTicks(unitMs),
                    color = fg,
                    backgroundColor = bgAlwaysBlack
                ).toByteArray()
            }

            FgMode.STROBE -> {
                LSEffectPayload.Effects.strobe(
                    period = msToTicks(unitMs),
                    color = fg,
                    backgroundColor = bgAlwaysBlack
                ).toByteArray()
            }

            FgMode.BREATH -> {
                LSEffectPayload.Effects.breath(
                    period = msToTicks(fgMs),
                    color = fg,
                    backgroundColor = bgAlwaysBlack
                ).toByteArray()
            }
        }
    }

    private fun fgModeForHold(musicId: Int, tMs: Long): FgMode {
        val segment = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 31_415 + segment * 271)
        val x = rnd.nextInt(100)
        return when {
            x < 30 -> FgMode.ON_PULSE
            x < 55 -> FgMode.BLINK
            x < 80 -> FgMode.STROBE
            else -> FgMode.BREATH
        }
    }

    // ─────────────────────────────────────────────
    // Color: BG는 항상 BLACK
    // ─────────────────────────────────────────────

    private fun colorsForHold(musicId: Int, p: ThemePalette, tMs: Long): HoldColors {
        val segment = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + segment * 97)

        val fgChoices = listOf(p.c1, p.c2, p.c3, p.white)
        val fg = fgChoices[rnd.nextInt(fgChoices.size)]

        return HoldColors(fg = fg, bg = Colors.BLACK)
    }

    // ─────────────────────────────────────────────
    // Beat interval 추정/보정 (기존 유지)
    // ─────────────────────────────────────────────

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
        return if (diffs.size % 2 == 1) diffs[mid] else ((diffs[mid - 1] + diffs[mid]) / 2L)
    }

    private fun normalizeBeatInterval(intervalMs: Long): Long {
        var x = intervalMs.coerceAtLeast(MIN_BEAT_GAP_MS)
        while (x > MAX_BEAT_MS && x / 2 >= MIN_BEAT_GAP_MS) x /= 2
        while (x < MIN_BEAT_MS && x * 2 <= MAX_BEAT_MS) x *= 2
        return x.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
    }

    // ─────────────────────────────────────────────
    // Beat 추출: novelty + peak picking (기존 유지)
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
    // Bass envelope: PCM -> 1pole LPF -> hop energy (기존 유지)
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
                            val sample =
                                ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
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
        val x = c * (1 - abs((hh / 60f) % 2 - 1))
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

    private fun fallback(musicId: Int, palette: ThemePalette, durationMs: Long = 60_000L): List<Pair<Long, ByteArray>> {
        val beatIntervalMs = DEFAULT_BEAT_INTERVAL_MS
        val unitMs = (beatIntervalMs / TOTAL_UNITS.toLong()).coerceAtLeast(1L)
        val fgMs = unitMs * FG_UNITS

        val frames = ArrayList<Pair<Long, ByteArray>>()
        var t = 0L
        while (t < durationMs) {
            val hold = colorsForHold(musicId, palette, t)
            val fg = LSEffectPayload.Effects.on(hold.fg, transit = ON_TRANSIT).toByteArray()
            val bg = LSEffectPayload.Effects.on(Colors.BLACK, transit = ON_TRANSIT).toByteArray()
            frames.add(t to fg)
            frames.add((t + fgMs) to bg)
            t += beatIntervalMs
        }
        return frames
    }
}