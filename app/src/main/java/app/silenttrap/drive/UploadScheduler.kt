package app.silenttrap.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.silenttrap.SilentTrapApp
import java.util.concurrent.TimeUnit

object UploadScheduler {
    const val TAG = "silent-trap-upload"

    fun enqueue(context: Context, segmentId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_ID to segmentId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "upload-$segmentId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueuePending(context: Context) {
        val app = context.applicationContext as SilentTrapApp
        if (!app.settings.current.value.autoUpload) return
        if (DriveAuth.account(context) == null) return
        app.segments.pendingIds().forEach { enqueue(context, it) }
    }
}
