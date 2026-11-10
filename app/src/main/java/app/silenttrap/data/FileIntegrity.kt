package app.silenttrap.data

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object FileIntegrity {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun sidecarFor(file: File): File = File(file.parentFile, "${file.name}.sha256")

    fun writeSidecar(file: File, sha256: String) {
        FileOutputStream(sidecarFor(file)).use { output ->
            output.write("$sha256  ${file.name}\n".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
    }

    fun readSidecar(file: File): String? {
        val hash = sidecarFor(file).takeIf(File::isFile)?.useLines { lines ->
            lines.firstOrNull()?.substringBefore(' ')
        }
        return hash?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }
}