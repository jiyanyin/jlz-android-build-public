package dev.jlz.presence.runtime

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import dev.jlz.presence.study.StudyPatrol
import dev.jlz.presence.trip.TripController
import dev.jlz.presence.usage.ForegroundUsageTracker
import org.json.JSONObject

/**
 * Structured presence snapshot. Every observed block carries its own
 * observed_at_ms so the brain can reason about freshness instead of assuming a
 * single capture moment. Optional sensors that are missing simply report
 * unavailable and never crash the app.
 */
class PresenceSnapshot(
    private val context: Context,
    private val deviceId: String
) {
    fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("schema_version", 2)
            .put("device_id", deviceId)
            .put("device_type", NativePhoneSnapshot.deviceType(context))
            .put("active_at_ms", now)
            .put(
                "battery", JSONObject()
                    .put("percent", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
                    .put("charging", bm?.isCharging ?: false)
                    .put("available", bm != null)
                    .put("observed_at_ms", now)
            )
            .put(
                "screen", JSONObject()
                    .put("interactive", pm?.isInteractive ?: false)
                    .put("observed_at_ms", now)
            )
            .put(
                "foreground", JSONObject()
                    .put("package", ForegroundUsageTracker.currentPackageName() ?: "")
                    .put("observed_at_ms", now)
            )
            .put(
                "study", JSONObject()
                    .put("active", StudyPatrol.state.value.active)
                    .put("anomaly_count", StudyPatrol.state.value.anomalyCount)
                    .put("ends_at_ms", StudyPatrol.state.value.endsAtMs)
                    .put("observed_at_ms", now)
            )
            .put(
                "trip", JSONObject()
                    .put("active", TripController.active.value)
                    .put("observed_at_ms", now)
            )
            .put(
                "capabilities", JSONObject()
                    .put("location", true)
                    .put("accessibility", true)
                    .put("overlay", true)
                    // V2 removed capabilities are reported explicitly unavailable.
                    .put("health_connect", "removed")
                    .put("white_noise", "removed")
                    .put("sleep_audio", "removed")
            )
    }
}
