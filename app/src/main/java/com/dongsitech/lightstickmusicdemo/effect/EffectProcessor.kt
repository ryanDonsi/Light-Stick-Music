package com.dongsitech.lightstickmusicdemo.effect

import com.dongsitech.lightstickmusicdemo.model.FrequencyBand

class EffectProcessor(
    private val writeQueueManager: (r: Byte, g: Byte, b: Byte, transit: Byte) -> Unit
) {
    private var lastPower = 0f
    private val beatThreshold = 0.25f

    fun process(band: FrequencyBand) {
        val power = band.bass + band.mid + band.treble
        val delta = power - lastPower
        lastPower = power

        val total = power + 1e-6f
        val r = (band.bass / total * 255).toInt().coerceIn(0, 255)
        val g = (band.mid / total * 255).toInt().coerceIn(0, 255)
        val b = (band.treble / total * 255).toInt().coerceIn(0, 255)

        val transitFrames = if (delta > beatThreshold) 1 else 5

        writeQueueManager(r.toByte(), g.toByte(), b.toByte(), transitFrames.toByte())
    }
}

