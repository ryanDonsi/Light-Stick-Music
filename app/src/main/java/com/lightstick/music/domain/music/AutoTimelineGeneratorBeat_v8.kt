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
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class AutoTimelineGeneratorBeat_v8 {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION = 8
        private const val HOP_MS = 50L

        private const val MIN_BEAT_MS = 350L
        private const val MAX_BEAT_MS = 900L

        private const val ON_TRANSIT = 2
        private const val COLOR_HOLD_MS = 2_000L

        private const val INTRO_PRESTART_TRANSIT_MS = 1_000L
        private const val SHORT_BRIDGE_MS = 3_000L

        private const val MIN_SECTION_MS = 1_000L
        private const val SECTION_MERGE_GAP_MS = 600L
    }

    private enum class EnvMode {
        LOW, MID, FULL
    }

    enum class FgEngine {
        ON_PULSE,
        BLINK,
        STROBE,
        BREATH,
        OFF_TRANSIT
    }

    enum class SectionType {
        INTRO,
        VERSE,
        CHORUS,
        BRIDGE,
        END
    }

    enum class ChangeLevel {
        LOW,
        MEDIUM,
        STRONG
    }

    data class Palette(
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor,
        val c5: LSColor,
        val white: LSColor,
        val black: LSColor,
        val size: Int
    )

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val engine: FgEngine,
        val beatMs: Long,
        val beats: Int,
        val source: String,
        val change: ChangeLevel
    )

    fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int = 4
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "v8 generate() start file=$musicPath musicId=$musicId paletteSize=$paletteSize")

        val pSize = paletteSize.coerceIn(3, 5)
        val palette = buildPalette(musicId, pSize)

        val lowEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.LOW)
        val midEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.MID)
        val fullEnv = decodeEnvelopeInternal(musicPath, hopMs = HOP_MS.toInt(), mode = EnvMode.FULL)

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "env empty -> return empty")
            return emptyList()
        }

        val envSize = min(lowEnv.size, min(midEnv.size, fullEnv.size))
        if (envSize <= 0) {
            Log.w(TAG, "envSize=0 -> return empty")
            return emptyList()
        }

        val durationMs = envSize.toLong() * HOP_MS

        val detect = KPopBeatDetectorV7.detect(
            lowEnv = lowEnv.take(envSize),
            midEnv = midEnv.take(envSize),
            fullEnv = fullEnv.take(envSize),
            params = KPopBeatDetectorV7.Params(
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = MAX_BEAT_MS,
                minPeakDistanceMs = 180L,
                onsetSmoothWindow = 3,
                segmentMs = 20_000L,
                peakThresholdK = 0.55f,
                minPeakAbs = 0.08f,
                snapToleranceMs = 120L,
                chainToleranceMs = 140L,
                minChainCount = 3
            )
        )

        val beatTimes = detect.beatTimesMs
            .filter { it in 0..durationMs }
            .sorted()

        if (beatTimes.isEmpty()) {
            Log.w(TAG, "beat detect FAIL -> return empty (skip save recommended)")
            return emptyList()
        }

        val beatMs = detect.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)

        val firstMusicMs = detectFirstMusicStartMs(
            energyFrames = fullEnv.take(envSize).toFloatArray(),
            hopMs = HOP_MS
        ).coerceIn(0L, durationMs)

        val introEndMs = when {
            firstMusicMs <= 0L -> 0L
            firstMusicMs <= INTRO_PRESTART_TRANSIT_MS -> 0L
            else -> firstMusicMs - INTRO_PRESTART_TRANSIT_MS
        }

        val forceTransitFromZero = firstMusicMs <= INTRO_PRESTART_TRANSIT_MS

        Log.d(
            TAG,
            "intro tuning firstMusicMs=$firstMusicMs introEndMs=$introEndMs forceTransitFromZero=$forceTransitFromZero durationMs=$durationMs"
        )

        val sections = buildSections(
            beatTimes = beatTimes,
            beatMs = beatMs,
            fullEnv = fullEnv.take(envSize),
            firstMusicMs = firstMusicMs,
            introEndMs = introEndMs,
            durationMs = durationMs,
            forceTransitFromZero = forceTransitFromZero
        )

        sections.forEachIndexed { idx, s ->
            Log.d(
                TAG,
                "section beat idx=$idx ${s.startMs}~${s.endMs} " +
                        "type=${s.type} beats=${s.beats} beatMs=${s.beatMs} source=${s.source} engine=${s.engine} change=${s.change}"
            )
        }

        val frames = buildFramesFromSections(
            musicId = musicId,
            palette = palette,
            sections = sections,
            beatTimes = beatTimes,
            durationMs = durationMs
        )

        Log.d(TAG, "v8 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    private fun buildSections(
        beatTimes: List<Long>,
        beatMs: Long,
        fullEnv: List<Float>,
        firstMusicMs: Long,
        introEndMs: Long,
        durationMs: Long,
        forceTransitFromZero: Boolean
    ): List<Section> {
        val raw = ArrayList<Section>()

        if (!forceTransitFromZero && introEndMs > 0L) {
            raw += Section(
                startMs = 0L,
                endMs = introEndMs,
                type = SectionType.INTRO,
                engine = FgEngine.BLINK,
                beatMs = beatMs,
                beats = estimateBeatCount(0L, introEndMs, beatMs),
                source = "intro-protected",
                change = ChangeLevel.STRONG
            )
        }

        if (firstMusicMs > 0L) {
            raw += Section(
                startMs = if (forceTransitFromZero) 0L else introEndMs,
                endMs = firstMusicMs,
                type = SectionType.INTRO,
                engine = FgEngine.ON_PULSE,
                beatMs = beatMs,
                beats = estimateBeatCount(if (forceTransitFromZero) 0L else introEndMs, firstMusicMs, beatMs),
                source = "intro-prestart-transit",
                change = ChangeLevel.STRONG
            )
        }

        val contentStartMs = firstMusicMs
        if (contentStartMs >= durationMs) {
            return raw.filter { it.endMs > it.startMs }
        }

        val winMs = (beatMs * 4L).coerceAtLeast(2_000L)
        val windows = ArrayList<Triple<Long, Long, Float>>() // start, end, score

        var t = contentStartMs
        while (t < durationMs) {
            val e = min(durationMs, t + winMs)
            val score = sectionEnergyScore(fullEnv, t, e)
            windows += Triple(t, e, score)
            t = e
        }

        if (windows.isEmpty()) {
            raw += Section(
                startMs = contentStartMs,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = beatMs,
                beats = estimateBeatCount(contentStartMs, durationMs, beatMs),
                source = "end-protected",
                change = ChangeLevel.STRONG
            )
            return raw.filter { it.endMs > it.startMs }
        }

        val scores = windows.map { it.third }
        val lowTh = percentile(scores, 0.35f)
        val highTh = percentile(scores, 0.70f)

        val contentSections = ArrayList<Section>()
        var currentStart = windows.first().first
        var currentEnd = windows.first().second
        var currentType = classifyType(windows.first().third, lowTh, highTh)

        for (i in 1 until windows.size) {
            val w = windows[i]
            val type = classifyType(w.third, lowTh, highTh)

            if (type == currentType || w.first - currentEnd <= SECTION_MERGE_GAP_MS) {
                if (type == currentType) {
                    currentEnd = w.second
                } else {
                    contentSections += buildContentSection(
                        startMs = currentStart,
                        endMs = currentEnd,
                        type = currentType,
                        beatMs = beatMs
                    )
                    currentStart = w.first
                    currentEnd = w.second
                    currentType = type
                }
            } else {
                contentSections += buildContentSection(
                    startMs = currentStart,
                    endMs = currentEnd,
                    type = currentType,
                    beatMs = beatMs
                )
                currentStart = w.first
                currentEnd = w.second
                currentType = type
            }
        }

        contentSections += buildContentSection(
            startMs = currentStart,
            endMs = currentEnd,
            type = currentType,
            beatMs = beatMs
        )

        val merged = mergeSmallSections(contentSections, beatMs)
        raw += adjustBridges(merged)

        val lastEnd = raw.maxOfOrNull { it.endMs } ?: 0L
        if (lastEnd < durationMs) {
            raw += Section(
                startMs = lastEnd,
                endMs = durationMs,
                type = SectionType.END,
                engine = FgEngine.OFF_TRANSIT,
                beatMs = beatMs,
                beats = estimateBeatCount(lastEnd, durationMs, beatMs),
                source = "end-protected",
                change = ChangeLevel.STRONG
            )
        }

        return raw
            .map { s ->
                val clampedStart = s.startMs.coerceIn(0L, durationMs)
                val clampedEnd = s.endMs.coerceIn(0L, durationMs)
                s.copy(
                    startMs = clampedStart,
                    endMs = clampedEnd,
                    beats = estimateBeatCount(clampedStart, clampedEnd, s.beatMs)
                )
            }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
    }

    private fun buildContentSection(
        startMs: Long,
        endMs: Long,
        type: SectionType,
        beatMs: Long
    ): Section {
        val duration = endMs - startMs

        val engine = when (type) {
            SectionType.VERSE -> FgEngine.ON_PULSE
            SectionType.CHORUS -> FgEngine.BLINK
            SectionType.BRIDGE -> if (duration < SHORT_BRIDGE_MS) FgEngine.ON_PULSE else FgEngine.BREATH
            SectionType.INTRO -> FgEngine.BLINK
            SectionType.END -> FgEngine.OFF_TRANSIT
        }

        val source = when (type) {
            SectionType.VERSE -> "sparse-global-blink"
            SectionType.CHORUS -> "sparse-global-blink"
            SectionType.BRIDGE -> if (duration < SHORT_BRIDGE_MS) "short-bridge-transit" else "sparse-global-breath"
            SectionType.INTRO -> "intro-protected"
            SectionType.END -> "end-protected"
        }

        val change = when {
            duration < SHORT_BRIDGE_MS && type == SectionType.BRIDGE -> ChangeLevel.STRONG
            duration < (beatMs * 4L) -> ChangeLevel.MEDIUM
            else -> ChangeLevel.STRONG
        }

        return Section(
            startMs = startMs,
            endMs = endMs,
            type = type,
            engine = engine,
            beatMs = beatMs,
            beats = estimateBeatCount(startMs, endMs, beatMs),
            source = source,
            change = change
        )
    }

    private fun adjustBridges(sections: List<Section>): List<Section> {
        return sections.map { s ->
            if (s.type == SectionType.BRIDGE && (s.endMs - s.startMs) < SHORT_BRIDGE_MS) {
                s.copy(
                    engine = FgEngine.ON_PULSE,
                    source = "short-bridge-transit",
                    change = ChangeLevel.STRONG
                )
            } else {
                s
            }
        }
    }

    private fun mergeSmallSections(sections: List<Section>, beatMs: Long): List<Section> {
        if (sections.isEmpty()) return emptyList()

        val out = ArrayList<Section>()
        var cur = sections.first()

        for (i in 1 until sections.size) {
            val next = sections[i]
            val curDur = cur.endMs - cur.startMs

            if (curDur < MIN_SECTION_MS || cur.beats <= 2) {
                cur = cur.copy(
                    endMs = next.endMs,
                    beats = estimateBeatCount(cur.startMs, next.endMs, beatMs)
                )
            } else {
                out += cur
                cur = next
            }
        }

        out += cur
        return out
    }

    private fun classifyType(score: Float, lowTh: Float, highTh: Float): SectionType {
        return when {
            score >= highTh -> SectionType.CHORUS
            score <= lowTh -> SectionType.BRIDGE
            else -> SectionType.VERSE
        }
    }

    private fun sectionEnergyScore(fullEnv: List<Float>, startMs: Long, endMs: Long): Float {
        val s = (startMs / HOP_MS).toInt().coerceIn(0, fullEnv.lastIndex)
        val e = (endMs / HOP_MS).toInt().coerceIn(s + 1, fullEnv.size)

        var sum = 0f
        var diffSum = 0f
        var maxV = 0f
        var prev = fullEnv[s]

        for (i in s until e) {
            val v = fullEnv[i]
            sum += v
            diffSum += abs(v - prev)
            maxV = max(maxV, v)
            prev = v
        }

        val n = max(1, e - s).toFloat()
        val mean = sum / n
        val activity = diffSum / n

        return (mean * 0.65f + activity * 0.20f + maxV * 0.15f).coerceIn(0f, 1f)
    }

    private fun buildFramesFromSections(
        musicId: Int,
        palette: Palette,
        sections: List<Section>,
        beatTimes: List<Long>,
        durationMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frames = ArrayList<Pair<Long, ByteArray>>(beatTimes.size * 2 + sections.size + 4)

        for ((index, section) in sections.withIndex()) {
            val sectionBeats = beatTimes.filter { it >= section.startMs && it < section.endMs }

            if (section.engine == FgEngine.OFF_TRANSIT) {
                frames += section.startMs to buildOffPayload()
                continue
            }

            if (sectionBeats.isEmpty()) {
                val fg = pickFgColor(palette, musicId, index, section.startMs)
                val bg = pickBgColor(palette, musicId, index, section.startMs)
                frames += section.startMs to buildPayload(section.engine, fg, bg, section.beatMs)
                continue
            }

            for ((beatIndex, t) in sectionBeats.withIndex()) {
                val fg = pickFgColor(palette, musicId, index, t)
                val bg = pickBgColor(palette, musicId, index, t)
                frames += t to buildPayload(section.engine, fg, bg, section.beatMs)

                if (section.engine == FgEngine.ON_PULSE) {
                    val offT = min(section.endMs, t + (section.beatMs * 3L / 10L))
                    if (offT > t) {
                        frames += offT to LSEffectPayload.Effects.on(
                            color = bg,
                            transit = ON_TRANSIT
                        ).toByteArray()
                    }
                }

                if (beatIndex == 0 && section.type == SectionType.INTRO && section.source == "intro-prestart-transit") {
                    frames += section.startMs to LSEffectPayload.Effects.on(
                        color = fg,
                        transit = ON_TRANSIT
                    ).toByteArray()
                }
            }
        }

        if (frames.none { it.first >= durationMs }) {
            frames += durationMs to buildOffPayload()
        }

        return frames
            .distinctBy { it.first to it.second.contentHashCode() }
            .sortedBy { it.first }
    }

    private fun buildPayload(
        engine: FgEngine,
        fg: LSColor,
        bg: LSColor,
        beatMs: Long
    ): ByteArray {
        return when (engine) {
            FgEngine.ON_PULSE -> {
                LSEffectPayload.Effects.on(
                    color = fg,
                    transit = ON_TRANSIT
                ).toByteArray()
            }

            FgEngine.BLINK -> {
                LSEffectPayload.Effects.blink(
                    period = max(1, beatMs.toInt()),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.STROBE -> {
                LSEffectPayload.Effects.strobe(
                    period = max(1, (beatMs / 2L).toInt()),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.BREATH -> {
                LSEffectPayload.Effects.breath(
                    period = max(1, beatMs.toInt()),
                    color = fg,
                    backgroundColor = bg
                ).toByteArray()
            }

            FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(): ByteArray {
        return LSEffectPayload.Effects.on(
            color = Colors.BLACK,
            transit = ON_TRANSIT
        ).toByteArray()
    }

    private fun pickBgColor(
        palette: Palette,
        musicId: Int,
        sectionIndex: Int,
        tMs: Long
    ): LSColor {
        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 1_000_003 + sectionIndex * 271 + seg * 97)

        val list = when {
            palette.size >= 4 -> listOf(palette.black, palette.c4)
            else -> listOf(palette.black)
        }
        return list[rnd.nextInt(list.size)]
    }

    private fun pickFgColor(
        palette: Palette,
        musicId: Int,
        sectionIndex: Int,
        tMs: Long
    ): LSColor {
        val seg = (tMs / COLOR_HOLD_MS).toInt()
        val rnd = Random(musicId * 31_415 + sectionIndex * 911 + seg * 13)

        val list = when {
            palette.size >= 5 -> listOf(palette.c1, palette.c2, palette.c3, palette.c5, palette.white)
            else -> listOf(palette.c1, palette.c2, palette.c3, palette.white)
        }
        return list[rnd.nextInt(list.size)]
    }

    private fun estimateBeatCount(startMs: Long, endMs: Long, beatMs: Long): Int {
        if (endMs <= startMs || beatMs <= 0L) return 0
        return max(1, ((endMs - startMs) / beatMs).toInt())
    }

    private fun detectFirstMusicStartMs(
        energyFrames: FloatArray,
        hopMs: Long
    ): Long {
        if (energyFrames.isEmpty()) return 0L

        val smooth = FloatArray(energyFrames.size)
        for (i in energyFrames.indices) {
            var sum = 0f
            var count = 0
            for (k in -2..2) {
                val j = i + k
                if (j in energyFrames.indices) {
                    sum += energyFrames[j]
                    count++
                }
            }
            smooth[i] = if (count > 0) sum / count else energyFrames[i]
        }

        val noiseWindow = ((1000L / hopMs).toInt())
            .coerceAtLeast(1)
            .coerceAtMost(smooth.size)

        var noiseSum = 0f
        for (i in 0 until noiseWindow) {
            noiseSum += smooth[i]
        }
        val noiseFloor = noiseSum / noiseWindow.toFloat()

        val threshold = max(noiseFloor * 2.2f, 0.015f)
        val needRun = ((250L / hopMs).toInt()).coerceAtLeast(2)

        var run = 0
        for (i in smooth.indices) {
            if (smooth[i] >= threshold) {
                run++
                if (run >= needRun) {
                    val startFrame = (i - run + 1).coerceAtLeast(0)
                    return startFrame * hopMs
                }
            } else {
                run = 0
            }
        }

        return 0L
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = (sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun buildPalette(seed: Int, paletteSize: Int): Palette {
        val rnd = Random(seed)

        val c1 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c2 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c3 = hsvToColor(rnd.nextFloat() * 360f, 0.85f, 1.0f)
        val c4 = hsvToColor(rnd.nextFloat() * 360f, 0.75f, 0.95f)
        val c5 = hsvToColor(rnd.nextFloat() * 360f, 0.75f, 0.95f)

        return Palette(
            c1 = c1,
            c2 = c2,
            c3 = c3,
            c4 = c4,
            c5 = c5,
            white = LSColor(255, 255, 255),
            black = LSColor(0, 0, 0),
            size = paletteSize
        )
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): LSColor {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c

        val (rf, gf, bf) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return LSColor(
            ((rf + m) * 255f).toInt().coerceIn(0, 255),
            ((gf + m) * 255f).toInt().coerceIn(0, 255),
            ((bf + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    private fun decodeEnvelopeInternal(
        musicPath: String,
        hopMs: Int,
        mode: EnvMode
    ): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(musicPath)

            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                extractor.release()
                return emptyList()
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                extractor.release()
                return emptyList()
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples = max(1, sampleRate * hopMs / 1000)

            val out = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            var sawInputEOS = false
            var sawOutputEOS = false
            val pcmWindow = ArrayList<Float>(hopSamples)

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val timeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            val mono = pcm16ToMonoFloat(chunk, channelCount)
                            val filtered = when (mode) {
                                EnvMode.FULL -> mono
                                EnvMode.LOW -> lowBandProxy(mono)
                                EnvMode.MID -> midBandProxy(mono)
                            }

                            for (v in filtered) {
                                pcmWindow += v
                                if (pcmWindow.size >= hopSamples) {
                                    out += rms(pcmWindow)
                                    pcmWindow.clear()
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Unit
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            normalizeEnvelope(out)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeEnvelopeInternal fail mode=$mode path=$musicPath: ${t.message}")
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            emptyList()
        }
    }

    private fun pcm16ToMonoFloat(bytes: ByteArray, channels: Int): List<Float> {
        if (bytes.isEmpty()) return emptyList()

        val out = ArrayList<Float>(bytes.size / 2 / max(1, channels))
        var i = 0

        while (i + 1 < bytes.size) {
            var sum = 0f
            var count = 0

            for (c in 0 until channels) {
                val idx = i + c * 2
                if (idx + 1 < bytes.size) {
                    val lo = bytes[idx].toInt() and 0xFF
                    val hi = bytes[idx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    val signed = if (sample > 32767) sample - 65536 else sample
                    sum += signed / 32768f
                    count++
                }
            }

            out += if (count == 0) 0f else sum / count.toFloat()
            i += channels * 2
        }

        return out
    }

    private fun lowBandProxy(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val lp = onePoleLowPass(src, 0.12f)
        return lp.map { abs(it) }
    }

    private fun midBandProxy(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val lp1 = onePoleLowPass(src, 0.35f)
        val lp2 = onePoleLowPass(src, 0.08f)
        return List(src.size) { i -> abs(lp1[i] - lp2[i]) }
    }

    private fun onePoleLowPass(src: List<Float>, alpha: Float): List<Float> {
        if (src.isEmpty()) return emptyList()

        val out = ArrayList<Float>(src.size)
        var y = 0f
        for (x in src) {
            y += alpha * (x - y)
            out += y
        }
        return out
    }

    private fun rms(src: List<Float>): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f
        for (x in src) sum += x * x
        return sqrt(sum / src.size.toFloat())
    }

    private fun normalizeEnvelope(src: List<Float>): List<Float> {
        if (src.isEmpty()) return emptyList()
        val smooth = movingAverage(src, 5)
        val mx = smooth.maxOrNull() ?: 0f
        if (mx <= 1e-6f) return List(smooth.size) { 0f }
        return smooth.map { (it / mx).coerceIn(0f, 1f) }
    }

    private fun movingAverage(src: List<Float>, window: Int): List<Float> {
        if (src.isEmpty() || window <= 1) return src

        val out = ArrayList<Float>(src.size)
        val half = window / 2

        for (i in src.indices) {
            var sum = 0f
            var count = 0
            val s = max(0, i - half)
            val e = min(src.lastIndex, i + half)

            for (j in s..e) {
                sum += src[j]
                count++
            }

            out += if (count == 0) 0f else sum / count.toFloat()
        }

        return out
    }

    fun getVersion(): Int = VERSION
}