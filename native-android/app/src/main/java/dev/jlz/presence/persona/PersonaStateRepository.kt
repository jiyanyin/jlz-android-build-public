package dev.jlz.presence.persona

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.personaDataStore by preferencesDataStore(name = "jlz_persona")

data class PersonaState(
    val label: String = "在场",
    val phrase: String = "我在手机这边。",
    val initiativeLevel: Float = 0.5f,
    val updatedAtMs: Long = 0L
)

class PersonaStateRepository(private val context: Context) {
    private object Keys {
        val label = stringPreferencesKey("label")
        val phrase = stringPreferencesKey("phrase")
        val initiative = floatPreferencesKey("initiative")
        val updatedAt = longPreferencesKey("updated_at_ms")
    }

    val state: Flow<PersonaState> = context.personaDataStore.data.map { prefs ->
        PersonaState(
            label = prefs[Keys.label] ?: "在场",
            phrase = prefs[Keys.phrase] ?: "我在手机这边。",
            initiativeLevel = prefs[Keys.initiative] ?: 0.5f,
            updatedAtMs = prefs[Keys.updatedAt] ?: 0L
        )
    }

    suspend fun update(
        label: String?,
        phrase: String?,
        initiativeLevel: Float?
    ) {
        context.personaDataStore.edit { prefs ->
            label?.takeIf { it.isNotBlank() }?.let { prefs[Keys.label] = it }
            phrase?.takeIf { it.isNotBlank() }?.let { prefs[Keys.phrase] = it }
            initiativeLevel?.let { prefs[Keys.initiative] = it.coerceIn(0f, 1f) }
            prefs[Keys.updatedAt] = System.currentTimeMillis()
        }
    }
}
