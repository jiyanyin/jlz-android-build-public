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
    val traffic: dev.jlz.presence.capture.CaptureTrafficPolicy? = null,
    val reports: PendingCommandReportStore? = null,
    val bridge: BridgeStore? = null,
    val bridgeName: String = ""
)

internal fun canonicalNativeDeviceId(smallestScreenWidthDp: Int): String =
    if (smallestScreenWidthDp >= 600) {
        "android-tablet-native-n0"
    } else {
        "android-phone-native-n0"
    }

private fun defaultNativeDeviceId(context: Context): String =
    canonicalNativeDeviceId(context.resources.configuration.smallestScreenWidthDp)

class RuntimeSettingsRepository(private val context: Context) {
    private val reportStore by lazy { PendingCommandReportStore(context) }
    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val token = stringPreferencesKey("token")
        val deviceId = stringPreferencesKey("device_id")
    }

    suspend fun load(): RuntimeSettings =
        context.runtimeDataStore.data.map { prefs ->
            BridgeStore(context).select(RuntimeSettings(
                traffic = dev.jlz.presence.capture.CaptureTrafficPolicy(context),
                reports = reportStore,
                baseUrl = prefs[Keys.baseUrl].orEmpty(),
                token = prefs[Keys.token].orEmpty(),
                // Production identity is derived from the physical form factor only.
                // Historical/test IDs in DataStore must never choose a Runtime identity.
                deviceId = defaultNativeDeviceId(context)
            ))
        }.first()

    suspend fun save(settings: RuntimeSettings) {
        context.runtimeDataStore.edit { prefs ->
            prefs[Keys.baseUrl] = settings.baseUrl.trim().trimEnd('/')
            prefs[Keys.token] = settings.token.trim()
            // Persist the canonical identity so legacy arbitrary values self-heal
            // the next time settings are saved.
            prefs[Keys.deviceId] = defaultNativeDeviceId(context)
        }
    }
}
