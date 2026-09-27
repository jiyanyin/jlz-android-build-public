package dev.jlz.presence.notification

import android.content.Context

/**
 * Last inline-reply delivery trace; deliberately excludes the message text,
 * authentication token and private chat content.
 */
object ReplyDeliveryTrace {
    private const val PREFS = "jlz_reply_delivery_trace"

    fun write(context: Context, stage: String, notificationId: Int, detail: String = "") {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("stage", stage.take(80))
            .putInt("notification_id", notificationId)
            .putString("detail", detail.take(100))
            .putLong("updated_at_ms", System.currentTimeMillis())
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE
        )
        val stage = prefs.getString("stage", "尚未收到新的通知回复")
        val notificationId = prefs.getInt("notification_id", -1)
        val detail = prefs.getString("detail", "")
        val timestamp = prefs.getLong("updated_at_ms", 0L)
        return "stage=" + stage +
            " · id=" + notificationId +
            " · detail=" + detail +
            " · time_ms=" + timestamp
    }
}
