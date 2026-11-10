package app.silenttrap

import app.silenttrap.audio.AudioConfig
import app.silenttrap.audio.BiquadHighPass
import app.silenttrap.audio.CircularShortBuffer
import app.silenttrap.audio.EnergySpeech
import app.silenttrap.audio.Level
import app.silenttrap.audio.Resampler
import app.silenttrap.audio.SegmentAssembler
import app.silenttrap.audio.SegmentSignalProcessor
import app.silenttrap.audio.SpeechDecision
import app.silenttrap.audio.SpeechGate
import app.silenttrap.audio.WavWriter
import app.silenttrap.audio.WhisperGain
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

object LogicChecks {
    fun runAll() {
        circularBufferKeepsNewestInOrder()
        whisperBoostLiftsQuietFramesAndLeavesSilence()
        savedWhisperIsLiftedWithoutHardClipping()
        speechGateHoldsThroughShortGaps()
        sensitiveModeUsesEitherDetector()
        highPassRemovesDcAndKeepsVoice()
        assemblerKeepsPreRollSpeechAndPostRoll()
        speechDuringPostRollExtendsTheClip()
        resamplerRoundTripsLength()
        wavHeaderMatchesPcm()
    }

    private fun circularBufferKeepsNewestInOrder() {
        val ring = CircularShortBuffer(3)
        ring.write(shortArrayOf(1, 2, 3, 4, 5))
        checkEquals(shortArrayOf(3, 4, 5), ring.snapshot(), "ring order")
    }

    private fun whisperBoostLiftsQuietFramesAndLeavesSilence() {
        val silence = WhisperGain.analysisGain(0.0001f, 0.25f)
        check(silence == 1f, "silence gain was $silence")
        val whisper = WhisperGain.analysisGain(0.005f, 0.25f)
        check(whisper in 18f..22f, "whisper gain was $whisper")
        val loud = WhisperGain.analysisGain(0.2f, 0.25f)
        check(loud == 1f, "loud gain was $loud")
        check(WhisperGain.maxAnalysisGain(0.25f) > WhisperGain.maxAnalysisGain(0.80f), "threshold gain")
        check(EnergySpeech.absoluteFloor < 0.001f, "floor")
    }

    private fun savedWhisperIsLiftedWithoutHardClipping() {
        val quiet = FloatArray(1000) { 0.003f }
        val gained = SegmentSignalProcessor.applyUserGain(quiet, 12f)
        val madeUp = SegmentSignalProcessor.whisperMakeup(gained, enabled = true)
        val peak = Level.peak(madeUp)
        check(peak > 0.20f, "makeup peak $peak")
        check(madeUp.all { it in -1f..1f }, "clipped")
        val hot = SegmentSignalProcessor.applyUserGain(FloatArray(100) { 0.9f }, 12f)
        check(hot.all { it <= 1f && it >= -1f }, "soft clip")
    }

    private fun speechGateHoldsThroughShortGaps() {
        val gate = SpeechGate()
        check(!gate.update(false, 2, 3), "idle")
        check(!gate.update(true, 2, 3), "one frame")
        check(gate.update(true, 2, 3), "latched")
        check(gate.update(false, 2, 3), "hold 1")
        check(gate.update(false, 2, 3), "hold 2")
        check(!gate.update(false, 2, 3), "released")
    }

    private fun sensitiveModeUsesEitherDetector() {
        check(SpeechDecision.frameIsSpeech(false, true, 0.25f), "whisper or")
        check(!SpeechDecision.frameIsSpeech(false, true, 0.70f), "strict ignores energy")
        check(SpeechDecision.frameIsSpeech(true, false, 0.70f), "strict silero")
        check(SpeechDecision.startFrames(0.25f) < SpeechDecision.startFrames(0.80f), "start frames")
    }

