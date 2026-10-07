package dev.jlz.presence.notification

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * One-time 2026-10-07 notification identity reset.
 *
 * HyperOS can keep stale per-package notification metadata after APK updates.
 * Rotate channels and resource names, cancel old posted notifications, then
 * let foreground services recreate their current v3 notifications.
 */
object NotificationIdentityMigration {
    private const val PREFS = "jlz_notification_identity_migration"
    private const val KEY_VERSION = "version"
    private const val VERSION = 2026100701

    private val legacyChannels = arrayOf(
        "jlz_native_runtime",
        "jlz_native_runtime_v2",
        "jlz_presence_overlay",
        "jlz_presence_overlay_v2",
        "jlz_study_progress_v1",
        "jlz_study_progress_v2",
        "jlz_trip",
        "jlz_trip_v2",
        "jlz_presence_messages",
        "jlz_presence_messages_v2",
        "jlz_presence_call",
        "jlz_presence_callback"
    )

    @Synchronized
    fun ensureFresh(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return

        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()

        if (Build.VERSION.SDK_INT >= 26) {
            legacyChannels.forEach { channelId ->
                runCatching { manager.deleteNotificationChannel(channelId) }
            }
        }

        check(prefs.edit().putInt(KEY_VERSION, VERSION).commit()) {
            "notification_identity_migration_save_failed"
        }
    }
}
