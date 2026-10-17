package dev.jlz.presence.usage

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Phone-use evidence only. Never estimates biological sleep or overrides a self report. */
object PhoneRestWindowPolicy {
    private val zone = ZoneId.of("Asia/Shanghai")
    fun range(now: Long): Pair<Long, Long> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return day.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli() to
            minOf(now, day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
    }
    fun estimate(raw: List<JSONObject>, rawSessions: List<JSONObject>, now: Long, complete: Boolean): JSONObject {
        val (from, until) = range(now)
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val morning = day.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val events = raw.filter { it.optLong("wall_clock_timestamp") in from..until }.sortedBy { it.optLong("wall_clock_timestamp") }
        fun user(e: JSONObject) = e.optString("origin") == "USER_OR_NON_RUNTIME"
        fun at(e: JSONObject?) = e?.optLong("wall_clock_timestamp")
        val unlock = events.firstOrNull { user(it) && it.optString("event_type") in setOf("KEYGUARD_HIDDEN", "USER_PRESENT") && it.optLong("wall_clock_timestamp") >= morning }
        val on = events.firstOrNull { user(it) && it.optString("event_type") == "SCREEN_ON" && it.optLong("wall_clock_timestamp") >= morning }
        val end = listOfNotNull(at(on), at(unlock)).minOrNull()
        // Without a morning endpoint only a pre-04:00 night candidate is eligible.
        // A noon/afternoon lock can never replace last night's start.
        val cutoff = end ?: minOf(until, morning)
        val off = events.lastOrNull { user(it) && it.optString("event_type") == "SCREEN_OFF" && it.optLong("wall_clock_timestamp") < cutoff }
        val start = at(off)
        val nightUse = events.filter { user(it) && it.optString("event_type") in setOf("SCREEN_ON", "USER_PRESENT", "KEYGUARD_HIDDEN") && it.optLong("wall_clock_timestamp") < morning }
        val unknown = events.filter { it.optString("origin") == "UNKNOWN" }
        val gaps = events.filter { it.optString("event_type") in setOf("DEVICE_BOOT", "DATA_GAP_START", "DATA_GAP_END") }
        val runtime = events.filter { it.optString("origin") in setOf("JLZ_RUNTIME", "WORK_TEST") }
        val sessions = rawSessions.filter { it.optString("origin") == "USER_OR_NON_RUNTIME" && it.optLong("session_start") in from..until }
        val interrupted = start != null && end != null && sessions.any { it.optLong("session_start") < end && it.optLong("session_end") > start }
        val bounded = start != null && end != null && end > start
        val reliable = complete && bounded && unknown.isEmpty() && gaps.isEmpty() && !interrupted
        return JSONObject().put("ok", complete).put("classification", "phone_inferred_rest_window")
            .put("display_label", "手机推测休息／未用手机窗口")
            .put("medical_sleep_data", false).put("warning", "NOT_MEDICAL_SLEEP_DATA")
            .put("self_report_priority", true).put("sleep_duration_minutes", JSONObject.NULL)
            .put("query_start_ms", from).put("query_end_ms", until).put("coverage_complete", complete)
            .put("first_morning_screen_on_at", at(on) ?: JSONObject.NULL)
            .put("first_morning_unlock_at", at(unlock) ?: JSONObject.NULL)
            .put("last_non_runtime_screen_off_at", start ?: JSONObject.NULL)
            .put("last_non_runtime_interaction_at", sessions.maxOfOrNull { it.optLong("session_end") } ?: JSONObject.NULL)
            .put("quiet_window_start", start ?: JSONObject.NULL).put("quiet_window_end", end ?: JSONObject.NULL)
            .put("unused_phone_window_minutes", if (reliable) (end!! - start!!) / 60_000L else JSONObject.NULL)
            .put("confidence", if (reliable) "medium_phone_evidence" else "low_incomplete_phone_evidence")
            .put("night_intermediate_use", JSONArray(nightUse)).put("unknown_origin_events", JSONArray(unknown))
            .put("data_gaps", JSONArray(gaps)).put("runtime_screen_events_after_that", JSONArray(runtime))
            .put("non_runtime_screen_events_after_that", JSONArray(events.filter { user(it) }))
            .put("foreground_interruption", interrupted)
            .put("evidence", JSONArray(events))
    }
}
