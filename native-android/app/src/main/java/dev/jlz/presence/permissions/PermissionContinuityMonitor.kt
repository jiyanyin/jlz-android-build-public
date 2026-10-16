package dev.jlz.presence.permissions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jlz.presence.MainActivity
import dev.jlz.presence.R
import dev.jlz.presence.notification.PresenceNotificationListenerService
import dev.jlz.presence.screen.PresenceAccessibilityService
import dev.jlz.presence.screen.ScreenObservationBus

/**
 * Passive, user-consent-preserving permission continuity monitor.
 *
 * This is a local early-warning system, not an Accessibility permission enabler.
 * It never alters Android Settings, starts a background UI, or requests a new
 * special permission without the owner's explicit action.
 */
class PermissionContinuityMonitor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jlz_permission_continuity_v1", Context.MODE_PRIVATE)
    private val notifications by lazy { app.getSystemService(NotificationManager::class.java) }

    data class Observation(val key: String, val state: String, val text: String)

    fun check() {
        val observations = listOf(
            observe("accessibility", PresenceAccessibilityService::class.java,
                ScreenObservationBus.isAvailable()),
            observe("notification_listener", PresenceNotificationListenerService::class.java,
                PresenceNotificationListenerService.isConnected())
        )
        observations.forEach(::updateState)
    }

    private fun observe(key: String, cls: Class<*>, bound: Boolean): Observation {
        val raw = runCatching {
            Settings.Secure.getString(
                app.contentResolver,
                if (key == "accessibility") Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                else "enabled_notification_listeners"
            ).orEmpty()
        }.getOrDefault("")
        val target = ComponentName(app, cls)
        val granted = raw.split(':').any { ComponentName.unflattenFromString(it) == target }
        return when {
            !granted -> Observation(key, "system_disabled",
                if (key == "accessibility") "无障碍开关已关闭" else "通知监听授权已关闭")
            !bound -> Observation(key, "service_disconnected",
                if (key == "accessibility") "无障碍服务断开" else "通知监听服务未连接")
            else -> Observation(key, "healthy", "")
        }
    }

    private fun updateState(o: Observation) {
        val prefix = o.key + "_"
        val seenHealthy = prefs.getBoolean(prefix + "seen_healthy", false)
        val lastState = prefs.getString(prefix + "last_state", "") ?: ""
        if (o.state == "healthy") {
            prefs.edit()
                .putBoolean(prefix + "seen_healthy", true)
                .putString(prefix + "last_state", "healthy")
                .putInt(prefix + "consecutive", 0)
                .putBoolean(prefix + "alerted", false)
                .apply()
            return
        }
        val n = if (lastState == o.state) prefs.getInt(prefix + "consecutive", 0) + 1 else 1
        val alreadyAlerted = prefs.getBoolean(prefix + "alerted", false)
        prefs.edit().putString(prefix + "last_state", o.state)
            .putInt(prefix + "consecutive", n).apply()

        // No alert on first install or before the user has successfully enabled
        // a permission. Two consecutive checks reduce short reconnect false alarms.
        if (!seenHealthy || n < 2 || alreadyAlerted) return
        val now = System.currentTimeMillis()
        // Also avoid repetitive alerts if toggled several times in one afternoon.
        val lastNotify = prefs.getLong(prefix + "last_notified", 0L)
        if (now - lastNotify < 3 * 60 * 60 * 1000L) return

        // Record once even if Android notifications are disabled. A disabled
        // permission is not silently treated as a successful notification.
        prefs.edit().putBoolean(prefix + "alerted", true)
            .putLong(prefix + "last_notified", now).apply()
        runCatching {
            dev.jlz.presence.data.LocalLifeStore(app).recordTimeline(
                type = "permission_dropped",
                title = o.text,
                detail = "系统授权与服务连接双重检查；请在权限医生核验。",
                metadataJson = org.json.JSONObject()
                    .put("permission_key", o.key)
                    .put("detected_state", o.state)
                    .put("source", "android_local_permission_monitor")
                    .put("observed_at_ms", now)
                    .toString()
            )
        }
        notifyOwner(o)
    }

    private fun notifyOwner(o: Observation) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        if (!notifications.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "世界之间·权限提醒",
                    NotificationManager.IMPORTANCE_LOW)
            )
            if (notifications.getNotificationChannel(CHANNEL)
                    ?.importance == NotificationManager.IMPORTANCE_NONE) return
        }
        val intent = Intent(app, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_PERMISSIONS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notice = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setContentTitle("世界之间 · 权限需要检查")
            .setContentText(o.text + "。点此打开权限检查。")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notifications.notify(if (o.key == "accessibility") 43011 else 43012, notice)
    }

    companion object {
        private const val CHANNEL = "jlz_permission_diagnostics_v1"
    }
}
