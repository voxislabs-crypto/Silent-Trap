package app.silenttrap.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = ByteArray(samples.size * 2)
        val data = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            data.putShort((clamped * 32767f).toInt().toShort())
        }
        writePcm16(file, pcm, sampleRate)
    }

    fun writePcm16(file: File, pcmLittleEndian: ByteArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            val dataBytes = pcmLittleEndian.size
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes)
            output.write(header.array())
            output.write(pcmLittleEndian)
        }
    }
}
