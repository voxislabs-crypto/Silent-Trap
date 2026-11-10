package app.silenttrap.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Butterworth high-pass. Cuts rumble before it gets multiplied by the whisper gain. */
class BiquadHighPass(
    sampleRate: Double,
    cutoffHz: Double,
) {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        configure(sampleRate, cutoffHz)
    }

    fun configure(sampleRate: Double, cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosine = cos(w0)
        val alpha = sin(w0) / (2.0 * sqrt(0.5))
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosine) / 2.0) / a0
        b1 = (-(1.0 + cosine)) / a0
        b2 = ((1.0 + cosine) / 2.0) / a0
        a1 = (-2.0 * cosine) / a0
        a2 = (1.0 - alpha) / a0
        reset()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(frame: ShortArray) {
        for (i in frame.indices) {
            val x = frame[i] / 32768.0
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            val clipped = y.coerceIn(-1.0, 1.0)
            frame[i] = (clipped * 32767.0).toInt().toShort()
        }
    }
}
