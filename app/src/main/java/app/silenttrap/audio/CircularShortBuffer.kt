package app.silenttrap.audio

class CircularShortBuffer(capacity: Int) {
    private var data = ShortArray(capacity.coerceAtLeast(1))
    private var writeIndex = 0
    private var filled = 0

    val capacity: Int get() = data.size
    val size: Int get() = filled

    fun write(frame: ShortArray, length: Int = frame.size) {
        val count = length.coerceIn(0, frame.size)
        for (i in 0 until count) {
            data[writeIndex] = frame[i]
            writeIndex++
            if (writeIndex == data.size) writeIndex = 0
            if (filled < data.size) filled++
        }
    }

    fun snapshot(): ShortArray {
        if (filled == 0) return ShortArray(0)
        val out = ShortArray(filled)
        val start = if (filled < data.size) 0 else writeIndex
        for (i in 0 until filled) {
            out[i] = data[(start + i) % data.size]
        }
        return out
    }

    fun clear() {
        writeIndex = 0
        filled = 0
    }
}
