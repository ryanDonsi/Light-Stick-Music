package com.lightstick.music.domain.music.player

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

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val shortBuffer = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)

        // PCM → Float 변환
        val floatSamples = samples.map { it / 32768f }.toFloatArray()

        // FFT를 위해 복소수 배열 준비
        val fftInput = FloatArray(floatSamples.size * 2) { 0f }
        for (i in floatSamples.indices) fftInput[i * 2] = floatSamples[i]

        // FFT 수행
        val fft = FloatFFT_1D(floatSamples.size.toLong())
        fft.realForwardFull(fftInput)

        // 주파수 밴드별 파워 추출 (예시로 대략적 분할)
        val bass = fftInput.slice(2..16 step 2).map { it * it }.average().toFloat()
        val mid = fftInput.slice(18..60 step 2).map { it * it }.average().toFloat()
        val treble = fftInput.slice(62..128 step 2).map { it * it }.average().toFloat()

        val band = FrequencyBand(bass, mid, treble)

        // 콜백 호출
        onFftAnalyzed(band)

        // 나머지 PCM 그대로 패스스루 (다음 AudioSink로 넘김)
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }
}