package app.silenttrap.audio

/**
 * Idle audio stays in a ring. On speech, the ring is the pre-roll.
 * After speech ends, recording continues for the post-roll. Speech during the
 * post-roll extends the same clip instead of cutting it.
 */
class SegmentAssembler(
    private val sampleRate: Int,
    preRollMs: Int,
    postRollMs: Int,
    private val maxSegmentMs: Int = AudioConfig.MAX_SEGMENT_MS,
) {
    enum class Phase { IDLE, SPEECH, POST_ROLL }

    private var postRollSamples = AudioConfig.msToSamples(sampleRate, postRollMs)
    private var ring = CircularShortBuffer(AudioConfig.msToSamples(sampleRate, preRollMs).coerceAtLeast(1))
    private val current = PcmBuffer()
    private var phase = Phase.IDLE
    private var postRemaining = 0
    private val maxSamples = AudioConfig.msToSamples(sampleRate, maxSegmentMs).coerceAtLeast(sampleRate)

    val debugPhase: Phase get() = phase

    fun updateWindows(preRollMs: Int, postRollMs: Int) {
        postRollSamples = AudioConfig.msToSamples(sampleRate, postRollMs)
        val newPre = AudioConfig.msToSamples(sampleRate, preRollMs).coerceAtLeast(1)
        if (newPre != ring.capacity && phase == Phase.IDLE) {
            val existing = ring.snapshot()
            ring = CircularShortBuffer(newPre)
            if (existing.isNotEmpty()) ring.write(existing)
        }
    }

    fun push(frame: ShortArray, speech: Boolean): ShortArray? {
        // Snapshot the ring before this frame is stored so the pre-roll is audio
        // from before the speech, not the speech itself.
        if (phase == Phase.IDLE && speech) {
            current.clear()
            val preRoll = ring.snapshot()
            if (preRoll.isNotEmpty()) current.append(preRoll)
            current.append(frame)
            ring.write(frame)
            phase = Phase.SPEECH
            return maybeSplit(speechContinues = true)
        }
        ring.write(frame)
        return when (phase) {
            Phase.IDLE -> null
            Phase.SPEECH -> {
                if (speech) {
                    current.append(frame)
                    maybeSplit(speechContinues = true)
                } else {
                    phase = Phase.POST_ROLL
                    postRemaining = postRollSamples
                    consumePost(frame)
                }
            }
            Phase.POST_ROLL -> {
                if (speech) {
                    current.append(frame)
                    phase = Phase.SPEECH
                    maybeSplit(speechContinues = true)
                } else {
                    consumePost(frame)
                }
            }
        }
    }

    private fun consumePost(frame: ShortArray): ShortArray? {
        val take = minOf(frame.size, postRemaining)
        if (take > 0) current.append(frame, take)
        postRemaining -= take
        if (postRemaining > 0) return null
        return finish()
    }

    private fun maybeSplit(speechContinues: Boolean): ShortArray? {
        if (current.size < maxSamples) return null
        val done = finish()
        if (speechContinues) {
            current.append(ring.snapshot())
            phase = Phase.SPEECH
        }
        return if (done.isEmpty()) null else done
    }

    private fun finish(): ShortArray {
        phase = Phase.IDLE
        postRemaining = 0
        val done = current.snapshot()
        current.clear()
        return done
    }
}
