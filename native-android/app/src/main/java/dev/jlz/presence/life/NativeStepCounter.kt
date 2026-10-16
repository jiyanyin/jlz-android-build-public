package dev.jlz.presence.life

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.notification.NotificationAdapter
import dev.jlz.presence.usage.PendingActivityEventStore
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Device hardware steps. Huawei notification counts remain a separate fallback. */
class NativeStepCounter(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val manager = app.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val tracker = WalkingSessionTracker(app)
    @Volatile private var registered = false

    fun available(): Boolean = sensor != null
    fun permitted(): Boolean = Build.VERSION.SDK_INT < 29 ||
        app.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (registered) return true
        if (!permitted()) return false
        val found = sensor ?: return false
        registered = manager?.registerListener(this, found, SensorManager.SENSOR_DELAY_NORMAL) == true
        return registered
    }
    fun stop() {
        if (registered) manager?.unregisterListener(this)
        registered = false
    }
    fun tick(nowMs: Long = System.currentTimeMillis()) = tracker.tick(nowMs)
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val count = event.values.firstOrNull()?.toLong() ?: return
        tracker.observe(count, System.currentTimeMillis())
    }
}

data class NativeStepReading(val stepsSinceFirstObservation: Int, val observedAtMs: Long,
                             val dayBaselineAtMs: Long, val completeDayEstimate: Boolean)

object NativeStepSnapshot {
    fun walking(context: Context, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val p = context.applicationContext.getSharedPreferences("jlz_native_steps_v1", Context.MODE_PRIVATE)
        val at = p.getLong("session_last_step_at", 0L)
        val active = p.getString("session_id", null) != null
        val fresh = active && at > 0 && nowMs - at in 0..300_000L
        return JSONObject().put("state", when {
            !active -> "no_active_session"
            !fresh -> "awaiting_finish_confirmation"
            p.getBoolean("start_confirmed", false) -> "walking_confirmed"
            else -> "you_are_moving"
        }).put("observed_at_ms", if (active && at > 0) at else JSONObject.NULL)
            .put("started_at_ms", if (active) p.getLong("session_start_at", 0L) else JSONObject.NULL)
            .put("confirmed_by_user", active && p.getBoolean("start_confirmed", false))
            .put("fresh", fresh)
            .put("steps", if (active) (p.getLong("session_end_count", 0L) - p.getLong("session_start_count", 0L)).coerceAtLeast(0L) else JSONObject.NULL)
    }
    fun today(context: Context, nowMs: Long = System.currentTimeMillis()): NativeStepReading? {
        val prefs = context.applicationContext.getSharedPreferences("jlz_native_steps_v1", Context.MODE_PRIVATE)
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        if (prefs.getString("day", "") != date) return null
        val at = prefs.getLong("sensor_at", 0L)
        val baselineAt = prefs.getLong("day_baseline_at", 0L)
        if (at <= 0L || nowMs - at !in 0..(3 * 60 * 60_000L)) return null
        val steps = (prefs.getLong("sensor_count", 0L) - prefs.getLong("day_baseline", 0L))
            .coerceIn(0L, 200_000L).toInt()
        val early = baselineAt > 0 && Instant.ofEpochMilli(baselineAt)
            .atZone(ZoneId.of("Asia/Shanghai")).hour < 2
        return NativeStepReading(steps, at, baselineAt, early)
    }

    fun exercise(context: Context, date: String): Pair<Long, Long> {
        val prefs = context.applicationContext.getSharedPreferences("jlz_native_steps_v1", Context.MODE_PRIVATE)
        return prefs.getLong("exercise_minutes:$date", 0L) to
            prefs.getLong("exercise_steps:$date", 0L)
    }
}

