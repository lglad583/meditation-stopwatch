package com.meditation.stopwatch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meditation.stopwatch.audio.SoundId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meditation_settings")

/** Persisted user settings. Volumes are 0..1 per sound. */
data class Settings(
    val volumes: Map<SoundId, Float> = SoundId.entries.associateWith { it.defaultVolume },
    val masterVolume: Float = 0.8f,
    val breathGuide: Boolean = true,
    val breathLabels: Boolean = true,
    val keepScreenOn: Boolean = true,
    val renderScale: Float = 0.6f,
) {
    fun volume(id: SoundId): Float = volumes[id] ?: 0f
}

/**
 * Single process-wide settings repository.  Reads are exposed as a hot [StateFlow] so the audio
 * engine can poll `settings.value` without suspending; writes are fire-and-forget.
 */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<Settings> = store.data.map { p ->
        Settings(
            volumes = SoundId.entries.associateWith { id -> p[volKey(id)] ?: id.defaultVolume },
            masterVolume = p[MASTER] ?: 0.8f,
            breathGuide = p[BREATH_GUIDE] ?: true,
            breathLabels = p[BREATH_LABELS] ?: true,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
            renderScale = p[RENDER_SCALE] ?: 0.6f,
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    fun setVolume(id: SoundId, v: Float) = scope.launch { store.edit { it[volKey(id)] = v.coerceIn(0f, 1f) } }
    fun setMasterVolume(v: Float) = scope.launch { store.edit { it[MASTER] = v.coerceIn(0f, 1f) } }
    fun setBreathGuide(on: Boolean) = scope.launch { store.edit { it[BREATH_GUIDE] = on } }
    fun setBreathLabels(on: Boolean) = scope.launch { store.edit { it[BREATH_LABELS] = on } }
    fun setKeepScreenOn(on: Boolean) = scope.launch { store.edit { it[KEEP_SCREEN_ON] = on } }
    fun setRenderScale(s: Float) = scope.launch { store.edit { it[RENDER_SCALE] = s.coerceIn(0.3f, 1f) } }
    fun muteAll() = scope.launch { store.edit { p -> SoundId.entries.forEach { p[volKey(it)] = 0f } } }

    companion object {
        private fun volKey(id: SoundId) = floatPreferencesKey("vol_${id.name}")
        private val MASTER = floatPreferencesKey("master_volume")
        private val BREATH_GUIDE = booleanPreferencesKey("breath_guide")
        private val BREATH_LABELS = booleanPreferencesKey("breath_labels")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val RENDER_SCALE = floatPreferencesKey("render_scale")

        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) { instance ?: SettingsRepository(context).also { instance = it } }
    }
}
