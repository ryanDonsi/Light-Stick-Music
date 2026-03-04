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
import kotlin.math.sqrt

/**
 * ✅ AutoTimelineGeneratorV2
 *
 * 목표:
 * - 곡 단위 테마 팔레트(3~5색 + white/black 포함) 고정
 * - 곡 흐름(에너지/온셋 밀도)에 따라 stepMs, strobeMinGap 유동
 * - Role(CALM/GROOVE/BUILD/CLIMAX/BREAK) 기반 Base 패턴(BREATH/BLINK/OFF) + Accent(STROBE) 적용
 * - 초기화 시 "없는 곡만" 생성 후 저장하는 용도로 사용
 */
class AutoTimelineGeneratorV2 {

    companion object {
        private const val TAG = "AutoTimelineGenV2"
    }

    enum class Role { CALM, GROOVE, BUILD, CLIMAX, BREAK }

    data class ThemePalette(
        val black: LSColor,
        val white: LSColor,
        val whiteTint: LSColor,
        val c1: LSColor,
        val c2: LSColor,
        val c3: LSColor,
        val c4: LSColor? = null
    )

    data class SongParams(
        val stepMs: Long,
        val eLow: Float,
        val eHighEnter: Float,
        val eHighExit: Float,
        val onsetDensity: Float, // onset/sec (rough)
        val strobeMinGapMs: Long
    )

    data class SlotFeature(
        val tMs: Long,
        val e: Float,
        val dE: Float,
        val onset: Boolean
    )

    /**
     * @param paletteSize 3~5 추천 (3: c1,c2,c3 / 4: +c4 / 5 이상은 현재 미사용)
     */
    fun generate(
        musicPath: String,
        musicId: Int,
        paletteSize: Int = 4
    ): List<Pair<Long, ByteArray>> {

        // 1) 1차 스캔: 200ms RMS
        val coarse = decodeRmsSeries(musicPath) // 기본값 200ms
        if (coarse.isEmpty()) return fallback(musicId)

        // 2) 곡 단위 파라미터(템포/스텝/간격/임계값) 계산
        val params = deriveSongParams(coarse)

        // 3) 팔레트 생성 (곡 단위 고정)
        val palette = buildPalette(musicId, paletteSize)

        // 4) 유동 stepMs로 슬롯 생성
        val slots = buildSlots(coarse, params.stepMs)

        // 5) 슬롯 → frames(payload) 생성
        return buildFrames(slots, params, palette)
    }

    // ─────────────────────────────────────────────────────────────
    // Song-level params (thresholds + onset density -> dynamic)
    // ─────────────────────────────────────────────────────────────

