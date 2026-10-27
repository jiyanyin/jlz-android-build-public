package dev.jlz.presence.life

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jlz.presence.place.PlaceWeatherCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** One rolling, inexact alarm. No cloud task, MCP call, or exact-alarm permission. */
object LocalDailyReminderSchedule {
    const val ACTION = "dev.jlz.presence.life.DAILY_REMINDER"
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fixed = mapOf(
        "weather_morning" to (8 to 0),
        "depart_morning" to (8 to 40),
        "water_morning" to (10 to 30),
        "lunch" to (12 to 0),
        "nap" to (13 to 30),
        "nap_followup_1340" to (13 to 40),
        "nap_followup_1350" to (13 to 50),
        "nap_followup_1400" to (14 to 0),
        "nap_followup_1410" to (14 to 10),
        "nap_followup_1420" to (14 to 20),
        "weather_afternoon" to (14 to 30),
        "depart_afternoon" to (14 to 40),
        "afternoon" to (16 to 30),
        "dinner" to (18 to 0),
        "steps_evening" to (18 to 30)
    )

    data class Slot(val key: String, val atMs: Long)

    /** Foreground-service fallback. AlarmManager may be late under OEM doze. */
    fun dueForRunningService(nowMs: Long): List<Slot> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (0..1).flatMap { back ->
            val date = today.minusDays(back.toLong())
            (fixed.toList() + (0..23).map { "hourly" to (it to 20) }).map { (key, time) ->
                Slot(key, date.atTime(time.first, time.second).atZone(zone).toInstant().toEpochMilli())
            }
        }.filter { nowMs - it.atMs in 0L..90_000L }
    }

    suspend fun deliver(context: Context, slot: Slot) {
        if (!LivingReminderWindow.allowed(slot.key,slot.atMs,System.currentTimeMillis())) return
        val app = context.applicationContext
        val engine = LocalLifeReminderEngine(app)
        if (slot.key == "steps_evening") {
            engine.eveningSteps()
            return
        }
        if (slot.key == "hourly") runCatching { WeeklyBehaviorStore(app).sample() }
        if (slot.key.startsWith("weather_")) {
            val weather = runCatching { PlaceWeatherCoordinator(app)
                .snapshot(locationTimeoutMs = 1_500L, weatherTimeoutMs = 2_500).weather }.getOrNull()
            engine.scheduledWeather(slot.key, weather, slot.atMs)
        } else engine.scheduled(slot.key, slot.atMs)
    }

    fun next(nowMs: Long): Slot {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val date = now.toLocalDate()
        val slots = fixed.map { (key, time) -> key to time } +
            listOf("hourly" to (now.hour to 20), "hourly" to (((now.hour + 1) % 24) to 20))
        return (0..1).flatMap { day ->
            slots.map { (key, time) ->
                Slot(key, date.plusDays(day.toLong()).atTime(time.first, time.second)
                    .atZone(zone).toInstant().toEpochMilli())
            }
        }.filter { it.atMs > nowMs }.minBy { it.atMs }
    }

    fun schedule(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val slot = next(nowMs)
        val intent = Intent(context, LocalDailyReminderReceiver::class.java)
            .setAction(ACTION).putExtra("slot", slot.key).putExtra("scheduled_at_ms", slot.atMs)
        val pending = PendingIntent.getBroadcast(context, 9020, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.atMs, pending)
    }
}

class LocalDailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalDailyReminderSchedule.ACTION) return
        val app = context.applicationContext
        val slot = intent.getStringExtra("slot") ?: return
        val scheduledAt = intent.getLongExtra("scheduled_at_ms", 0L)
        // Reschedule before doing network work. Android/OEM may delay inexact alarms.
        LocalDailyReminderSchedule.schedule(app, maxOf(System.currentTimeMillis(), scheduledAt))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 14:20 has both the nap follow-up and the independent hourly slot.
                val coincident = LocalDailyReminderSchedule.dueForRunningService(scheduledAt)
                    .filter { it.atMs == scheduledAt }
                for (due in coincident.ifEmpty { listOf(LocalDailyReminderSchedule.Slot(slot, scheduledAt)) }) {
                    LocalDailyReminderSchedule.deliver(app, due)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
