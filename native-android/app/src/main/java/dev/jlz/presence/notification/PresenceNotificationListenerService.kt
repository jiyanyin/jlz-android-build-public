package dev.jlz.presence.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PresenceNotificationListenerService :
    NotificationListenerService() {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val healthDedup = mutableMapOf<String, Pair<String, Long>>()

    private fun shouldRecordHealth(key: String, title: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        val content = title + "\\n" + body
        synchronized(healthDedup) {
            val previous = healthDedup[key]
            if (previous != null && now - previous.second < 30_000L) {
                return false
            }
            if (previous != null && previous.first == content && now - previous.second < 300_000L) {
                return false
            }
            healthDedup[key] = content to now
            return true
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        // Persistent Huawei Health steps may have been posted before the user
        // granted notification access. Reuse the SAME filtering path for the
        // active Android notification snapshot; this is not Huawei UI scraping.
        runCatching { activeNotifications?.take(120)?.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {
        if (sbn.packageName == packageName) return
        // User-selected hard exclusion: VPN foreground status is noisy and
        // carries no useful life event. It must not enter audit, DB or Runtime.
        if (sbn.packageName == "org.ikuuu.vpn") return

        val extras = sbn.notification.extras
        val rawTitle = extras
            ?.getCharSequence("android.title")
            ?.toString()
            .orEmpty()
        val regularBody = extras
            ?.getCharSequence("android.text")
            ?.toString()
            .orEmpty()
        val bigBody = extras?.getCharSequence("android.bigText")?.toString().orEmpty()
        val lines = extras?.getCharSequenceArray("android.textLines")
            ?.joinToString(" · ") { it.toString() }.orEmpty()
        // Only text Android exposes to the listener; custom RemoteViews may
        // display extra numbers not present in these fields.
        val rawBody = listOf(regularBody, bigBody, lines)
            .filter { it.isNotBlank() }.distinct().joinToString("\n").take(1800)

        scope.launch {
            val appContext = applicationContext
            val sources =
                NotificationSourceRepository(appContext)

            sources.noteSeen(sbn.packageName)
            if (!sources.isAllowed(sbn.packageName)) {
                return@launch
            }

            val isHealth = HuaweiHealthNotificationReading.isHuaweiHealth(sbn.packageName)
            if (isHealth && !shouldRecordHealth(sbn.key, rawTitle, rawBody)) {
                return@launch
            }
            val policy = NotificationPrivacyPolicy.evaluate(
                packageName = sbn.packageName,
                title = rawTitle,
                body = rawBody,
                category = sbn.notification.category,
                flags = sbn.notification.flags,
                allowOngoingHealth = isHealth
            )

            val eventIdentity = (
                sbn.packageName + "|" + sbn.key + "|" + sbn.postTime + "|" +
                    policy.disposition.name + "|" + policy.title + "|" + policy.body
            )
            val eventId = UUID.nameUUIDFromBytes(
                eventIdentity.toByteArray(Charsets.UTF_8)
            ).toString()
            val metadata = JSONObject()
                .put("event_id", eventId)
                .put("package_name", sbn.packageName)
                .put("notification_key", sbn.key)
                .put("posted_at_ms", sbn.postTime)
                .put("policy", policy.disposition.name.lowercase())
                .put("policy_reason", policy.reason)
                .put("observed_at_ms", System.currentTimeMillis())
                .put("capture_kind", if (isHealth) "health_notification" else "notification")
                .put("source_kind", "android_notification_text")

            val isReadable = rawTitle.isNotBlank() || rawBody.isNotBlank()
            val accepted = isReadable &&
                policy.disposition != NotificationDisposition.DROP_LOW_VALUE
            if (!accepted) {
                metadata.put("audit_result", if (!isReadable) "no_readable_text" else "filtered")
                LocalLifeStore(appContext).recordTimeline(
                    type = "notification_observation",
                    title = "已观察到通知 · 无正文记录",
                    detail = "",
                    eventId = eventId,
                    id = eventId,
                    metadataJson = metadata.toString()
                )
                return@launch
            }
            metadata.put("audit_result", if (policy.disposition ==
                NotificationDisposition.MASK_SENSITIVE) "masked" else "recorded")
            // Runtime subtitle is intentionally short; retain the complete
            // privacy-filtered notification text in typed metadata.
            metadata.put("visible_title", policy.title.take(160))
            metadata.put("visible_body", policy.body.take(1600))
            val stepCount = if (isHealth && policy.disposition == NotificationDisposition.FORWARD) {
                HuaweiHealthNotificationReading.stepsIfShown(sbn.packageName, rawTitle, rawBody)
            } else null
            if (isHealth) {
                val kcal = HuaweiHealthNotificationReading.caloriesIfShown(
                    sbn.packageName, rawTitle, rawBody
                )
                if (stepCount != null) metadata.put("steps_from_visible_notification", stepCount)
                if (kcal != null) metadata.put("kcal_from_visible_notification", kcal)
                metadata.put("sensor_live", false)
                metadata.put("wearable_source_verified", false)
            }

            LocalLifeStore(appContext).recordTimeline(
                type = if (isHealth) "health_notification" else "notification",
                title = policy.title.ifBlank { sbn.packageName },
                detail = policy.body,
                eventId = eventId,
                id = eventId,
                createdAtMs = System.currentTimeMillis(),
                metadataJson = metadata.toString()
            )

            // Local persistence precedes best-effort HTTP; the next Runtime
            // heartbeat flushes any notifications received while offline.
            val outbox = PendingNotificationEventStore(appContext)
            outbox.enqueue(
                id = eventId,
                kind = if (isHealth) "health_notification" else "notification",
                title = policy.title,
                body = policy.body,
                packageName = sbn.packageName,
                metadata = metadata
            )
            runCatching {
                val settings = RuntimeSettingsRepository(appContext).load()
                if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                    outbox.sync(RuntimeApiClient(settings), limit = 25)
                }
            }
        }
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        scope.cancel()
        super.onDestroy()
    }

    private fun activeNotificationJson(sbn: StatusBarNotification): JSONObject {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = listOf(
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" · ") { it.toString() }.orEmpty()
        ).filter { it.isNotBlank() }.distinct().joinToString("\n")
        val isHealth = HuaweiHealthNotificationReading.isHuaweiHealth(sbn.packageName)
        val policy = NotificationPrivacyPolicy.evaluate(sbn.packageName, title, body,
            sbn.notification.category, sbn.notification.flags, allowOngoingHealth = isHealth)
        val visibleTitle = if (policy.disposition == NotificationDisposition.DROP_LOW_VALUE) "" else policy.title
        val visibleBody = if (policy.disposition == NotificationDisposition.DROP_LOW_VALUE) "" else policy.body
        val actions = JSONArray()
        sbn.notification.actions?.forEachIndexed { index, action ->
            actions.put(JSONObject().put("index", index).put("title", action.title?.toString().orEmpty().take(120))
                .put("semantic_action", action.semanticAction).put("has_remote_input", !action.remoteInputs.isNullOrEmpty()))
        }
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        return JSONObject().put("package", sbn.packageName).put("package_name", sbn.packageName)
            .put("app_name", appName).put("title", visibleTitle).put("body", visibleBody)
            .put("subtitle", extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().take(200))
            .put("posted_at", sbn.postTime).put("observed_at", System.currentTimeMillis())
            .put("notification_id", sbn.id).put("key", sbn.key)
            .put("ongoing", sbn.isOngoing).put("category", sbn.notification.category ?: JSONObject.NULL)
            .put("progress", JSONObject()
                .put("max", extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0)
                .put("value", extras?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0)
                .put("indeterminate", extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ?: false))
            .put("available_actions", actions).put("privacy", policy.disposition.name.lowercase())
            .put("steps", HuaweiHealthNotificationReading.stepsIfShown(sbn.packageName, title, body) ?: JSONObject.NULL)
            .put("calories_kcal", HuaweiHealthNotificationReading.caloriesIfShown(sbn.packageName, title, body) ?: JSONObject.NULL)
            .put("active", true)
    }

    companion object {
        @Volatile private var connected: PresenceNotificationListenerService? = null

        fun isConnected(): Boolean = connected != null

        /** Direct active-notification read. It is independent of upload throttling. */
        fun currentNotificationsJson(
            packageName: String? = null,
            sinceMs: Long = 0L,
            activeOnly: Boolean = true,
            limit: Int = 50
        ): JSONObject {
            val listener = connected ?: return JSONObject().put("ok", false)
                .put("reason", "notification_listener_not_connected")
            val safeLimit = limit.coerceIn(1, 120)
            val active = runCatching { listener.activeNotifications.orEmpty().asSequence()
                .filter { it.packageName != "org.ikuuu.vpn" }
                .filter { packageName.isNullOrBlank() || it.packageName == packageName }
                .filter { sinceMs <= 0L || it.postTime >= sinceMs }
                .sortedByDescending { it.postTime }.take(safeLimit)
                .map(listener::activeNotificationJson).toList() }.getOrElse {
                return JSONObject().put("ok", false).put("reason", "active_notification_read_failed")
                    .put("detail", (it.message ?: it.javaClass.simpleName).take(180))
            }
            val items = JSONArray(active)
            if (!activeOnly && items.length() < safeLimit) {
                val from = if (sinceMs > 0) sinceMs else System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                LocalLifeStore(listener.applicationContext).listNotificationObservations(from, safeLimit).forEach { event ->
                    val meta = runCatching { JSONObject(event.metadataJson) }.getOrDefault(JSONObject())
                    val pkg = meta.optString("package_name")
                    if ((packageName.isNullOrBlank() || pkg == packageName) && items.length() < safeLimit &&
                        active.none { it.optString("key") == meta.optString("notification_key") }) {
                        items.put(JSONObject().put("package", pkg).put("package_name", pkg)
                            .put("app_name", pkg).put("title", event.title).put("body", event.detail)
                            .put("subtitle", "").put("posted_at", meta.optLong("posted_at_ms", event.createdAtMs))
                            .put("observed_at", meta.optLong("observed_at_ms", event.createdAtMs))
                            .put("notification_id", JSONObject.NULL).put("key", meta.optString("notification_key"))
                            .put("ongoing", false).put("category", JSONObject.NULL)
                            .put("available_actions", JSONArray()).put("active", false).put("source", "local_recent_notification_store"))
                    }
                }
            }
            return JSONObject().put("ok", true).put("source", "android_notification_listener_active")
                .put("active_only", activeOnly).put("package_filter", packageName ?: JSONObject.NULL)
                .put("observed_at_ms", System.currentTimeMillis()).put("count", items.length()).put("notifications", items)
        }

        /** User-requested foreground diagnostic, not continuous polling. */
        fun rescanActive(): Int {
            val listener = connected ?: return -1
            return runCatching {
                val current = listener.activeNotifications?.take(120).orEmpty()
                current.forEach { listener.onNotificationPosted(it) }
                current.size
            }.getOrDefault(-1)
        }
    }
}
