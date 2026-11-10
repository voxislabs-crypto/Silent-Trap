package app.silenttrap.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.silenttrap.audio.WhisperGain
import app.silenttrap.data.CaptureSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: CaptureSettings,
    accountEmail: String?,
    onBack: () -> Unit,
    onChange: ((CaptureSettings) -> CaptureSettings) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle("Whisper detection")
            Text(
                "The speech detector hears a boosted copy of the microphone. Lower numbers catch quieter speech and also more room noise. The default is set for a whisper.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledSlider(
                title = "VAD threshold ${"%.2f".format(settings.vadThreshold)}",
                detail = "Whisper at 0.25 · stricter toward 0.90. Quiet frames are boosted up to ${WhisperGain.maxAnalysisGain(settings.vadThreshold).toInt()}× before Silero.",
                value = settings.vadThreshold,
                range = 0.10f..0.90f,
                onChange = { value -> onChange { it.copy(vadThreshold = value) } },
            )
            SectionTitle("Clip edges")
            LabeledSlider(
                title = "Pre-roll ${"%.1f".format(settings.preRollMs / 1000f)} s",
                detail = "Audio kept from before the speech starts.",
                value = settings.preRollMs / 1000f,
                range = 0.5f..10f,
                onChange = { value -> onChange { it.copy(preRollMs = (value * 1000).roundToInt()) } },
            )
            LabeledSlider(
                title = "Post-roll ${"%.1f".format(settings.postRollMs / 1000f)} s",
                detail = "Audio kept after the speech ends.",
                value = settings.postRollMs / 1000f,
                range = 0.5f..10f,
                onChange = { value -> onChange { it.copy(postRollMs = (value * 1000).roundToInt()) } },
            )
            SectionTitle("Amplification")
            Text(
                "Saved clips are gained, then softly clipped. Whisper makeup lifts a clip that is still quiet after that gain. Software noise reduction is not included in this build. Hardware noise suppression stays off, because it removes whispers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledSlider(
                title = "Gain ${settings.gain.roundToInt()}×",
                detail = "Applied when the clip is saved. 12× is the whisper default.",
                value = settings.gain,
                range = 1f..32f,
                onChange = { value -> onChange { it.copy(gain = value) } },
            )
            ToggleRow(
                title = "Whisper makeup",
                detail = "If the saved clip is still quiet, raise its peak toward a listenable level.",
                checked = settings.whisperMakeup,
                onChecked = { checked -> onChange { it.copy(whisperMakeup = checked) } },
            )
            ToggleRow(
                title = "High-pass filter",
                detail = "Cuts rumble around 120 Hz before the gain multiplies it.",
                checked = settings.highPass,
                onChecked = { checked -> onChange { it.copy(highPass = checked) } },
            )
            SectionTitle("Drive")
            ToggleRow(
                title = "Auto-upload",
                detail = "Uploads each clip to the Silent Trap Segments folder. Sign in below. A clip stays on the phone until Drive accepts it.",
                checked = settings.autoUpload,
                onChecked = { checked -> onChange { it.copy(autoUpload = checked) } },
            )
            LabeledSlider(
                title = if (settings.keepLocal == 0) {
                    "Don't keep a copy on the phone"
                } else {
                    "Keep ${settings.keepLocal} local clips"
                },
                detail = when {
                    settings.autoUpload && settings.keepLocal == 0 ->
                        "After Drive accepts a clip, the phone file is deleted. Uploads that are still waiting or failed stay here so they can be retried. Raise this to keep recent clips for playback."
                    settings.autoUpload ->
                        "After a successful upload, older copies past this count are removed. Pending and failed uploads stay on the phone."
                    settings.keepLocal == 0 ->
                        "Auto-upload is off. Zero removes every clip from this phone as soon as it is saved, without sending it to Drive."
                    else ->
                        "Auto-upload is off, so this caps how many clips stay on the phone."
                },
                value = settings.keepLocal.toFloat(),
                range = 0f..50f,
                onChange = { value -> onChange { it.copy(keepLocal = value.roundToInt()) } },
            )
            Text(
                if (accountEmail == null) "Google account: not signed in" else "Google account: $accountEmail",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSignIn) { Text(if (accountEmail == null) "Sign in" else "Re-auth") }
                if (accountEmail != null) {
                    OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                }
            }
            SectionTitle("Battery")
            Text(
                if (unrestricted) {
                    "Battery optimization is unrestricted for Silent Trap. That is what you want for all-day listening."
                } else {
                    "Android can pause the microphone in Doze. Set Silent Trap to Unrestricted under Battery."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open battery settings") }
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open app settings") }
            SectionTitle("Transcription")
            Text(
                "On-device transcription is not included. A small Whisper model can be added later. The model is large, and running it on every clip would compete with whisper capture.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LabeledSlider(
    title: String,
    detail: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
