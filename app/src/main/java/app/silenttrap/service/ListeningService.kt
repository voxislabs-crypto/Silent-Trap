package app.silenttrap.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import app.silenttrap.MainActivity
import app.silenttrap.R
import app.silenttrap.SilentTrapApp
import app.silenttrap.audio.AudioCaptureEngine
import app.silenttrap.data.FileIntegrity
import app.silenttrap.audio.WavWriter
import app.silenttrap.data.RunState
import app.silenttrap.data.UploadState
import app.silenttrap.drive.DriveAuth
import app.silenttrap.drive.UploadScheduler
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ListeningService : Service() {
    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var engine: AudioCaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        try {
            startInForeground(getString(R.string.app_name), "Starting microphone")
        } catch (error: SecurityException) {
            (application as SilentTrapApp).reliabilityLog.record("CAPTURE_START_FAILED", "Microphone permission is missing")
            (application as SilentTrapApp).live.update {
                it.copy(runState = RunState.ERROR, message = "Microphone permission is missing")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startEngine()
        observeStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        engine?.shutdown()
        engine = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startEngine() {
        if (engine != null) return
        val app = application as SilentTrapApp
        acquireWakeLock()
        app.reliabilityLog.beginSession()
        val capture = AudioCaptureEngine(
            context = this,
            settings = { app.settings.current.value },
            live = app.live,
            reliabilityLog = app.reliabilityLog,
            onSegment = { segment -> saveSegment(segment) },
        )
        engine = capture
        capture.start()
    }

    private fun saveSegment(segment: app.silenttrap.audio.CapturedSegment) {
        val app = application as SilentTrapApp
        val settings = app.settings.current.value
        val availableBytes = StatFs(filesDir.absolutePath).availableBytes
        val lowStorage = availableBytes < LOW_STORAGE_WARNING_BYTES
        val previousLowStorage = app.live.status.value.lowStorage
        app.live.update { it.copy(lowStorage = lowStorage) }
        if (lowStorage != previousLowStorage) {
            app.reliabilityLog.record(
                if (lowStorage) "LOW_STORAGE" else "STORAGE_RECOVERED",
                "availableBytes=$availableBytes",
            )
        }
        val name = app.segments.allocateFileName(app.segments.suggestFileName())
        val file = app.segments.newFile(name)
        WavWriter.write(file, segment.samples, app.silenttrap.audio.AudioConfig.SAMPLE_RATE)
        val sha256 = runCatching { FileIntegrity.sha256(file) }
            .onFailure { app.reliabilityLog.record("CHECKSUM_FAILED", "file=${file.name}; ${it.message}") }
            .getOrNull()
        if (sha256 != null) {
            runCatching { FileIntegrity.writeSidecar(file, sha256) }
                .onFailure { app.reliabilityLog.record("CHECKSUM_SIDECAR_FAILED", "file=${file.name}; ${it.message}") }
        }
        val signedIn = DriveAuth.account(this) != null
        val state = when {
            settings.autoUpload && signedIn -> UploadState.PENDING
            settings.autoUpload -> UploadState.PENDING
            else -> UploadState.LOCAL_ONLY
        }
        val record = app.segments.add(
            samplesSaved = true,
            fileName = name,
            durationMs = segment.durationMs,
            inputPeakDbfs = segment.inputPeakDbfs,
            outputPeakDbfs = segment.outputPeakDbfs,
            uploadState = state,
            sha256 = sha256,
        )
        app.reliabilityLog.record("CLIP_SAVED", "file=${file.name}; sha256=${sha256 ?: "unavailable"}")
        if (settings.autoUpload && signedIn) {
            UploadScheduler.enqueue(this, record.id)
        }
        app.segments.prune(settings)
    }

    private fun observeStatus() {
        if (observing) return
        observing = true
        val app = application as SilentTrapApp
        val session = app.live.status.map { status ->
            SessionNote(
                runState = status.runState,
                pendingSaves = status.pendingSaves,
                message = status.message,
                micLabel = status.micLabel,
                lowInput = status.lowInput,
                lowStorage = status.lowStorage,
            )
        }.distinctUntilChanged()
        scope.launch {
            combine(
                session,
                WorkManager.getInstance(this@ListeningService).getWorkInfosByTagFlow(UploadScheduler.TAG),
            ) { note, work ->
                val uploading = work.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                note to uploading
            }.collect { (note, uploading) ->
                app.live.update { it.copy(uploading = uploading) }
                if (note.runState == RunState.ERROR) {
                    app.reliabilityLog.endSession("capture_error", note.message)
                    startInForeground("Microphone problem", note.message ?: "Recording stopped")
                    stopSelf()
                    return@collect
                }
                if (note.runState == RunState.IDLE) return@collect
                val title = if (note.runState == RunState.SPEECH) "Speech heard" else "Silent Trap is listening"
                val detail = when {
                    note.lowStorage -> "Storage is low; clips may fail to save"
                    note.lowInput -> "Input has remained near silence"
                    uploading > 0 && note.pendingSaves > 0 -> "Saving a clip, uploading $uploading"
                    uploading > 0 -> "Uploading $uploading"
                    note.pendingSaves > 0 -> "Saving a clip"
                    note.runState == RunState.SPEECH -> "Recording this phrase"
                    else -> note.micLabel.ifBlank { "Waiting for speech" }
                }
                startInForeground(title, detail)
            }
        }
    }

    private data class SessionNote(
        val runState: RunState,
        val pendingSaves: Int,
        val message: String?,
        val micLabel: String,
        val lowInput: Boolean,
        val lowStorage: Boolean,
    )

    private fun stopListening() {
        (application as SilentTrapApp).reliabilityLog.endSession("user_stopped")
        engine?.shutdown()
        engine = null
        releaseWakeLock()
        val app = application as SilentTrapApp
        app.live.update { it.copy(runState = RunState.IDLE, analysisGain = 1f, pendingSaves = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(title: String, detail: String) {
        // Always call startForeground. A second startForegroundService() has its own
        // deadline, and skipping the call because the text did not change will crash.
        val notification = buildNotification(title, detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, detail: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SilentTrapApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_waveform)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "silenttrap:listen").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "app.silenttrap.STOP"
        private const val LOW_STORAGE_WARNING_BYTES = 100L * 1024L * 1024L

        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_STOP))
        }
    }
}
