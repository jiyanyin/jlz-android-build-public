package dev.jlz.presence.life

import android.content.Context
import dev.jlz.presence.focus.EntertainmentPolicy
import dev.jlz.presence.focus.EntertainmentProfile
import dev.jlz.presence.focus.EntertainmentReleasePlan
import dev.jlz.presence.focus.EntertainmentTier
import dev.jlz.presence.focus.LocalAppCategory
import dev.jlz.presence.focus.LocalAppClassifier
import dev.jlz.presence.study.StudyMetricsStore
import dev.jlz.presence.usage.SystemUsageSnapshot
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BehaviorDay(val entertainmentMinutes: Int?, val appMinutes: Int?, val sampledHour: Int)

object WeeklyBehaviorPolicy {
    fun tighten(days: List<BehaviorDay>): Boolean {
        val reliable = days.filter { it.sampledHour >= 18 && it.entertainmentMinutes != null }
        return reliable.size >= 4 && (reliable.count { (it.entertainmentMinutes ?: 0) >= 45 } >= 4 ||
            reliable.count { (it.appMinutes ?: 0) >= 30 } >= 4)
    }
    fun minutes(base: Int, tighten: Boolean): Int =
        if (tighten) (base - 2).coerceAtLeast(2) else base
}

/** Only local daily aggregates are stored; no screen text, GPS points or memory excerpts. */
class WeeklyBehaviorStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jlz_weekly_behavior_v1", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun date(atMs: Long) = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    fun day(date: String): JSONObject {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date))
        return prefs.getString("day:$date", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().put("date", date).put("available", false)
    }

    fun sample(nowMs: Long = System.currentTimeMillis()) {
        val today = date(nowMs)
        val usage = SystemUsageSnapshot.today(app)
        val classifier = LocalAppClassifier(app)
        val totals = JSONObject()
        var entertainmentMs = 0L
        if (usage.optBoolean("usage_permission_ready", false)) {
            val rows = usage.optJSONArray("totals")
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val pkg = row.optString("package_name")
                val category = classifier.classify(pkg)
                if (category == LocalAppCategory.GAME ||
                    (category == LocalAppCategory.FEED &&
                     EntertainmentPolicy.profile(pkg)?.tier != EntertainmentTier.SHOPPING)) {
                    val minutes = (row.optLong("duration_ms", 0L) / 60_000L).coerceAtLeast(0L)
                    totals.put(pkg, minutes)
                    entertainmentMs += row.optLong("duration_ms", 0L).coerceAtLeast(0L)
                }
            }
        }
        val studyMinutes = runCatching { StudyMetricsStore(app).use { store ->
            store.dayMetrics(today.atStartOfDay(zone).toInstant().toEpochMilli(), nowMs + 1)
                .effectiveStudyMs / 60_000L
        } }.getOrDefault(0L)
        val native = NativeStepSnapshot.today(app, nowMs)
        val huawei = HuaweiHealthObservationStore(app).snapshot(nowMs)
        val huaweiSteps = if (huawei.isNull("steps")) null else huawei.optInt("steps")
        val steps = when {
            native?.completeDayEstimate == true -> maxOf(native.stepsSinceFirstObservation, huaweiSteps ?: 0)
            huaweiSteps != null -> huaweiSteps
            else -> null
        }
        val key = "day:$today"
        val old = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val merged = JSONObject().put("date", today.toString())
            .put("observed_at_ms", nowMs)
            .put("sampled_hour", Instant.ofEpochMilli(nowMs).atZone(zone).hour)
            .put("study_minutes", maxOf(studyMinutes, old?.optLong("study_minutes", 0L) ?: 0L))
            .put("steps", steps ?: old?.optInt("steps", -1) ?: -1)
            .put("entertainment_minutes", if (usage.optBoolean("usage_permission_ready", false))
                maxOf(entertainmentMs / 60_000L, old?.optLong("entertainment_minutes", 0L) ?: 0L)
                else old?.optLong("entertainment_minutes", -1L) ?: -1L)
            .put("usage_available", usage.optBoolean("usage_permission_ready", false) ||
                old?.optBoolean("usage_available", false) == true)
        val appMinutes = JSONObject()
        old?.optJSONObject("apps")?.let { previous ->
            previous.keys().forEach { pkg -> appMinutes.put(pkg, previous.optLong(pkg)) }
        }
        totals.keys().forEach { pkg -> appMinutes.put(pkg,
            maxOf(totals.optLong(pkg), appMinutes.optLong(pkg))) }
        merged.put("apps", appMinutes)
        val edit = prefs.edit().putString(key, merged.toString())
        prefs.all.keys.filter { it.startsWith("day:") }.forEach { item ->
            val day = runCatching { LocalDate.parse(item.removePrefix("day:")) }.getOrNull()
            if (day != null && day.isBefore(today.minusDays(14))) edit.remove(item)
        }
        edit.apply()
    }

    fun decision(packageName: String, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val today = date(nowMs)
        val days = (1L..7L).mapNotNull { age ->
            prefs.getString("day:${today.minusDays(age)}", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
        val evidence = days.map { row ->
            BehaviorDay(
                row.optInt("entertainment_minutes", -1).takeIf { row.optBoolean("usage_available") && it >= 0 },
                row.optJSONObject("apps")?.optInt(packageName, -1)?.takeIf { it >= 0 },
                row.optInt("sampled_hour", 0)
            )
        }
        val tighter = WeeklyBehaviorPolicy.tighten(evidence)
        return JSONObject().put("tighter", tighter)
            .put("sampled_days", evidence.count { it.sampledHour >= 18 && it.entertainmentMinutes != null })
            .put("high_entertainment_days", evidence.count { (it.entertainmentMinutes ?: 0) >= 45 })
            .put("high_this_app_days", evidence.count { (it.appMinutes ?: 0) >= 30 })
            .put("rule", "4 of 7 reliable days: entertainment>=45m or this app>=30m; release -2m, floor 2m")
    }

    fun adjust(profile: EntertainmentProfile, base: EntertainmentReleasePlan): EntertainmentReleasePlan {
        if (profile.tier == EntertainmentTier.SHOPPING) return base
        val tighten = decision(profile.packageName).optBoolean("tighter", false)
        val minutes = WeeklyBehaviorPolicy.minutes(base.minutes, tighten)
        return if (minutes == base.minutes) base else base.copy(minutes = minutes,
            label = base.label + " · 七日使用偏多，本轮缩短2分钟")
    }
}
