package app.silenttrap.audio

class PcmBuffer(initialCapacity: Int = 16_000) {
    private var data = ShortArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun append(samples: ShortArray, length: Int = samples.size) {
        val count = length.coerceIn(0, samples.size)
        if (count == 0) return
        ensure(size + count)
        samples.copyInto(data, destinationOffset = size, startIndex = 0, endIndex = count)
        size += count
    }

    fun snapshot(): ShortArray = data.copyOf(size)

    private fun ensure(needed: Int) {
        if (needed <= data.size) return
        var cap = data.size
        while (cap < needed) cap *= 2
        data = data.copyOf(cap)
    }
}
