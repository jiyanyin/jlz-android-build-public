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

data class TimelineSegment(
    val id: String,
    val startAtMs: Long,
    val endAtMs: Long,
    val title: String,
    val detail: String,
    val periodLabel: String,
    val appLabels: List<String>,
    val activeDurationMs: Long,
    val wallDurationMs: Long,
    val sessionCount: Int,
    val origin: String
)

data class TopAppUsage(
    val label: String,
    val durationMs: Long,
    val sessionCount: Int
)

data class UnifiedTimelineSnapshot(
    val generatedAtMs: Long,
    val startAtMs: Long,
    val items: List<UnifiedTimelineItem>,
    val segments: List<TimelineSegment>,
    val topApps: List<TopAppUsage>,
    val screenOnCount: Int,
    val unlockCount: Int,
    val appSessionCount: Int,
    val foregroundMinutes: Long,
    val runtimeAttributedCount: Int,
    val firstUnlockAtMs: Long?,
    val latestActivityAtMs: Long?
)

/**
 * Read-only presentation layer for "what happened on my phone today".
 *
 * Raw evidence remains in Android UsageEvents / DeviceActivityJournal /
 * LocalLifeStore. This class only normalizes those sources into one timeline.
 * OS unlock events are treated as the phone owner's actions by the user's
 * explicit product preference; all other phone events keep source labels.
 */
class UnifiedPhoneTimeline(private val context: Context) {
    private val activity = DeviceActivityJournal(context)
    private val life = LocalLifeStore(context)
    private val appLabelCache = mutableMapOf<String, String>()

    private data class AppEvidence(
        val packageName: String,
        val label: String,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val origin: String,
        val confidence: String
    )

    private data class SegmentBuilder(
        var startMs: Long,
        var endMs: Long,
        var activeMs: Long,
        var sessionCount: Int,
        val appsInOrder: MutableList<String>,
        val appDurations: MutableMap<String, Long>,
        val origins: MutableSet<String>
    )

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
        var firstUnlockAtMs: Long? = null

