package dev.jlz.presence.notification

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationSourceDataStore by preferencesDataStore(
    name = "jlz_notification_sources"
)

class NotificationSourceRepository(private val context: Context) {
    private object Keys {
        val allowedPackages = stringSetPreferencesKey("allowed_packages")
        val globalOptIn = booleanPreferencesKey("all_notification_sources_enabled")
        val seenPackages = stringSetPreferencesKey("seen_packages")
    }

    suspend fun seenPackages(): Set<String> =
        context.notificationSourceDataStore.data
            .map { it[Keys.seenPackages] ?: emptySet() }
            .first()

    suspend fun noteSeen(packageName: String) {
        if (packageName.isBlank()) return
        context.notificationSourceDataStore.edit { prefs ->
            prefs[Keys.seenPackages] = (prefs[Keys.seenPackages] ?: emptySet()) + packageName
        }
    }

    suspend fun allowedPackages(): Set<String> =
        context.notificationSourceDataStore.data
            .map { it[Keys.allowedPackages] ?: emptySet() }
            .first()

    suspend fun globalEnabled(): Boolean =
        context.notificationSourceDataStore.data
            .map { it[Keys.globalOptIn] ?: false }.first()

    suspend fun setGlobalEnabled(enabled: Boolean) {
        context.notificationSourceDataStore.edit { prefs ->
            prefs[Keys.globalOptIn] = enabled
        }
    }

    suspend fun isAllowed(packageName: String): Boolean =
        context.notificationSourceDataStore.data
            .map { prefs -> prefs[Keys.globalOptIn] == true ||
                packageName in (prefs[Keys.allowedPackages] ?: emptySet()) }
            .first()

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        if (packageName.isBlank()) return
        context.notificationSourceDataStore.edit { prefs ->
            val current = prefs[Keys.allowedPackages] ?: emptySet()
            prefs[Keys.allowedPackages] =
                if (allowed) current + packageName else current - packageName
        }
    }
}
