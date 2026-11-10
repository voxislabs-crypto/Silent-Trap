package app.silenttrap.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SegmentRepository(context: Context) {
    private val root = File(context.filesDir, "segments").apply { mkdirs() }
    private val wavDir = File(root, "wav").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val lock = Any()
    private val _records = MutableStateFlow<List<SegmentRecord>>(emptyList())
    val records: StateFlow<List<SegmentRecord>> = _records

    init {
        synchronized(lock) {
            load()
            recoverStuckUploadsLocked()
        }
    }

    fun fileFor(record: SegmentRecord): File = File(wavDir, record.fileName)

    fun allocateFileName(requested: String = suggestFileName()): String = synchronized(lock) {
        uniqueName(requested)
    }

    fun newFile(fileName: String): File = File(wavDir, fileName)

    fun add(
        samplesSaved: Boolean,
        fileName: String,
        durationMs: Long,
        inputPeakDbfs: Float,
        outputPeakDbfs: Float,
        uploadState: UploadState,
        sha256: String? = null,
    ): SegmentRecord {
        synchronized(lock) {
            val record = SegmentRecord(
                id = "${System.currentTimeMillis()}-${System.nanoTime()}",
                fileName = fileName,
                createdAtEpochMs = System.currentTimeMillis(),
                durationMs = durationMs,
                inputPeakDbfs = inputPeakDbfs,
                outputPeakDbfs = outputPeakDbfs,
                uploadState = uploadState,
                localDeleted = !samplesSaved,
                sha256 = sha256,
            )
            _records.value = listOf(record) + _records.value
            persistLocked()
            return record
        }
    }

    fun suggestFileName(epochMs: Long = System.currentTimeMillis()): String {
        val stamp = Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(FILE_STAMP)
        return "${stamp}_segment.wav"
    }

    fun update(id: String, block: (SegmentRecord) -> SegmentRecord) {
        synchronized(lock) {
            _records.value = _records.value.map { if (it.id == id) block(it) else it }
            persistLocked()
        }
    }

    fun pendingIds(): List<String> = synchronized(lock) {
        _records.value
            .filter { it.uploadState == UploadState.PENDING || it.uploadState == UploadState.FAILED }
            .filter { !it.localDeleted }
            .map { it.id }
    }

    fun record(id: String): SegmentRecord? = synchronized(lock) {
        _records.value.firstOrNull { it.id == id }
    }

    fun deleteLocal(id: String) {
        synchronized(lock) {
            val record = _records.value.firstOrNull { it.id == id } ?: return
            if (!record.localDeleted) {
                val file = fileFor(record)
                file.delete()
                FileIntegrity.sidecarFor(file).delete()
            }
            _records.value = _records.value.filterNot { it.id == id }
            persistLocked()
        }
    }

    fun prune(settings: CaptureSettings) {
        synchronized(lock) {
            val keep = settings.keepLocal
            val candidates = _records.value.filter { record ->
                if (record.localDeleted) return@filter false
                if (settings.autoUpload) record.uploadState == UploadState.UPLOADED
                else true
            }.sortedByDescending { it.createdAtEpochMs }
            val drop = candidates.drop(keep)
            if (drop.isEmpty()) return
            val dropIds = drop.map { it.id }.toSet()
            drop.forEach {
                val file = fileFor(it)
                file.delete()
                FileIntegrity.sidecarFor(file).delete()
            }
            _records.value = _records.value.map { record ->
                if (record.id in dropIds) record.copy(localDeleted = true) else record
            }
            persistLocked()
        }
    }

    private fun recoverStuckUploadsLocked() {
        var changed = false
        val next = _records.value.map { record ->
            if (record.uploadState == UploadState.UPLOADING) {
                changed = true
                record.copy(uploadState = UploadState.PENDING)
            } else {
                record
            }
        }
        if (changed) {
            _records.value = next
            persistLocked()
        }
    }

    private fun uniqueName(requested: String): String {
        if (!File(wavDir, requested).exists() && _records.value.none { it.fileName == requested }) {
            return requested
        }
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "${stem}_$n$ext"
            if (!File(wavDir, candidate).exists()) return candidate
            n++
        }
    }

    private fun load() {
        if (!index.exists()) {
            _records.value = emptyList()
            return
        }
        try {
            val array = JSONArray(index.readText())
            val parsed = buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toRecord())
                }
            }
            _records.value = parsed.sortedByDescending { it.createdAtEpochMs }
        } catch (_: Exception) {
            _records.value = rebuildFromWavs()
            persistLocked()
        }
    }

    private fun rebuildFromWavs(): List<SegmentRecord> {
        val wavs = wavDir.listFiles { file -> file.extension.equals("wav", ignoreCase = true) } ?: return emptyList()
        return wavs.sortedByDescending { it.lastModified() }.map { file ->
            SegmentRecord(
                id = file.lastModified().toString() + "_" + file.name,
                fileName = file.name,
                createdAtEpochMs = file.lastModified(),
                durationMs = ((file.length() - 44).coerceAtLeast(0) / 2) * 1000L / 16_000L,
                inputPeakDbfs = -90f,
                outputPeakDbfs = -90f,
                uploadState = UploadState.LOCAL_ONLY,
                sha256 = FileIntegrity.readSidecar(file),
            )
        }
    }

    private fun persistLocked() {
        val array = JSONArray()
        _records.value.forEach { array.put(it.toJson()) }
        val temp = File(root, "index.json.tmp")
        temp.writeText(array.toString())
        if (index.exists() && !index.delete()) {
            index.writeText(array.toString())
            temp.delete()
            return
        }
        if (!temp.renameTo(index)) {
            index.writeText(array.toString())
            temp.delete()
        }
    }

    private fun SegmentRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("createdAtEpochMs", createdAtEpochMs)
        put("durationMs", durationMs)
        put("inputPeakDbfs", inputPeakDbfs.toDouble())
        put("outputPeakDbfs", outputPeakDbfs.toDouble())
        put("uploadState", uploadState.name)
        put("sha256", sha256)
        put("driveFileId", driveFileId)
        put("error", error)
        put("localDeleted", localDeleted)
    }

    private fun JSONObject.toRecord(): SegmentRecord {
        val stateName = optString("uploadState", UploadState.LOCAL_ONLY.name)
        val state = runCatching { UploadState.valueOf(stateName) }.getOrDefault(UploadState.LOCAL_ONLY)
        return SegmentRecord(
            id = getString("id"),
            fileName = getString("fileName"),
            createdAtEpochMs = getLong("createdAtEpochMs"),
            durationMs = getLong("durationMs"),
            inputPeakDbfs = optDouble("inputPeakDbfs", -90.0).toFloat(),
            outputPeakDbfs = optDouble("outputPeakDbfs", -90.0).toFloat(),
            uploadState = state,
            driveFileId = optString("driveFileId").ifBlank { null },
            error = optString("error").ifBlank { null },
            localDeleted = optBoolean("localDeleted", false),
            sha256 = optString("sha256").ifBlank { null },
        )
    }

    companion object {
        private val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
