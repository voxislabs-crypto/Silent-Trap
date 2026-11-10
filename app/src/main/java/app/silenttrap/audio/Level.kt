package app.silenttrap.audio

import kotlin.math.log10
import kotlin.math.sqrt

object Level {
    fun rms(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var acc = 0.0
        for (sample in frame) {
            val x = sample / 32768.0
            acc += x * x
        }
        return sqrt(acc / frame.size).toFloat()
    }

    fun rms(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var acc = 0.0
        for (sample in frame) acc += sample * sample
        return sqrt(acc / frame.size).toFloat()
    }

    fun peak(frame: FloatArray): Float {
        var peak = 0f
        for (sample in frame) {
            val a = kotlin.math.abs(sample)
            if (a > peak) peak = a
        }
        return peak
    }

    fun dbfs(amplitude: Float): Float {
        if (amplitude < 1e-7f) return -90f
        return (20.0 * log10(amplitude.toDouble())).toFloat()
    }
}
