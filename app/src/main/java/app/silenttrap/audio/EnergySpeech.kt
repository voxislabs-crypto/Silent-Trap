package app.silenttrap.audio

import kotlin.math.pow

/**
 * Backup detector for whispers Silero still misses after the analysis boost.
 * A frame counts only when it rises above the tracked room floor. Stationary
 * noise becomes the floor and does not retrigger.
 */
object EnergySpeech {
    /** About -62 dBFS. Below this is mic self-noise, not a whisper. */
    val absoluteFloor: Float = 10.0.pow(-62.0 / 20.0).toFloat()

    fun margin(threshold: Float): Float = 1.15f + threshold.coerceIn(0.1f, 0.9f) * 5.5f

    fun isHot(rms: Float, threshold: Float, noiseFloor: Float): Boolean {
        if (rms < absoluteFloor) return false
        return rms > noiseFloor * margin(threshold)
    }
}

class NoiseFloorTracker {
    var floor: Float = 0.02f
        private set

    private var primed = 0

    fun observe(rms: Float, speechActive: Boolean) {
        if (primed < 16 && !speechActive) {
            floor = if (primed == 0) rms.coerceAtLeast(1e-5f) else floor * 0.8f + rms * 0.2f
            primed++
            floor = floor.coerceIn(1e-5f, 0.2f)
            return
        }
        if (!speechActive) {
            floor = if (rms < floor) {
                floor * 0.85f + rms * 0.15f
            } else {
                floor * 0.995f + rms * 0.005f
            }
        }
        floor = floor.coerceIn(1e-5f, 0.2f)
    }

    fun reset() {
        floor = 0.02f
        primed = 0
    }
}
