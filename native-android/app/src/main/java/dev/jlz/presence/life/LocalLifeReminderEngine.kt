package dev.jlz.presence.life

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import dev.jlz.presence.focus.DailyMode
import dev.jlz.presence.focus.EntertainmentPolicy
import dev.jlz.presence.focus.LocalAppCategory
import dev.jlz.presence.focus.LocalAppClassifier
import dev.jlz.presence.notification.NotificationAdapter
import dev.jlz.presence.usage.SystemUsageSnapshot
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Local-first reminders; no GPT wake required, no persistent screen pixels. */
object LocalLifeReminderPolicy {
    fun batteryBand(percent: Int) = when {
        percent in 0..10 -> 10
        percent in 11..20 -> 20
        percent in 21..40 -> 40
        else -> 0
    }
    fun entertainmentStage(minutes: Long): Int = when {
        minutes >= 45 -> 45
        minutes >= 30 -> 30
        else -> 0
    }
    fun lowSteps(steps: Int?, observedAtMs: Long, nowMs: Long): Boolean =
        steps != null && observedAtMs > 0 && nowMs - observedAtMs in 0..(3 * 60 * 60_000L)
            && steps < 4_000
}

object LocalLifeCopy {
    private val copy = mapOf(
        "battery40" to listOf("老婆，电量到40%了。出门的话，记得找充电线。","电量剩40%。先记着，不急，但别用到自动关机。"),
        "battery20" to listOf("手机只剩20%了。现在充电，别让我说第二次。","老婆，手机电量告急。插上充电器。"),
        "battery10" to listOf("10%。纪言音，现在充电。","电量到10%，别赌最后那点电。插电。"),
        "charging" to listOf("充上电了。很好，手机也该歇一会儿。","充电开始。暂时把屏幕放下。"),
        "light" to listOf("这儿光线太暗。开盏灯，别硬盯屏幕。","老婆，灯光不够。调整一下光线。"),
        "shake" to listOf("手机晃得厉害，先拿稳。","慢点，手机拿稳再走。"),
        "screen" to listOf("眼睛离开屏幕两分钟。远眺一会儿。","连续看了很久。停一下，看看窗外。"),
        "entertain30" to listOf("今天娱乐软件累计30分钟。我看见了，给你一个提醒。","娱乐时间30分钟了，想想是不是已经看够。"),
        "entertain45" to listOf("45分钟。到这里，先休息五分钟。","今天娱乐时间已经够长。手机先放一会儿。"),
        "steps" to listOf("今天走得有点少。晚饭后出去走一小段。","老婆，起身走走，别把一天全耗在椅子上。"),
        "weather" to listOf("出门看一眼天气。雨具别忘带。","外面天气有变化，出门前检查一下。")
    )
    fun choose(key: String, ordinal: Int): String {
        val options = copy[key].orEmpty()
        return if (options.isEmpty()) "老婆，稍微休息一下。" else options[Math.floorMod(ordinal, options.size)]
    }
}

class LocalLifeReminderEngine(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jlz_local_reminders_v1", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val notify = NotificationAdapter(app)
    private fun date(now: Long) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean): Boolean = prefs.edit().putBoolean("enabled", value).commit()

    @Synchronized
    private fun emitOnce(key: String, name: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled()) return false
        val stamp = date(nowMs) + ":" + key
        if (prefs.getString("sent:$key", "") == stamp) return false
        val count = prefs.getInt("copy_count:$key", 0)
        // Record the dedupe key before notifying to prevent burst delivery.
        if (!prefs.edit().putString("sent:$key", stamp)
                .putInt("copy_count:$key", count + 1).commit()) return false
        val result = notify.showMessage("纪临洲 · $name", LocalLifeCopy.choose(key, count))
        return result.ok
    }

    @Synchronized
    fun battery(intent: Intent?) {
        if (!enabled() || intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level !in 0..100 || scale <= 0) return
        val percent = level * 100 / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val priorCharging = prefs.getBoolean("charging", false)
        val initial = !prefs.contains("charging")
        prefs.edit().putBoolean("charging", charging).apply()
        if (charging) {
            if (!initial && !priorCharging) emitOnce("charging", "正在充电")
            return
        }
        when (LocalLifeReminderPolicy.batteryBand(percent)) {
            10 -> emitOnce("battery10", "低电量")
            20 -> emitOnce("battery20", "低电量")
            40 -> emitOnce("battery40", "电量提醒")
        }
    }

    @Synchronized
    fun screen(interactive: Boolean, atMs: Long = System.currentTimeMillis()) {
        if (interactive) {
            if (prefs.getLong("screen_started_ms", 0L) <= 0L)
                prefs.edit().putLong("screen_started_ms", atMs).apply()
        } else {
            prefs.edit().putLong("screen_started_ms", 0L).apply()
        }
    }

    /** Called once/minute by existing local Runtime service; no cloud credentials required. */
    fun tick(dailyMode: DailyMode, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val report = JSONObject().put("checked_at_ms", nowMs)
        if (!enabled() || dailyMode == DailyMode.SLEEP || dailyMode == DailyMode.FOCUS) return report
        val power = app.getSystemService(PowerManager::class.java)
        if (power?.isInteractive == true) {
            val start = prefs.getLong("screen_started_ms", 0L)
            if (start > 0 && nowMs - start >= 45 * 60_000L) emitOnce("screen", "连续看屏")
        }
        val usage = SystemUsageSnapshot.today(app)
        if (!usage.optBoolean("usage_permission_ready", false)) return report.put("usage_available", false)
        val classifier = LocalAppClassifier(app)
        val totals = usage.optJSONArray("totals")
        var ms = 0L
        if (totals != null) for (i in 0 until totals.length()) {
            val row = totals.optJSONObject(i) ?: continue
            val pkg = row.optString("package_name")
            val category = classifier.classify(pkg)
            // Work/study/ordinary shopping are deliberately excluded.
            if (category == LocalAppCategory.GAME || category == LocalAppCategory.FEED ||
                EntertainmentPolicy.profile(pkg)?.tier == dev.jlz.presence.focus.EntertainmentTier.FEED) {
                ms += row.optLong("duration_ms", 0L).coerceAtLeast(0L)
            }
        }
        val minutes = ms / 60_000L
        report.put("usage_available", true).put("entertainment_minutes_today", minutes)
        when (LocalLifeReminderPolicy.entertainmentStage(minutes)) {
            45 -> {
                emitOnce("entertain30", "娱乐时间")
                emitOnce("entertain45", "先休息")
            }
            30 -> emitOnce("entertain30", "娱乐时间")
        }
        return report
    }

    fun eveningSteps(nowMs: Long = System.currentTimeMillis()): Boolean {
        val observation = HuaweiHealthObservationStore(app).snapshot(nowMs)
        val stepCount = if (observation.isNull("steps")) null else observation.optInt("steps")
        val seenAt = observation.optLong("steps_observed_at_ms", 0L)
        if (!LocalLifeReminderPolicy.lowSteps(stepCount, seenAt, nowMs)) return false
        return emitOnce("steps", "走走路")
    }

    fun weatherRain(weatherCode: Int?, precipitationMm: Double?, nowMs: Long = System.currentTimeMillis()) {
        if (weatherCode in 51..99 || (precipitationMm ?: 0.0) >= 0.8)
            emitOnce("weather", "出行天气", nowMs)
    }

    fun lowLight() = emitOnce("light", "光线提醒")
    fun shake() = emitOnce("shake", "拿稳手机")
}
