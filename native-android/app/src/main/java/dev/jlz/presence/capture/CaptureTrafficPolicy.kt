package dev.jlz.presence.capture

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** One durable ledger for automatic reservations and actual HTTP body bytes.
 * Reservations are conservative: failed enqueue/capture never refunds the budget.
 * Explicit user/AI capture bypasses automatic limits, but remains accounted.
 */
class CaptureTrafficPolicy(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_capture_traffic_v1", Context.MODE_PRIVATE)

    private fun periods(now: Long): Pair<String, String> {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        return day to day.take(7)
    }

    private fun roll(now: Long) {
        val (day, month) = periods(now)
        val edit = prefs.edit()
        if (prefs.getString("day", "") != day) {
            edit.putString("day", day)
            for (key in listOf("auto_count_today", "auto_bytes_today", "upload_bytes_today", "download_bytes_today", "screenshot_bytes_today")) edit.putLong(key, 0)
        }
        if (prefs.getString("month", "") != month) {
            edit.putString("month", month)
            for (key in listOf("auto_bytes_month", "upload_bytes_month", "download_bytes_month", "screenshot_bytes_month")) edit.putLong(key, 0)
        }
        check(edit.commit()) { "traffic_ledger_not_saved" }
    }

    fun enabledFor(pkg: String): Boolean = synchronized(LOCK) {
        prefs.getBoolean("automatic_enabled", false) &&
            prefs.getStringSet("automatic_packages", emptySet()).orEmpty().contains(pkg) &&
            pkg != "com.openai.chatgpt"
    }

    fun canCapture(pkg: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        roll(now)
        enabledFor(pkg) && prefs.getLong("auto_count_today", 0) < DAILY_COUNT &&
            prefs.getLong("auto_bytes_today", 0) < DAILY_BYTES &&
            prefs.getLong("auto_bytes_month", 0) < MONTHLY_BYTES &&
            prefs.getLong("screenshot_bytes_month", 0) < MONTHLY_SCREENSHOT_WARNING &&
            now - prefs.getLong("last:$pkg", 0) >= COOLDOWN_MS
    }

    fun reserve(pkg: String, bytes: ByteArray, now: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        if (!canCapture(pkg, now)) return@synchronized false
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (prefs.getString("sha:$pkg", "") == hash) return@synchronized false
        val size = bytes.size.toLong()
        if (prefs.getLong("auto_bytes_today", 0) + size > DAILY_BYTES ||
            prefs.getLong("auto_bytes_month", 0) + size > MONTHLY_BYTES) return@synchronized false
        check(prefs.edit()
            .putLong("auto_count_today", prefs.getLong("auto_count_today", 0) + 1)
            .putLong("auto_bytes_today", prefs.getLong("auto_bytes_today", 0) + size)
            .putLong("auto_bytes_month", prefs.getLong("auto_bytes_month", 0) + size)
            .putLong("last:$pkg", now).putString("sha:$pkg", hash).commit())
        true
    }

    /** Charge BEFORE an automatic transfer, including each retry, even if it fails. */
    fun reserveAutomaticTransfer(bytes: Int): Boolean = synchronized(LOCK) {
        roll(System.currentTimeMillis())
        val day = prefs.getLong("auto_transfer_today", 0)
        val period = periods(System.currentTimeMillis())
        val actualDay = if (prefs.getString("transfer_day", "") == period.first) day else 0
        val month = if (prefs.getString("transfer_month", "") == period.second) prefs.getLong("auto_transfer_month", 0) else 0
        if (actualDay + bytes > DAILY_BYTES || month + bytes > MONTHLY_BYTES ||
            prefs.getLong("screenshot_bytes_month", 0) + bytes > MONTHLY_SCREENSHOT_WARNING) return@synchronized false
        check(prefs.edit().putString("transfer_day", period.first).putString("transfer_month", period.second)
            .putLong("auto_transfer_today", actualDay + bytes).putLong("auto_transfer_month", month + bytes).commit())
        true
    }

    fun record(upload: Long = 0, download: Long = 0, screenshot: Long = 0) = synchronized(LOCK) {
        roll(System.currentTimeMillis())
        val edit = prefs.edit()
        for ((name, value) in listOf("upload_bytes" to upload, "download_bytes" to download, "screenshot_bytes" to screenshot)) {
            for (period in listOf("today", "month")) {
                val key = "${name}_$period"
                edit.putLong(key, prefs.getLong(key, 0) + value.coerceAtLeast(0))
            }
        }
        check(edit.commit()) { "traffic_accounting_not_saved" }
    }

    fun snapshot(): JSONObject = synchronized(LOCK) {
        roll(System.currentTimeMillis())
        JSONObject().put("source", "android_http_body_bytes").put("excludes", "TLS/headers,Runtime-Storage,MCP-client")
            .put("automatic_default", false).put("automatic_daily_count_limit", DAILY_COUNT)
            .put("automatic_daily_byte_limit", DAILY_BYTES).put("automatic_monthly_byte_limit", MONTHLY_BYTES)
            .also { json -> for (name in listOf("upload_bytes", "download_bytes", "screenshot_bytes", "auto_bytes")) {
                for (period in listOf("today", "month")) json.put("${name}_$period", prefs.getLong("${name}_$period", 0))
            } }
    }

    companion object {
        private val LOCK = Any()
        const val DAILY_COUNT = 24L
        const val DAILY_BYTES = 6L * 1024 * 1024
        const val MONTHLY_BYTES = 120L * 1024 * 1024
        const val MONTHLY_SCREENSHOT_WARNING = 300L * 1024 * 1024
        const val COOLDOWN_MS = 30L * 60 * 1000
    }
}
