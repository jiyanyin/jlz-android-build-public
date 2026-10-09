package dev.jlz.presence.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import dev.jlz.presence.R
import dev.jlz.presence.WebShellActivity
import java.util.UUID

data class NotificationResult(
    val ok: Boolean,
    val code: String,
    val notificationId: Int? = null,
    val eventId: String? = null,
    val intentId: String? = null
)

class NotificationAdapter(private val context: Context) {
    private val manager =
        context.getSystemService(NotificationManager::class.java)

    fun showMessage(
        title: String = "我在找你",
        message: String,
        eventId: String = UUID.randomUUID().toString(),
        intentId: String = UUID.randomUUID().toString()
    ): NotificationResult {
        ensureChannel()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationResult(
                ok = false,
                code = "notification_permission_missing"
            )
        }

        val notificationId = nextNotificationId()
        val avatar = createAvatarBitmap()

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://between-worlds-prod.onrender.com/?shell=android#echo"),
                context,
                WebShellActivity::class.java
            )
                .putExtra(NotificationReplyReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(NotificationReplyReceiver.EXTRA_INTENT_ID, intentId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val replyFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, NotificationReplyReceiver::class.java)
                .putExtra(
                    NotificationReplyReceiver.EXTRA_NOTIFICATION_ID,
                    notificationId
                )
                .putExtra(
                    NotificationReplyReceiver.EXTRA_ORIGINAL_MESSAGE,
                    message
                )
                .putExtra(
                    NotificationReplyReceiver.EXTRA_ORIGINAL_TITLE,
                    title
                )
                .putExtra(
                    NotificationReplyReceiver.EXTRA_EVENT_ID,
                    eventId
                )
                .putExtra(
                    NotificationReplyReceiver.EXTRA_INTENT_ID,
                    intentId
                ),
            replyFlags
        )

        val remoteInput = RemoteInput.Builder(
            NotificationReplyReceiver.KEY_REPLY
        )
            .setLabel("回我一句")
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "回复",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val user = Person.Builder().setName("你").build()
        val companion = Person.Builder()
            .setName("纪临洲")
            .setIcon(IconCompat.createWithBitmap(avatar))
            .setImportant(true)
            .build()

        // This is a private 1:1 chat, not a group with a "我在找你" room title.
        // Some OEM notification layouts pick the local user's "你" monogram
        // as the group avatar when a conversation title is supplied.
        val style = JlzMessagingStyle.incoming(
            user, companion, message, System.currentTimeMillis()
        )

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setLargeIcon(avatar)
            .setContentTitle(title.ifBlank { "我在找你" })
            .setContentText(message)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()

        manager.notify(notificationId, notification)

        return NotificationResult(
            ok = true,
            code = "shown",
            notificationId = notificationId,
            eventId = eventId,
            intentId = intentId
        )
    }


    /**
     * Android's direct-reply spinner is completed by re-posting the ORIGINAL
     * notification with the SAME id and tag. Cancelling alone is not a
     * reliable completion handshake on Android/OEM MessagingStyle surfaces.
     */
    fun updateReplyState(
        notificationId: Int,
        originalTitle: String,
        originalMessage: String,
        reply: String,
        status: String
    ): Boolean {
        if (notificationId < 0) return false
        val avatar = createAvatarBitmap()
        val user = Person.Builder().setName("你").build()
        val companion = Person.Builder()
            .setName("纪临洲")
            .setIcon(IconCompat.createWithBitmap(avatar))
            .build()

        val now = System.currentTimeMillis()
        val style = JlzMessagingStyle.afterReply(
            user, companion, originalMessage, reply, now
        )

        val openChat = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://between-worlds-prod.onrender.com/?shell=android#echo"),
                context,
                WebShellActivity::class.java
            )
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Deliberately keep the same channel, ID and null tag as showMessage().
        // We show an explicit status instead of silently retracting the message.
        val updated = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setLargeIcon(avatar)
            .setContentTitle(originalTitle.ifBlank { "纪临洲" })
            .setContentText(status)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openChat)
            .build()
        manager.notify(notificationId, updated)
        return true
    }

    /**
     * Channel importance and OS-level notification enablement are user
     * settings. Even IMPORTANCE_HIGH cannot guarantee an OEM heads-up banner.
     */
    fun headsUpStatus(): String {
        ensureChannel()
        if (!manager.areNotificationsEnabled()) {
            return "APP 通知已在系统中关闭；请打开通知管理。"
        }
        val importance = manager.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_UNSPECIFIED
        return when {
            importance == NotificationManager.IMPORTANCE_NONE ->
                "消息通知渠道被关闭；请打开系统通知管理。"
            importance < NotificationManager.IMPORTANCE_HIGH ->
                "消息通知渠道重要程度不足（importance=" + importance +
                    "）；请在系统中启用横幅/悬浮通知。"
            else ->
                "消息渠道重要程度为 HIGH；若仍无顶部横幅，请检查华为系统的横幅/悬浮通知、勿扰模式与通知提醒方式。"
        }
    }

    private fun nextNotificationId(): Int {
        synchronized(idLock) {
            val preferences = context.applicationContext.getSharedPreferences(
                "jlz_presence_notification_id_v1", Context.MODE_PRIVATE
            )
            val next = preferences.getInt("next_id", 4100)
                .let { if (it >= 1_900_000_000) 4100 else it + 1 }
            check(preferences.edit().putInt("next_id", next).commit()) {
                "notification_id_save_failed"
            }
            return next
        }
    }

    private fun ensureChannel() {
        NotificationIdentityMigration.ensureFresh(context)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "我发给你的消息",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description =
                        "JLZ Runtime 通过在场 App 发给你的主动消息"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun createAvatarBitmap(): Bitmap {
        val source = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.jlz_chat_avatar
        ) ?: error("jlz_chat_avatar_decode_failed")
        return if (source.width == AVATAR_SIZE && source.height == AVATAR_SIZE) {
            source
        } else {
            Bitmap.createScaledBitmap(
                source,
                AVATAR_SIZE,
                AVATAR_SIZE,
                true
            ).also {
                if (it !== source) source.recycle()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "jlz_presence_messages_v3"
        private const val AVATAR_SIZE = 128
        private val idLock = Any()
    }
}

/**
 * A direct conversation between the user and 纪临洲.
 *
 * Do not set conversationTitle on MessagingStyle for a one-to-one chat:
 * Android/OEM launchers may interpret it as a group conversation and show
 * the local user's fallback monogram instead of the incoming sender's icon.
 *
 * The sender Person already has our user-chosen planet avatar; this code
 * intentionally does NOT change drawable resources, Gate, or reply routing.
 */
internal object JlzMessagingStyle {
    fun incoming(
        user: Person,
        companion: Person,
        text: String,
        atMs: Long
    ): NotificationCompat.MessagingStyle =
        NotificationCompat.MessagingStyle(user)
            .setGroupConversation(false)
            .addMessage(text, atMs, companion)

    fun afterReply(
        user: Person,
        companion: Person,
        original: String,
        reply: String,
        atMs: Long
    ): NotificationCompat.MessagingStyle =
        NotificationCompat.MessagingStyle(user)
            .setGroupConversation(false)
            .addMessage(original, atMs - 1000L, companion)
            .addMessage(reply, atMs, user)
}
