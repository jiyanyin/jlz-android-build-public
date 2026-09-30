package dev.jlz.presence.runtime

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.runtimeDataStore by preferencesDataStore(name = "jlz_runtime")

data class RuntimeSettings(
    val baseUrl: String = "",
    val token: String = "",
    val deviceId: String = "android-phone-native-n0",
    val traffic: dev.jlz.presence.capture.CaptureTrafficPolicy? = null
)

class RuntimeSettingsRepository(private val context: Context) {
    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val token = stringPreferencesKey("token")
        val deviceId = stringPreferencesKey("device_id")
    }

    suspend fun load(): RuntimeSettings =
        context.runtimeDataStore.data.map { prefs ->
            RuntimeSettings(
                traffic = dev.jlz.presence.capture.CaptureTrafficPolicy(context),
                baseUrl = prefs[Keys.baseUrl].orEmpty(),
                token = prefs[Keys.token].orEmpty(),
                deviceId = prefs[Keys.deviceId].orEmpty().ifBlank { "android-phone-native-n0" }
            )
        }.first()

    suspend fun save(settings: RuntimeSettings) {
        context.runtimeDataStore.edit { prefs ->
            prefs[Keys.baseUrl] = settings.baseUrl.trim().trimEnd('/')
            prefs[Keys.token] = settings.token.trim()
            prefs[Keys.deviceId] = settings.deviceId.trim().ifBlank { "android-phone-native-n0" }
        }
    }
}
