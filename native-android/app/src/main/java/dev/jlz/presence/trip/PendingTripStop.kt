package dev.jlz.presence.trip

import android.content.Context
import dev.jlz.presence.runtime.RuntimeApiClient

/** Stores a stopped session ID only (no coordinates) to finish server stop after outages. */
object PendingTripStop {
    private const val PREFS = "jlz_trip_stop_pending_v1"
    private const val KEY = "stopped_session"
    fun remember(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, sessionId).apply()
    }
    fun flush(context: Context, api: RuntimeApiClient): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sid = prefs.getString(KEY, "").orEmpty()
        if (sid.isBlank()) return true
        return try {
            val result = api.stopTrip(sid)
            if (result.optBoolean("ok", false)) prefs.edit().remove(KEY).apply()
            result.optBoolean("ok", false)
        } catch (e: Exception) {
            // A stale/unknown session cannot be incorrectly marked as live data.
            false
        }
    }
}
