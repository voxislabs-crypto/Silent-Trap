package app.silenttrap.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import app.silenttrap.data.CaptureSettings
import app.silenttrap.data.LiveStatusStore
import app.silenttrap.data.ReliabilityLog
import app.silenttrap.data.RunState
import app.silenttrap.vad.SileroSpeechDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class CapturedSegment(
    val samples: FloatArray,
    val durationMs: Long,
    val inputPeakDbfs: Float,
    val outputPeakDbfs: Float,
)

class AudioCaptureEngine(
    private val context: Context,
    private val settings: () -> CaptureSettings,
    private val live: LiveStatusStore,
    private val reliabilityLog: ReliabilityLog,
    private val onSegment: (CapturedSegment) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val processor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "silent-trap-process").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::loop, "silent-trap-capture").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(2_000)
        thread = null
    }

    fun shutdown() {
        stop()
        processor.shutdown()
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var recorder: AudioRecord? = null
        var detector: SileroSpeechDetector? = null
        var suppressor: NoiseSuppressor? = null
        try {
            val opened = openRecorder()
            recorder = opened.recorder
            suppressor = disableHardwareNoiseSuppressor(opened.recorder.audioSessionId)
            detector = SileroSpeechDetector(context)
            live.update {
                it.copy(
                    runState = RunState.LISTENING,
                    micLabel = opened.label,
                    message = null,
                    lowInput = false,
                )
            }
            capture(opened.recorder, detector)
        } catch (error: Throwable) {
            Log.e(TAG, "Listening stopped", error)
            live.update {
                it.copy(
                    runState = RunState.ERROR,
                    message = error.message ?: "Microphone failed",
                )
            }
        } finally {
            running.set(false)
            detector?.close()
            suppressor?.release()
            recorder?.run {
                try {
                    stop()
                } catch (_: IllegalStateException) {
                }
                release()
            }
            if (live.status.value.runState != RunState.ERROR) {
                live.update { it.copy(runState = RunState.IDLE, analysisGain = 1f, lowInput = false) }
            }
        }
    }

    private fun capture(recorder: AudioRecord, detector: SileroSpeechDetector) {
        val initial = settings()
        val assembler = SegmentAssembler(
            sampleRate = AudioConfig.SAMPLE_RATE,
            preRollMs = initial.preRollMs,
            postRollMs = initial.postRollMs,
        )
        val highPass = BiquadHighPass(AudioConfig.SAMPLE_RATE.toDouble(), AudioConfig.HIGH_PASS_HZ)
        val floor = NoiseFloorTracker()
        val gate = SpeechGate()
        var highPassWasOn = false
        var lowInputSince: Long? = null
        var lowInputReported = false
        val incoming = ShortArray(AudioConfig.FRAME_SAMPLES * 4)
        val frame = ShortArray(AudioConfig.FRAME_SAMPLES)
        var filled = 0

        recorder.startRecording()
        while (running.get()) {
            val read = recorder.read(incoming, 0, incoming.size)
            if (read < 0) {
                throw IllegalStateException("Microphone read failed ($read)")
            }
            var index = 0
            while (index < read) {
                frame[filled++] = incoming[index++]
                if (filled < frame.size) continue
                filled = 0
                val current = settings()
                assembler.updateWindows(current.preRollMs, current.postRollMs)
                if (current.highPass) {
                    if (!highPassWasOn) {
                        highPass.reset()
                        highPassWasOn = true
                    }
                    highPass.process(frame)
                } else {
                    highPassWasOn = false
                }

                val rms = Level.rms(frame)
                val now = SystemClock.elapsedRealtime()
                if (rms < LOW_INPUT_RMS) {
                    val since = lowInputSince ?: now.also { lowInputSince = it }
                    if (!lowInputReported && now - since >= LOW_INPUT_WARNING_MS) {
                        lowInputReported = true
                        live.update { it.copy(lowInput = true) }
                        reliabilityLog.record("INPUT_NEAR_SILENCE", "levelDbfs=${Level.dbfs(rms)}")
                    }
                } else {
                    lowInputSince = null
                    if (lowInputReported) {
                        lowInputReported = false
                        live.update { it.copy(lowInput = false) }
                        reliabilityLog.record("INPUT_SIGNAL_RETURNED")
                    }
                }
                val gain = WhisperGain.analysisGain(rms, current.vadThreshold)
                val boosted = WhisperGain.boostShorts(frame, gain)
                val silero = detector.isSpeech(boosted, current.vadThreshold)
                val energy = EnergySpeech.isHot(rms, current.vadThreshold, floor.floor)
                val rawSpeech = SpeechDecision.frameIsSpeech(silero, energy, current.vadThreshold)
                val speech = gate.update(
                    rawSpeech,
                    SpeechDecision.startFrames(current.vadThreshold),
                    SpeechDecision.stopFrames(),
                )
                floor.observe(rms, speech)

                live.update { status ->
                    status.copy(
                        runState = if (speech) RunState.SPEECH else RunState.LISTENING,
                        levelDbfs = Level.dbfs(rms).roundToInt().coerceIn(-90, 0),
                        analysisGain = gain,
                    )
                }

                val completed = assembler.push(frame.copyOf(), speech) ?: continue
                submit(completed, current)
            }
        }
    }

    private fun submit(pcm: ShortArray, settings: CaptureSettings) {
        live.update { it.copy(pendingSaves = it.pendingSaves + 1) }
        processor.execute {
            try {
                onSegment(process(pcm, settings))
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to process a segment", error)
                reliabilityLog.record("CLIP_SAVE_FAILED", error.message)
                live.update { it.copy(message = error.message ?: "Could not save the clip") }
            } finally {
                live.update { it.copy(pendingSaves = (it.pendingSaves - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun process(pcm: ShortArray, settings: CaptureSettings): CapturedSegment {
        val inputPeak = Level.dbfs(Level.peak(SegmentSignalProcessor.shortsToFloat(pcm)))
        val samples = SegmentSignalProcessor.whisperMakeup(
            SegmentSignalProcessor.applyUserGain(
                SegmentSignalProcessor.shortsToFloat(pcm),
                settings.gain,
            ),
            settings.whisperMakeup,
        )
        return CapturedSegment(
            samples = samples,
            durationMs = pcm.size * 1000L / AudioConfig.SAMPLE_RATE,
            inputPeakDbfs = inputPeak,
            outputPeakDbfs = Level.dbfs(Level.peak(samples)),
        )
    }

    private data class OpenedRecorder(val recorder: AudioRecord, val label: String)

    @SuppressLint("MissingPermission")
    private fun openRecorder(): OpenedRecorder {
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "Raw microphone",
            MediaRecorder.AudioSource.MIC to "Standard microphone",
        )
        var lastError: Throwable? = null
        for ((source, label) in sources) {
            try {
                val recorder = buildRecorder(source)
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return OpenedRecorder(recorder, label)
                }
                recorder.release()
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("No microphone is available", lastError)
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(source: Int): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) throw IllegalStateException("This device rejected 16 kHz mono recording")
        val buffer = maxOf(min, AudioConfig.FRAME_SAMPLES * 8) * 2
        return AudioRecord(
            source,
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer,
        )
    }

    private fun disableHardwareNoiseSuppressor(sessionId: Int): NoiseSuppressor? {
        if (!NoiseSuppressor.isAvailable()) return null
        return try {
            NoiseSuppressor.create(sessionId)?.also { it.enabled = false }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not disable the hardware noise suppressor", error)
            null
        }
    }

    private companion object {
        const val TAG = "SilentTrap"
        const val LOW_INPUT_RMS = 0.00003f
        const val LOW_INPUT_WARNING_MS = 10_000L
    }
}