internal class WalkingSessionTracker(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jlz_native_steps_v1", Context.MODE_PRIVATE)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

    @Synchronized fun observe(count: Long, atMs: Long) {
        if (count < 0) return
        val previous = prefs.getLong("sensor_count", -1)
        val previousAt = prefs.getLong("sensor_at", 0L)
        val sameDay = prefs.getString("day", "") == day(atMs)
        // The counter resets on phone reboot; a first observation is a baseline, not a walk.
        if (previous < 0 || count < previous || !sameDay) {
            prefs.edit().putLong("sensor_count", count).putLong("sensor_at", atMs)
                .putString("day", day(atMs)).putLong("day_baseline", count)
                .putLong("day_baseline_at", atMs)
                .putLong("window_count", count).putLong("window_at", atMs)
                .remove("session_id").apply()
            return
        }
        if (count == previous || atMs <= previousAt) return
        val delta = count - previous
        if (delta > 2_000) return // corrupted or stale sensor value
        prefs.edit().putLong("sensor_count", count).putLong("sensor_at", atMs).apply()
        val session = prefs.getString("session_id", null)
        if (session != null) {
            prefs.edit().putLong("session_last_step_at", atMs)
                .putLong("session_end_count", count).putBoolean("end_prompted", false).apply()
            return
        }
        if (atMs < prefs.getLong("declined_until", 0L)) return
        var windowAt = prefs.getLong("window_at", atMs)
        var windowCount = prefs.getLong("window_count", previous)
        if (atMs - windowAt > 3 * 60_000L || count < windowCount) {
            windowAt = previousAt
            windowCount = previous
            prefs.edit().putLong("window_at", windowAt).putLong("window_count", windowCount).apply()
        }
        if (count - windowCount < 12L) return
        val id = UUID.randomUUID().toString()
        if (prefs.edit().putString("session_id", id).putLong("session_start_at", windowAt)
                .putLong("session_last_step_at", atMs).putLong("session_start_count", windowCount)
                .putLong("session_end_count", count).putBoolean("start_confirmed", false)
                .putBoolean("end_prompted", false).commit()) {
            publish(id, "walking_detected", "你开始持续走动了", atMs, false)
            prompt(id, false, "我看到你在持续走动。开始步行了吗？", "是，在走", "不是")
        }
    }

    @Synchronized fun tick(nowMs: Long) {
        val id = prefs.getString("session_id", null) ?: return
        val last = prefs.getLong("session_last_step_at", 0L)
        if (last <= 0 || nowMs - last < 5 * 60_000L) return
        if (!prefs.getBoolean("start_confirmed", false)) {
            // No answer means no confirmed exercise, never silently invent a walk.
            if (nowMs - last >= 15 * 60_000L) prefs.edit().remove("session_id").apply()
            return
        }
        if (!prefs.getBoolean("end_prompted", false)) {
            if (prefs.edit().putBoolean("end_prompted", true).commit())
                prompt(id, true, "脚步停了五分钟。走完了吗？", "走完了", "还在走")
        }
    }

    @Synchronized fun answer(id: String, ending: Boolean, yes: Boolean, nowMs: Long) {
        if (prefs.getString("session_id", null) != id) return
        notifications?.cancel(if (ending) END_NOTIFICATION else START_NOTIFICATION)
        if (!ending) {
            if (yes) {
                prefs.edit().putBoolean("start_confirmed", true).apply()
                publish(id, "walking_confirmed", "你确认开始步行", nowMs, true)
            } else {
                publish(id, "walking_not_exercise", "你未将这段走动确认为散步", nowMs, false)
                prefs.edit().remove("session_id").putLong("declined_until", nowMs + 15 * 60_000L).apply()
            }
            return
        }
        if (!yes) {
            prefs.edit().putBoolean("end_prompted", false).putLong("session_last_step_at", nowMs).apply()
            return
        }
        val start = prefs.getLong("session_start_at", 0L)
        val last = prefs.getLong("session_last_step_at", 0L)
        val steps = (prefs.getLong("session_end_count", 0L) -
            prefs.getLong("session_start_count", 0L)).coerceAtLeast(0L)
        if (start > 0 && last >= start && steps >= 12) {
            val minutes = ((last - start) / 60_000L).coerceAtLeast(1L)
            val date = day(start)
            prefs.edit().putLong("exercise_minutes:$date",
                prefs.getLong("exercise_minutes:$date", 0L) + minutes)
                .putLong("exercise_steps:$date", prefs.getLong("exercise_steps:$date", 0L) + steps)
                .remove("session_id").apply()
            val event = LocalLifeStore(app).recordTimeline("exercise", "步行",
                "确认步行${minutes}分钟 · ${steps}步")
            PendingActivityEventStore(app).use { outbox ->
                outbox.enqueue(id = event.id, source = "native_step_counter",
                    kind = "confirmed_walk", title = "确认步行", body = "${minutes}分钟 · ${steps}步",
                    metadata = JSONObject().put("date", date).put("duration_minutes", minutes)
                        .put("steps", steps).put("confirmed_by_user", true), observedAtMs = last)
            }
        } else prefs.edit().remove("session_id").apply()
    }

    private fun publish(session: String, kind: String, title: String, atMs: Long, confirmed: Boolean) {
        PendingActivityEventStore(app).use { outbox ->
            outbox.enqueue(id = UUID.nameUUIDFromBytes("$session:$kind".toByteArray()).toString(),
                source = "native_step_counter", kind = kind, title = title, body = title,
                metadata = JSONObject().put("actor", "owner").put("motion_state", kind)
                    .put("confirmed_by_user", confirmed).put("date", day(atMs)), observedAtMs = atMs)
        }
    }

    private fun prompt(id: String, ending: Boolean, message: String, yes: String, no: String) {
        NotificationAdapter(app).headsUpStatus() // create the existing private message channel
        val notificationId = if (ending) END_NOTIFICATION else START_NOTIFICATION
        fun choice(value: Boolean): PendingIntent {
            val intent = Intent(app, WalkingConfirmationReceiver::class.java)
                .setAction("dev.jlz.presence.life.WALK_CONFIRM")
                .putExtra("session_id", id).putExtra("ending", ending).putExtra("yes", value)
            return PendingIntent.getBroadcast(app,
                notificationId * 2 + if (value) 1 else 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(app, NotificationAdapter.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setContentTitle("纪临洲 · 步行确认")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, yes, choice(true)).addAction(0, no, choice(false))
            .setAutoCancel(true).build()
        runCatching { notifications?.notify(notificationId, notification) }
    }

    companion object {
        private const val START_NOTIFICATION = 8291
        private const val END_NOTIFICATION = 8292
    }
}

class WalkingConfirmationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "dev.jlz.presence.life.WALK_CONFIRM") return
        val id = intent.getStringExtra("session_id") ?: return
        WalkingSessionTracker(context).answer(id, intent.getBooleanExtra("ending", false),
            intent.getBooleanExtra("yes", false), System.currentTimeMillis())
    }
}
