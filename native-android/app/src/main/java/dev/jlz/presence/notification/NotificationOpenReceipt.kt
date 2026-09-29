package dev.jlz.presence.notification

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore
import org.json.JSONObject

/**
 * Device-side evidence for explicit user interaction with our OWN notification.
 *
 * Post == notification manager accepted a call.
 * Active == Android enumerated an active notification.
 * Opened/replied == Android delivered an explicit tap/inline reply intent.
 * None of these proves that the user read the whole text or saw a heads-up banner.
 *
 * Uses the existing durable notification outbox, so temporary network failure
 * doesn't lose the event. Neither private reply text nor other apps' notifications
 * are copied into this receipt.
 */
object NotificationOpenReceipt {
    const val EXTRA_FROM_NOTIFICATION = "jlz_from_notification"

    fun recordOpen(
        context: Context, eventId: String?, intentId: String?,
        notificationId: Int
    ) = record(context, "notification_opened", "用户点击了世界之间通知",
        eventId, intentId, notificationId)

    fun recordReply(
        context: Context, eventId: String?, intentId: String?,
        notificationId: Int
    ) = record(context, "notification_replied", "用户从通知中回复",
        eventId, intentId, notificationId)

    private fun record(
        context: Context, kind: String, title: String, eventId: String?,
        intentId: String?, notificationId: Int
    ) {
        if (eventId.isNullOrBlank() || eventId.length > 100) return
        val appContext = context.applicationContext
        val occurredAt = System.currentTimeMillis()
        val receiptId = kind + ":" + eventId
        val metadata = JSONObject()
            .put("notification_event_id", eventId)
            .put("notification_id", notificationId)
            .put("intent_id", intentId ?: JSONObject.NULL)
            .put("interaction_at_ms", occurredAt)
            .put("evidence", if (kind == "notification_opened")
                "android_explicit_notification_tap" else "android_inline_reply_saved")
            .put("heads_up_display_verified", false)
            .put("whole_message_read_verified", false)
        // Persist BEFORE any optional HTTP attempt. The runtime heartbeat
        // already flushes PendingNotificationEventStore without duplicates.
        PendingNotificationEventStore(appContext).enqueue(
            id = receiptId, kind = kind, title = title, body = "",
            packageName = appContext.packageName, metadata = metadata
        )
        LocalLifeStore(appContext).recordTimeline(
            type = kind, title = title, detail = "",
            eventId = eventId, intentId = intentId,
            id = receiptId, createdAtMs = occurredAt,
            metadataJson = metadata.toString()
        )
    }
}
