package dev.jlz.presence.callback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R
import dev.jlz.presence.notification.NotificationIdentityMigration

class PresenceCallNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "jlz_presence_call_v3"
        const val NOTIF_ID = 2001
    }
    fun ensureChannel() {
        NotificationIdentityMigration.ensureFresh(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "\u5728\u573a\u6765\u7535", NotificationManager.IMPORTANCE_HIGH).apply { setBypassDnd(true) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
    fun showCall(eventId: String?, intentId: String?, reason: String, topic: String) {
        ensureChannel()
        val fsi = Intent(context, PresenceCallbackActivity::class.java).apply {
            putExtra(PresenceCallbackActivity.EXTRA_EVENT_ID, eventId)
            putExtra(PresenceCallbackActivity.EXTRA_INTENT_ID, intentId)
            putExtra(PresenceCallbackActivity.EXTRA_REASON, reason)
            putExtra(PresenceCallbackActivity.EXTRA_TOPIC, topic)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(context, eventId?.hashCode() ?: 0, fsi, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setContentTitle("\u7eaa\u4e34\u6d32\u6b63\u5728\u627e\u4f60")
            .setContentText(topic.ifBlank { reason })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi).setAutoCancel(true).setOngoing(true).build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
    }
    fun dismiss() { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
}
