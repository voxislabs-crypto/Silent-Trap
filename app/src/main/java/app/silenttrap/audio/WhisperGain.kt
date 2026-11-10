package app.silenttrap.audio

import kotlin.math.tanh

/**
 * Whisper path.
 *
 * Silero's packaged modes bottom out at a probability of 0.5 (NORMAL). Quiet speech
 * often never reaches that score because the waveform is near zero. Boosting a copy
 * of the frame up toward about -20 dBFS lets the same model hear a whisper without
 * lowering the model into "everything is speech."
 *
 * Frames below about -62 dBFS are left alone so digital silence is not amplified
 * into a trigger. Anything louder and still quiet is boosted toward -20 dBFS,
 * capped by the sensitivity slider. The saved file gets a separate user gain
 * plus optional makeup.
 */
object WhisperGain {
    const val ANALYSIS_TARGET = 0.10f

    fun maxAnalysisGain(threshold: Float): Float {
        val t = threshold.coerceIn(0.10f, 0.90f)
        return (7f / t).coerceIn(3f, 32f)
    }

    fun analysisGain(rms: Float, threshold: Float): Float {
        val maxGain = maxAnalysisGain(threshold)
        if (rms < EnergySpeech.absoluteFloor) return 1f
        if (rms >= ANALYSIS_TARGET) return 1f
        val needed = ANALYSIS_TARGET / rms.coerceAtLeast(1e-5f)
        return needed.coerceIn(1f, maxGain)
    }

    fun softClip(sample: Float): Float {
        val drive = 1.15f
        val norm = tanh(drive)
        return (tanh(sample * drive) / norm).coerceIn(-1f, 1f)
    }

    fun boostShorts(frame: ShortArray, gain: Float): ShortArray {
        if (gain == 1f) return frame.copyOf()
        val out = ShortArray(frame.size)
        for (i in frame.indices) {
            val shaped = softClip(frame[i] / 32768f * gain)
            out[i] = (shaped * 32767f).toInt().toShort()
        }
        return out
    }
}
