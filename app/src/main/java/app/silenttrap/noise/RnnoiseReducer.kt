package app.silenttrap.noise

import android.util.Log
import app.silenttrap.audio.Resampler
import cn.enaium.rnnoise.createRnnoise

/**
 * RNNoise runs at 48 kHz in 480-sample frames. Audio is gained before this step
 * so a whisper is near speech level and is less likely to be treated as noise.
 * If the native library fails to load, callers keep the gained audio.
 */
object RnnoiseReducer {
    private const val TAG = "SilentTrap"
    @Volatile private var unavailable = false

    val failed: Boolean get() = unavailable

    fun reduce(samples16k: FloatArray): FloatArray? {
        if (unavailable || samples16k.isEmpty()) return null
        return try {
            createRnnoise().use { denoiser ->
                val up = Resampler.upsample3(samples16k)
                val frameSize = denoiser.frameSize
                val denoised = FloatArray(up.size)
                val input = FloatArray(frameSize)
                val output = FloatArray(frameSize)
                var offset = 0
                while (offset + frameSize <= up.size) {
                    for (i in 0 until frameSize) input[i] = up[offset + i]
                    denoiser.processFrame(input, output)
                    for (i in 0 until frameSize) denoised[offset + i] = output[i]
                    offset += frameSize
                }
                while (offset < up.size) {
                    denoised[offset] = up[offset]
                    offset++
                }
                Resampler.downsample3(denoised, samples16k.size)
            }
        } catch (error: Throwable) {
            unavailable = true
            Log.w(TAG, "RNNoise unavailable, keeping gain and high-pass only", error)
            null
        }
    }
}
