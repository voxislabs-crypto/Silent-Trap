package app.silenttrap

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.silenttrap.data.LiveStatusStore
import app.silenttrap.data.ReliabilityLog
import app.silenttrap.data.SegmentRepository
import app.silenttrap.data.SettingsRepository
import app.silenttrap.drive.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SilentTrapApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var segments: SegmentRepository
        private set
    lateinit var reliabilityLog: ReliabilityLog
        private set
    val live = LiveStatusStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        segments = SegmentRepository(this)
        reliabilityLog = ReliabilityLog(this)
        reliabilityLog.recoverUnfinishedSession()
        createChannel()
        scope.launch {
            settings.awaitReady()
            UploadScheduler.enqueuePending(this@SilentTrapApp)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.listening_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.listening_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "listening"
    }
}
