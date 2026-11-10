package app.silenttrap.vad

import android.content.Context
import app.silenttrap.audio.AudioConfig
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * Silero via android-vad. The library does not expose a raw probability, so the
 * whisper threshold is applied by boosting the frame before this call and by
 * picking the closest built-in mode. NORMAL is 0.5, which is the sensitive end
 * of the modes that still reject silence.
 */
class SileroSpeechDetector(context: Context) : AutoCloseable {
    private val vad = VadSilero(
        context.applicationContext,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        speechDurationMs = 0,
        silenceDurationMs = 0,
    )

    fun isSpeech(frame: ShortArray, threshold: Float): Boolean {
        require(frame.size == AudioConfig.FRAME_SAMPLES) {
            "Silero expects ${AudioConfig.FRAME_SAMPLES} samples"
        }
        vad.mode = modeFor(threshold)
        return vad.isSpeech(frame)
    }

    override fun close() {
        vad.close()
    }

    private fun modeFor(threshold: Float): Mode = when {
        threshold >= 0.80f -> Mode.VERY_AGGRESSIVE
        threshold >= 0.60f -> Mode.AGGRESSIVE
        else -> Mode.NORMAL
    }
}
