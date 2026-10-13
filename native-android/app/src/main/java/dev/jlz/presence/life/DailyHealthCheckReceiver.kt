package dev.jlz.presence.life

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Inexact while-idle daily health checks; Android can defer these past 08:05 or
 * 18:30. Do not force-unlock or foreground-launch Huawei Health behind the
 * user's back: record that data was actually observed or explicitly missing.
 */
object DailyHealthSchedule {
    const val ACTION_MORNING = "dev.jlz.presence.health.MORNING_0805"
    const val ACTION_EVENING = "dev.jlz.presence.health.EVENING_1830"
    private val zone = ZoneId.of("Asia/Shanghai")
    fun nextOccurrence(nowMs: Long, hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var next = now.toLocalDate().atTime(hour, minute).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
    fun schedule(context: Context, action: String, nowMs: Long = System.currentTimeMillis()) {
        val pair = when(action) {
            ACTION_MORNING -> 8 to 5
            ACTION_EVENING -> 18 to 30
            else -> return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val requestCode = if (action == ACTION_MORNING) 805 else 1830
        val intent = Intent(context, DailyHealthCheckReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // This API does not require SCHEDULE_EXACT_ALARM, but actual firing can
        // be delayed by Doze or OEM background restrictions.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            nextOccurrence(nowMs, pair.first, pair.second), pending)
    }
    fun scheduleAll(context: Context) {
        schedule(context, ACTION_MORNING)
        schedule(context, ACTION_EVENING)
    }
}

class DailyHealthCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(DailyHealthSchedule.ACTION_MORNING, DailyHealthSchedule.ACTION_EVENING)) return
        DailyHealthSchedule.schedule(context.applicationContext, action)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                if (action == DailyHealthSchedule.ACTION_MORNING) {
                    val store = HuaweiHealthObservationStore(app)
                    store.markMorningAttempt()
                    // Check already visible Health notification and UI only.
                    // The sleep field remains unavailable unless actual Huawei
                    // sleep text is visible to the authorized accessibility reader.
                    dev.jlz.presence.notification.PresenceNotificationListenerService.rescanActive()
                    val visible = dev.jlz.presence.screen.ScreenObservationBus.observations.value
                    if (visible?.packageName == "com.huawei.health" &&
                        System.currentTimeMillis() - visible.observedAtMs in 0..60_000L) {
                        store.recordVisibleHuaweiPage(visible.visibleText, visible.observedAtMs)
                    }
                } else {
                    LocalLifeReminderEngine(app).eveningSteps()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
