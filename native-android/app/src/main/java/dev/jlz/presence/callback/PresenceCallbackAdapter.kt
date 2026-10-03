package dev.jlz.presence.callback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jlz.presence.R
import dev.jlz.presence.data.LocalLifeStore
import java.util.UUID

data class CallbackResult(
    val ok: Boolean,
    val code: String,
    val eventId: String,
    val intentId: String
)

class PresenceCallbackAdapter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(
        reason: String,
        topic: String = "",
        eventId: String = UUID.randomUUID().toString(),
        intentId: String = UUID.randomUUID().toString()
    ): CallbackResult {
        ensureChannel()
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return CallbackResult(false, "notification_permission_missing", eventId, intentId)
        }

        val requestCode = synchronized(idLock) {
            val preferences = context.applicationContext.getSharedPreferences(
                "jlz_presence_callback_id_v1", Context.MODE_PRIVATE
            )
            val id = preferences.getInt("next_id", 5200)
                .let { if (it >= 1_900_000_000) 5200 else it + 1 }
            check(preferences.edit().putInt("next_id", id).commit()) {
                "callback_id_save_failed"
            }
            id
        }
        val intent = Intent(context, PresenceCallbackActivity::class.java)
            .putExtra(PresenceCallbackActivity.EXTRA_EVENT_ID, eventId)
            .putExtra(PresenceCallbackActivity.EXTRA_INTENT_ID, intentId)
            .putExtra(PresenceCallbackActivity.EXTRA_REASON, reason)
            .putExtra(PresenceCallbackActivity.EXTRA_TOPIC, topic)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val fullScreen = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.jlz_chat_avatar))
            .setContentTitle("纪临洲正在找你")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(requestCode, notification)
        } catch (error: Exception) {
            return CallbackResult(false,
                "incoming_notification_rejected:" + error.javaClass.simpleName,
                eventId, intentId)
        }
        LocalLifeStore(context.applicationContext).recordTimeline(
            type = "callback",
            title = "我来找你",
            detail = reason,
            eventId = eventId,
            intentId = intentId,
            metadataJson = org.json.JSONObject().put("actor", "assistant").put("status", "outgoing").toString(),
            // Stable row id so accept/later results update THIS event, not a new card.
            id = stableRowId(eventId, intentId)
        )
        val fullScreenEligible = Build.VERSION.SDK_INT < 34 ||
            manager.canUseFullScreenIntent()
        return CallbackResult(
            true,
            if (fullScreenEligible) "call_notification_posted_fullscreen_allowed"
            else "call_notification_posted_heads_up_only_fullscreen_permission_required",
            eventId, intentId
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "纪临洲 · 过来",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "纪临洲需要把一件事直接带到你面前时使用"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "jlz_presence_callback"
        private val idLock = Any()

        /** Deterministic Timeline row id so the call and its result share one event. */
        fun stableRowId(eventId: String?, intentId: String?): String =
            "callback:" + (eventId?.takeIf { it.isNotBlank() } ?: intentId?.takeIf { it.isNotBlank() } ?: "unknown")
    }
}
