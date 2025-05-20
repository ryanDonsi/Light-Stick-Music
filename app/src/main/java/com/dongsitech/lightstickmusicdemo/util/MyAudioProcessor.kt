package com.dongsitech.lightstickmusicdemo.util

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.sqrt

@androidx.media3.common.util.UnstableApi
class MyAudioProcessor(
    private val onAnalyze: (LedColorBand) -> Unit
) : AudioProcessor {

    private var inputEnded = false
    private var isActive = false
    private var audioFormat: AudioProcessor.AudioFormat? = null

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        audioFormat = inputAudioFormat
        isActive = inputAudioFormat.encoding == android.media.AudioFormat.ENCODING_PCM_16BIT
        return if (isActive) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive) return
        val shortBuffer: ShortBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val pcm = ShortArray(shortBuffer.remaining())
        Log.d("MyAudioProcessor", "queueInput called with ${inputBuffer.remaining()} bytes")
        shortBuffer.get(pcm)

        analyzePCM(pcm)
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun isEnded(): Boolean = inputEnded

    override fun flush() {
        inputEnded = false
    }

    override fun reset() {
        flush()
        isActive = false
        audioFormat = null
    }

    override fun getOutput(): ByteBuffer = EMPTY_BUFFER

    companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).apply {
            position(0)
            limit(0)
        }
    }

    private fun analyzePCM(samples: ShortArray) {
        val windowSize = 1024
        if (samples.size < windowSize) return

        val fftInput = DoubleArray(windowSize) { i -> samples.getOrElse(i) { 0 }.toDouble() }
        val real = fftInput.copyOf()
        val imag = DoubleArray(windowSize)

        fft(real, imag)

        val magnitudes = real.zip(imag) { r, i -> sqrt(r * r + i * i) }

        val treble = averageBand(magnitudes, 512, 1023)
        val mid = averageBand(magnitudes, 170, 511)
        val bass = averageBand(magnitudes, 0, 169)

        val red = (bass * 255).coerceIn(0.0, 255.0).toInt()
        val green = (mid * 255).coerceIn(0.0, 255.0).toInt()
        val blue = (treble * 255).coerceIn(0.0, 255.0).toInt()
        val blink = (bass + mid + treble) > 1.5

        onAnalyze(LedColorBand(red, green, blue, blink))
    }

    private fun averageBand(data: List<Double>, start: Int, end: Int): Double {
        val safeStart = start.coerceAtMost(data.size - 1)
        val safeEnd = end.coerceAtMost(data.size - 1)
        val sub = data.subList(safeStart, safeEnd + 1)
        return sub.average()
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        val levels = log2(n)
        if (1 shl levels != n) throw IllegalArgumentException("Length is not power of 2")

        val cosTable = DoubleArray(n / 2) { i -> kotlin.math.cos(2.0 * Math.PI * i / n) }
        val sinTable = DoubleArray(n / 2) { i -> kotlin.math.sin(2.0 * Math.PI * i / n) }

        val rev = IntArray(n)
        for (i in 0 until n) {
            rev[i] = rev[i shr 1] shr 1 or ((i and 1) shl (levels - 1))
            if (i < rev[i]) {
                val tempReal = real[i]
                real[i] = real[rev[i]]
                real[rev[i]] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[rev[i]]
                imag[rev[i]] = tempImag
            }
        }

        var size = 2
        while (size <= n) {
            val halfsize = size / 2
            val tablestep = n / size
            for (i in 0 until n step size) {
                var k = 0
                for (j in i until i + halfsize) {
                    val l = j + halfsize
                    val tpre = real[l] * cosTable[k] + imag[l] * sinTable[k]
                    val tpim = -real[l] * sinTable[k] + imag[l] * cosTable[k]
                    real[l] = real[j] - tpre
                    imag[l] = imag[j] - tpim
                    real[j] += tpre
                    imag[j] += tpim
                    k += tablestep
                }
            }
            size *= 2
        }
    }

    private fun log2(n: Int): Int = Integer.SIZE - n.countLeadingZeroBits() - 1
}
