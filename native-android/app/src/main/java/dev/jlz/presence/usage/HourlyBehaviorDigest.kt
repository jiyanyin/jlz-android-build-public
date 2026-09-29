package dev.jlz.presence.usage

import android.content.Context
import dev.jlz.presence.runtime.RuntimeApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar

/**
 * Compact, private-by-default phone observation digest.
 *
 * The complete UsageEvents journal is never mirrored here. Once a *completed*
 * hour, aggregate at most 500 app sessions and 500 screen events, retaining
 * only top apps and counts. Post ONE digest per heartbeat, with a deterministic
 * event ID, then advance the cursor ONLY after the server echoes that ID.
 * Subsequent native history requests can still read the real device evidence.
 */
object HourlyBehaviorDigest {
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val RETRY_BACKFILL_HOURS = 24
    private const val PAGE_LIMIT = 500
    private const val MAX_APPS = 4
    private const val PREFS = "jlz_behavior_digest_v1"
    private const val CURSOR_PREFIX = "confirmed_end_"
    private val formatUtc: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    private fun hourStart(nowMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun lastConfirmedHourEnd(context: Context, deviceId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(CURSOR_PREFIX + deviceId, 0L)

    private fun build(
        context: Context, deviceId: String, start: Long, end: Long
    ): JSONObject {
        val activity = DeviceActivityJournal(context.applicationContext)
        val appPage = activity.readAppSessions(
            context, start, end, limit = PAGE_LIMIT, deviceId = deviceId
        )
        val screenPage = activity.readScreenTimeline(
            context, start, end, limit = PAGE_LIMIT, deviceId = deviceId
        )
        val ready = appPage.optBoolean("ok", false) &&
            screenPage.optBoolean("ok", false)
        val metadata = JSONObject()
            .put("schema", "jlz_behavior_hour_v1")
            .put("start_ms", start)
            .put("end_ms", end)
            .put("source", "android_usage_events")
            .put("available", ready)
            .put("raw_events_uploaded", false)

        if (!ready) {
            return metadata.put(
                "reason",
                appPage.optString("reason").ifBlank {
                    screenPage.optString("reason").ifBlank { "device_usage_unavailable" }
                }.take(80)
            )
        }

        val apps = appPage.optJSONArray("sessions") ?: JSONArray()
        val totals = linkedMapOf<String, Long>()
        val counts = linkedMapOf<String, Int>()
        var appSessionCount = 0
        var foregroundMs = 0L
        for (i in 0 until apps.length()) {
            val item = apps.optJSONObject(i) ?: continue
            val pkg = item.optString("package_name")
            if (pkg.isBlank()) continue
            val duration = item.optLong("duration_ms").coerceIn(0L, HOUR_MS)
            if (duration < 3_000L) continue
            // Noise is neither a useful personal activity nor a reason to
            // transmit OS component identifiers or package-installer usage.
            if (pkg in setOf(
                    "android", "com.android.systemui",
                    "com.miui.home", "com.miui.packageinstaller",
                    "org.ikuuu.vpn"
                )
            ) continue
            totals[pkg] = (totals[pkg] ?: 0L) + duration
            counts[pkg] = (counts[pkg] ?: 0) + 1
            foregroundMs += duration
            appSessionCount++
        }
        val ordered = totals.entries.sortedByDescending { it.value }
        val top = JSONArray()
        for ((pkg, duration) in ordered.take(MAX_APPS)) {
            top.put(
                JSONObject()
                    .put("package", pkg.take(110))
                    .put("minutes", (duration + 30_000L) / 60_000L)
                    .put("sessions", counts[pkg] ?: 0)
            )
        }

        val screen = screenPage.optJSONArray("items") ?: JSONArray()
        var unlocks = 0
        var screenOns = 0
        for (i in 0 until screen.length()) {
            when (screen.optJSONObject(i)?.optString("event_type")) {
                "KEYGUARD_HIDDEN" -> unlocks++
                "SCREEN_ON" -> screenOns++
            }
        }
        return metadata
            .put("unlocks_user_default", unlocks)
            .put("screen_on_events", screenOns)
            .put("foreground_minutes", (foregroundMs + 30_000L) / 60_000L)
            .put("app_sessions", appSessionCount)
            .put("distinct_apps", ordered.size)
            .put("top_apps", top)
            .put("other_app_count", (ordered.size - MAX_APPS).coerceAtLeast(0))
            .put("app_sessions_truncated", appPage.optString("next_cursor").isNotBlank())
            .put("screen_events_truncated", screenPage.optString("next_cursor").isNotBlank())
    }

    /**
     * At most one small structured activity event per heartbeat; no minute-by-
     * minute duplicates. A failed or ambiguous ACK leaves the cursor intact.
     * After a long gap, intentionally bound catch-up to the most recent day.
     */
    fun sendDue(
        context: Context,
        api: RuntimeApiClient,
        deviceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val currentHour = hourStart(nowMs)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = CURSOR_PREFIX + deviceId
        val last = preferences.getLong(key, 0L)
        val earliest = currentHour - RETRY_BACKFILL_HOURS * HOUR_MS
        val start = if (last <= 0L) earliest
            else last.coerceAtLeast(earliest)
        val end = start + HOUR_MS
        if (end > currentHour) return false

        val digest = build(context, deviceId, start, end)
        val eventId = "behavior-hour:" + start + ":" + deviceId.take(60)
        val total = digest.optLong("foreground_minutes")
        val unlocks = digest.optInt("unlocks_user_default")
        val ready = digest.optBoolean("available")
        val top = digest.optJSONArray("top_apps")
        val topPackages = if (top == null) "" else (0 until minOf(2, top.length()))
            .mapNotNull { top.optJSONObject(it)?.optString("package")
                ?.takeIf { name -> name.isNotBlank() } }.joinToString("、")
        val subtitle = if (ready) {
            "解锁 " + unlocks + " 次 · 前台约 " + total + " 分钟" +
                if (topPackages.isBlank()) "" else " · " + topPackages
        } else "该时段 UsageEvents 读取不完整，未推断使用情况"
        val result = api.postActivityEvent(
            source = "android_usage_digest",
            type = "hourly_usage_digest",
            title = "手机每小时摘要",
            subtitle = subtitle.take(220),
            metadata = digest,
            eventId = eventId.take(100),
            createdAtIso = formatUtc.format(Instant.ofEpochMilli(end - 1))
        )
        val acknowledged = result.optBoolean("ok", false) &&
            result.optJSONObject("event")?.optString("id") == eventId.take(100)
        if (!acknowledged) return false
        return preferences.edit().putLong(key, end).commit()
    }
}
