package dev.jlz.presence.trip

import android.content.Context
import dev.jlz.presence.runtime.RuntimeApiClient

/** Stores a stopped session ID only (no coordinates) to finish server stop after outages. */
object PendingTripStop {
    private const val PREFS = "jlz_trip_stop_pending_v1"
    private const val KEY = "stopped_session"
    private const val CREATED = "created_at_ms"
    private const val TRIED = "last_attempt_ms"
    private const val RETRY_MS = 120_000L
    private const val KEEP_MS = 24*60*60*1000L
    fun remember(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, sessionId)
            .putLong(CREATED, System.currentTimeMillis())
            .remove(TRIED).apply()
    }
    fun flush(context: Context, api: RuntimeApiClient): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sid = prefs.getString(KEY, "").orEmpty()
        if (sid.isBlank()) return true
        val now = System.currentTimeMillis()
        val created = prefs.getLong(CREATED, now)
        if (now - created >= KEEP_MS) {
            prefs.edit().clear().apply()
            return true
        }
        if (now - prefs.getLong(TRIED, 0L) < RETRY_MS) return false
        prefs.edit().putLong(TRIED, now).apply()
        return try {
            val result = api.stopTrip(sid)
            if (result.optBoolean("ok", false)) prefs.edit().clear().apply()
            result.optBoolean("ok", false)
        } catch (e: Exception) {
            // A stale/unknown session cannot be incorrectly marked as live data.
            false
        }
    }
}
