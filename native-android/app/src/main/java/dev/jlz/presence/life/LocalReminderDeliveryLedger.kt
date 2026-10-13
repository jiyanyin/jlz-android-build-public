package dev.jlz.presence.life

import android.content.SharedPreferences

/**
 * Persist a once-per-scope reminder ONLY after Android accepts the notification.
 * A refused POST_NOTIFICATIONS permission, blocked channel or transient exception
 * must leave the intent eligible for the next local tick.
 *
 * All LocalLifeReminderEngine instances use one process-wide lock, so the
 * Android service and scheduled receiver cannot double-post the same key.
 */
internal class LocalReminderDeliveryLedger(private val prefs: SharedPreferences) {
    fun send(key: String, scope: String, deliver: (Int) -> Boolean): Boolean =
        synchronized(LOCK) {
            val stamp = "$scope:$key"
            if (prefs.getString("sent:$key", "") == stamp) return@synchronized false
            val ordinal = prefs.getInt("copy_count:$key", 0)
            if (!runCatching { deliver(ordinal) }.getOrDefault(false)) return@synchronized false
            prefs.edit()
                .putString("sent:$key", stamp)
                .putInt("copy_count:$key", ordinal + 1)
                .commit()
        }

    private companion object {
        val LOCK = Any()
    }
}
