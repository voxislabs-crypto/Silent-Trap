package app.silenttrap.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.silenttrap.SilentTrapApp
import app.silenttrap.data.UploadState
import java.io.IOException

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SilentTrapApp
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val record = app.segments.record(id) ?: return Result.success()
        if (record.localDeleted || record.uploadState == UploadState.UPLOADED) return Result.success()
        val file = app.segments.fileFor(record)
        if (!file.exists()) {
            app.segments.update(id) {
                it.copy(uploadState = UploadState.FAILED, error = "Local file is missing.")
            }
            return Result.failure()
        }
        app.segments.update(id) { it.copy(uploadState = UploadState.UPLOADING, error = null) }
        return try {
            val driveId = DriveClient(applicationContext).uploadWav(file)
            app.segments.update(id) {
                it.copy(uploadState = UploadState.UPLOADED, driveFileId = driveId, error = null)
            }
            app.segments.prune(app.settings.current.value)
            Result.success()
        } catch (error: DriveAuthRequiredException) {
            app.segments.update(id) {
                it.copy(uploadState = UploadState.FAILED, error = error.message)
            }
            Result.failure()
        } catch (error: IOException) {
            val message = error.message ?: "Upload failed"
            if (runAttemptCount >= 6) {
                app.segments.update(id) { it.copy(uploadState = UploadState.FAILED, error = message) }
                Result.failure()
            } else {
                app.segments.update(id) { it.copy(uploadState = UploadState.PENDING, error = message) }
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_ID = "segment_id"
    }
}
