package com.lightstick.music.domain.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AutoTimelineGeneratorBeat v8 (개선판)
 *
 * v8 원본 대비 수정 사항:
 * ① buildSectionBeatGrid — actualBeats 실제 사용 (실제 비트 >= 기대값 60% 이상이면 우선 적용,
 *                           부족한 경우에는 그리드 생성 후 실제 비트에 스냅)
 * ② climaxMoments 프레임 반영 — BLINK/STROBE 섹션에서 클라이맥스 인접 비트에 반박자 프레임 추가
 * ③ CHORUS 엔진 분리 — beatMs 기준으로 STROBE(≤400ms) / BLINK(>400ms) 배정 (v7 수준 회복)
 * ④ forceTransitFromZero 전달 — buildSections 파라미터에 추가하여 짧은 인트로 케이스 정확 처리
 * ⑤ 멀티채널 섹션 에너지 스코어 — low/mid/full 세 채널 조합으로 섹션 분류 정밀도 향상
 * ⑥ adjustBridges 중복 제거 — buildContentSection에서 엔진 결정을 완결하고 adjustBridges 제거
 * ⑦ mergeSmallSections 타입 손실 수정 — 더 긴 섹션의 타입/엔진을 채택
 *
 * [PERF] 단일 패스 오디오 디코딩
 * - decodeEnvelopeInternal × 3 → decodeAllEnvelopes × 1
 * - MediaCodec 1회, 인라인 IIR 필터, 누산 변수 RMS
 * - 예상 처리 시간: ~50초 → ~20초 (약 60% 절감)
 */
class AutoTimelineGeneratorBeat_v8 : AutoTimelineGenerator {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE

        private const val VERSION = 8
        private const val HOP_MS = 50L

        private const val MIN_BEAT_MS = 290L
        private const val MAX_BEAT_MS = 1200L

        private const val ON_TRANSIT = 2

        private const val INTRO_PRESTART_TRANSIT_MS = 1_000L
        private const val MIN_SECTION_MS = 1_000L
        private const val SECTION_MERGE_GAP_MS = 0L

        private const val ACTUAL_BEAT_USE_RATIO = 0.45f

        private const val CLIMAX_WINDOW_HALF_MS = 4_000L
        private const val CLIMAX_MIN_CV         = 0.35f  // 0.30 → 0.55: 조용한 발라드 오탐 방지
        private const val CLIMAX_MIN_PEAK_RATIO = 2.0f   // 1.80 → 2.80: 진짜 클라이막스만 감지

        private const val SECTION_GAP_BREATH_THRESHOLD_MS = 2_000L

        private const val ON_PULSE_BASE_HOLD_MS   = 700L
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_PULSE_BG_TRANSIT     = 5

        // ON ROTATE: 발라드/조용한 포크송 전용 transit 값
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT

        // 발라드/포크 감지 임계값
        private const val BALLAD_BEAT_MS_THRESHOLD  = 550L   // 느린 템포 (≈109 BPM 이하)
        // 평균 에너지 상한 — 발라드는 전반적으로 조용하므로 0.50f 이하
        // (lowFraction 방식은 low/full 비율≈0.95로 모든 곡이 동일 → 무용)
        private const val BALLAD_AVG_ENERGY_MAX     = 0.50f
        // 활성 프레임 하한 — 이 값 이하인 프레임은 무음/인트로로 간주, 평균에서 제외
        // 볼륨은 정규화(÷max)로 무관하나 긴 인트로/아웃트로가 평균을 끌어내리는 현상 방지
        private const val BALLAD_ACTIVE_ENERGY_MIN  = 0.15f

