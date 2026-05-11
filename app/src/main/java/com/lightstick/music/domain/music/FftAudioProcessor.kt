package com.lightstick.music.domain.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.data.model.FrequencyBand
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class FftAudioProcessor(
    private val onFftAnalyzed: (FrequencyBand) -> Unit
) : BaseAudioProcessor() {

    private var sampleRate: Int = 44100

    private var cachedFftSize: Int = -1
    private var cachedFft: FloatFFT_1D? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            sampleRate = inputAudioFormat.sampleRate
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val shortBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)

        val n = samples.size
        if (n == 0) {
            val output = replaceOutputBuffer(size)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val floatSamples = FloatArray(n) { samples[it] / 32768f }

        val fftInput = FloatArray(n * 2) { 0f }
        for (i in floatSamples.indices) fftInput[i * 2] = floatSamples[i]

        if (n != cachedFftSize) {
            cachedFft = null
            cachedFft = FloatFFT_1D(n.toLong())
            cachedFftSize = n
        }
        cachedFft!!.realForwardFull(fftInput)

        fun hzToBinIndex(hz: Float): Int =
            ((hz * n / sampleRate).toInt() * 2).coerceIn(0, fftInput.size - 2)

        fun bandPower(fromHz: Float, toHz: Float): Float {
            val from = hzToBinIndex(fromHz)
            val to   = hzToBinIndex(toHz)
            if (to <= from) return 0f
            var sum = 0.0
            var count = 0
            var i = from
            while (i <= to) {
                val re = fftInput[i]
                val im = fftInput[i + 1]
                sum += re * re + im * im
                count++
                i += 2
            }
            return if (count > 0) (sum / count).toFloat() else 0f
        }

        val bass   = bandPower(20f,   250f)
        val mid    = bandPower(250f,  4000f)
        val treble = bandPower(4000f, 16000f)

        onFftAnalyzed(FrequencyBand(bass, mid, treble))

        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }
}
