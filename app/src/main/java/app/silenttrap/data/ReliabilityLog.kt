package app.silenttrap.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

class ReliabilityLog(context: Context) {
    private val directory = File(context.filesDir, "segments/wav").apply { mkdirs() }
    val file = File(directory, "reliability.log")
    private val activeSession = File(directory, ".active-session")

    @Synchronized
    fun recoverUnfinishedSession() {
        if (!activeSession.isFile) return
        val startedAt = runCatching { activeSession.readText().trim() }.getOrDefault("unknown time")
        record("CAPTURE_INTERRUPTED", "Previous session started at $startedAt and ended without a stop event")
        activeSession.delete()
    }

    @Synchronized
    fun beginSession() {
        val startedAt = Instant.now().toString()
        runCatching {
            FileOutputStream(activeSession).use { output ->
                output.write(startedAt.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
        }.onFailure { Log.w(TAG, "Could not write capture session marker", it) }
        record("CAPTURE_STARTED")
    }

    @Synchronized
    fun endSession(reason: String, detail: String? = null) {
        record("CAPTURE_ENDED", listOfNotNull(reason, detail).joinToString(": "))
        if (!activeSession.delete() && activeSession.exists()) {
            record("SESSION_MARKER_CLEANUP_FAILED")
        }
    }

    @Synchronized
    fun record(event: String, detail: String? = null) {
        val safeDetail = detail?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\t', ' ')
        val line = buildString {
            append(Instant.now()).append('\t').append(event)
            if (!safeDetail.isNullOrBlank()) append('\t').append(safeDetail)
            append('\n')
        }
        runCatching {
            FileOutputStream(file, true).use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }.onFailure { Log.w(TAG, "Could not append reliability log", it) }
    }

    private companion object {
        const val TAG = "SilentTrap"
    }
}