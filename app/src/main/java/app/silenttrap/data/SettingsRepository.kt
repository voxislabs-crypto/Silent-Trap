package app.silenttrap.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.settingsStore by preferencesDataStore(name = "silent_trap_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _current = MutableStateFlow(CaptureSettings())
    val current: StateFlow<CaptureSettings> = _current
    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            dataStore.data.map { it.toSettings() }.collect { settings ->
                _current.value = settings
                if (!ready.isCompleted) ready.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() {
        ready.await()
    }

    fun update(block: (CaptureSettings) -> CaptureSettings) {
        val next = block(_current.value).sanitized()
        _current.value = next
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_THRESHOLD] = next.vadThreshold
                prefs[KEY_PRE] = next.preRollMs
                prefs[KEY_POST] = next.postRollMs
                prefs[KEY_GAIN] = next.gain
                prefs[KEY_NR] = next.noiseReduction
                prefs[KEY_HP] = next.highPass
                prefs[KEY_MAKEUP] = next.whisperMakeup
                prefs[KEY_UPLOAD] = next.autoUpload
                prefs[KEY_KEEP] = next.keepLocal
                prefs[KEY_NOTICE] = next.acceptedNotice
            }
        }
    }

    private fun Preferences.toSettings(): CaptureSettings {
        return CaptureSettings(
            vadThreshold = this[KEY_THRESHOLD] ?: 0.25f,
            preRollMs = this[KEY_PRE] ?: 3_000,
            postRollMs = this[KEY_POST] ?: 3_000,
            gain = this[KEY_GAIN] ?: 12f,
            noiseReduction = this[KEY_NR] ?: true,
            highPass = this[KEY_HP] ?: true,
            whisperMakeup = this[KEY_MAKEUP] ?: true,
            autoUpload = this[KEY_UPLOAD] ?: true,
            keepLocal = this[KEY_KEEP] ?: 0,
            acceptedNotice = this[KEY_NOTICE] ?: false,
        ).sanitized()
    }

    private companion object {
        val KEY_THRESHOLD = floatPreferencesKey("vad_threshold")
        val KEY_PRE = intPreferencesKey("pre_roll_ms")
        val KEY_POST = intPreferencesKey("post_roll_ms")
        val KEY_GAIN = floatPreferencesKey("gain")
        val KEY_NR = booleanPreferencesKey("noise_reduction")
        val KEY_HP = booleanPreferencesKey("high_pass")
        val KEY_MAKEUP = booleanPreferencesKey("whisper_makeup")
        val KEY_UPLOAD = booleanPreferencesKey("auto_upload")
        val KEY_KEEP = intPreferencesKey("keep_local")
        val KEY_NOTICE = booleanPreferencesKey("accepted_notice")
    }
}
