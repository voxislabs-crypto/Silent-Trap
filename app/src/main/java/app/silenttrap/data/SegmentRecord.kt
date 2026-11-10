package app.silenttrap.data

enum class UploadState {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,
    LOCAL_ONLY,
}

data class SegmentRecord(
    val id: String,
    val fileName: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val inputPeakDbfs: Float,
    val outputPeakDbfs: Float,
    val uploadState: UploadState,
    val driveFileId: String? = null,
    val error: String? = null,
    val localDeleted: Boolean = false,
    val sha256: String? = null,
)