    private fun deriveSongParams(rms: List<Float>): SongParams {
        val sorted = rms.sorted()

        fun percentile(p: Float): Float {
            val idx = (sorted.size * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }

        val eLow = percentile(0.30f)
        val eHigh = percentile(0.70f)

        // onset 후보: 단순 RMS 미분 피크
        var onsetCount = 0
        for (i in 2 until rms.size - 2) {
            val d = rms[i] - rms[i - 1]
            if (d > 0.08f && d > (rms[i + 1] - rms[i])) {
                onsetCount++
            }
        }

        val durationSec = max(1f, rms.size * 0.2f) // 200ms hop 기반
        val onsetDensity = onsetCount / durationSec

        // stepMs 유동 (rough)
        val stepMs = when {
            onsetDensity < 0.6f -> 650L
            onsetDensity < 1.2f -> 500L
            onsetDensity < 2.0f -> 400L
            else -> 300L
        }

        // strobe 최소 간격 유동
        val strobeMinGapMs = when {
            onsetDensity < 0.8f -> 2200L
            onsetDensity < 1.5f -> 1500L
            else -> 1200L
        }

        // hysteresis: enter는 높게, exit는 조금 낮게
        val eHighEnter = eHigh
        val eHighExit = (eHigh * 0.85f).coerceAtLeast(eLow)

        return SongParams(
            stepMs = stepMs,
            eLow = eLow,
            eHighEnter = eHighEnter,
            eHighExit = eHighExit,
            onsetDensity = onsetDensity,
            strobeMinGapMs = strobeMinGapMs
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Slotting (dynamic step)
    // ─────────────────────────────────────────────────────────────

    private fun buildSlots(rms200ms: List<Float>, stepMs: Long): List<SlotFeature> {
        val hopMs = 200L
        val durationMs = rms200ms.size * hopMs

        fun rmsAt(tMs: Long): Float {
            val idx = (tMs / hopMs).toInt().coerceIn(0, rms200ms.lastIndex)
            return rms200ms[idx]
        }

        fun onsetAt(tMs: Long): Boolean {
            val idx = (tMs / hopMs).toInt().coerceIn(2, rms200ms.lastIndex - 2)
            val d = rms200ms[idx] - rms200ms[idx - 1]
            return d > 0.08f && d > (rms200ms[idx + 1] - rms200ms[idx])
        }

        val slots = mutableListOf<SlotFeature>()
        var t = 0L
        var prev = rmsAt(0L)

        while (t < durationMs) {
            val e = rmsAt(t)
            val dE = e - prev
            val onset = onsetAt(t)
            slots.add(SlotFeature(tMs = t, e = e, dE = dE, onset = onset))
            prev = e
            t += stepMs
        }

        return slots
    }

    // ─────────────────────────────────────────────────────────────
    // Frames (role + effect selection, dynamic & flow-aware)
    // ─────────────────────────────────────────────────────────────

    private fun buildFrames(
        slots: List<SlotFeature>,
        params: SongParams,
        palette: ThemePalette
    ): List<Pair<Long, ByteArray>> {

        val frames = ArrayList<Pair<Long, ByteArray>>(slots.size)

        // 흐름 유지: 역할 최소 지속 시간
        val minRoleDurationMs = max(6_000L, params.stepMs * 8)

        var role: Role = Role.CALM
        var roleSinceMs = 0L

        var lastStrobeMs = Long.MIN_VALUE
        var prevE = slots.firstOrNull()?.e ?: 0f

        // BUILD 진행도 추적
        var buildStartMs: Long? = null
        val buildLenGuessMs = 10_000L

        for (s in slots) {
            val desired = decideRoleFlowAware(
                current = s,
                prevE = prevE,
                currentRole = role,
                params = params
            )

            // 역할 변화는 최소 구간 유지 후 허용
            if (desired != role && (s.tMs - roleSinceMs) >= minRoleDurationMs) {
                role = desired
                roleSinceMs = s.tMs
            }

            // ✅ buildStartMs!! 제거 (안전)
            val buildProgress = if (role == Role.BUILD) {
                val start = buildStartMs ?: s.tMs.also { buildStartMs = it }
                val elapsed = (s.tMs - start).coerceAtLeast(0L)
                (elapsed.toFloat() / buildLenGuessMs.toFloat()).coerceIn(0f, 1f)
            } else {
                buildStartMs = null
                0f
            }

            val bg = palette.black
            val baseColor = pickColor(role, palette, s.tMs)

            val basePayload = when (role) {
                Role.CALM -> LSEffectPayload.Effects.breath(
                    period = breathPeriod(s.e),
                    color = baseColor,
                    backgroundColor = bg
                ).toByteArray()

                Role.GROOVE -> LSEffectPayload.Effects.blink(
                    period = grooveBlinkPeriod(s.e),
                    color = baseColor,
                    backgroundColor = bg
                ).toByteArray()

                Role.BUILD -> LSEffectPayload.Effects.blink(
                    period = buildBlinkPeriod(buildProgress),
                    color = baseColor,
                    backgroundColor = bg
                ).toByteArray()

                Role.CLIMAX -> LSEffectPayload.Effects.blink(
                    period = climaxBlinkPeriod(s.e),
                    color = baseColor,
                    backgroundColor = bg
                ).toByteArray()

                Role.BREAK -> LSEffectPayload.Effects.off().toByteArray()
            }

            // Accent: CLIMAX + onset only, 최소 간격 유동
            val canStrobe =
                role == Role.CLIMAX &&
                        s.onset &&
                        (s.tMs - lastStrobeMs) >= params.strobeMinGapMs

            val payload = if (canStrobe) {
                lastStrobeMs = s.tMs
                LSEffectPayload.Effects.strobe(
                    period = strobePeriod(params.onsetDensity),
                    color = palette.whiteTint,
                    backgroundColor = bg
                ).toByteArray()
            } else {
                basePayload
            }

            frames.add(s.tMs to payload)
            prevE = s.e
        }

        return frames
    }

    private fun decideRoleFlowAware(
        current: SlotFeature,
        prevE: Float,
        currentRole: Role,
        params: SongParams
    ): Role {
        // BREAK: 급락/무음
        if (prevE > 0.15f && (prevE - current.e) > 0.20f) return Role.BREAK
        if (current.e < 0.08f) return Role.BREAK

        // CLIMAX: hysteresis
        val inClimax = currentRole == Role.CLIMAX
        if (!inClimax && current.e >= params.eHighEnter) return Role.CLIMAX
        if (inClimax && current.e >= params.eHighExit) return Role.CLIMAX

        // CALM
        if (current.e < params.eLow) return Role.CALM

        // BUILD (simple): 상승
        if (current.dE > 0.05f) return Role.BUILD

        return Role.GROOVE
    }

    // ─────────────────────────────────────────────────────────────
    // Palette
    // ─────────────────────────────────────────────────────────────

    private fun buildPalette(musicId: Int, paletteSize: Int): ThemePalette {
        val baseHue = ((musicId * 53) % 360).toFloat()

        val c1 = hsvToRgb(baseHue, 0.85f, 0.95f)
        val c2 = hsvToRgb(wrap360(baseHue + 18f), 0.60f, 1.00f)
        val c3 = hsvToRgb(wrap360(baseHue - 18f), 0.85f, 0.80f)
        val c4 = if (paletteSize >= 4) hsvToRgb(wrap360(baseHue + 30f), 0.75f, 0.90f) else null

        val whiteTint = hsvToRgb(baseHue, 0.15f, 1.00f)

        return ThemePalette(
            black = Colors.BLACK,
            white = Colors.WHITE,
            whiteTint = whiteTint,
            c1 = c1,
            c2 = c2,
            c3 = c3,
            c4 = c4
        )
    }

    private fun pickColor(role: Role, p: ThemePalette, tMs: Long): LSColor =
        when (role) {
            Role.CALM -> if ((tMs / 4000L) % 2L == 0L) p.c3 else p.whiteTint
            Role.GROOVE -> if ((tMs / 4000L) % 2L == 0L) p.c1 else p.c2
            Role.BUILD -> if ((tMs / 2000L) % 2L == 0L) p.c2 else p.c1
            Role.CLIMAX -> p.c1
            Role.BREAK -> p.black
        }

    // ─────────────────────────────────────────────────────────────
    // period rules
    // ─────────────────────────────────────────────────────────────

    private fun breathPeriod(energy: Float): Int =
        clampInt((80f - 30f * energy).toInt(), 45, 80)

    private fun grooveBlinkPeriod(energy: Float): Int =
        clampInt((18f - 8f * energy).toInt(), 12, 16)

    private fun climaxBlinkPeriod(energy: Float): Int =
        clampInt((10f - 6f * energy).toInt(), 6, 9)

    private fun buildBlinkPeriod(buildProgress: Float): Int {
        val p = buildProgress.coerceIn(0f, 1f)
        return (18 + (8 - 18) * p).toInt().coerceIn(8, 18)
    }

    private fun strobePeriod(onsetDensity: Float): Int =
        when {
            onsetDensity < 0.8f -> 6
            onsetDensity < 1.5f -> 5
            else -> 4
        }

    private fun clampInt(v: Int, min: Int, max: Int) = v.coerceIn(min, max)

    // ─────────────────────────────────────────────────────────────
    // HSV utils
    // ─────────────────────────────────────────────────────────────

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

        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return LSColor(r, g, b)
    }

    // ─────────────────────────────────────────────────────────────
    // RMS decode (PCM) -> windowMs RMS series
    // ─────────────────────────────────────────────────────────────

    /**
     * @param windowMs RMS 윈도우 크기(ms). 기본 200ms.
     */
    private fun decodeRmsSeries(path: String, windowMs: Int = 200): List<Float> {
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
            if (audioTrack < 0 || format == null) return emptyList()

            extractor.selectTrack(audioTrack)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val windowSamplesTarget = max(1, sampleRate * windowMs / 1000)

            val rmsList = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            var windowEnergy = 0.0
            var windowSamples = 0

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
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
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
                        // ✅ 람다(run) 내부 continue 사용 금지 → if 분리
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer == null) {
                            codec.releaseOutputBuffer(outIndex, false)
                            continue
                        }

                        val bytes = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outBuffer.get(bytes)

                        // PCM 16bit LE 가정 (대부분의 디코더 출력)
                        var i = 0
                        while (i + 1 < bytes.size) {
                            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                            val s = sample / 32768.0
                            windowEnergy += s * s
                            windowSamples++

                            if (windowSamples >= windowSamplesTarget) {
                                val rms = sqrt(windowEnergy / max(1, windowSamples))
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                                rmsList.add(rms)
                                windowEnergy = 0.0
                                windowSamples = 0
                            }
                            i += 2
                        }

                        codec.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 포맷 변경: 필요하면 여기서 샘플레이트/채널 갱신
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 출력 버퍼 아직 없음
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            return rmsList

        } catch (t: Throwable) {
            runCatching { extractor.release() }
            Log.e(TAG, "decodeRmsSeries failed: ${t.message}")
            return emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Fallback
    // ─────────────────────────────────────────────────────────────

    private fun fallback(musicId: Int, durationMs: Long = 180_000L): List<Pair<Long, ByteArray>> {
        val palette = buildPalette(musicId, 3)
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        var t = 0L
        while (t < durationMs) {
            frames.add(t to LSEffectPayload.Effects.breath(70, palette.c1, palette.black).toByteArray())
            t += 500L
        }
        return frames
    }
}