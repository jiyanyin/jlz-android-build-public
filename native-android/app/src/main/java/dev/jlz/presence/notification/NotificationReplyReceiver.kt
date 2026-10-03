package dev.jlz.presence.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val originalTitle = intent.getStringExtra(EXTRA_ORIGINAL_TITLE).orEmpty()
        val originalMessage = intent.getStringExtra(EXTRA_ORIGINAL_MESSAGE).orEmpty()
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val intentId = intent.getStringExtra(EXTRA_INTENT_ID)

        ReplyDeliveryTrace.write(
            appContext,
            "receiver_entered",
            notificationId,
            if (notificationId < 0) "notification_id_missing" else "remote_input_pending"
        )
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        val notificationAdapter = NotificationAdapter(appContext)

        if (reply.isBlank()) {
            ReplyDeliveryTrace.write(
                appContext, "no_remote_input", notificationId, "reply_blank_or_missing"
            )
            runCatching {
                notificationAdapter.updateReplyState(
                    notificationId, originalTitle, originalMessage, "",
                    "没有读取到回复，请打开「找我」再说一次。"
                )
            }
            return
        }

        if (reply.length > 1200) {
            runCatching {
                notificationAdapter.updateReplyState(
                    notificationId, originalTitle, originalMessage, reply.take(60),
                    "这条太长了，打开「找我」分段发给我。"
                )
            }
            return
        }

        // First persist the user's words before acknowledging the UI.
        // A temporarily offline phone must not lose her reply.
        val receiptAtMs = System.currentTimeMillis()
        val pendingReply = runCatching {
            PendingReplyStore(appContext).keep(
                text = reply,
                parentEventId = eventId,
                intentId = intentId,
                observedAtMs = receiptAtMs,
                replyToTitle = originalTitle,
                replyToText = originalMessage
            )
        }.getOrElse {
            ReplyDeliveryTrace.write(
                appContext, "local_save_failed", notificationId, it.javaClass.simpleName
            )
            runCatching {
                notificationAdapter.updateReplyState(
                    notificationId, originalTitle, originalMessage, reply,
                    "本机保存失败，请打开「找我」重发。"
                )
            }
            return
        }
        runCatching {
            notificationAdapter.updateReplyState(
                notificationId, originalTitle, originalMessage, reply,
                "已收到，正在送达 Runtime…"
            )
        }.onSuccess { updated ->
            ReplyDeliveryTrace.write(
                appContext,
                if (updated) "notification_acknowledged" else "notification_id_missing",
                notificationId
            )
        }.onFailure {
            ReplyDeliveryTrace.write(
                appContext, "notification_ack_failed", notificationId, it.javaClass.simpleName
            )
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = RuntimeSettingsRepository(appContext).load()
                require(settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                    "runtime_not_configured"
                }
                ReplyDeliveryTrace.write(appContext, "posting_runtime", notificationId)

                val sentIds = PendingReplyStore(appContext)
                    .sync(RuntimeApiClient(settings), limit = 40)
                require(pendingReply.id in sentIds) {
                    "reply_saved_waiting_for_retry"
                }

                ReplyDeliveryTrace.write(appContext, "server_ack", notificationId)
                runCatching {
                    LocalLifeStore(appContext).recordTimeline(
                        type = "reply",
                        title = "你从通知里回了我",
                        detail = reply,
                        eventId = eventId,
                        intentId = intentId,
                        metadataJson = JSONObject()
                            .put("delivery", "confirmed")
                            .put("message_id", pendingReply.id)
                            .toString()
                    )
                }.onFailure {
                    ReplyDeliveryTrace.write(
                        appContext, "local_timeline_failed", notificationId,
                        it.javaClass.simpleName
                    )
                }

                // Android may throttle multiple updates to the same notification
                // inside one second. Allow the initial spinner-clearing update
                // to settle before reporting final delivery.
                val waitMs = 1_200L - (System.currentTimeMillis() - receiptAtMs)
                if (waitMs > 0L) delay(waitMs)

                val updated = notificationAdapter.updateReplyState(
                    notificationId, originalTitle, originalMessage, reply,
                    "已送达"
                )
                ReplyDeliveryTrace.write(
                    appContext,
                    if (updated) "delivery_notification_updated" else "notification_id_missing",
                    notificationId,
                    if (updated) "server_ack_and_notification_updated"
                    else "server_ack_but_notification_id_missing"
                )
            } catch (failure: Exception) {
                ReplyDeliveryTrace.write(
                    appContext,
                    "delivery_unconfirmed",
                    notificationId,
                    failure.javaClass.simpleName
                )
                runCatching {
                    LocalLifeStore(appContext).recordTimeline(
                        type = "reply_unsent",
                        title = "通知回复已存本机 · 待补送",
                        detail = reply,
                        eventId = eventId,
                        intentId = intentId,
                        metadataJson = JSONObject()
                            .put("delivery", "unconfirmed")
                            .put("error_type", failure.javaClass.simpleName)
                            .toString()
                    )
                }
                runCatching {
                    notificationAdapter.updateReplyState(
                        notificationId, originalTitle, originalMessage, reply,
                        "回复已保存在本机；联网后会自动补送。"
                    )
                }.onFailure {
                    ReplyDeliveryTrace.write(
                        appContext,
                        "failure_notification_update_failed",
                        notificationId,
                        it.javaClass.simpleName
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY = "jlz_presence_inline_reply"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_INTENT_ID = "intent_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ORIGINAL_MESSAGE = "original_message"
        const val EXTRA_ORIGINAL_TITLE = "original_title"
    }
}
