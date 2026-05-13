package com.lightstick.music.core.util

import android.media.AudioManager
import android.media.ToneGenerator

class CountdownSoundPlayer {

    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: Exception) {
        }
    }

    // 짧은 비프 ("띠!")
    fun playShortBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    // 긴 이중 비프 ("띠~이!")
    fun playLongBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}
