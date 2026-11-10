package app.silenttrap.audio

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 512
    const val HIGH_PASS_HZ = 120.0
    /** Hard cap so a long conversation cannot grow without bound. Splits overlap by the pre-roll. */
    const val MAX_SEGMENT_MS = 8 * 60 * 1000

    fun msToSamples(sampleRate: Int, ms: Int): Int {
        return (sampleRate.toLong() * ms / 1000L).toInt().coerceAtLeast(0)
    }
}
