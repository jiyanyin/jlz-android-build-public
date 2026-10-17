package dev.jlz.presence.notification

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Recheck local call/car/meeting evidence immediately before a prepared notification. */
object PresenceInterruptionPolicy {
    fun reason(context: Context, now: Long = System.currentTimeMillis()): String? {
        if (Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).hour in setOf(3, 4)) return "protected_03_04"
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) return "call_active"
        val ui = context.getSystemService(UiModeManager::class.java)
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_CAR) return "driving_car_mode"
        val calendar = runCatching { dev.jlz.presence.life.NativeCalendarBridge(context).snapshot(now - 12 * 60 * 60_000L, now + 60_000L) }.getOrNull()
        val events = calendar?.optJSONArray("events")
        for (i in 0 until (events?.length() ?: 0)) {
            val event = events?.optJSONObject(i) ?: continue
            if (event.optBoolean("all_day") || now !in event.optLong("start_ms") until event.optLong("end_ms")) continue
            if (listOf("会议", "开会", "meeting", "call", "通话", "驾驶", "开车").any { event.optString("title").contains(it, true) }) return "calendar_meeting_or_driving"
        }
        return null
    }
}
