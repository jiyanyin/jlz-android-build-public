package dev.jlz.presence.capture

import android.content.Context
import android.os.PowerManager
import dev.jlz.presence.overlay.FloatingPresenceService
import dev.jlz.presence.screen.ScreenObservationBus
import dev.jlz.presence.screen.ScreenshotCaptureResult
import dev.jlz.presence.study.StudySessionRepository
import dev.jlz.presence.usage.ForegroundUsageTracker
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Owner-authorized sampling of the visible screen, using the existing
 * Accessibility gateway and durable image outbox (no second screenshot API).
 * Neither successful capture nor upload implies GPT has actually viewed it.
 */
class AutomaticCaptureCoordinator(private val context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "jlz_presence_auto_capture_v1", Context.MODE_PRIVATE
    )
    private val outbox = PendingScreenshotQueue(context.applicationContext)
    private val study = StudySessionRepository(context.applicationContext)

    fun enabled(): Boolean = preferences.getBoolean("enabled", true)

    fun setEnabled(value: Boolean): Boolean =
        preferences.edit().putBoolean("enabled", value).commit()

    suspend fun captureIfDue(): String {
        if (!enabled()) return "automatic_capture_disabled"
        val interactive = context.getSystemService(PowerManager::class.java)
            ?.isInteractive == true
        if (!interactive || !ScreenObservationBus.isAvailable()) {
            return "screen_off_or_accessibility_unavailable"
        }
        val observed = ScreenObservationBus.observations.value
            ?: return "screen_observation_missing"
        val now = System.currentTimeMillis()
        val age = now - observed.observedAtMs
        if (age < 0L || age > 30 * 60_000L) return "screen_observation_stale"
        val packageName = observed.packageName
            ?.takeIf { it.isNotBlank() && it != context.packageName }
            ?: return "phone_foreground_not_observed"
        val state = study.state.first()
        val sessionId = state.sessionId
            .takeIf { state.active && it.isNotBlank() }
        val mode = if (sessionId == null) "life" else "study"
        val intervalMs = if (sessionId == null) LIFE_INTERVAL_MS else STUDY_INTERVAL_MS
        val key = "last_attempt_$mode"
        val previous = preferences.getLong(key, 0L)
        if (previous > 0L && now >= previous && now - previous < intervalMs) {
            return "not_due:$mode"
        }
        // Record attempt BEFORE invoking OS screenshot: Android/OEM failure or
        // an unwatched secure window must not cause a rapid capture retry loop.
        check(preferences.edit().putLong(key, now).commit()) {
            "auto_capture_attempt_not_recorded"
        }
        val image = when (val capture = FloatingPresenceService.captureForRuntime()) {
            is ScreenshotCaptureResult.Captured -> capture
            is ScreenshotCaptureResult.Unavailable ->
                return "screenshot_unavailable:" + capture.reason.take(140)
        }
        val eventId = UUID.randomUUID().toString()
        outbox.enqueue(
            capture = image, eventId = eventId,
            sourcePackage = packageName,
            studySessionId = sessionId, origin = "automatic_$mode"
        )
        return "saved_on_phone:$mode:event_id=$eventId"
    }

    companion object {
        // Capped local snapshots, never every animation frame or every tap.
        private const val STUDY_INTERVAL_MS = 4 * 60_000L
        private const val LIFE_INTERVAL_MS = 12 * 60_000L
    }
}
