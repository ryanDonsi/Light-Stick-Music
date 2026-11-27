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
 * 채널을 모노로 섞어 2048 샘플마다(Hann 창) 한 번 분석한다.
 * [shouldAnalyze] 가 false 면(자동 모드 꺼짐, 타임라인 재생 중 등) FFT 자체를 건너뛴다.
 */
@UnstableApi
class FftAudioProcessor(
    private val shouldAnalyze: () -> Boolean = { true },
    private val onFftAnalyzed: (FrequencyBand) -> Unit
) : BaseAudioProcessor() {

    private var sampleRate: Int = 44100
    private var channelCount: Int = 2

    // 고정 크기 분석 창: 2048 샘플(44.1kHz 기준 약 46ms, bin 간격 약 21.5Hz)
    // 예전에는 버퍼 길이(가변)를 그대로 FFT 크기로 썼고, 스테레오 인터리브 샘플을
    // 모노로 취급해 주파수 대역이 채널 수만큼 아래로 밀려 계산됐다.
    private val fft = FloatFFT_1D(FFT_SIZE.toLong())
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val mono = FloatArray(FFT_SIZE)   // 모노 샘플 누적 버퍼
    private var monoFill = 0
    private val fftBuf = FloatArray(FFT_SIZE)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        monoFill = 0
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
        val shorts = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val ch = channelCount
        val frames = shorts.remaining() / ch
        var idx = shorts.position()
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += shorts.get(idx + c)
            idx += ch
            mono[monoFill++] = sum / ch / 32768f
            if (monoFill == FFT_SIZE) {
                analyzeWindow()
                monoFill = 0
            }
        }
    }

    private fun analyzeWindow() {
        for (i in 0 until FFT_SIZE) fftBuf[i] = mono[i] * window[i]
        // realForward 결과: a[2k]=Re[k], a[2k+1]=Im[k] (0<k<n/2), a[0]=Re[0], a[1]=Re[n/2]
        fft.realForward(fftBuf)

        val binHz = sampleRate.toFloat() / FFT_SIZE
        fun bandPower(fromHz: Float, toHz: Float): Float {
            val from = (fromHz / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
            val to   = (toHz / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
            if (to <= from) return 0f
            var sum = 0.0
            for (k in from..to) {
                val re = fftBuf[2 * k]
                val im = fftBuf[2 * k + 1]
                sum += re * re + im * im
            }
            return (sum / (to - from + 1)).toFloat()
        }

        // 청각적으로 의미 있는 주파수 대역 분할
        val bass   = bandPower(20f,   250f)   // 저음
        val mid    = bandPower(250f,  4000f)  // 중음
        val treble = bandPower(4000f, 16000f) // 고음

        onFftAnalyzed(FrequencyBand(bass, mid, treble))
    }

    private companion object {
        const val FFT_SIZE = 2048
    }
}
