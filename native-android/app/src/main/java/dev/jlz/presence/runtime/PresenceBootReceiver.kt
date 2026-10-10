package dev.jlz.presence.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jlz.presence.overlay.FloatingPresenceMode
import dev.jlz.presence.overlay.FloatingPresenceService
import dev.jlz.presence.usage.DeviceActivityJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PresenceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                val tripCache = app.getSharedPreferences("jlz_manual_trip_cache", Context.MODE_PRIVATE)
                tripCache.getString("session_id", null)?.let { dev.jlz.presence.trip.PendingTripStop.remember(app, it) }
                tripCache.edit().remove("points").remove("session_id").apply()
                val prefs = PresenceDevicePreferencesRepository(app).load()
                val settings = RuntimeSettingsRepository(app).load()
                val focus = dev.jlz.presence.focus.FocusRepository(app)
                val mode = focus.current()
                if (mode.dailyMode in setOf(dev.jlz.presence.focus.DailyMode.FOCUS, dev.jlz.presence.focus.DailyMode.BREAK)) {
                    if (mode.modeNow() == dev.jlz.presence.focus.DailyMode.NORMAL) focus.setDailyMode(dev.jlz.presence.focus.DailyMode.NORMAL)
                    else runCatching { dev.jlz.presence.study.StudyTimerService.sync(app) }
                }
                if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
                    DeviceActivityJournal(app).recordEvent(
                        context = app,
                        eventType = "DEVICE_BOOT",
                        origin = DeviceActivityJournal.Origin.SYSTEM,
                        deviceId = settings.deviceId
                    )
                }
                if (
                    prefs.autoStartRuntime &&
                    settings.baseUrl.isNotBlank() &&
                    settings.token.isNotBlank()
                ) {
                    NativeRuntimeService.start(app)
                }
                if (prefs.restoreOverlay) {
                    FloatingPresenceService.start(
                        app,
                        prefs.overlayMessage,
                        runCatching {
                            FloatingPresenceMode.valueOf(prefs.overlayMode)
                        }.getOrDefault(FloatingPresenceMode.LIFE)
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