        // [PERF] 단일 패스 IIR 필터 계수
        private const val LOW_ALPHA     = 0.12f
        private const val MID_LP1_ALPHA = 0.35f
        private const val MID_LP2_ALPHA = 0.08f
    }

    enum class FgEngine {
        ON_PULSE,
        BLINK,
        STROBE,
        BREATH,
        ON_TRANSIT_ROTATE,
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
        MEDIUM,
        STRONG
    }

    data class ColorSet(val fg: LSColor, val bg: LSColor)

    data class Palette(
        val black: LSColor,
        val white: LSColor,
        val onPulseSets: List<ColorSet>,
        val blinkSets:   List<ColorSet>,
        val strokeSets:  List<ColorSet>,
        val breathSet:   ColorSet,
        val bridgeSets:  List<ColorSet>,
        val chorusBg:    LSColor,
        val colorGroup:  List<LSColor>
    )

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val type: SectionType,
        val engine: FgEngine,
        val beatMs: Long,
        val beats: Int,
        val source: String,
        val change: ChangeLevel,
        val energyScore: Float = 0f,
        val relScore: Float = 0f
    )

    // =========================================================================
    // Public entry point
    // =========================================================================

    override fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): List<Pair<Long, ByteArray>> {
        Log.d(TAG, "v8 generate() start file=$musicPath musicId=$musicId paletteSize=$paletteSize")

        Log.d(TAG, "palette source=musicId seed=$musicId")
        val palette = buildPalette(musicId)

        // [PERF] 단일 패스 디코딩 — MediaCodec 1회로 low/mid/full 동시 추출
        val (lowEnv, midEnv, fullEnv) = decodeAllEnvelopes(musicPath, HOP_MS.toInt())

        if (lowEnv.isEmpty() || midEnv.isEmpty() || fullEnv.isEmpty()) {
            Log.w(TAG, "env empty -> return empty")
            return emptyList()
        }

        val envSize = minOf(lowEnv.size, midEnv.size, fullEnv.size)
        if (envSize <= 0) {
            Log.w(TAG, "envSize=0 -> return empty")
            return emptyList()
        }

        val durationMs = envSize.toLong() * HOP_MS

        val detect = BeatDetectorV8.detect(
            lowEnv = lowEnv.take(envSize),
            midEnv = midEnv.take(envSize),
            fullEnv = fullEnv.take(envSize),
            params = BeatDetectorV8.Params(
                hopMs = HOP_MS,
                minBeatMs = MIN_BEAT_MS,
                maxBeatMs = MAX_BEAT_MS,
                minPeakDistanceMs = 140L,
                // onsetSmoothWindow 3→5 (250ms MA):
                // 기타 harmonic ringing(200~500ms 지속)을 평활화하여
                // 비트 사이에 발생하는 harmonic onset 가성피크 억제
                onsetSmoothWindow = 5,
                segmentMs = 20_000L,
                peakThresholdK = 0.55f,         //0.22f,
                minPeakAbs = 0.08f,             //0.04f,
                snapToleranceMs = 100L,         //150L,
                chainToleranceMs = 120L,        //170L,
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

        Log.d(
            TAG,
            "beat detect OK totalBeats=${beatTimes.size} " +
                    "beatMs=${detect.beatMs} first=${beatTimes.firstOrNull()} last=${beatTimes.lastOrNull()}"
        )

        // 1곡 전체에 단일 beatMs 적용 — 구간마다 beatMs가 달라 비트가 들죽날죽하게
        // 느껴지는 문제 해결. detect.beatMs(전체 비트 간격 중앙값)을 기준으로 하되,
        // 900ms 초과 시 절반으로 보정하여 느린 곡의 반속 감지 오류도 교정한다.
        val beatMs = detect.beatMs.coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
            .let { raw -> if (raw > 900L) raw / 2L else raw }
        Log.d(TAG, "globalBeatMs=$beatMs (raw=${detect.beatMs})")

        val firstMusicMs = detectFirstMusicStartMs(
            energyFrames = fullEnv.take(envSize).toFloatArray(),
            hopMs = HOP_MS
        ).coerceIn(0L, durationMs)

        val forceTransitFromZero = firstMusicMs <= INTRO_PRESTART_TRANSIT_MS

        val introEndMs = when {
            firstMusicMs <= 0L -> 0L
            firstMusicMs <= INTRO_PRESTART_TRANSIT_MS -> 0L
            else -> firstMusicMs - INTRO_PRESTART_TRANSIT_MS
        }

        Log.d(
            TAG,
            "intro tuning firstMusicMs=$firstMusicMs introEndMs=$introEndMs " +
                    "forceTransitFromZero=$forceTransitFromZero durationMs=$durationMs"
        )

        val climaxMoments = detectClimaxPeakMoments(
            fullEnv = fullEnv.take(envSize),
            durationMs = durationMs,
            beatMs = beatMs
        )
        Log.d(TAG, "climax moments=${climaxMoments.joinToString()}")

        // 활성 프레임만 평균 — 긴 인트로/아웃트로 무음 구간이 평균을 끌어내리는 현상 방지
        val allFrames    = fullEnv.take(envSize)
        val activeFrames = allFrames.filter { it > BALLAD_ACTIVE_ENERGY_MIN }
        val avgFullEnergy = if (activeFrames.size > 10) activeFrames.average().toFloat()
                           else allFrames.average().toFloat()
        val isBalladMode  = isQuietFolkOrBallad(beatMs, avgFullEnergy)
        Log.d(TAG, "balladMode=$isBalladMode beatMs=$beatMs " +
                "activeAvgEnergy=${"%.3f".format(avgFullEnergy)} " +
                "allAvgEnergy=${"%.3f".format(allFrames.average().toFloat())} " +
                "activeFrames=${activeFrames.size}/${allFrames.size} " +
                "climax=${climaxMoments.size}")

        // 발라드 모드: 클라이맥스 연출 완전 차단
        val effectiveClimaxMoments = if (isBalladMode) emptyList() else climaxMoments

        val sections = buildSections(
            beatMs = beatMs,
            lowEnv = lowEnv.take(envSize),
            midEnv = midEnv.take(envSize),
            fullEnv = fullEnv.take(envSize),
            firstMusicMs = firstMusicMs,
            durationMs = durationMs,
            forceTransitFromZero = forceTransitFromZero,
            climaxMoments = effectiveClimaxMoments,
            isBalladMode = isBalladMode
        )

        sections.forEachIndexed { idx, s ->
            Log.d(
                TAG,
                "section beat idx=$idx ${s.startMs}~${s.endMs} " +
                        "type=${s.type} beats=${s.beats} beatMs=${s.beatMs} " +
                        "source=${s.source} engine=${s.engine} change=${s.change}"
            )
        }

        val frames = buildFramesFromSections(
            musicId = musicId,
            palette = palette,
            sections = sections,
            beatTimes = beatTimes,
            durationMs = durationMs,
            climaxMoments = effectiveClimaxMoments,
            isBalladMode = isBalladMode
        )

        Log.d(TAG, "v8 frames(final)=${frames.size}")
        return frames.sortedBy { it.first }
    }

    // =========================================================================
    // [PERF] 단일 패스 오디오 디코딩
    //
    // 변경 전: decodeEnvelopeInternal(LOW) + (MID) + (FULL) — MediaCodec 3회, ~50초
    // 변경 후: decodeAllEnvelopes() 1회
    //   - MediaCodec 디코딩 1회
    //   - 인라인 1-pole IIR 필터 (LOW_ALPHA / MID_LP1_ALPHA, MID_LP2_ALPHA)
    //   - 누산 변수 RMS (sum() 배열 순회 없음, O(1))
    // =========================================================================

    private fun decodeAllEnvelopes(
        musicPath: String,
        hopMs: Int
    ): Triple<List<Float>, List<Float>, List<Float>> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(musicPath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return Triple(emptyList(), emptyList(), emptyList())
            }

            extractor.selectTrack(trackIndex)

            val mime         = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val hopSamples   = (sampleRate.toLong() * hopMs / 1000L).toInt().coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo   = MediaCodec.BufferInfo()
            var sawInputEOS  = false
            var sawOutputEOS = false

            val estimatedFrames = (sampleRate.toLong() * 300L / hopSamples).toInt()
            val outLow  = ArrayList<Float>(estimatedFrames)
            val outMid  = ArrayList<Float>(estimatedFrames)
            val outFull = ArrayList<Float>(estimatedFrames)

            // IIR 필터 상태
            var lowZ   = 0f
            var midLP1 = 0f
            var midLP2 = 0f

            // 누산 변수 RMS
            var lowSumSq  = 0f
            var midSumSq  = 0f
            var fullSumSq = 0f
            var winPos    = 0

            val stepBytes = channelCount * 2  // PCM16

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
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

                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)

                            var byteIdx = 0
                            while (byteIdx + stepBytes <= bytes.size) {
                                var monoSum = 0f
                                for (c in 0 until channelCount) {
                                    val lo = bytes[byteIdx + c * 2].toInt() and 0xFF
                                    val hi = bytes[byteIdx + c * 2 + 1].toInt()
                                    monoSum += (hi shl 8 or lo).toShort().toFloat()
                                }
                                val rawSample = monoSum / channelCount / 32768f

                                // 인라인 IIR 필터
                                lowZ   += LOW_ALPHA     * (rawSample - lowZ)
                                midLP1 += MID_LP1_ALPHA * (rawSample - midLP1)
                                midLP2 += MID_LP2_ALPHA * (rawSample - midLP2)

                                val lowSample  = abs(lowZ)
                                val midSample  = abs(midLP1 - midLP2)
                                val fullSample = abs(rawSample)

                                lowSumSq  += lowSample  * lowSample
                                midSumSq  += midSample  * midSample
                                fullSumSq += fullSample * fullSample
                                winPos++

                                if (winPos >= hopSamples) {
                                    val n = hopSamples.toFloat()
                                    outLow  += sqrt(lowSumSq  / n)
                                    outMid  += sqrt(midSumSq  / n)
                                    outFull += sqrt(fullSumSq / n)
                                    lowSumSq  = 0f; midSumSq = 0f; fullSumSq = 0f; winPos = 0
                                }
                                byteIdx += stepBytes
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            sawOutputEOS = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            // 마지막 불완전 윈도우 처리
            if (winPos > 0) {
                val n = winPos.toFloat()
                outLow  += sqrt(lowSumSq  / n)
                outMid  += sqrt(midSumSq  / n)
                outFull += sqrt(fullSumSq / n)
            }

            Triple(
                normalizeEnvelope(outLow),
                normalizeEnvelope(outMid),
                normalizeEnvelope(outFull)
            )

        } catch (t: Throwable) {
            Log.e(TAG, "decodeAllEnvelopes fail path=$musicPath: ${t.message}")
            try { codec?.stop()       } catch (_: Throwable) {}
            try { codec?.release()    } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            Triple(emptyList(), emptyList(), emptyList())
        }
    }

    // =========================================================================
    // Section building
    // =========================================================================

    private fun buildSections(
        beatMs: Long,
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        firstMusicMs: Long,
        durationMs: Long,
        @Suppress("UNUSED_PARAMETER") forceTransitFromZero: Boolean,
        climaxMoments: List<Long> = emptyList(),
        isBalladMode: Boolean = false
    ): List<Section> {
        val raw = ArrayList<Section>()

        val introMinGapMs = 2_000L
        if (firstMusicMs > introMinGapMs) {
            val introStartMs = (firstMusicMs - INTRO_PRESTART_TRANSIT_MS).coerceAtLeast(0L)
            raw += Section(
                startMs = introStartMs,
                endMs = firstMusicMs,
                type = SectionType.INTRO,
                engine = FgEngine.BREATH,
                beatMs = beatMs,
                beats = estimateBeatCount(introStartMs, firstMusicMs, beatMs),
                source = "intro-breath",
                change = ChangeLevel.STRONG
            )
        }

        val contentStartMs = firstMusicMs
        if (contentStartMs >= durationMs) {
            return raw.filter { it.endMs > it.startMs }
        }

        val winMs = (beatMs * 4L).coerceAtLeast(2_000L)
        val windows = ArrayList<Triple<Long, Long, Float>>()

        var t = contentStartMs
        while (t < durationMs) {
            val e = min(durationMs, t + winMs)
            val score = sectionEnergyScore(lowEnv, midEnv, fullEnv, t, e)
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
        var currentScoreSum = windows.first().third
        var currentScoreCount = 1

        for (i in 1 until windows.size) {
            val w = windows[i]
            val type = classifyType(w.third, lowTh, highTh)

            if (type == currentType || w.first - currentEnd <= SECTION_MERGE_GAP_MS) {
                if (type == currentType) {
                    currentEnd = w.second
                    currentScoreSum += w.third
                    currentScoreCount++
                } else {
                    val avgScore = if (currentScoreCount > 0) currentScoreSum / currentScoreCount else 0f
                    contentSections += buildContentSection(
                        startMs = currentStart,
                        endMs = currentEnd,
                        type = currentType,
                        beatMs = beatMs,
                        energyScore = avgScore,
                        lowTh = lowTh,
                        highTh = highTh,
                        climaxMoments = climaxMoments,
                        isBalladMode = isBalladMode
                    )
                    currentStart = w.first
                    currentEnd = w.second
                    currentType = type
                    currentScoreSum = w.third
                    currentScoreCount = 1
                }
            } else {
                val avgScore = if (currentScoreCount > 0) currentScoreSum / currentScoreCount else 0f
                contentSections += buildContentSection(
                    startMs = currentStart,
                    endMs = currentEnd,
                    type = currentType,
                    beatMs = beatMs,
                    energyScore = avgScore,
                    lowTh = lowTh,
                    highTh = highTh,
                    climaxMoments = climaxMoments,
                    isBalladMode = isBalladMode
                )
                currentStart = w.first
                currentEnd = w.second
                currentType = type
                currentScoreSum = w.third
                currentScoreCount = 1
            }
        }

        val lastAvgScore = if (currentScoreCount > 0) currentScoreSum / currentScoreCount else 0f
        contentSections += buildContentSection(
            startMs = currentStart,
            endMs = currentEnd,
            type = currentType,
            beatMs = beatMs,
            energyScore = lastAvgScore,
            lowTh = lowTh,
            highTh = highTh,
            climaxMoments = climaxMoments,
            isBalladMode = isBalladMode
        )

        val merged = mergeSmallSections(contentSections, beatMs)
        raw += merged

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
        beatMs: Long,
        energyScore: Float = 0f,
        lowTh: Float = 0f,
        highTh: Float = 1f,
        climaxMoments: List<Long> = emptyList(),
        isBalladMode: Boolean = false
    ): Section {
        val beats = estimateBeatCount(startMs, endMs, beatMs)
        val sectionMidMs = (startMs + endMs) / 2L
        val isClimaxSection = climaxMoments.any { kotlin.math.abs(it - sectionMidMs) <= 6_000L }

        val normalizedType = when {
            type == SectionType.BRIDGE && beats < 6 -> SectionType.VERSE
            else -> type
        }

        val range = (highTh - lowTh).coerceAtLeast(1e-6f)
        val rel = ((energyScore - lowTh) / range).coerceIn(0f, 1f)

        val engine = when (normalizedType) {
            SectionType.VERSE -> when {
                // 발라드/포크: BREATH 위주, 에너지가 높은 구간만 ON ROTATE
                isBalladMode -> if (rel < 0.55f) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
                rel < 0.10f && beats < 8 -> FgEngine.BREATH
                rel < 0.75f -> FgEngine.ON_PULSE
                else        -> FgEngine.ON_TRANSIT_ROTATE
            }
            SectionType.CHORUS -> when {
                // 발라드/포크: STROBE·ON_PULSE 없이 BREATH / ON ROTATE만 사용
                isBalladMode && (isClimaxSection || rel >= 0.65f) -> FgEngine.ON_TRANSIT_ROTATE
                isBalladMode -> FgEngine.BREATH
                beatMs <= 290L  -> FgEngine.STROBE
                isClimaxSection -> FgEngine.STROBE
                rel >= 0.40f    -> FgEngine.ON_TRANSIT_ROTATE
                else            -> FgEngine.ON_PULSE
            }
            SectionType.BRIDGE -> when {
                isBalladMode -> FgEngine.BREATH  // 발라드: bridge는 항상 BREATH
                beats < 8   -> FgEngine.ON_TRANSIT_ROTATE
                else        -> FgEngine.ON_PULSE
            }
            SectionType.INTRO -> FgEngine.BREATH
            SectionType.END -> FgEngine.OFF_TRANSIT
        }

        val source = when (normalizedType) {
            SectionType.VERSE -> when (engine) {
                FgEngine.BREATH            -> "verse-breath-black-bg"
                FgEngine.ON_TRANSIT_ROTATE -> "verse-rotate-black-bg"
                else                       -> "verse-on-pulse-black-bg"
            }
            SectionType.CHORUS -> when (engine) {
                FgEngine.STROBE            -> "chorus-strobe-color-bg"
                FgEngine.ON_TRANSIT_ROTATE -> "chorus-rotate-color-bg"
                FgEngine.BREATH            -> "chorus-breath-color-bg"
                else                       -> "chorus-on-pulse-color-bg"
            }
            SectionType.BRIDGE -> when {
                beats < 8  -> "bridge-rotate"
                beats < 16 -> "bridge-breath-to-rotate"
                else       -> "bridge-breath-rotate"
            }
            SectionType.INTRO -> "intro-breath"
            SectionType.END -> "end-protected"
        }

        val change = when {
            normalizedType == SectionType.BRIDGE && beats < 20 -> ChangeLevel.STRONG
            beats < 8 -> ChangeLevel.MEDIUM
            else -> ChangeLevel.STRONG
        }

        Log.d(
            TAG,
            "section energy type=$normalizedType engine=$engine " +
                    "energyScore=${"%.3f".format(energyScore)} relScore=${"%.3f".format(rel)} " +
                    "lowTh=${"%.3f".format(lowTh)} highTh=${"%.3f".format(highTh)} " +
                    "beatMs=${beatMs}ms beats=$beats"
        )

        return Section(
            startMs = startMs,
            endMs = endMs,
            type = normalizedType,
            engine = engine,
            beatMs = beatMs,
            beats = beats,
            source = source,
            change = change,
            energyScore = energyScore,
            relScore = rel
        )
    }

    private fun mergeSmallSections(sections: List<Section>, beatMs: Long): List<Section> {
        if (sections.isEmpty()) return emptyList()

        val MIN_CHORUS_MS = 8_000L

        val out = ArrayList<Section>()
        var cur = sections.first()

        for (i in 1 until sections.size) {
            val next = sections[i]
            val curDur = cur.endMs - cur.startMs
            val tooShortChorus = cur.type == SectionType.CHORUS && curDur < MIN_CHORUS_MS

            if (curDur < MIN_SECTION_MS || cur.beats <= 2 || tooShortChorus) {
                val nextDur = next.endMs - next.startMs
                val newBeats = estimateBeatCount(cur.startMs, next.endMs, beatMs)

                cur = if (nextDur > curDur) {
                    next.copy(startMs = cur.startMs, beats = newBeats)
                } else {
                    cur.copy(endMs = next.endMs, beats = newBeats)
                }
            } else {
                out += cur
                cur = next
            }
        }

        out += cur
        return out
    }

    private fun classifyType(score: Float, lowTh: Float, highTh: Float): SectionType {
        val bridgeTh = lowTh * 0.85f
        return when {
            score >= highTh   -> SectionType.CHORUS
            score <= bridgeTh -> SectionType.BRIDGE
            else              -> SectionType.VERSE
        }
    }

    private fun sectionEnergyScore(
        lowEnv: List<Float>,
        midEnv: List<Float>,
        fullEnv: List<Float>,
        startMs: Long,
        endMs: Long
    ): Float {
        val safeSize = minOf(lowEnv.size, midEnv.size, fullEnv.size)
        if (safeSize == 0) return 0f

        val s = (startMs / HOP_MS).toInt().coerceIn(0, safeSize - 1)
        val e = (endMs   / HOP_MS).toInt().coerceIn(s + 1, safeSize)

        var fullSum = 0f
        var diffSum = 0f
        var maxV    = 0f
        var lowSum  = 0f
        var midSum  = 0f
        var prev = fullEnv[s]

        for (i in s until e) {
            val v = fullEnv[i]
            fullSum += v
            diffSum += abs(v - prev)
            maxV = max(maxV, v)
            lowSum += lowEnv[i]
            midSum += midEnv[i]
            prev = v
        }

        val n            = max(1, e - s).toFloat()
        val mean         = fullSum / n
        val activity     = diffSum / n
        val lowRatio     = if (mean > 1e-6f) (lowSum / n) / mean else 0f
        val onsetDensity = midSum / n
        val lowPenalty   = (lowRatio     * 0.08f).coerceIn(0f, 0.08f)
        val onsetBonus   = (onsetDensity * 0.12f)

        return (mean * 0.60f + activity * 0.20f + maxV * 0.10f + onsetBonus - lowPenalty)
            .coerceIn(0f, 1f)
    }

    private fun detectClimaxPeakMoments(
        fullEnv: List<Float>,
        durationMs: Long,
        beatMs: Long
    ): List<Long> {
        if (fullEnv.size < 8) return emptyList()

        data class PeakCandidate(val tMs: Long, val score: Float)

        val scoreArray = FloatArray(fullEnv.size) { 0f }
        for (i in 2 until fullEnv.size - 2) {
            val energy   = fullEnv[i]
            val rise     = max(0f, fullEnv[i] - fullEnv[i - 1])
            val localAvg = (fullEnv[i-2] + fullEnv[i-1] + fullEnv[i+1] + fullEnv[i+2]) / 4f
            val contrast = max(0f, energy - localAvg)
            scoreArray[i] = energy * 0.50f + rise * 0.30f + contrast * 0.20f
        }

        val scoreList = scoreArray.toList().filter { it > 0f }
        if (scoreList.isEmpty()) return emptyList()

        val envMean  = scoreList.average().toFloat()
        val envStd   = sqrt(scoreList.fold(0f) { acc, v -> acc + (v - envMean) * (v - envMean) } / scoreList.size)
        val cv       = if (envMean > 0f) envStd / envMean else 0f
        val peakScore = scoreList.max()
        val peakRatio = if (envMean > 0f) peakScore / envMean else 0f

        if (cv < CLIMAX_MIN_CV || peakRatio < CLIMAX_MIN_PEAK_RATIO) {
            Log.d(TAG, "climax skip: CV=${"%.3f".format(cv)} peakRatio=${"%.2f".format(peakRatio)} → no climax")
            return emptyList()
        }
        Log.d(TAG, "climax CV=${"%.3f".format(cv)} peakRatio=${"%.2f".format(peakRatio)} → proceed detection")

        val candidates = ArrayList<PeakCandidate>()
        for (i in 2 until scoreArray.size - 2) {
            val score = scoreArray[i]
            if (score <= 0f) continue
            val isLocalPeak = score >= scoreArray[i-1] && score >= scoreArray[i-2] &&
                    score >= scoreArray[i+1] && score >= scoreArray[i+2]
            if (isLocalPeak) candidates += PeakCandidate(tMs = i.toLong() * HOP_MS, score = score)
        }
        if (candidates.isEmpty()) return emptyList()

        val sortedScores = scoreList.sorted()
        val p90 = sortedScores[(sortedScores.lastIndex * 0.90f).toInt().coerceIn(0, sortedScores.lastIndex)]

        val strongCandidates = candidates
            .filter { it.score >= p90 * 1.18f && it.score >= envMean + envStd * 1.30f }
            .sortedByDescending { it.score }

        if (strongCandidates.isEmpty()) return emptyList()

        val minGapMs = max(800L, beatMs * 4L)
        val selected = ArrayList<PeakCandidate>()
        for (c in strongCandidates) {
            if (selected.none { abs(it.tMs - c.tMs) < minGapMs }) selected += c
            if (selected.size >= 3) break
        }

        return selected.sortedBy { it.tMs }.map { it.tMs.coerceIn(0L, durationMs) }
    }

    // =========================================================================
    // Beat grid building
    // =========================================================================

    private fun buildSectionBeatGrid(section: Section, actualBeats: List<Long>): List<Long> {
        if (section.endMs <= section.startMs || section.beatMs <= 0L) return emptyList()

        val expectedBeats = estimateBeatCount(section.startMs, section.endMs, section.beatMs)
        val minActualRequired = (expectedBeats * ACTUAL_BEAT_USE_RATIO).toInt().coerceAtLeast(2)

        if (actualBeats.size >= minActualRequired) {
            Log.d(TAG, "beatGrid section=${section.type} actualBeats=${actualBeats.size} " +
                    "expected=$expectedBeats → using actual beats (with gap fill)")
            return fillBeatGaps(actualBeats.sorted(), section.beatMs, section.endMs)
        }

        val grid = ArrayList<Long>()
        var t = section.startMs
        while (t < section.endMs) { grid += t; t += section.beatMs }

        if (actualBeats.isEmpty()) {
            Log.d(TAG, "beatGrid section=${section.type} no actualBeats → pure grid size=${grid.size}")
            return grid
        }

        val snapMs  = section.beatMs / 4L
        val snapped = grid.map { g ->
            val closest = actualBeats.minByOrNull { abs(it - g) }
            if (closest != null && abs(closest - g) <= snapMs) closest else g
        }
        Log.d(TAG, "beatGrid section=${section.type} gridBeats=${grid.size} " +
                "actualBeats=${actualBeats.size} snapMs=$snapMs → snapped grid")
        return snapped.distinct().sorted()
    }

    private fun fillBeatGaps(beats: List<Long>, beatMs: Long, sectionEndMs: Long): List<Long> {
        if (beats.size < 2 || beatMs <= 0L) return beats

        val gapThreshold = beatMs * 3L / 2L
        val out = ArrayList<Long>(beats.size * 2)
        out += beats.first()

        for (i in 1 until beats.size) {
            val prev = beats[i - 1]; val cur = beats[i]; val gap = cur - prev
            if (gap > gapThreshold) {
                val fillCount = ((gap + beatMs / 2L) / beatMs).toInt() - 1
                if (fillCount > 0) {
                    val step = gap / (fillCount + 1).toLong()
                    for (k in 1..fillCount) {
                        val interpolated = prev + step * k
                        if (interpolated < sectionEndMs) out += interpolated
                    }
                    Log.d(TAG, "beatGapFill prev=${prev}ms cur=${cur}ms gap=${gap}ms beatMs=${beatMs}ms filled=$fillCount beats")
                }
            }
            out += cur
        }
        return out.distinct().sorted()
    }

    // =========================================================================
    // Frame building
    // =========================================================================

    private fun buildFramesFromSections(
        @Suppress("UNUSED_PARAMETER") musicId: Int,
        palette: Palette,
        sections: List<Section>,
        beatTimes: List<Long>,
        durationMs: Long,
        climaxMoments: List<Long>,
        isBalladMode: Boolean = false
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimes.size * 4 + sections.size + 8)

        fun putFrame(
            t: Long, payload: ByteArray, section: Section, frameType: String, engine: FgEngine,
            fg: LSColor? = null, bg: LSColor? = null, transit: Int? = null,
            period: Int? = null, randomDelay: Int? = null, note: String? = null
        ) {
            if (t < 0L) return
            if (frameMap.containsKey(t)) {
                Log.w(TAG, "timeline overwrite t=${t}ms type=$frameType section=${section.type} engine=$engine source=${section.source}")
            }
            frameMap[t] = payload
            logTimelineFrame(t, section, frameType, engine, fg, bg, transit, period, randomDelay, note)
        }

        fun isNearClimax(tMs: Long) = climaxMoments.any { abs(it - tMs) <= CLIMAX_WINDOW_HALF_MS }

        data class RepeatKey(
            val engine: FgEngine,
            val fgR: Int, val fgG: Int, val fgB: Int,
            val bgR: Int, val bgG: Int, val bgB: Int,
            val period: Int, val randomDelay: Int
        )
        var lastRepeatKey: RepeatKey? = null

        if (!frameMap.containsKey(0L)) {
            frameMap[0L] = buildOffPayload()
            Log.d(TAG, "timeline t=0ms OFF (always)")
        }

        var prevSectionEndMs = 0L
        val sameTypeCountMap = mutableMapOf<SectionType, Int>()

        for ((index, section) in sections.withIndex()) {
            val sameTypeIdx = sameTypeCountMap.getOrDefault(section.type, 0)
            sameTypeCountMap[section.type] = sameTypeIdx + 1
            lastRepeatKey = null

            val interSectionGapMs = if (index > 0) (section.startMs - prevSectionEndMs).coerceAtLeast(0L) else 0L
            val insertTransitionBreath = interSectionGapMs >= SECTION_GAP_BREATH_THRESHOLD_MS &&
                    section.engine != FgEngine.BREATH && section.engine != FgEngine.OFF_TRANSIT

            val actualSectionBeats = beatTimes.filter { it >= section.startMs && it < section.endMs }
            val effectiveSectionBeats = buildSectionBeatGrid(section, actualSectionBeats)

            Log.d(TAG, "section timeline idx=$index type=${section.type} range=${section.startMs}~${section.endMs} " +
                    "section.beats=${section.beats} actualSectionBeats=${actualSectionBeats.size} " +
                    "gridSectionBeats=${effectiveSectionBeats.size} engine=${section.engine} source=${section.source}")

            if (section.engine == FgEngine.OFF_TRANSIT) {
                putFrame(section.startMs, buildOffPayload(), section, "SECTION_OFF", FgEngine.OFF_TRANSIT, transit = ON_TRANSIT)
                prevSectionEndMs = section.endMs; continue
            }

            if (section.engine == FgEngine.BREATH) {
                val (fg, bg) = colorsForEngine(palette, section.engine, sameTypeIdx)
                // VERSE: randomDelay=0 (비트 동기 우선) / 나머지(BRIDGE 등): randomDelay=5 (파도 효과 유지)
                val breathSectionDelay = if (section.type == SectionType.VERSE) 0 else 5
                putFrame(section.startMs, buildPayload(section.engine, fg, bg, section.beatMs),
                    section, "SECTION_START", FgEngine.BREATH,
                    fg = fg, bg = bg, period = msToBreathPeriod(section.beatMs), randomDelay = breathSectionDelay)
                prevSectionEndMs = section.endMs; continue
            }

            if (effectiveSectionBeats.isEmpty()) {
                val (fg, bg) = colorsForEngine(palette, section.engine, sameTypeIdx)
                val rotateTransit = if (section.engine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0
                putFrame(section.startMs, buildPayload(section.engine, fg, bg, section.beatMs,
                    rotateTransit = rotateTransit),
                    section, "SECTION_START", section.engine, fg = fg, bg = bg,
                    transit = when (section.engine) {
                        FgEngine.ON_PULSE          -> ON_TRANSIT
                        FgEngine.ON_TRANSIT_ROTATE -> rotateTransit.takeIf { it > 0 }
                        else                       -> null
                    },
                    period = when (section.engine) {
                        FgEngine.STROBE -> msToStrobePeriod(section.beatMs)
                        else -> null
                    }, note = "no-effective-beats")
                continue
            }

            val firstBeat  = effectiveSectionBeats.first()
            val coverGapMs = firstBeat - section.startMs

            if (insertTransitionBreath) {
                val mFg = palette.white; val mBg = palette.breathSet.bg
                putFrame(section.startMs, buildPayload(FgEngine.BREATH, mFg, mBg, section.beatMs),
                    section, "TRANSITION_BREATH", FgEngine.BREATH,
                    fg = mFg, bg = mBg, period = msToBreathPeriod(section.beatMs), randomDelay = 5,
                    note = "gap=${interSectionGapMs}ms transition-marker")
                Log.d(TAG, "transition breath: idx=$index t=${section.startMs}ms gap=${interSectionGapMs}ms")

            } else if (coverGapMs > 0L && section.type != SectionType.INTRO) {
                val longCoverThresholdMs = section.beatMs * 3L / 2L
                if (coverGapMs <= longCoverThresholdMs) {
                    val coverEngine = when (section.engine) {
                        FgEngine.ON_TRANSIT_ROTATE, FgEngine.STROBE -> FgEngine.BREATH
                        else -> section.engine
                    }
                    val (cvFg, cvBg) = colorsForEngine(palette, coverEngine, sameTypeIdx)
                    putFrame(section.startMs, buildPayload(coverEngine, cvFg, cvBg, section.beatMs),
                        section, "SECTION_COVER", coverEngine, fg = cvFg, bg = cvBg,
                        transit = if (coverEngine == FgEngine.ON_PULSE) ON_TRANSIT else null,
                        period = if (coverEngine == FgEngine.BREATH) msToBreathPeriod(section.beatMs) else null,
                        randomDelay = if (coverEngine == FgEngine.BREATH) 5 else null,
                        note = "section-cover gap=${coverGapMs}ms sectionEngine=${section.engine.name}")
                } else {
                    var fillT = section.startMs; var fillIdx = 0
                    val beatEngineForFill = if (section.type == SectionType.BRIDGE)
                        bridgePhaseEngine(0, section.beats, section.beatMs, section.relScore, isBalladMode)
                    else section.engine
                    while (fillT < firstBeat) {
                        val (cvFg, cvBg) = colorsForEngine(palette, beatEngineForFill, sameTypeIdx, fillIdx, section.type)
                        val fillRotateTransit = if (section.engine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                            ON_ROTATE_BALLAD_TRANSIT else 0
                        putFrame(fillT, buildPayload(section.engine, cvFg, cvBg, section.beatMs,
                            rotateTransit = fillRotateTransit),
                            section, if (fillIdx == 0) "SECTION_COVER" else "SECTION_COVER_FILL", section.engine,
                            fg = cvFg, bg = cvBg,
                            transit = when (section.engine) {
                                FgEngine.ON_PULSE          -> ON_TRANSIT
                                FgEngine.ON_TRANSIT_ROTATE -> fillRotateTransit.takeIf { it > 0 }
                                else                       -> null
                            },
                            period = when (section.engine) {
                                FgEngine.STROBE -> msToStrobePeriod(section.beatMs)
                                else -> null
                            }, note = "section-cover-fill gap=${coverGapMs}ms fillIdx=$fillIdx sectionEngine=${section.engine.name}")
                        if (beatEngineForFill == FgEngine.ON_PULSE) {
                            val offT = min(firstBeat, fillT + section.beatMs * 3L / 10L)
                            if (offT > fillT)
                                putFrame(offT, buildPayload(FgEngine.ON_PULSE, cvBg, cvBg, section.beatMs),
                                    section, "SECTION_COVER_BG", FgEngine.ON_PULSE,
                                    fg = cvBg, transit = ON_TRANSIT, note = "cover-restore fillIdx=$fillIdx")
                        }
                        fillT += section.beatMs; fillIdx++
                    }
                    Log.d(TAG, "section-cover long gap=${coverGapMs}ms filled=$fillIdx beats")
                }
            }

            // STROBE 섹션은 섹션 전체 범위 기준으로 nearClimax를 한 번만 판정
            // → 섹션 중간에 BREATH→STROBE가 갑자기 전환되는 현상 방지
            val sectionNearClimax: Boolean = if (section.engine == FgEngine.STROBE) {
                climaxMoments.any { climax ->
                    climax + CLIMAX_WINDOW_HALF_MS >= section.startMs &&
                            climax - CLIMAX_WINDOW_HALF_MS <= section.endMs
                }
            } else false

            for ((beatIndex, t) in effectiveSectionBeats.withIndex()) {
                if (beatIndex == 0 && section.type == SectionType.INTRO) {
                    val (introFg, _) = colorsForEngine(palette, FgEngine.BREATH, sameTypeIdx)
                    putFrame(section.startMs, buildPayload(FgEngine.BREATH, introFg, LSColor(0,0,0), section.beatMs),
                        section, "INTRO_BREATH_START", FgEngine.BREATH, fg = introFg,
                        period = msToBreathPeriod(section.beatMs), randomDelay = 3,
                        note = if (actualSectionBeats.isEmpty()) "grid-intro" else "actual-intro")
                } else {
                    // STROBE 섹션: 섹션 단위 판정 사용 / 그 외: 비트 단위 판정
                    val nearClimax = if (section.engine == FgEngine.STROBE) sectionNearClimax
                    else isNearClimax(t)

                    val beatEngine = if (section.type == SectionType.BRIDGE)
                        bridgePhaseEngine(beatIndex, effectiveSectionBeats.size, section.beatMs, section.relScore, isBalladMode)
                    else section.engine

                    val effectiveBeatEngine = when {
                        beatEngine == FgEngine.STROBE && !nearClimax -> FgEngine.BREATH
                        else -> beatEngine
                    }

                    val (fg, bg) = colorsForEngine(palette, effectiveBeatEngine, sameTypeIdx, beatIndex, section.type)
                    val bgNonNull: LSColor = bg ?: LSColor(0, 0, 0)

                    val beatPeriod = when (effectiveBeatEngine) {
                        FgEngine.STROBE -> 1
                        FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                        else            -> null
                    }
                    val beatRandomDelay = when {
                        effectiveBeatEngine == FgEngine.STROBE && nearClimax -> 2
                        effectiveBeatEngine == FgEngine.ON_TRANSIT_ROTATE    -> null
                        effectiveBeatEngine == FgEngine.ON_PULSE             -> null
                        // VERSE BREATH: randomDelay=0 / BRIDGE BREATH: randomDelay=5 (파도 효과 유지)
                        effectiveBeatEngine == FgEngine.BREATH &&
                                section.type == SectionType.VERSE            -> 0
                        effectiveBeatEngine == FgEngine.BREATH               -> 5
                        else                                                  -> null
                    }

                    // ON ROTATE: 발라드 모드이면 transit 적용 (부드러운 색 전환)
                    val beatRotateTransit = if (effectiveBeatEngine == FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                        ON_ROTATE_BALLAD_TRANSIT else 0

                    val skipOnPulseOdd = (beatEngine == FgEngine.ON_PULSE && beatIndex % 2 != 0)

                    val skipRepeat = if (skipOnPulseOdd) {
                        true
                    } else if (effectiveBeatEngine == FgEngine.ON_TRANSIT_ROTATE
                        || effectiveBeatEngine == FgEngine.STROBE
                        || effectiveBeatEngine == FgEngine.BREATH) {
                        val key = RepeatKey(effectiveBeatEngine,
                            fg.r, fg.g, fg.b, bgNonNull.r, bgNonNull.g, bgNonNull.b,
                            beatPeriod ?: 0, beatRandomDelay ?: 0)
                        val dup = (key == lastRepeatKey); lastRepeatKey = key; dup
                    } else {
                        lastRepeatKey = null; false
                    }

                    if (skipRepeat) {
                        Log.d(TAG, "timeline skip-repeat t=${t}ms section=${section.type} " +
                                "engine=${effectiveBeatEngine.name} beatIndex=$beatIndex → same fg/bg/period")
                    } else {
                        putFrame(t, buildPayload(effectiveBeatEngine, fg, bg, section.beatMs, beatPeriod,
                            beatRandomDelay ?: 0, rotateTransit = beatRotateTransit),
                            section, "BEAT_FG", effectiveBeatEngine, fg = fg, bg = bg,
                            transit = when (effectiveBeatEngine) {
                                FgEngine.ON_PULSE          -> 0
                                FgEngine.ON_TRANSIT_ROTATE -> beatRotateTransit.takeIf { it > 0 }
                                else                       -> null
                            },
                            period = beatPeriod, randomDelay = beatRandomDelay,
                            note = buildString {
                                append("beatIndex=$beatIndex")
                                append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                                if (nearClimax) append(" [climax]")
                                if (section.type == SectionType.BRIDGE) append(" [bridge-phase=${beatEngine.name}]")
                            })
                    }

                    if (beatEngine == FgEngine.ON_PULSE && !skipOnPulseOdd) {
                        val isWhiteFg = (fg.r == 255 && fg.g == 255 && fg.b == 255)
                        val holdMs = minOf(ON_PULSE_ACCENT_HOLD_MS * 2L, section.beatMs * 44L / 100L).coerceAtLeast(60L)
                        val offT   = minOf(section.endMs - 1L, t + holdMs)
                        if (offT > t) {
                            putFrame(offT, LSEffectPayload.Effects.on(color = bg, transit = ON_TRANSIT).toByteArray(),
                                section, "BEAT_BG", FgEngine.ON_PULSE, fg = bg, transit = ON_PULSE_BG_TRANSIT,
                                note = buildString {
                                    val cycleMs = section.beatMs * 2L
                                    val gapMs   = cycleMs - holdMs
                                    val dutyPct = holdMs * 100L / cycleMs
                                    append("restore beatIndex=$beatIndex hold=${holdMs}ms gap=${gapMs}ms duty=${dutyPct}% bgTransit=${ON_PULSE_BG_TRANSIT * 10}ms")
                                    append(if (isWhiteFg) " [base-long]" else " [accent-short]")
                                    append(if (actualSectionBeats.isEmpty()) " grid-beat" else " actual-beat")
                                })
                        }
                    }
                }
            }
            prevSectionEndMs = section.endMs
        }

        if (frameMap.keys.none { it >= durationMs }) {
            val endSection = Section(durationMs, durationMs, SectionType.END, FgEngine.OFF_TRANSIT, 0L, 0, "final-off", ChangeLevel.STRONG)
            putFrame(durationMs, buildOffPayload(), endSection, "FINAL_OFF", FgEngine.OFF_TRANSIT, transit = ON_TRANSIT)
        }

        Log.d(TAG, "timeline final uniqueFrames=${frameMap.size}")
        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    // =========================================================================
    // Payload builders
    // =========================================================================

    private fun buildPayload(
        engine: FgEngine, fg: LSColor, bg: LSColor, beatMs: Long,
        period: Int? = null, randomDelay: Int = 0, rotateTransit: Int = 0
    ): ByteArray = when (engine) {
        FgEngine.ON_PULSE -> {
            // FG transit=0: white/color 모두 즉시 켜짐 → 강한 비트 임팩트
            LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
        }
        FgEngine.BLINK ->
            LSEffectPayload.Effects.blink(period = period ?: msToBlinkPeriod(beatMs),
                color = fg, backgroundColor = bg, randomDelay = randomDelay).toByteArray()
        FgEngine.STROBE ->
            LSEffectPayload.Effects.strobe(period = period ?: msToStrobePeriod(beatMs),
                color = fg, backgroundColor = bg, randomDelay = randomDelay).toByteArray()
        FgEngine.BREATH ->
            LSEffectPayload.Effects.breath(period = period ?: msToBreathPeriod(beatMs),
                color = fg, backgroundColor = bg,
                randomDelay = randomDelay.takeIf { it > 0 } ?: 5).toByteArray()
        FgEngine.ON_TRANSIT_ROTATE ->
            // rotateTransit=0: 즉시 색 전환 (일반곡), >0: 부드러운 페이드 전환 (발라드/포크)
            LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
        FgEngine.OFF_TRANSIT -> buildOffPayload()
    }

    private fun buildOffPayload(): ByteArray =
        LSEffectPayload.Effects.off(transit = ON_TRANSIT).toByteArray()

    // =========================================================================
    // 발라드 / 조용한 포크 감지
    // =========================================================================

    /**
     * 오디오 특성 기반으로 조용한 발라드/포크곡 여부를 판단한다.
     *
     * 조건:
     * - beatMs >= BALLAD_BEAT_MS_THRESHOLD : 느린 템포 (≈109 BPM 이하)
     * - avgFullEnergy < BALLAD_AVG_ENERGY_MAX : 평균 에너지가 낮음 (0.50f)
     *   → lowFraction(low÷full) 방식은 모든 곡에서 ≈0.95로 수렴하여 판별 불가 → 폐기
     *
     * 클라이맥스 횟수는 판정에서 제외:
     * 발라드 모드가 확정되면 호출부에서 effectiveClimaxMoments=empty 로 치환하여
     * 클라이맥스 연출을 완전 차단한다 (별도 조건 체크 불필요).
     */
    private fun isQuietFolkOrBallad(
        beatMs: Long,
        avgFullEnergy: Float
    ): Boolean = beatMs >= BALLAD_BEAT_MS_THRESHOLD &&
            avgFullEnergy < BALLAD_AVG_ENERGY_MAX

    // =========================================================================
    // Bridge phase engine
    // =========================================================================

    private fun bridgePhaseEngine(
        beatIndex: Int, totalBeats: Int,
        @Suppress("UNUSED_PARAMETER") beatMs: Long, relScore: Float = 0.5f,
        isBalladMode: Boolean = false
    ): FgEngine {
        // 발라드 모드: bridge 전 구간 BREATH (ON ROTATE·STROBE 없음)
        if (isBalladMode) return FgEngine.BREATH
        if (totalBeats <= 0) return FgEngine.STROBE
        val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)
        return when {
            totalBeats < 8  -> FgEngine.STROBE
            totalBeats < 16 -> {
                val phase = beatIndex.toFloat() / totalBeats
                if (phase < strobeEntry) FgEngine.BREATH else FgEngine.ON_TRANSIT_ROTATE
            }
            else -> {
                val phase = beatIndex.toFloat() / totalBeats
                val rotateEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
                when {
                    phase < rotateEntry -> FgEngine.BREATH
                    else                -> FgEngine.ON_TRANSIT_ROTATE
                }
            }
        }
    }

    // =========================================================================
    // Period helpers
    // =========================================================================

    private fun msToBlinkPeriod(beatMs: Long)  = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToStrobePeriod(beatMs: Long) = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long) = (beatMs / 20L).toInt().coerceIn(1, 255)

    // =========================================================================
    // Color helpers
    // =========================================================================

    private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f

    private fun buildPalette(seed: Int): Palette {
        val rawHue  = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val baseHue = (((rawHue % 360) + 360) % 360).toFloat()

        val cMain  = hsvToColor(baseHue,                 1.00f, 1.00f)
        val cStep1 = hsvToColor(wrap360(baseHue +  60f), 1.00f, 1.00f)
        val cStep2 = hsvToColor(wrap360(baseHue -  60f), 0.85f, 0.95f)
        val cStep3 = hsvToColor(wrap360(baseHue - 120f), 1.00f, 1.00f)
        val cDeep  = hsvToColor(baseHue,                 1.00f, 0.48f)
        val black  = LSColor(0, 0, 0)
        val white  = LSColor(255, 255, 255)
        val colorGroup = listOf(cMain, cStep1, cStep2, cStep3)
        val cMainLuma  = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain

        return Palette(
            black       = black,
            white       = white,
            onPulseSets = listOf(ColorSet(white, patternABg), ColorSet(cMain, black)),
            blinkSets   = listOf(ColorSet(cMain, black), ColorSet(cStep1, black)),
            strokeSets  = listOf(ColorSet(white, black), ColorSet(white, black)),
            breathSet   = ColorSet(white, patternABg),
            bridgeSets  = listOf(ColorSet(cStep2, black), ColorSet(cMain, black)),
            chorusBg    = cDeep,
            colorGroup  = colorGroup
        )
    }

    private fun colorsForEngine(
        palette: Palette, engine: FgEngine, sectionIndex: Int,
        beatIndex: Int = 0, sectionType: SectionType = SectionType.VERSE
    ): Pair<LSColor, LSColor> {
        val isPatternA = (sectionIndex % 2 == 0)
        val effectiveColors: List<LSColor> = when (sectionType) {
            SectionType.CHORUS -> listOf(palette.white) + palette.colorGroup.take(3)
            SectionType.VERSE  -> palette.colorGroup.take(3)
            SectionType.BRIDGE -> listOf(
                palette.colorGroup.getOrElse(2) { palette.colorGroup[0] },
                palette.colorGroup[0],
                palette.white
            )
            else -> palette.colorGroup
        }
        val groupColor   = effectiveColors[beatIndex   % effectiveColors.size]
        val sectionColor = effectiveColors[sectionIndex % effectiveColors.size]

        return when (engine) {
            FgEngine.ON_PULSE ->
                if (isPatternA) palette.white to palette.onPulseSets[0].bg
                else            sectionColor  to palette.black
            FgEngine.BLINK, FgEngine.ON_TRANSIT_ROTATE -> groupColor to palette.black
            FgEngine.STROBE  -> palette.white to palette.black
            FgEngine.BREATH  -> palette.breathSet.fg to palette.breathSet.bg
            else ->
                if (isPatternA) palette.bridgeSets[0].fg to palette.black
                else            groupColor               to palette.black
        }
    }

    // =========================================================================
    // Logging helpers
    // =========================================================================

    private fun colorToString(c: LSColor) = "(${c.r},${c.g},${c.b})"

    private fun logTimelineFrame(
        t: Long, section: Section, frameType: String, engine: FgEngine,
        fg: LSColor? = null, bg: LSColor? = null, transit: Int? = null,
        period: Int? = null, randomDelay: Int? = null, note: String? = null
    ) {
        val extra = buildString {
            fg?.let          { append(" fg=${colorToString(it)}")  }
            bg?.let          { append(" bg=${colorToString(it)}")  }
            transit?.let     { append(" transit=$it")              }
            period?.let      { append(" period=$it")               }
            randomDelay?.let { append(" randomDelay=$it")          }
            note?.let        { append(" note=$it")                 }
        }
        Log.d(TAG, "timeline add t=${t}ms type=${frameType}[${engine.name}] section=${section.type} source=${section.source} beats=${section.beats}$extra")
    }

    // =========================================================================
    // Math / analysis helpers
    // =========================================================================

    private fun estimateBeatCount(startMs: Long, endMs: Long, beatMs: Long): Int {
        if (endMs <= startMs || beatMs <= 0L) return 0
        return max(1, ((endMs - startMs) / beatMs).toInt())
    }

    private fun detectFirstMusicStartMs(energyFrames: FloatArray, hopMs: Long): Long {
        if (energyFrames.isEmpty()) return 0L
        val smooth = FloatArray(energyFrames.size)
        for (i in energyFrames.indices) {
            var sum = 0f; var count = 0
            for (k in -2..2) { val j = i + k; if (j in energyFrames.indices) { sum += energyFrames[j]; count++ } }
            smooth[i] = if (count > 0) sum / count else energyFrames[i]
        }
        val noiseWindow = ((1000L / hopMs).toInt()).coerceAtLeast(1).coerceAtMost(smooth.size)
        var noiseSum = 0f
        for (i in 0 until noiseWindow) noiseSum += smooth[i]
        val noiseFloor = noiseSum / noiseWindow.toFloat()
        val threshold  = max(noiseFloor * 2.2f, 0.015f)
        val needRun    = ((250L / hopMs).toInt()).coerceAtLeast(2)
        var run = 0
        for (i in smooth.indices) {
            if (smooth[i] >= threshold) { run++; if (run >= needRun) return (i - run + 1).coerceAtLeast(0) * hopMs }
            else run = 0
        }
        return 0L
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): LSColor {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s; val x = c * (1f - abs((hh / 60f) % 2f - 1f)); val m = v - c
        val (rf, gf, bf) = when {
            hh < 60f  -> Triple(c, x, 0f); hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x); hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c); else      -> Triple(c, 0f, x)
        }
        return LSColor(((rf+m)*255f).toInt().coerceIn(0,255), ((gf+m)*255f).toInt().coerceIn(0,255), ((bf+m)*255f).toInt().coerceIn(0,255))
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
            var sum = 0f; var count = 0
            val s = max(0, i - half); val e = min(src.lastIndex, i + half)
            for (j in s..e) { sum += src[j]; count++ }
            out += if (count == 0) 0f else sum / count.toFloat()
        }
        return out
    }

    fun getVersion(): Int = VERSION
}