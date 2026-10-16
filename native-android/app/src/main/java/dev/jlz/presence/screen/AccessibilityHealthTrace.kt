package dev.jlz.presence.screen

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Local, privacy-safe evidence for an Accessibility toggle that turns itself off.
 * Never re-enables a system permission: only the user can do that in Settings.
 */
object AccessibilityHealthTrace {
    private const val PREFS = "jlz_accessibility_health"
    private const val CONNECTED_AT = "connected_at"
    private const val UNBOUND_AT = "unbound_at"
    private const val DESTROYED_AT = "destroyed_at"

    fun onConnected(context: Context) = mark(context, CONNECTED_AT)
    fun onUnbound(context: Context) = mark(context, UNBOUND_AT)
    fun onDestroyed(context: Context) = mark(context, DESTROYED_AT)

    private fun mark(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key, System.currentTimeMillis()).apply()
    }

    data class Snapshot(
        val systemEnabled: Boolean,
        val serviceBound: Boolean,
        val lastConnectedAtMs: Long,
        val lastUnboundAtMs: Long,
        val lastDestroyedAtMs: Long,
        val latestProcessExitAtMs: Long?,
        val latestProcessExitReason: String?
    )

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val component = ComponentName(app, PresenceAccessibilityService::class.java)
        val enabled = runCatching {
            val raw = Settings.Secure.getString(
                app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            raw.split(':').any { value ->
                ComponentName.unflattenFromString(value) == component
            }
        }.getOrDefault(false)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val latestExit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                app.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(null, 0, 8)
                    ?.firstOrNull()
            }.getOrNull()
        } else null
        return Snapshot(
            systemEnabled = enabled,
            serviceBound = ScreenObservationBus.isAvailable(),
            lastConnectedAtMs = prefs.getLong(CONNECTED_AT, 0L),
            lastUnboundAtMs = prefs.getLong(UNBOUND_AT, 0L),
            lastDestroyedAtMs = prefs.getLong(DESTROYED_AT, 0L),
            latestProcessExitAtMs = latestExit?.timestamp,
            latestProcessExitReason = latestExit?.let { "code=${it.reason}, status=${it.status}" }
        )
    }
}
