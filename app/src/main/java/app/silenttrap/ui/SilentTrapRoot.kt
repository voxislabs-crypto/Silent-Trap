package app.silenttrap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.silenttrap.SilentTrapApp
import app.silenttrap.data.UploadState
import app.silenttrap.drive.DriveAuth
import app.silenttrap.drive.DriveClient
import app.silenttrap.drive.UploadScheduler
import app.silenttrap.service.ListeningService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Home, Settings }

@Composable
fun SilentTrapRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as SilentTrapApp
    val settings by app.settings.current.collectAsState()
    val live by app.live.status.collectAsState()
    val segments by app.segments.records.collectAsState()
    var screen by remember { mutableStateOf(Screen.Home) }
    var accountEmail by remember { mutableStateOf(DriveAuth.email(context)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            accountEmail = DriveAuth.email(context)
            UploadScheduler.enqueuePending(context)
            scope.launch { snackbar.showSnackbar("Signed in") }
        } catch (error: ApiException) {
            if (error.statusCode != 12501) {
                scope.launch { snackbar.showSnackbar("Google sign-in failed (${error.statusCode})") }
            }
        } catch (error: Exception) {
            scope.launch { snackbar.showSnackbar(error.message ?: "Google sign-in failed") }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] == true
        if (mic) {
            ListeningService.start(context)
        } else {
            scope.launch { snackbar.showSnackbar("Microphone permission is required") }
        }
    }

    BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Home }

    if (!settings.acceptedNotice) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { app.settings.update { it.copy(acceptedNotice = true) } }) {
                    Text("I understand")
                }
            },
            title = { Text("Before you record") },
            text = {
                Text(
                    "Silent Trap records only while its notification is visible. You are responsible for following the law where you record. " +
                        "Nebraska is a one-party consent state: you may record a conversation you are part of. " +
                        "Recording a private conversation you are not part of can be illegal. This is not legal advice.",
                )
            },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    live = live,
                    segments = segments,
                    fileFor = app.segments::fileFor,
                    signedIn = accountEmail != null,
                    autoUpload = settings.autoUpload,
                    keepLocal = settings.keepLocal,
                    onStart = {
                        if (!settings.acceptedNotice) return@HomeScreen
                        val needed = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (needed.isEmpty()) ListeningService.start(context)
                        else permissions.launch(needed.toTypedArray())
                    },
                    onStop = { ListeningService.stop(context) },
                    onOpenSettings = { screen = Screen.Settings },
                    onUpload = { record ->
                        app.segments.update(record.id) { it.copy(uploadState = UploadState.PENDING, error = null) }
                        if (DriveAuth.account(context) == null) {
                            scope.launch { snackbar.showSnackbar("Sign in on the settings screen first") }
                        } else {
                            UploadScheduler.enqueue(context, record.id)
                        }
                    },
                    onDelete = { record ->
                        scope.launch {
                            val failure = withContext(Dispatchers.IO) {
                                val driveId = record.driveFileId
                                if (driveId != null && DriveAuth.account(context) != null) {
                                    try {
                                        DriveClient(context).delete(driveId)
                                        null
                                    } catch (error: Exception) {
                                        error.message ?: "Drive delete failed"
                                    }
                                } else {
                                    null
                                }
                            }
                            app.segments.deleteLocal(record.id)
                            if (failure != null) snackbar.showSnackbar("Removed on this phone. Drive: $failure")
                        }
                    },
                    onShare = { record ->
                        val file = app.segments.fileFor(record)
                        if (!file.exists()) {
                            scope.launch { snackbar.showSnackbar("That clip is no longer on this phone") }
                            return@HomeScreen
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/wav"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share clip"))
                    },
                    onPlaybackError = { message -> scope.launch { snackbar.showSnackbar(message) } },
                )
                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    accountEmail = accountEmail,
                    onBack = { screen = Screen.Home },
                    onChange = { block -> app.settings.update(block) },
                    onSignIn = { signIn.launch(DriveAuth.signInIntent(context)) },
                    onSignOut = {
                        DriveClient(context).clearCache()
                        DriveAuth.client(context).signOut().addOnCompleteListener {
                            accountEmail = null
                        }
                    },
                )
            }
        }
    }
}
