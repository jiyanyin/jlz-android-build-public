package dev.jlz.presence.life

import java.time.Instant
import java.time.ZoneId

/** A delayed alarm is not permission to say "this afternoon" at midnight. */
object LivingReminderWindow {
    fun allowed(key: String, scheduled: Long, now: Long): Boolean {
        if (scheduled <= 0 || now < scheduled) return false
        val zone=ZoneId.of("Asia/Shanghai")
        val planned=Instant.ofEpochMilli(scheduled).atZone(zone)
        val actual=Instant.ofEpochMilli(now).atZone(zone)
        if (planned.toLocalDate()!=actual.toLocalDate()) return false
        val minutes=when {
            key.startsWith("depart_") || key.startsWith("nap") || key=="hourly" -> 10L
            key.startsWith("weather_") || key=="afternoon" -> 30L
            else -> 45L
        }
        return now-scheduled <= minutes*60_000L && (key!="hourly" || planned.hour==actual.hour)
    }
}
