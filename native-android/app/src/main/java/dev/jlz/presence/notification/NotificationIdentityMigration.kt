package dev.jlz.presence.notification

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * One-time notification identity reset for RC5.5.
 *
 * HyperOS can retain old per-package notification metadata and system-created
 * autogroup summaries even after the APK and small-icon resources change.
 * This deliberately clears the package's posted notifications, retires the
 * old channels, then lets current services recreate fresh v2 channels.
 */
object NotificationIdentityMigration {
    private const val PREFS = "jlz_notification_identity_migration"
    private const val KEY_VERSION = "version"
    private const val VERSION = 2026100401

    private val legacyChannels = arrayOf(
        "jlz_native_runtime",
        "jlz_presence_overlay",
        "jlz_study_progress_v1",
        "jlz_trip",
        "jlz_presence_messages"
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
