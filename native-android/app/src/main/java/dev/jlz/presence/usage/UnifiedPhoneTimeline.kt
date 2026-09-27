package dev.jlz.presence.usage

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class UnifiedTimelineItem(
    val id: String,
    val atMs: Long,
    val endMs: Long? = null,
    val title: String,
    val detail: String = "",
    val category: String,
    val origin: String,
    val actor: String,
    val source: String,
    val confidence: String = "",
    val side: Int = 0
)

data class UnifiedTimelineSnapshot(
    val generatedAtMs: Long,
    val startAtMs: Long,
    val items: List<UnifiedTimelineItem>,
    val screenOnCount: Int,
    val unlockCount: Int,
    val appSessionCount: Int,
    val foregroundMinutes: Long,
    val runtimeAttributedCount: Int
)

/**
 * Read-only presentation layer for "what happened on my phone today".
 *
 * Raw evidence remains in Android UsageEvents / DeviceActivityJournal /
 * LocalLifeStore. This class only normalizes those sources into one timeline.
 * USER_OR_NON_RUNTIME is intentionally displayed as phone-side evidence, not
 * proof that the human user performed the action.
 */
class UnifiedPhoneTimeline(private val context: Context) {
    private val activity = DeviceActivityJournal(context)
    private val life = LocalLifeStore(context)
    private val appLabelCache = mutableMapOf<String, String>()

    fun today(nowMs: Long = System.currentTimeMillis(), limit: Int = 500): UnifiedTimelineSnapshot {
        val start = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val screenJson = activity.readScreenTimeline(
            context = context,
            startMs = start,
            endMs = nowMs,
            limit = limit.coerceIn(50, 500)
        )
        val appJson = activity.readAppSessions(
            context = context,
            startMs = start,
            endMs = nowMs,
            limit = limit.coerceIn(50, 500)
        )

        val screenItems = mutableListOf<UnifiedTimelineItem>()
        val screenArray = screenJson.optJSONArray("items")
        var screenOnCount = 0
        var unlockCount = 0
        if (screenArray != null) {
            for (i in 0 until screenArray.length()) {
                val item = screenArray.optJSONObject(i) ?: continue
                val type = item.optString("event_type")
                val at = item.optLong("wall_clock_timestamp")
                if (at <= 0L) continue
                if (type == "SCREEN_ON") screenOnCount++
                if (type == "KEYGUARD_HIDDEN") unlockCount++
                val origin = item.optString("origin", "UNKNOWN")
                val title = when (type) {
                    "SCREEN_ON" -> "亮屏"
                    "SCREEN_OFF" -> "熄屏"
                    "KEYGUARD_SHOWN" -> "进入锁屏"
                    "KEYGUARD_HIDDEN" -> "解锁"
                    "USER_PRESENT" -> "进入手机"
                    "DEVICE_BOOT" -> "手机启动"
                    "DATA_GAP_START" -> "记录中断"
                    "DATA_GAP_END" -> "记录恢复"
                    else -> type.ifBlank { "设备事件" }
                }
                val detailParts = mutableListOf<String>()
                item.optString("foreground_package").takeIf { it.isNotBlank() && it != "null" }?.let {
                    detailParts += appLabel(it)
                }
                item.optString("screen_off_reason").takeIf { it.isNotBlank() }?.let {
                    detailParts += when (it) {
                        "RUNTIME_OR_TEST_SCREEN_OFF" -> "由 Runtime/测试触发"
                        "NON_RUNTIME_SCREEN_OFF" -> "非 Runtime 熄屏"
                        else -> it
                    }
                }
                screenItems += UnifiedTimelineItem(
                    id = item.optString("event_id", "screen:$at:$type"),
                    atMs = at,
                    title = title,
                    detail = detailParts.joinToString(" · "),
                    category = "PHONE",
                    origin = origin,
                    actor = item.optString("actor", actorFor(origin)),
                    source = item.optString("source", screenJson.optString("source", "device")),
                    confidence = if (item.has("command_id") && !item.isNull("command_id")) "high_command_correlation" else "",
                    side = if (origin == "JLZ_RUNTIME") -1 else 0
                )
            }
        }

        val appItems = mutableListOf<UnifiedTimelineItem>()
        var foregroundMs = 0L
        val sessions = appJson.optJSONArray("sessions")
        if (sessions != null) {
            for (i in 0 until sessions.length()) {
                val item = sessions.optJSONObject(i) ?: continue
                val pkg = item.optString("package_name")
                val startMs = item.optLong("session_start")
                val endMs = item.optLong("session_end")
                val duration = item.optLong("duration_ms")
                if (pkg.isBlank() || startMs <= 0L || duration < 3_000L || isSystemNoise(pkg)) continue
                foregroundMs += duration
                val origin = item.optString("origin", "UNKNOWN")
                appItems += UnifiedTimelineItem(
                    id = "app:$pkg:$startMs:$endMs",
                    atMs = startMs,
                    endMs = endMs.takeIf { it > startMs },
                    title = "使用 ${appLabel(pkg)}",
                    detail = formatDuration(duration),
                    category = "APP",
                    origin = origin,
                    actor = item.optString("actor", actorFor(origin)),
                    source = item.optString("source", "android_usage_events"),
                    confidence = item.optString("confidence"),
                    side = if (origin == "JLZ_RUNTIME") -1 else 0
                )
            }
        }

        val lifeItems = life.listTimelineSince(start, 500).mapNotNull { ev ->
            if (ev.type in setOf("notification", "notification_observation", "health_notification")) {
                return@mapNotNull null
            }
            val meta = runCatching { JSONObject(ev.metadataJson) }.getOrNull()
            val actor = meta?.optString("actor").orEmpty()
            UnifiedTimelineItem(
                id = "life:${ev.id}",
                atMs = ev.createdAtMs,
                title = ev.title,
                detail = ev.detail,
                category = "RELATION",
                origin = when (actor) {
                    "assistant" -> "JLZ_RUNTIME"
                    "user" -> "USER"
                    "system" -> "SYSTEM"
                    else -> "LOCAL"
                },
                actor = actor.ifBlank { "local" },
                source = "local_life_store",
                side = when (actor) {
                    "assistant" -> -1
                    "user" -> 1
                    else -> 0
                }
            )
        }

        val merged = (screenItems + appItems + lifeItems)
            .sortedByDescending { it.atMs }
            .take(limit.coerceIn(50, 1000))

        return UnifiedTimelineSnapshot(
            generatedAtMs = nowMs,
            startAtMs = start,
            items = merged,
            screenOnCount = screenOnCount,
            unlockCount = unlockCount,
            appSessionCount = appItems.size,
            foregroundMinutes = foregroundMs / 60_000L,
            runtimeAttributedCount = merged.count { it.origin == "JLZ_RUNTIME" }
        )
    }

