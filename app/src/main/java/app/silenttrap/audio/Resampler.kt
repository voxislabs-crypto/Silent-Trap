package app.silenttrap.audio

/** 16 kHz to 48 kHz is an exact 3x ratio, which is what RNNoise requires. */
object Resampler {
    fun upsample3(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val out = FloatArray(input.size * 3)
        for (i in input.indices) {
            val a = input[i]
            val b = if (i + 1 < input.size) input[i + 1] else a
            out[i * 3] = a
            out[i * 3 + 1] = a + (b - a) / 3f
            out[i * 3 + 2] = a + (b - a) * (2f / 3f)
        }
        return out
    }

    fun downsample3(input: FloatArray, outputLength: Int): FloatArray {
        val out = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val j = i * 3
            if (j >= input.size) break
            val a = input[j]
            val b = if (j + 1 < input.size) input[j + 1] else a
            val c = if (j + 2 < input.size) input[j + 2] else b
            out[i] = (a + b + c) / 3f
        }
        return out
    }
}
