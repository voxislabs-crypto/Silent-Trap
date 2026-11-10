package app.silenttrap.audio

object SpeechDecision {
    /**
     * Sensitive settings (the whisper default) accept either detector.
     * Stricter settings require Silero, so a door creak does not open a clip.
     */
    fun frameIsSpeech(silero: Boolean, energyHot: Boolean, threshold: Float): Boolean {
        return if (threshold <= 0.55f) silero || energyHot else silero
    }

    /** Frames of 512 samples are 32 ms at 16 kHz. */
    fun startFrames(threshold: Float): Int = when {
        threshold < 0.40f -> 2
        threshold < 0.70f -> 3
        else -> 5
    }

    fun stopFrames(): Int = 6
}

class SpeechGate {
    private var speechRun = 0
    private var silenceRun = 0
    private var active = false

    fun update(frameSpeech: Boolean, startFrames: Int, stopFrames: Int): Boolean {
        if (frameSpeech) {
            speechRun++
            silenceRun = 0
            if (speechRun >= startFrames) active = true
        } else {
            silenceRun++
            speechRun = 0
            if (silenceRun >= stopFrames) active = false
        }
        return active
    }

    fun reset() {
        speechRun = 0
        silenceRun = 0
        active = false
    }
}
