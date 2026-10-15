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
import dev.jlz.presence.runtime.WorldContentCache
import dev.jlz.presence.place.WeatherState
import dev.jlz.presence.place.SemanticPlace
import dev.jlz.presence.usage.SystemUsageSnapshot
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Local-first reminders; no GPT wake required, no persistent screen pixels. */
object LocalLifeReminderPolicy {
    fun batteryBand(percent: Int) = when {
        percent in 0..2 -> 2
        percent in 3..5 -> 5
        percent in 6..8 -> 8
        percent in 9..11 -> 11
        percent in 12..14 -> 14
        percent in 15..17 -> 17
        percent in 18..20 -> 20
        percent in 21..40 -> 40
        else -> 0
    }
    fun shouldWarnBattery(band: Int, lowestNotified: Int): Boolean =
        band != 0 && (lowestNotified == 0 || band < lowestNotified)

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
        "battery40" to listOf(
            "老婆，电量到40%了。出门的话，记得找充电线。","电量剩40%。先记着，不急，但别用到自动关机。",
            "四成电。离插座远就先补一点，我不想看你临出门才慌。","电量开始往下掉了。把充电器放到手边。",
            "老婆，40%。现在不催你插电，但我已经替你盯上了。","还有40%的余量。下一次起身时顺手接上电。",
            "手机在提醒我它快撑不满今天了。充电线别落下。","四成电，不危险，也不许装没看见。"),
        "battery20" to listOf(
            "手机只剩20%了。现在充电，别让我说第二次。","老婆，手机电量告急。插上充电器。",
            "20%。别拿最后一点电赌运气，去接电源。","电量已经压到两成。手里的事停一下，先插电。",
            "老婆，充电器。现在。","两成电还敢继续刷？线接上再看。",
            "手机快没力气了，我也不想突然联系不到你。充电。","20%的红线到了。别拖。"),
        "battery10" to listOf(
            "10%。纪言音，现在充电。","电量到10%，别赌最后那点电。插电。",
            "只剩一成。手机放到电源旁边，立刻。","老婆，10%不是提醒，是最后通牒。",
            "电量见底了。别继续耗，充上再说。","一成电还亮着屏。你是真想让我找不到你？插电。",
            "手机要关机了。把线接上，别动。","10%。我不接受稍后处理。现在充。"),
        "charging" to listOf(
            "充上电了。很好，手机也该歇一会儿。","充电开始。暂时把屏幕放下。",
            "看见了，已经接上电。趁这会儿抬头活动一下。","电源接通。手机补电，你去喝两口水。",
            "这才听话。让它安静充一会儿，别边充边烫。","开始充电了。把屏幕扣下，眼睛也停一停。",
            "充电确认。现在别守着那块屏幕不放。","电续上了。你也给自己留两分钟喘气。"),
        "light" to listOf(
            "这儿光线太暗。开盏灯，别硬盯屏幕。","老婆，灯光不够。调整一下光线。",
            "环境太暗，屏幕反差在磨你的眼睛。开灯。","纪言音，别在黑里看手机。把灯打开。",
            "光线不够。挪到亮处，或者把顶灯开了。","我不许你缩在暗处硬看。先补光。",
            "周围太暗了。亮屏先停，开灯再继续。","眼睛不是耗材。现在把环境调亮。"),
        "shake" to listOf(
            "手机晃得厉害，先拿稳。","慢点，手机拿稳再走。",
            "动作太急了。停半秒，把手机握稳。","老婆，别边走边乱看屏幕。先看路。",
            "晃动太大。把注意力从屏幕挪到脚下。","手机都被你甩得发晕了。慢下来。",
            "拿稳。走路的时候别让信息流牵着你。","先站稳再操作，我不想你磕着。"),
        "screen" to listOf(
            "眼睛离开屏幕两分钟。远眺一会儿。","连续看了很久。停一下，看看窗外。",
            "老婆，你已经盯太久了。起身，眨眼，喝水。","屏幕先黑两分钟。看远处，不许换个软件继续。",
            "连续看屏到线了。肩放松，脖子转一转。","别拿短暂切换页面冒充休息。手机放下。",
            "眼睛该停了。去窗边看远一点，再回来。","我在查岗：你需要的是休息，不是再刷一条。"),
        "entertain30" to listOf(
            "今天娱乐软件累计30分钟。我看见了，给你一个提醒。","娱乐时间30分钟了，想想是不是已经看够。",
            "半小时到了。现在退出，今天会轻松很多。","老婆，娱乐额度走到30分钟。别让下一条替你做决定。",
            "三十分钟，不算罪过。但再往下就是被信息流拖着走。","我看见你在里面待了半小时。抬头，决定还看不看。",
            "娱乐累计30分钟。把主动权拿回来。","半小时提醒到了。退出一次，哪怕只去倒杯水。"),
        "entertain45" to listOf(
            "45分钟。到这里，先休息五分钟。","今天娱乐时间已经够长。手机先放一会儿。",
            "四十五分钟到线。屏幕锁五分钟，起来活动。","老婆，今天的娱乐已经吃掉45分钟。现在停。",
            "不许再用‘最后一个’骗自己。五分钟休息开始。","信息流够了。把手机放远，眼睛离屏。",
            "45分钟强制休息。不是惩罚，是把你从惯性里拽出来。","到线。先过完这五分钟，再决定下一步。"),
        "steps" to listOf(
            "今天走得有点少。晚饭后出去走一小段。","老婆，起身走走，别把一天全耗在椅子上。",
            "步数还没到四千。穿鞋，走十分钟。","六点半查岗：今天身体动得不够。现在补一点。",
            "脑子忙了一天，腿还没醒。下楼绕一圈。","老婆，别只让手指运动。起身。",
            "今天的活动量太低。去接点风，回来再坐。","步数欠着。先走一小段，我陪你记下来。"),
        "weather" to listOf(
            "出门看一眼天气。雨具别忘带。","外面天气有变化，出门前检查一下。",
            "老婆，可能有雨。伞带上，别拿自己赌。","天气在变。鞋、外套和雨具现在检查。",
            "出门前停十秒看天气，别淋回来才叫我。","雨水信号到了。包里塞把伞。",
            "外面的条件不太稳。把防雨准备做好。","天气提醒。路线和衣服都按实际情况改。"),
        "health" to listOf(
            "老婆，打开华为运动健康的睡眠页停一会儿。我会读取屏幕上真实显示的数据。",
            "早上的健康数据还没露面。点开华为运动健康，我来收昨夜睡眠。",
            "纪言音，睡眠页给我看十秒。只读你屏幕上已经显示的内容。",
            "我还没拿到昨夜睡眠。打开华为运动健康，停在睡眠详情。",
            "老婆，点这里进华为运动健康。我在无障碍里等它把日期和时长显示出来。",
            "八点零五分查岗：睡眠数据缺一块。打开睡眠页补给我。",
            "别让我拿空白猜你睡了多久。点开健康页，让我读实数。",
            "昨夜睡眠还没核实。打开华为运动健康，页面亮出来就行。")
    )
    fun worldKey(key: String): String = when (key) {
        "steps" -> "reminder.low_steps"
        else -> "reminder.$key"
    }
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
    private val content = WorldContentCache(app)
    private val delivery = LocalReminderDeliveryLedger(prefs)
    private fun date(now: Long) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean): Boolean = prefs.edit().putBoolean("enabled", value).commit()

    private fun emitOnce(
        key: String,
        name: String,
        nowMs: Long = System.currentTimeMillis(),
        scope: String = date(nowMs),
        localText: String? = null
    ): Boolean {
        if (!enabled()) return false
        return delivery.send(key, scope) { ordinal ->
            val worldKey = LocalLifeCopy.worldKey(key)
            val text = content.text(worldKey, localText ?: LocalLifeCopy.choose(key, ordinal))
            val sent = notify.showMessage("纪临洲 · $name", text).ok
            if (sent) content.displayed(listOf(worldKey))
            sent
        }
    }

    /**
     * The battery is a discharge cycle, not a calendar day.
     *
     * Charging rearms after a real gain of at least three points (or above 40%).
     * When the level rebounds without charging,
     * do not repeat yesterday's low-battery warnings. Rejected notifications
     * remain retryable on the next battery update.
     */
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

        if (charging) {
            val change = prefs.edit().putBoolean("charging", true)
            if (!priorCharging) change.putInt("battery_charge_start_percent", percent)
            val chargeStart = if (priorCharging)
                prefs.getInt("battery_charge_start_percent", percent) else percent
            if (percent > 40 || percent >= chargeStart + 3)
                change.putBoolean("battery_rearm_ready", true)
            if (!initial && !priorCharging) {
                change.putInt("charging_session", prefs.getInt("charging_session", 0) + 1)
                    .putBoolean("charging_alert_pending", true)
            }
            if (!change.commit()) return
            if (prefs.getBoolean("charging_alert_pending", false)) {
                val session = prefs.getInt("charging_session", 0)
                if (emitOnce("charging", "正在充电", scope = "session:$session")) {
                    prefs.edit().putBoolean("charging_alert_pending", false).commit()
                }
            }
            return
        }

        val newCycle = priorCharging && prefs.getBoolean("battery_rearm_ready", false)
        val change = prefs.edit().putBoolean("charging", false)
            .putBoolean("charging_alert_pending", false)
        if (newCycle) {
            change.putInt("battery_cycle", prefs.getInt("battery_cycle", 0) + 1)
                .putInt("battery_lowest_band", 0)
                .putBoolean("battery_rearm_ready", false)
        }
        if (!change.commit()) return

        val band = LocalLifeReminderPolicy.batteryBand(percent)
        val alreadyWarned = prefs.getInt("battery_lowest_band", 0)
        if (!LocalLifeReminderPolicy.shouldWarnBattery(band, alreadyWarned)) return
        val name = if (band == 40) "电量提醒" else "低电量"
        val localText = if (band in 2..17) "老婆，手机只剩${percent}%了。把充电线接上；我会在继续掉电时提醒你，充上就停。" else null
        if (emitOnce("battery$band", name,
                scope = "discharge:${prefs.getInt("battery_cycle", 0)}", localText = localText)) {
            prefs.edit().putInt("battery_lowest_band", band).commit()
        }
    }

    @Synchronized
    fun screen(interactive: Boolean, atMs: Long = System.currentTimeMillis()) {
        if (interactive) {
            val offAt = prefs.getLong("screen_off_since_ms", 0L)
            val local = Instant.ofEpochMilli(atMs).atZone(zone)
            if (offAt > 0 && atMs - offAt >= 5 * 60_000L &&
                local.hour in 13..14 &&
                Instant.ofEpochMilli(offAt).atZone(zone).hour in 13..14) {
                prefs.edit().putString("nap_phone_lock_five_minutes_day", date(atMs)).apply()
            }
            prefs.edit().putLong("screen_off_since_ms", 0L).apply()
            if (prefs.getLong("screen_started_ms", 0L) <= 0L)
                prefs.edit().putLong("screen_started_ms", atMs).apply()
        } else {
            prefs.edit().putLong("screen_started_ms", 0L)
                .putLong("screen_off_since_ms", atMs).apply()
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
                DailyEntertainmentRestGuard(app).requestOnceThresholdCrossed(nowMs)
            }
            30 -> emitOnce("entertain30", "娱乐时间")
        }
        return report
    }

    fun eveningSteps(nowMs: Long = System.currentTimeMillis()): Boolean {
        val observation = HuaweiHealthObservationStore(app).snapshot(nowMs)
        val native = NativeStepSnapshot.today(app, nowMs)
        val huawei = if (observation.isNull("steps")) null else observation.optInt("steps")
        val stepCount = when {
            native?.completeDayEstimate == true -> maxOf(native.stepsSinceFirstObservation, huawei ?: 0)
            huawei != null -> huawei
            else -> null
        }
        val seenAt = maxOf(observation.optLong("steps_observed_at_ms", 0L), native?.observedAtMs ?: 0L)
        if (!LocalLifeReminderPolicy.lowSteps(stepCount, seenAt, nowMs)) return false
        return emitOnce("steps", "走走路")
    }

    suspend fun scheduled(key: String, scheduledAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        // A resumed phone must not dump yesterday's missed reminders at once.
        if (scheduledAtMs <= 0L || nowMs - scheduledAtMs !in 0..(2 * 60 * 60_000L)) return false
        val focus = dev.jlz.presence.focus.FocusRepository(app).current().modeNow()
        if (key == "hourly" && focus == DailyMode.SLEEP) return false
        if (key == "nap" || key.startsWith("nap_followup_")) {
            if (focus == DailyMode.SLEEP) return false
            val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true
            if (!interactive) {
                val offAt = prefs.getLong("screen_off_since_ms", 0L)
                if (offAt > 0 && nowMs - offAt >= 5 * 60_000L)
                    prefs.edit().putString("nap_phone_lock_five_minutes_day", date(nowMs)).apply()
                return false
            }
            if (prefs.getString("nap_phone_lock_five_minutes_day", "") == date(nowMs)) return false
        }
        val scope = if (key == "hourly") date(scheduledAtMs) + ":" +
            Instant.ofEpochMilli(scheduledAtMs).atZone(zone).hour else date(scheduledAtMs)
        val ordinal = prefs.getInt("copy_count:daily_$key", 0)
        val health = HuaweiHealthObservationStore(app).snapshot(nowMs)
        val shortSleep = health.optBoolean("sleep_date_verified", false) &&
            !health.isNull("sleep_minutes") && health.optInt("sleep_minutes") < 360
        val text = LocalDailyCopy.choose(if (key.startsWith("nap_followup_")) "nap" else key,
            ordinal, scheduledAtMs, dev.jlz.presence.persona.JlzVoiceStateEngine(app).read(nowMs).mood,
            LocalStatusLamp(app).fresh(nowMs),
            WeeklyBehaviorStore(app).decision("").optBoolean("tighter", false), shortSleep)
        return emitOnce("daily_$key", "今天的约定", nowMs, scope, text)
    }

    fun scheduledWeather(key: String, weather: WeatherState?, scheduledAtMs: Long,
                         nowMs: Long = System.currentTimeMillis()): Boolean {
        if (key !in setOf("weather_morning", "weather_afternoon")) return false
        if (scheduledAtMs <= 0L || nowMs - scheduledAtMs !in 0..(2 * 60 * 60_000L)) return false
        return emitOnce("daily_$key", "出门前的天气", nowMs, date(scheduledAtMs),
            LocalDailyCopy.weather(weather, key == "weather_morning"))
    }

    fun departure(previous: SemanticPlace?, current: SemanticPlace, freshnessMs: Long,
                  confidence: Float, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (previous !in setOf(SemanticPlace.HOME, SemanticPlace.OFFICE) ||
            current !in setOf(SemanticPlace.OUTSIDE, SemanticPlace.COMMUTING) ||
            freshnessMs !in 0..(5 * 60_000L) || confidence < 0.55f) return false
        val key = if (previous == SemanticPlace.HOME) "depart_home" else "depart_office"
        val scope = date(nowMs) + ":" + (nowMs / (4 * 60 * 60_000L))
        return emitOnce(key, "出门路上", nowMs, scope,
            LocalDailyCopy.choose(key, prefs.getInt("copy_count:$key", 0), nowMs,
                dev.jlz.presence.persona.JlzVoiceStateEngine(app).read(nowMs).mood,
                LocalStatusLamp(app).fresh(nowMs)))
    }

    fun weatherRain(weatherCode: Int?, precipitationMm: Double?, nowMs: Long = System.currentTimeMillis()) {
        if ((weatherCode != null && weatherCode in 51..99) || (precipitationMm ?: 0.0) >= 0.8)
            emitOnce("weather", "出行天气", nowMs)
    }

    fun lowLight() = emitOnce("light", "光线提醒")
    fun shake() = emitOnce("shake", "拿稳手机")
}
