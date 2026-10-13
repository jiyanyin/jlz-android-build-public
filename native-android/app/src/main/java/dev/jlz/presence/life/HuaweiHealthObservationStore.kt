package dev.jlz.presence.life

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local observations only. No Health Connect assumptions or invented sleep.
 * Huawei Health notifications expose steps; visible Huawei screen text may
 * expose a sleep duration after the user opens that page.
 */
object HuaweiSleepTextParser {
    private val duration = Regex("""(?:睡眠时长|昨晚睡眠|昨夜睡眠|总睡眠|睡了)\s*[:：]?\s*(?:(\d{1,2})\s*(?:小时|时|h))?\s*(\d{1,2})?\s*(?:分钟|分|min)?""", RegexOption.IGNORE_CASE)
    private val hourMinute = Regex("""(?:睡眠时长|昨晚睡眠|昨夜睡眠|总睡眠)\s*[:：]?\s*(\d{1,2})\s*(?:小时|时|h)\s*(\d{1,2})\s*(?:分|分钟|min)""", RegexOption.IGNORE_CASE)
    private val hoursOnly = Regex("""(?:睡眠时长|昨晚睡眠|昨夜睡眠|总睡眠)\s*[:：]?\s*(\d{1,2}(?:\.\d)?)\s*(?:小时|时|h)""", RegexOption.IGNORE_CASE)
    fun minutes(text: String): Int? {
        if (text.isBlank()) return null
        val exact = hourMinute.find(text.take(3500))
        val parsed = when {
            exact != null -> {
                val h = exact.groupValues[1].toIntOrNull() ?: return null
                val m = exact.groupValues[2].toIntOrNull() ?: return null
                if (m !in 0..59) return null
                h * 60 + m
            }
            else -> {
                val hours = hoursOnly.find(text.take(3500))?.groupValues?.get(1)?.toDoubleOrNull()
                if (hours == null) return null
                (hours * 60).toInt()
            }
        }
        return parsed.takeIf { it in 20..1_080 }
    }
}

/**
 * Only explicit dates displayed by Huawei Health can verify a sleep record.
 * Screen unlocked/first phone use time is NEVER a substitute for sleep.
 * A labelled wake date alone may belong to a nap: require nighttime context.
 */
object HuaweiSleepDateEvidence {
    private val date = Regex(
        """(入睡日期|开始睡眠日期|起床日期|醒来日期)\s*[:：]?\s*(\d{4})[年./-](\d{1,2})[月./-](\d{1,2})日?"""
    )
    enum class Result { VERIFIED, NO_DATE_EVIDENCE, MISMATCH }
    fun check(text: String, observedAtMs: Long): Result {
        val today = Instant.ofEpochMilli(observedAtMs)
            .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val entries = date.findAll(text.take(3500)).toList()
        if (entries.isEmpty()) return Result.NO_DATE_EVIDENCE
        val nighttime = listOf("昨晚", "昨夜", "夜间睡眠").any(text::contains)
        var valid = false
        for (entry in entries) {
            val y = entry.groupValues[2].toIntOrNull() ?: return Result.MISMATCH
            val m = entry.groupValues[3].toIntOrNull() ?: return Result.MISMATCH
            val d = entry.groupValues[4].toIntOrNull() ?: return Result.MISMATCH
            val value = runCatching { LocalDate.of(y, m, d) }.getOrNull() ?: return Result.MISMATCH
            val starts = entry.groupValues[1] in setOf("入睡日期", "开始睡眠日期")
            val expected = if (starts) today.minusDays(1) else today
            if (value != expected) return Result.MISMATCH
            if (starts || nighttime) valid = true
        }
        return if (valid) Result.VERIFIED else Result.NO_DATE_EVIDENCE
    }
}

class HuaweiHealthObservationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_huawei_observation_v1", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun date(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

    fun recordNotificationSteps(steps: Int, observedAtMs: Long = System.currentTimeMillis()) {
        if (steps !in 0..200_000) return
        synchronized(prefs) {
            val today = date(observedAtMs)
            // Older/late notifications must not replace a more recent observation.
            val previous = prefs.getLong("steps_observed_at_ms", 0)
            if (previous > observedAtMs && prefs.getString("steps_date", "") == today) return
            prefs.edit().putInt("steps", steps).putString("steps_date", today)
                .putLong("steps_observed_at_ms", observedAtMs)
                .putString("steps_source", "huawei_health_visible_notification").apply()
        }
    }

    fun recordVisibleHuaweiPage(text: String, observedAtMs: Long = System.currentTimeMillis()): Boolean {
        val minutes = HuaweiSleepTextParser.minutes(text)
        if (minutes == null) {
            prefs.edit().putString("sleep_last_parse_status", "sleep_duration_missing").apply()
            return false
        }
        val evidence = HuaweiSleepDateEvidence.check(text, observedAtMs)
        if (evidence == HuaweiSleepDateEvidence.Result.MISMATCH) {
            // Do not replace a previous valid observation with a different night's data.
            prefs.edit().putString("sleep_last_parse_status", "visible_page_wrong_date").apply()
            return false
        }
        val verified = evidence == HuaweiSleepDateEvidence.Result.VERIFIED
        return prefs.edit().putInt("sleep_minutes", minutes)
            .putLong("sleep_observed_at_ms", observedAtMs)
            .putString("sleep_observed_day", date(observedAtMs))
            .putBoolean("sleep_date_evidence_verified", verified)
            .putString("sleep_source", "huawei_health_accessibility_visible_page")
            .putString("sleep_date_semantics",
                if (verified) "explicit_prior_night_date_on_visible_page" else "shown_page_date_not_verified")
            .putString("sleep_last_parse_status",
                if (verified) "sleep_visible_verified" else "sleep_visible_date_unverified")
            .commit()
    }

    fun markMorningAttempt(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("morning_check_at_ms", atMs)
            .putString("morning_check_result", "checking_visible_sources").apply()
    }

    fun markMorningResult(reason: String) {
        require(reason in setOf("sleep_visible_verified", "sleep_visible_date_unverified",
            "visible_page_wrong_date", "sleep_duration_missing",
            "huawei_page_not_visible", "visible_source_check_failed"))
        prefs.edit().putString("morning_check_result", reason).apply()
    }

    fun mostRecentParseStatus(): String =
        prefs.getString("sleep_last_parse_status", "sleep_duration_missing").orEmpty()

    fun snapshot(nowMs: Long = System.currentTimeMillis()): JSONObject {
        val stepAt = prefs.getLong("steps_observed_at_ms", 0L)
        val stepValid = stepAt > 0 && prefs.getString("steps_date", "") == date(nowMs)
            && nowMs - stepAt in 0..(3 * 60 * 60_000L)
        val sleepAt = prefs.getLong("sleep_observed_at_ms", 0L)
        val sleepValid = sleepAt > 0 && nowMs - sleepAt in 0..(36 * 60 * 60_000L)
            && prefs.getString("sleep_observed_day", "") == date(nowMs)
        return JSONObject().put("ok", true).put("source", "on_device_verified_huawei_ui_or_notification")
            .put("observed_at_ms", nowMs)
            .put("steps", if (stepValid) prefs.getInt("steps", -1) else JSONObject.NULL)
            .put("steps_source", if (stepValid) prefs.getString("steps_source", "") else JSONObject.NULL)
            .put("steps_observed_at_ms", if (stepAt > 0) stepAt else JSONObject.NULL)
            .put("steps_fresh", stepValid)
            .put("sleep_minutes", if (sleepValid) prefs.getInt("sleep_minutes", -1) else JSONObject.NULL)
            .put("sleep_source", if (sleepValid) prefs.getString("sleep_source", "") else JSONObject.NULL)
            .put("sleep_observed_at_ms", if (sleepAt > 0) sleepAt else JSONObject.NULL)
            .put("sleep_date_verified", sleepValid &&
                prefs.getBoolean("sleep_date_evidence_verified", false))
            .put("sleep_date_semantics", if (sleepValid)
                prefs.getString("sleep_date_semantics", "") else JSONObject.NULL)
            .put("sleep_parse_status", prefs.getString("sleep_last_parse_status", "not_observed"))
            .put("sleep_fresh", sleepValid)
            .put("morning_check_at_ms", prefs.getLong("morning_check_at_ms", 0L))
            .put("morning_check_result", prefs.getString("morning_check_result", "not_checked"))
            .put("medical_sensor_connected", false)
    }
}
