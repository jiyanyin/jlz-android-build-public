package dev.jlz.presence.runtime

import android.content.Context
import dev.jlz.presence.life.HuaweiHealthObservationStore
import dev.jlz.presence.place.PlaceAnchorRepository
import dev.jlz.presence.place.DeviceLocationAdapter
import dev.jlz.presence.screen.ScreenObservationBus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Status only. Never exports tokens, exact anchors or unlock credentials. */
object LocalConnectionStatus {
    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): JSONObject = runBlocking {
        val settings = RuntimeSettingsRepository(context).load()
        val status = NativeClientDiagnostics.status.value
        val at = status.lastHeartbeatAtMs ?: 0L
        val anchors = PlaceAnchorRepository(context)
        JSONObject().put("configured", settings.baseUrl.isNotBlank() && settings.token.isNotBlank())
            .put("device_id", settings.deviceId)
            .put("service_running", status.serviceRunning)
            .put("background_connected", status.runtimeConnected && at > 0 && nowMs - at in 0..180_000L)
            .put("last_heartbeat_at_ms", if (at > 0) at else JSONObject.NULL)
            .put("request_failed", status.lastError != null)
            .put("accessibility_ready", ScreenObservationBus.isAvailable())
            .put("location_permitted", DeviceLocationAdapter(context).hasPermission())
            .put("home_anchor_configured", anchors.hasHome())
            .put("office_anchor_configured", anchors.hasOffice())
            .put("health", HuaweiHealthObservationStore(context).snapshot(nowMs))
    }
}
