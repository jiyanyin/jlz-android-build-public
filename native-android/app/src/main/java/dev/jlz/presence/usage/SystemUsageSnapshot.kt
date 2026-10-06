package dev.jlz.presence.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Permission-backed daily OS usage, separate from accessibility heuristics. */
object SystemUsageSnapshot {
    fun hasPermission(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Android UsageEvents are historical observations, not evidence that the
     * app is currently on screen. An explicit non-interactive event closes
     * open sessions instead of counting phone-off time as app use.
     */
    fun today(context: Context): JSONObject {
        val now = System.currentTimeMillis()
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val base = JSONObject()
            .put("source", "android_usage_stats")
            .put("observed_at_ms", now).put("since_ms", day)
            .put("usage_permission_ready", hasPermission(context))
        if (!hasPermission(context)) {
            return base.put("totals", JSONArray())
                .put("screen_time_today_minutes", JSONObject.NULL)
                .put("reason", "usage_access_not_granted")
        }
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return base.put("totals", JSONArray())
                .put("reason", "usage_stats_manager_unavailable")
        val events = manager.queryEvents(day, now)
        val event = UsageEvents.Event()
        val starts = linkedMapOf<String, Long>()
        val totals = mutableMapOf<String, Long>()
        val recentTotals = mutableMapOf<String, Long>()
        val recentWindowMinutes = 60
        val recentStart = (now - recentWindowMinutes * 60_000L).coerceAtLeast(day)
        var interactive = true
        var lastPackage: String? = null
        var unlocks = 0
        var lastUnlockedAt = 0L

        fun close(pkg: String, at: Long) {
            val started = starts.remove(pkg) ?: return
            if (at <= started) return
            val safeEnd = at.coerceAtMost(now)
            val duration = (safeEnd - started).coerceAtMost(now - day)
            if (duration > 0L) {
                totals[pkg] = (totals[pkg] ?: 0L) + duration
            }
            val overlapStart = maxOf(started, recentStart)
            if (safeEnd > overlapStart) {
                recentTotals[pkg] = (recentTotals[pkg] ?: 0L) + (safeEnd - overlapStart)
            }
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val time = event.timeStamp.coerceIn(day, now)
            if (Build.VERSION.SDK_INT >= 28 && type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                starts.keys.toList().forEach { close(it, time) }
                interactive = false
                lastPackage = null
                continue
            }
            if (Build.VERSION.SDK_INT >= 28 && type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                interactive = true
                continue
            }
            if (Build.VERSION.SDK_INT >= 28 && type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                unlocks++
                lastUnlockedAt = time
            }
            val pkg = event.packageName?.takeIf { it.isNotBlank() } ?: continue
            val foreground = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED)
            val background = type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                (Build.VERSION.SDK_INT >= 29 &&
                    (type == UsageEvents.Event.ACTIVITY_PAUSED ||
                     type == UsageEvents.Event.ACTIVITY_STOPPED))
            if (foreground && interactive) {
                if (!starts.containsKey(pkg)) starts[pkg] = time
                lastPackage = pkg
            } else if (background) {
                close(pkg, time)
                if (lastPackage == pkg) lastPackage = null
            }
        }
        if (interactive) starts.keys.toList().forEach { close(it, now) }
        // Keep the heartbeat payload deliberately small. Full raw UsageEvents
        // stay on-device; Runtime receives only bounded aggregates.
        val ordered = totals.entries.filter { it.value > 0L }
            .sortedByDescending { it.value }.take(16)
        val recentOrdered = recentTotals.entries.filter { it.value > 0L }
            .sortedByDescending { it.value }.take(12)
        val totalMs = totals.values.sum().coerceAtMost(now - day)
        val recentTotalMs = recentTotals.values.sum()
            .coerceAtMost((now - recentStart).coerceAtLeast(0L))
        val attribution = DeviceActivityJournal(context.applicationContext)
            .usageAttribution(context, day, now)
        return base
            .put("totals", JSONArray().apply {
                ordered.forEach { entry ->
                    put(JSONObject().put("package_name", entry.key)
                        .put("duration_ms", entry.value))
                }
            })
            .put("screen_time_today_minutes", totalMs / 60_000L)
            .put("device_screen_time_ms", totalMs)
            .put("recent_window_minutes", recentWindowMinutes)
            .put("recent_window_since_ms", recentStart)
            .put("recent_window_screen_time_ms", recentTotalMs)
            .put("recent_window_totals", JSONArray().apply {
                recentOrdered.forEach { entry ->
                    put(JSONObject().put("package_name", entry.key)
                        .put("duration_ms", entry.value))
                }
            })
            .put("known_runtime_screen_time_ms",
                attribution.optLong("known_runtime_foreground_app_time_ms"))
            .put("known_work_test_screen_time_ms",
                attribution.optLong("known_work_test_foreground_app_time_ms"))
            .put("user_attributed_screen_time_ms", JSONObject.NULL)
            .put("user_or_non_runtime_screen_time_ms",
                attribution.optLong("user_or_non_runtime_foreground_app_time_ms"))
            .put("unknown_screen_time_ms",
                attribution.optLong("unknown_foreground_app_time_ms"))
            .put("screen_time_attribution_method", attribution.optString("attribution_method"))
            .put("screen_time_semantics",
                "foreground_app_usage; USER_OR_NON_RUNTIME is not proof of the human operator")
            .put("activity_attribution_note", attribution.optString("note"))
            .put("unlock_count_today", unlocks)
            .put("device_unlock_count_today", unlocks)
            .put("user_attributed_unlock_count_today", JSONObject.NULL)
            .put("unlock_count_today_semantics",
                "device KEYGUARD_HIDDEN events; may include Runtime/Work unless provenance is queried")
            .put("last_unlock_at_ms", if (lastUnlockedAt > 0L) lastUnlockedAt else JSONObject.NULL)
            .put("last_device_unlock_at_ms", if (lastUnlockedAt > 0L) lastUnlockedAt else JSONObject.NULL)
            .put("last_unlock_at_ms_semantics",
                "backward-compatible alias of last_device_unlock_at_ms; not proof of human input")
            .put("current_package_by_usage_events", lastPackage)
    }
}