    private fun highPassRemovesDcAndKeepsVoice() {
        val filter = BiquadHighPass(16_000.0, 120.0)
        val dc = ShortArray(8_000) { 8_000 }
        filter.process(dc)
        check(kotlin.math.abs(dc.last().toInt()) < 200, "dc tail ${dc.last()}")

        val tone = BiquadHighPass(16_000.0, 120.0)
        val voice = ShortArray(3_200)
        for (i in voice.indices) {
            voice[i] = (sin(2.0 * PI * 2_000.0 * i / 16_000.0) * 8_000.0).toInt().toShort()
        }
        tone.process(voice)
        val tail = voice.copyOfRange(voice.size - 1_600, voice.size)
        check(Level.rms(tail) > 0.05f, "voice rms ${Level.rms(tail)}")
    }

    private fun assemblerKeepsPreRollSpeechAndPostRoll() {
        val assembler = SegmentAssembler(sampleRate = 1_000, preRollMs = 20, postRollMs = 10)
        check(assembler.push(frame(1), false) == null, "idle 1")
        check(assembler.push(frame(2), false) == null, "idle 2")
        check(assembler.push(frame(3), false) == null, "idle 3")
        check(assembler.push(frame(4), true) == null, "speech start")
        check(assembler.push(frame(5), true) == null, "speech continue")
        val done = assembler.push(frame(6), false)
        check(done != null && done.size == 50, "length ${done?.size}")
        check(done!!.take(10).all { it == 2.toShort() }, "pre-roll start")
        check(done.copyOfRange(10, 20).all { it == 3.toShort() }, "pre-roll end")
        check(done.copyOfRange(20, 30).all { it == 4.toShort() }, "speech start samples")
        check(done.copyOfRange(30, 40).all { it == 5.toShort() }, "speech continue samples")
        check(done.copyOfRange(40, 50).all { it == 6.toShort() }, "post-roll")
        check(assembler.debugPhase == SegmentAssembler.Phase.IDLE, "back to idle")
    }

    private fun speechDuringPostRollExtendsTheClip() {
        val assembler = SegmentAssembler(sampleRate = 1_000, preRollMs = 10, postRollMs = 20)
        assembler.push(frame(1), false)
        assembler.push(frame(2), true)
        assembler.push(frame(3), false)
        val extended = assembler.push(frame(4), true)
        check(extended == null, "speech resumed ${extended?.size}")
        check(assembler.debugPhase == SegmentAssembler.Phase.SPEECH, "phase ${assembler.debugPhase}")
        assembler.push(frame(5), false)
        val done = assembler.push(frame(6), false)
        check(done != null, "extended clip missing")
        check(done!!.last() == 6.toShort(), "post-roll reached the later silence")
    }

    private fun resamplerRoundTripsLength() {
        val input = FloatArray(10) { it / 10f }
        val up = Resampler.upsample3(input)
        check(up.size == 30, "upsample ${up.size}")
        val down = Resampler.downsample3(up, input.size)
        check(down.size == 10, "downsample")
    }

    private fun wavHeaderMatchesPcm() {
        val dir = File(System.getProperty("java.io.tmpdir"), "silent-trap-check").apply { mkdirs() }
        val file = File(dir, "clip.wav")
        WavWriter.write(file, floatArrayOf(0.5f, -0.5f), AudioConfig.SAMPLE_RATE)
        val bytes = file.readBytes()
        check(bytes.size == 48, "wav size ${bytes.size}")
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4) == "RIFF", "riff")
        check(String(bytes, 8, 4) == "WAVE", "wave")
        header.position(24)
        check(header.int == AudioConfig.SAMPLE_RATE, "rate")
        header.position(40)
        check(header.int == 4, "data bytes")
        file.delete()
    }

    private fun frame(value: Int): ShortArray = ShortArray(10) { value.toShort() }

    private fun check(condition: Boolean, message: String) {
        if (!condition) error(message)
    }

    private fun checkEquals(expected: ShortArray, actual: ShortArray, message: String) {
        check(expected.contentEquals(actual), "$message expected ${expected.toList()} was ${actual.toList()}")
    }
}

fun main() {
    LogicChecks.runAll()
    println("silent trap audio checks passed")
}