        if (screenArray != null) {
            for (i in 0 until screenArray.length()) {
                val item = screenArray.optJSONObject(i) ?: continue
                val type = item.optString("event_type")
                val at = item.optLong("wall_clock_timestamp")
                if (at <= 0L) continue

                if (type == "SCREEN_ON") screenOnCount++
                if (type == "KEYGUARD_HIDDEN") {
                    unlockCount++
                    if (firstUnlockAtMs == null || at < firstUnlockAtMs!!) firstUnlockAtMs = at
                }

                val origin = item.optString("origin", "UNKNOWN")
                val title = when (type) {
                    "SCREEN_ON" -> "亮屏"
                    "SCREEN_OFF" -> "熄屏"
                    "KEYGUARD_SHOWN" -> "进入锁屏"
                    "KEYGUARD_HIDDEN" -> "音音解锁手机"
                    "USER_PRESENT" -> "音音进入手机"
                    "DEVICE_BOOT" -> "手机启动"
                    "DATA_GAP_START" -> "记录中断"
                    "DATA_GAP_END" -> "记录恢复"
                    else -> type.ifBlank { "设备事件" }
                }

                val detailParts = mutableListOf<String>()
                item.optString("foreground_package")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { detailParts += appLabel(it) }

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
                    actor = if (type == "KEYGUARD_HIDDEN" ||
                        type == "USER_PRESENT") "user"
                        else item.optString("actor", actorFor(origin)),
                    source = item.optString("source", screenJson.optString("source", "device")),
                    confidence = if (item.has("command_id") && !item.isNull("command_id")) {
                        "high_command_correlation"
                    } else "",
                    side = when {
                        type == "KEYGUARD_HIDDEN" || type == "USER_PRESENT" -> 1
                        origin == "JLZ_RUNTIME" -> -1
                        else -> 0
                    }
                )
            }
        }

        val appEvidence = mutableListOf<AppEvidence>()
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
                if (
                    pkg.isBlank() ||
                    startMs <= 0L ||
                    endMs <= startMs ||
                    duration < 3_000L ||
                    isSystemNoise(pkg)
                ) continue

                foregroundMs += duration
                val origin = item.optString("origin", "UNKNOWN")
                val label = appLabel(pkg)
                val confidence = item.optString("confidence")

                appEvidence += AppEvidence(
                    packageName = pkg,
                    label = label,
                    startMs = startMs,
                    endMs = endMs,
                    durationMs = duration,
                    origin = origin,
                    confidence = confidence
                )

                appItems += UnifiedTimelineItem(
                    id = "app:$pkg:$startMs:$endMs",
                    atMs = startMs,
                    endMs = endMs,
                    title = "使用 $label",
                    detail = formatDuration(duration),
                    category = "APP",
                    origin = origin,
                    actor = item.optString("actor", actorFor(origin)),
                    source = item.optString("source", "android_usage_events"),
                    confidence = confidence,
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

        val segments = buildSegments(appEvidence)
        val topApps = appEvidence
            .groupBy { it.label }
            .map { (label, values) ->
                TopAppUsage(
                    label = label,
                    durationMs = values.sumOf { it.durationMs },
                    sessionCount = values.size
                )
            }
            .sortedByDescending { it.durationMs }
            .take(5)

        return UnifiedTimelineSnapshot(
            generatedAtMs = nowMs,
            startAtMs = start,
            items = merged,
            segments = segments.sortedByDescending { it.startAtMs },
            topApps = topApps,
            screenOnCount = screenOnCount,
            unlockCount = unlockCount,
            appSessionCount = appItems.size,
            foregroundMinutes = foregroundMs / 60_000L,
            runtimeAttributedCount = merged.count { it.origin == "JLZ_RUNTIME" },
            firstUnlockAtMs = firstUnlockAtMs,
            latestActivityAtMs = merged.maxOfOrNull { it.endMs ?: it.atMs }
        )
    }

    private fun buildSegments(apps: List<AppEvidence>): List<TimelineSegment> {
        if (apps.isEmpty()) return emptyList()

        val sorted = apps.sortedBy { it.startMs }
        val result = mutableListOf<TimelineSegment>()
        var current: SegmentBuilder? = null

        fun flush() {
            val segment = current ?: return
            val uniqueApps = segment.appsInOrder.distinct()
            val top = segment.appDurations.maxByOrNull { it.value }
            val title = when {
                uniqueApps.isEmpty() -> "手机活动"
                uniqueApps.size == 1 -> uniqueApps.first()
                top != null && top.value >= segment.activeMs * 0.60 -> "主要在 ${top.key}"
                else -> uniqueApps.take(3).joinToString(" → ")
            }

            val wallMs = (segment.endMs - segment.startMs).coerceAtLeast(segment.activeMs)
            val detailParts = mutableListOf<String>()
            detailParts += "连续 ${formatDuration(wallMs)}"
            detailParts += "前台 ${formatDuration(segment.activeMs)}"
            if (uniqueApps.size > 1) detailParts += "${uniqueApps.size} 个 App"
            if (segment.sessionCount > 1) detailParts += "${segment.sessionCount} 段"

            val origin = when {
                segment.origins.size == 1 -> segment.origins.first()
                segment.origins.isEmpty() -> "UNKNOWN"
                else -> "MIXED"
            }

            result += TimelineSegment(
                id = "segment:${segment.startMs}:${segment.endMs}",
                startAtMs = segment.startMs,
                endAtMs = segment.endMs,
                title = title,
                detail = detailParts.joinToString(" · "),
                periodLabel = periodLabel(segment.startMs),
                appLabels = uniqueApps,
                activeDurationMs = segment.activeMs,
                wallDurationMs = wallMs,
                sessionCount = segment.sessionCount,
                origin = origin
            )
            current = null
        }

        for (app in sorted) {
            val builder = current
            if (builder == null) {
                current = SegmentBuilder(
                    startMs = app.startMs,
                    endMs = app.endMs,
                    activeMs = app.durationMs,
                    sessionCount = 1,
                    appsInOrder = mutableListOf(app.label),
                    appDurations = mutableMapOf(app.label to app.durationMs),
                    origins = mutableSetOf(app.origin)
                )
                continue
            }

            val gap = app.startMs - builder.endMs
            val shouldMerge = gap in 0L..SEGMENT_GAP_MS

            if (!shouldMerge) {
                flush()
                current = SegmentBuilder(
                    startMs = app.startMs,
                    endMs = app.endMs,
                    activeMs = app.durationMs,
                    sessionCount = 1,
                    appsInOrder = mutableListOf(app.label),
                    appDurations = mutableMapOf(app.label to app.durationMs),
                    origins = mutableSetOf(app.origin)
                )
                continue
            }

            builder.endMs = maxOf(builder.endMs, app.endMs)
            builder.activeMs += app.durationMs
            builder.sessionCount += 1
            builder.appsInOrder += app.label
            builder.appDurations[app.label] = (builder.appDurations[app.label] ?: 0L) + app.durationMs
            builder.origins += app.origin
        }

        flush()
        return result.filter { it.activeDurationMs >= 5_000L }
    }

    private fun periodLabel(atMs: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = atMs }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5 -> "凌晨"
            in 6..8 -> "早上"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            else -> "晚上"
        }
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

    companion object {
        private const val SEGMENT_GAP_MS = 90_000L
    }
}
