package com.lightstick.music.domain.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.data.model.FrequencyBand
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 재생 중인 PCM 을 그대로 통과시키면서 저/중/고음 에너지를 계산한다.
 *
 * 오디오 렌더 스레드에서 호출되므로 버퍼마다 새 배열을 만들지 않고 재사용한다.
 * [shouldAnalyze] 가 false 면(자동 모드 꺼짐, 타임라인 재생 중 등) FFT 자체를 건너뛴다.
 */
@UnstableApi
class FftAudioProcessor(
    private val shouldAnalyze: () -> Boolean = { true },
    private val onFftAnalyzed: (FrequencyBand) -> Unit
) : BaseAudioProcessor() {

    private var sampleRate: Int = 44100

    // FFT 인스턴스/작업 버퍼 캐싱: 동일한 사이즈면 재사용
    private var cachedFftSize: Int = -1
    private var cachedFft: FloatFFT_1D? = null
    private var samples: ShortArray = ShortArray(0)
    private var fftInput: FloatArray = FloatArray(0)

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

        if (shouldAnalyze()) analyze(inputBuffer)

        // PCM 그대로 패스스루 (다음 AudioSink로 넘김)
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }

    private fun analyze(inputBuffer: ByteBuffer) {
        // duplicate(): 원본 버퍼의 position 을 건드리지 않고 읽는다
        val shortBuffer = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val n = shortBuffer.remaining()
        if (n == 0) return

        if (samples.size < n) samples = ShortArray(n)
        shortBuffer.get(samples, 0, n)

        // FFT 복소수 배열 준비 (realForwardFull: [re0, im0, re1, im1, ...])
        if (fftInput.size != n * 2) fftInput = FloatArray(n * 2)
        val fftInput = fftInput
        for (i in 0 until n) {
            fftInput[i * 2] = samples[i] / 32768f
            fftInput[i * 2 + 1] = 0f
        }

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
    }
}
