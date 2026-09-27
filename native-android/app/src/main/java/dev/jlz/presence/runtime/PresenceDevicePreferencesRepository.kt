package dev.jlz.presence.runtime

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.presencePreferencesDataStore by preferencesDataStore(
    name = "jlz_presence_preferences"
)

data class PresenceDevicePreferences(
    val autoStartRuntime: Boolean = true,
    val restoreOverlay: Boolean = false,
    val overlayMode: String = "LIFE",
    val overlayMessage: String = "JLZ"
)

class PresenceDevicePreferencesRepository(private val context: Context) {
    private object Keys {
        val autoStart = booleanPreferencesKey("auto_start_runtime")
        val restoreOverlay = booleanPreferencesKey("restore_overlay")
        val overlayMode = stringPreferencesKey("overlay_mode")
        val overlayMessage = stringPreferencesKey("overlay_message")
    }

    suspend fun load(): PresenceDevicePreferences =
        context.presencePreferencesDataStore.data.map { prefs ->
            PresenceDevicePreferences(
                autoStartRuntime = prefs[Keys.autoStart] ?: true,
                restoreOverlay = prefs[Keys.restoreOverlay] ?: false,
                overlayMode = prefs[Keys.overlayMode] ?: "LIFE",
                overlayMessage = prefs[Keys.overlayMessage] ?: "JLZ"
            )
        }.first()

    suspend fun setOverlayState(
        enabled: Boolean,
        mode: String? = null,
        message: String? = null
    ) {
        context.presencePreferencesDataStore.edit { prefs ->
            prefs[Keys.restoreOverlay] = enabled
            mode?.takeIf { it.isNotBlank() }?.let {
                prefs[Keys.overlayMode] = it
            }
            message?.takeIf { it.isNotBlank() }?.let {
                prefs[Keys.overlayMessage] = it
            }
        }
    }

    suspend fun setRestoreOverlay(enabled: Boolean) {
        setOverlayState(enabled)
    }

    suspend fun setAutoStartRuntime(enabled: Boolean) {
        context.presencePreferencesDataStore.edit { prefs ->
            prefs[Keys.autoStart] = enabled
        }
    }
}
