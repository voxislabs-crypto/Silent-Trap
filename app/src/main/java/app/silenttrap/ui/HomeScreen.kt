package app.silenttrap.ui

import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.silenttrap.data.LiveStatus
import app.silenttrap.data.RunState
import app.silenttrap.data.SegmentRecord
import app.silenttrap.data.UploadState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    live: LiveStatus,
    segments: List<SegmentRecord>,
    fileFor: (SegmentRecord) -> File,
    signedIn: Boolean,
    autoUpload: Boolean,
    keepLocal: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpload: (SegmentRecord) -> Unit,
    onDelete: (SegmentRecord) -> Unit,
    onShare: (SegmentRecord) -> Unit,
    onPlaybackError: (String) -> Unit,
) {
    var playingId by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    val listening = live.runState == RunState.LISTENING || live.runState == RunState.SPEECH
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Silent Trap") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(live) }
            item {
                Button(
                    onClick = { if (listening) onStop() else onStart() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Text(
                        if (listening) "Stop listening" else "Start listening",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    buildString {
                        append("The notification stays up while the microphone is on. Each clip keeps the seconds before the speech, the speech, and the seconds after.")
                        when {
                            autoUpload && !signedIn ->
                                append(" Sign in with Google on the settings screen. Until then, clips stay on this phone.")
                            autoUpload && keepLocal == 0 ->
                                append(" Finished clips go to Google Drive, and the phone copy is removed after the upload succeeds.")
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text("Recent clips", style = MaterialTheme.typography.titleMedium) }
            if (segments.isEmpty()) {
                item {
                    Text(
                        "Nothing saved yet. Start listening and speak quietly. A whisper should move the meter. The clip shows up after the phrase ends.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(segments, key = { it.id }) { record ->
                SegmentCard(
                    record = record,
                    playing = playingId == record.id,
                    hasFile = !record.localDeleted && fileFor(record).exists(),
                    onPlay = {
                        val file = fileFor(record)
                        try {
                            player.reset()
                            player.setDataSource(file.absolutePath)
                            player.setOnCompletionListener { playingId = null }
                            player.prepare()
                            player.start()
                            playingId = record.id
                        } catch (error: Exception) {
                            playingId = null
                            onPlaybackError(error.message ?: "Playback failed")
                        }
                    },
                    onShare = { onShare(record) },
                    onUpload = { onUpload(record) },
                    onDelete = { onDelete(record) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(live: LiveStatus) {
    val listening = live.runState == RunState.LISTENING || live.runState == RunState.SPEECH
    val speech = live.runState == RunState.SPEECH
    val dot by animateColorAsState(
        targetValue = when {
            speech -> MaterialTheme.colorScheme.primary
            listening -> Color(0xFF8C9A78)
            live.runState == RunState.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "speech-dot",
    )
    val label = when {
        live.runState == RunState.ERROR -> "Error"
        speech -> "Speech"
        live.pendingSaves > 0 -> "Saving"
        live.uploading > 0 -> "Uploading"
        listening -> "Listening"
        else -> "Idle"
    }
    val meter = ((live.levelDbfs + 70) / 60f).coerceIn(0f, 1f)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(dot))
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(
                micLine(live),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(progress = { meter }, modifier = Modifier.fillMaxWidth())
            Text(
                "Input ${live.levelDbfs} dBFS",
                style = MaterialTheme.typography.labelLarge,
            )
            if (listening) {
                Text(
                    "Detector boost ${live.analysisGain.toInt().coerceAtLeast(1)}× on this frame",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            live.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (live.noiseReductionFallback) {
                Text(
                    "Noise reduction is unavailable on this device. Clips still get the high-pass filter and gain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun micLine(live: LiveStatus): String {
    val mic = live.micLabel.ifBlank { "Microphone idle" }
    val uploads = if (live.uploading > 0) " · Uploading ${live.uploading}" else ""
    return mic + uploads
}

@Composable
private fun SegmentCard(
    record: SegmentRecord,
    playing: Boolean,
    hasFile: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Text(formatWhen(record.createdAtEpochMs), fontWeight = FontWeight.Medium)
            Text(
                "${formatDuration(record.durationMs)} · in ${record.inputPeakDbfs.toInt()} dB → out ${record.outputPeakDbfs.toInt()} dB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uploadLabel(record),
                style = MaterialTheme.typography.bodySmall,
                color = if (record.uploadState == UploadState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlay, enabled = hasFile) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = if (playing) "Playing" else "Play")
                }
                IconButton(onClick = onShare, enabled = hasFile) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
                IconButton(onClick = onUpload, enabled = hasFile && record.uploadState != UploadState.UPLOADED) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "Upload")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun uploadLabel(record: SegmentRecord): String {
    val state = when (record.uploadState) {
        UploadState.PENDING -> "Waiting to upload"
        UploadState.UPLOADING -> "Uploading"
        UploadState.UPLOADED -> if (record.localDeleted) "On Drive · local copy removed" else "On Drive"
        UploadState.FAILED -> record.error ?: "Upload failed"
        UploadState.LOCAL_ONLY -> if (record.localDeleted) "Local copy removed" else "On this phone"
    }
    return state
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatWhen(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(WHEN)
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a")