    private fun appLabel(packageName: String): String = appLabelCache.getOrPut(packageName) {
        when (packageName) {
            "com.openai.chatgpt" -> "ChatGPT"
            "com.tencent.mm" -> "微信"
            "com.xingin.xhs" -> "小红书"
            "dev.jlz.presence" -> "世界之间"
            "com.android.settings" -> "系统设置"
            "com.miui.home" -> "桌面"
            else -> runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName.substringAfterLast('.'))
        }
    }

    private fun isSystemNoise(packageName: String): Boolean =
        packageName in setOf(
            "android",
            "com.android.systemui",
            "miui.systemui.plugin",
            "com.sohu.inputmethod.sogou",
            "com.miui.packageinstaller",
            "com.android.intentresolver",
            "com.android.photopicker"
        )

    private fun actorFor(origin: String): String = when (origin) {
        "JLZ_RUNTIME" -> "assistant"
        "WORK_TEST" -> "work_test"
        "SYSTEM" -> "system"
        "USER_OR_NON_RUNTIME" -> "phone_non_runtime"
        else -> "unknown"
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return when {
            minutes >= 60 -> String.format(Locale.getDefault(), "%d小时%02d分", minutes / 60L, minutes % 60L)
            minutes > 0 -> String.format(Locale.getDefault(), "%d分%02d秒", minutes, seconds)
            else -> "${seconds}秒"
        }
    }
}
