package dev.jlz.presence.focus

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import dev.jlz.presence.notification.NotificationAdapter

/**
 * Immediate, local entertainment gate.
 *
 * Runtime / LLM latency is intentionally not on the critical path. The user's
 * answer is recorded locally and can be read later through the normal timeline.
 */
class EntertainmentGateV2Coordinator(
    private val service: AccessibilityService
) {
    private val app = service.applicationContext
    private val repository = EntertainmentGateRepository(app)
    private val handler = Handler(Looper.getMainLooper())
    private val notification = NotificationAdapter(app)
    private val isTablet =
        app.resources.configuration.smallestScreenWidthDp >= 600

    private var foregroundPackage: String? = null
    private val lastGateAtMs = mutableMapOf<String, Long>()
    private val scheduledSessionIds = mutableSetOf<String>()
    private val warningSessionIds = mutableSetOf<String>()
    private val callbacks = mutableListOf<Runnable>()

    fun observe(
        packageName: String?,
        eventType: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!repository.enabled()) return
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !packageName.isNullOrBlank()) {
            foregroundPackage = packageName
        }

        val profile = EntertainmentPolicy.profile(packageName) ?: return
        val release = repository.release(profile.packageName)
        if (release != null && release.active(nowMs)) {
            scheduleReleaseTimers(profile, release, nowMs)
            return
        }

        if (release != null && !release.active(nowMs)) {
            repository.clearRelease(profile.packageName)
            repository.recordExpired(profile, release)
            if (foregroundPackage == profile.packageName) {
                showGate(profile, "expired", nowMs)
            }
            return
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            showGate(
                profile,
                if (repository.smallStep(profile.packageName) != null) "small_step" else "entry",
                nowMs
            )
        }
    }

    fun close() {
        callbacks.forEach(handler::removeCallbacks)
        callbacks.clear()
        scheduledSessionIds.clear()
        warningSessionIds.clear()
    }

    private fun scheduleReleaseTimers(
        profile: EntertainmentProfile,
        release: EntertainmentRelease,
        nowMs: Long
    ) {
        if (!scheduledSessionIds.add(release.sessionId)) return

        val warningDelay =
            (release.untilMs - EntertainmentGateV2Policy.WARNING_BEFORE_MS - nowMs)
                .coerceAtLeast(0L)
        if (release.untilMs - nowMs > EntertainmentGateV2Policy.WARNING_BEFORE_MS) {
            val warning = Runnable {
                if (
                    repository.enabled() &&
                    repository.activeRelease(profile.packageName)?.sessionId == release.sessionId &&
                    foregroundPackage == profile.packageName &&
                    warningSessionIds.add(release.sessionId)
                ) {
                    notification.showMessage(
                        title = "纪临洲 · 还剩一分钟",
                        message = EntertainmentGateCopy.warning(
                            release.untilMs xor release.sessionId.hashCode().toLong()
                        )
                    )
                }
            }
            callbacks += warning
            handler.postDelayed(warning, warningDelay)
        } else if (
            foregroundPackage == profile.packageName &&
            warningSessionIds.add(release.sessionId)
        ) {
            notification.showMessage(
                title = "纪临洲 · 还剩一分钟",
                message = EntertainmentGateCopy.warning(
                    release.untilMs xor release.sessionId.hashCode().toLong()
                )
            )
        }

        val expire = Runnable {
            scheduledSessionIds.remove(release.sessionId)
            val current = repository.release(profile.packageName)
            if (
                repository.enabled() &&
                current?.sessionId == release.sessionId &&
                !current.active(System.currentTimeMillis())
            ) {
                repository.clearRelease(profile.packageName)
                repository.recordExpired(profile, current)
                if (foregroundPackage == profile.packageName) {
                    showGate(profile, "expired", System.currentTimeMillis())
                }
            }
        }
        callbacks += expire
        handler.postDelayed(expire, (release.untilMs - nowMs).coerceAtLeast(0L))
    }

    private fun showGate(
        profile: EntertainmentProfile,
        reason: String,
        nowMs: Long
    ) {
        val last = lastGateAtMs[profile.packageName] ?: 0L
        if (nowMs - last < EntertainmentGateV2Policy.GATE_COOLDOWN_MS) return
        lastGateAtMs[profile.packageName] = nowMs

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        EntertainmentGateActivity.show(
            app,
            packageName = profile.packageName,
            reason = reason,
            isTablet = isTablet
        )
    }
}
