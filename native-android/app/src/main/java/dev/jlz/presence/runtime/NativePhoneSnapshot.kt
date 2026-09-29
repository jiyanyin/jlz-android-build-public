package dev.jlz.presence.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject
import dev.jlz.presence.usage.DeviceActivityJournal
import dev.jlz.presence.life.LifeHealthJournalStore

/** Recent Android system samples, not inferred health or official GPT memory. */
object NativePhoneSnapshot {
    fun collect(
        context: Context,
        deviceId: String = DeviceActivityJournal.DEFAULT_DEVICE_ID
    ): JSONObject {
        val battery = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (100.0 * level / scale).toInt()
        } else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull()
        val kind = when {
            network == null -> "unknown_or_disconnected"
            network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            network.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val activity = DeviceActivityJournal(context.applicationContext)
        val highFrequency = activity.highFrequencySummary(context, deviceId = deviceId)
        return JSONObject()
            .put("source", "android_system_battery_power_network+android_usage_events")
            .put("device_id", deviceId)
            .put("device_type", "phone")
            .put("observed_at_ms", System.currentTimeMillis())
            .put("battery_percent", percent ?: JSONObject.NULL)
            .put("charging", charging)
            .put("screen_interactive", power.isInteractive)
            .put("lock_state", highFrequency.opt("lock_state"))
            .put("last_screen_on_at_ms", highFrequency.opt("last_screen_on_at_ms"))
            .put("last_screen_off_at_ms", highFrequency.opt("last_screen_off_at_ms"))
            .put("last_non_runtime_screen_off_at_ms", highFrequency.opt("last_non_runtime_screen_off_at_ms"))
            .put("last_lock_at_ms", highFrequency.opt("last_lock_at_ms"))
            .put("last_lock_origin", highFrequency.opt("last_lock_origin"))
            .put("last_unlock_at_ms", highFrequency.opt("last_unlock_at_ms"))
            .put("last_device_unlock_at_ms", highFrequency.opt("last_device_unlock_at_ms"))
            .put("last_unlock_origin", highFrequency.opt("last_unlock_origin"))
            .put("first_unlock_today_at_ms", highFrequency.opt("first_unlock_today_at_ms"))
            .put("first_device_unlock_today_at_ms", highFrequency.opt("first_device_unlock_today_at_ms"))
            .put("last_non_runtime_unlock_at_ms", highFrequency.opt("last_non_runtime_unlock_at_ms"))
            .put("first_non_runtime_unlock_today_at_ms", highFrequency.opt("first_non_runtime_unlock_today_at_ms"))
            .put("last_user_present_at_ms", highFrequency.opt("last_user_present_at_ms"))
            .put("last_user_present_origin", highFrequency.opt("last_user_present_origin"))
            .put("last_non_runtime_user_present_at_ms", highFrequency.opt("last_non_runtime_user_present_at_ms"))
            .put("last_non_runtime_interaction_at_ms", highFrequency.opt("last_non_runtime_interaction_at_ms"))
            .put("unlock_semantics", highFrequency.opt("unlock_semantics"))
            .put("current_screen_session_started_at_ms", highFrequency.opt("current_screen_session_started_at_ms"))
            .put("previous_screen_session", highFrequency.opt("previous_screen_session"))
            .put("network_type", kind)
            .put("cycle_state", runCatching {
                LifeHealthJournalStore(context.applicationContext).cycleSnapshot()
            }.getOrNull() ?: JSONObject.NULL)
    }
}
