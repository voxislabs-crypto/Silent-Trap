package app.silenttrap.audio

object SegmentSignalProcessor {
    fun shortsToFloat(samples: ShortArray): FloatArray {
        return FloatArray(samples.size) { samples[it] / 32768f }
    }

    fun applyUserGain(samples: FloatArray, gain: Float): FloatArray {
        val out = FloatArray(samples.size)
        val g = gain.coerceIn(1f, 40f)
        for (i in samples.indices) {
            out[i] = WhisperGain.softClip(samples[i] * g)
        }
        return out
    }

    /**
     * If the whole clip is still quiet after the user gain, lift the peak toward
     * about -6 dBFS. A clip that already has a loud moment is left alone.
     * Extra makeup is capped so noise cannot be multiplied without limit.
     */
    fun whisperMakeup(samples: FloatArray, enabled: Boolean, targetPeak: Float = 0.50f, maxExtra: Float = 8f): FloatArray {
        if (!enabled || samples.isEmpty()) return samples
        val peak = Level.peak(samples)
        if (peak < 1e-4f || peak >= 0.45f) return samples
        val makeup = (targetPeak / peak).coerceIn(1f, maxExtra)
        if (makeup <= 1.01f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = WhisperGain.softClip(samples[i] * makeup)
        }
        return out
    }
}
