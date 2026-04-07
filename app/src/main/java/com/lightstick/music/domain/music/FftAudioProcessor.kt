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

    // FFT 인스턴스 캐싱: 동일한 사이즈면 재사용
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

        // PCM → Float 변환
        val floatSamples = FloatArray(n) { samples[it] / 32768f }

        // FFT 복소수 배열 준비 (realForwardFull: [re0, im0, re1, im1, ...])
        val fftInput = FloatArray(n * 2) { 0f }
        for (i in floatSamples.indices) fftInput[i * 2] = floatSamples[i]

        // 캐시된 FFT 인스턴스 재사용 (사이즈 변경 시에만 재생성)
        if (n != cachedFftSize) {
            cachedFft = FloatFFT_1D(n.toLong())
            cachedFftSize = n
        }
        cachedFft!!.realForwardFull(fftInput)

        // 샘플레이트 기반 주파수 → bin 인덱스 변환
        // bin k의 중심 주파수 = k * sampleRate / n
        // fftInput에서 bin k의 위치 = k * 2 (실수부), k * 2 + 1 (허수부)
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

        // 청각적으로 의미 있는 주파수 대역 분할
        val bass   = bandPower(20f,   250f)   // 저음
        val mid    = bandPower(250f,  4000f)  // 중음
        val treble = bandPower(4000f, 16000f) // 고음

        onFftAnalyzed(FrequencyBand(bass, mid, treble))

        // 나머지 PCM 그대로 패스스루 (다음 AudioSink로 넘김)
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }
}
