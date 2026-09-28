package dev.jlz.presence.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import dev.jlz.presence.overlay.AvatarBehaviorSignal
import dev.jlz.presence.overlay.FloatingPresenceService
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.screen.ScreenshotCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

/**
 * Smart Capture V1. App switching starts a session; it no longer guarantees a
 * screenshot. Interaction density, dwell, duplicate detection and two budgets
 * decide whether a representative frame is retained.
 */
class AutomaticCaptureCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "jlz_presence_smart_capture_v1", Context.MODE_PRIVATE
    )
    private val outbox = PendingScreenshotQueue(appContext)
    private val settings = RuntimeSettingsRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentPackage: String? = null
    @Volatile private var generation: Long = 0L
    private var sessionStartedAtMs = 0L
    private var lastInteractionAtMs = 0L
    private var interactionCount = 0
    private var sessionCaptureCount = 0
    private var lastCaptureAtMs = 0L
    private var lastPerceptualHash: Long? = null
    private val scrollTimes = ArrayDeque<Long>()
    private var baselineJob: Job? = null
    private var dwellJob: Job? = null
    private var longSessionJob: Job? = null
    private var repeatNudgeJob: Job? = null

    fun enabled(): Boolean = preferences.getBoolean("enabled", true)
    fun setEnabled(value: Boolean): Boolean = preferences.edit().putBoolean("enabled", value).commit()

    @Synchronized
    fun onForegroundPackage(packageName: String?, observedAtMs: Long = System.currentTimeMillis()) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank() || pkg in IME_PACKAGES || pkg == currentPackage) return
        cancelSessionJobs()
        currentPackage = pkg
        generation += 1L
        sessionStartedAtMs = observedAtMs
        lastInteractionAtMs = observedAtMs
        interactionCount = 0
        sessionCaptureCount = 0
        lastCaptureAtMs = 0L
        lastPerceptualHash = null
        scrollTimes.clear()
        if (!enabled() || !eligiblePackage(pkg)) return
        val mine = generation
        if (pkg in ATTENTION_PACKAGES) {
            FloatingPresenceService.reportBehaviorSignal(
                appContext, AvatarBehaviorSignal.ENTERTAINMENT_ENTER, pkg
            )
        }
        baselineJob = scope.launch {
            delay(INITIAL_EVALUATION_MS)
            if (stillOn(pkg, mine) && interactionCount >= 2) {
                maybeCapture(pkg, mine, "engaged_session")
            }
        }
        longSessionJob = scope.launch {
            delay(FIRST_NUDGE_MS)
            if (!stillOn(pkg, mine)) return@launch
            FloatingPresenceService.reportBehaviorSignal(
                appContext, AvatarBehaviorSignal.LONG_SESSION, pkg,
                System.currentTimeMillis() - sessionStartedAtMs
            )
            if (interactionCount >= 4) maybeCapture(pkg, mine, "long_session")
        }
        repeatNudgeJob = scope.launch {
            delay(REPEAT_NUDGE_MS)
            if (!stillOn(pkg, mine)) return@launch
            FloatingPresenceService.reportBehaviorSignal(
                appContext, AvatarBehaviorSignal.LONG_SESSION_REPEAT, pkg,
                System.currentTimeMillis() - sessionStartedAtMs
            )
        }
    }

    /** Receives only event type/timing; no private visible text is persisted here. */
    @Synchronized
    fun onAccessibilitySignal(
        packageName: String?, eventType: Int,
        observedAtMs: Long = System.currentTimeMillis()
    ) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank()) return
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundPackage(pkg, observedAtMs)
            return
        }
        if (pkg != currentPackage || !eligiblePackage(pkg)) return
        if (eventType !in INTERACTION_EVENTS) return
        interactionCount = (interactionCount + 1).coerceAtMost(10_000)
        lastInteractionAtMs = observedAtMs
        val mine = generation
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollTimes.addLast(observedAtMs)
            while (scrollTimes.isNotEmpty() && observedAtMs - scrollTimes.first() > SCROLL_WINDOW_MS) {
                scrollTimes.removeFirst()
            }
            if (scrollTimes.size == SCROLL_BURST_THRESHOLD) {
                FloatingPresenceService.reportBehaviorSignal(
                    appContext, AvatarBehaviorSignal.SCROLL_BURST, pkg,
                    observedAtMs - sessionStartedAtMs
                )
                scope.launch {
                    delay(SCROLL_SETTLE_MS)
                    if (stillOn(pkg, mine)) maybeCapture(pkg, mine, "scroll_burst")
                }
            }
        }
        dwellJob?.cancel()
        dwellJob = scope.launch {
            delay(DWELL_MS)
            if (!stillOn(pkg, mine)) return@launch
            if (System.currentTimeMillis() - lastInteractionAtMs < DWELL_MS - 1_000L) return@launch
            FloatingPresenceService.reportBehaviorSignal(
                appContext, AvatarBehaviorSignal.LONG_DWELL, pkg,
                System.currentTimeMillis() - sessionStartedAtMs
            )
            maybeCapture(pkg, mine, "long_dwell")
        }
    }

    @Synchronized
    fun close() {
        cancelSessionJobs()
        currentPackage = null
        generation += 1L
        scope.cancel()
    }

    private fun cancelSessionJobs() {
        baselineJob?.cancel(); dwellJob?.cancel(); longSessionJob?.cancel(); repeatNudgeJob?.cancel()
        baselineJob = null; dwellJob = null; longSessionJob = null; repeatNudgeJob = null
    }

    private fun eligiblePackage(pkg: String): Boolean =
        enabled() && pkg !in NO_AUTOMATIC_CAPTURE_PACKAGES && pkg !in SYSTEM_SURFACE_PACKAGES

    private fun stillOn(packageName: String, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration || currentPackage != packageName) return false
        return appContext.getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    private suspend fun maybeCapture(packageName: String, expectedGeneration: Long, reason: String): String {
        val now = System.currentTimeMillis()
        if (!stillOn(packageName, expectedGeneration)) return "foreground_changed"
        if (sessionCaptureCount >= MAX_PER_SESSION) return "session_budget"
        if (now - lastCaptureAtMs < MIN_CAPTURE_GAP_MS) return "capture_cooldown"
        if (!claimHourlyBudget(now)) return "hourly_budget"

        val image = when (val capture = FloatingPresenceService.captureForRuntime()) {
            is ScreenshotCaptureResult.Captured -> capture
            is ScreenshotCaptureResult.Unavailable -> {
                releaseHourlyBudget(now)
                return "screenshot_unavailable:" + capture.reason.take(120)
            }
        }
        if (!stillOn(packageName, expectedGeneration)) {
            releaseHourlyBudget(now)
            return "foreground_changed_after_capture"
        }
        val hash = perceptualHash(image.bytes)
        val prior = lastPerceptualHash
        if (hash != null && prior != null && java.lang.Long.bitCount(hash xor prior) <= HASH_DISTANCE) {
            releaseHourlyBudget(now)
            return "duplicate_frame"
        }

        val eventId = UUID.randomUUID().toString()
        outbox.enqueue(
            capture = image, eventId = eventId, sourcePackage = packageName,
            studySessionId = null, origin = "smart_capture_$reason"
        )
        sessionCaptureCount += 1
        lastCaptureAtMs = now
        if (hash != null) lastPerceptualHash = hash

        val runtime = settings.load()
        if (runtime.baseUrl.isBlank() || runtime.token.isBlank()) return "saved_on_phone:event_id=$eventId"
        val sent = runCatching {
            outbox.sendPending(RuntimeApiClient(runtime), limit = 1, priorityEventId = eventId)
                .firstOrNull { it.eventId == eventId }
        }.getOrNull()
        return if (sent?.sent == true) "uploaded:event_id=$eventId" else "saved_on_phone:event_id=$eventId"
    }

    private fun perceptualHash(bytes: ByteArray): Long? = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        if (small !== bitmap) bitmap.recycle()
        val values = IntArray(64)
        var total = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val color = small.getPixel(x, y)
            val gray = (android.graphics.Color.red(color) * 30 +
                android.graphics.Color.green(color) * 59 + android.graphics.Color.blue(color) * 11) / 100
            values[y * 8 + x] = gray
            total += gray
        }
        small.recycle()
        val average = total / 64
        var result = 0L
        values.forEachIndexed { index, value -> if (value >= average) result = result or (1L shl index) }
        result
    }.getOrNull()

    @Synchronized
    private fun claimHourlyBudget(nowMs: Long): Boolean {
        val bucket = nowMs / HOUR_MS
        val storedBucket = preferences.getLong("budget_hour", -1L)
        val count = if (storedBucket == bucket) preferences.getInt("budget_count", 0) else 0
        if (count >= MAX_PER_HOUR) return false
        return preferences.edit().putLong("budget_hour", bucket).putInt("budget_count", count + 1).commit()
    }

    @Synchronized
    private fun releaseHourlyBudget(nowMs: Long) {
        val bucket = nowMs / HOUR_MS
        if (preferences.getLong("budget_hour", -1L) != bucket) return
        val count = preferences.getInt("budget_count", 0)
        preferences.edit().putInt("budget_count", (count - 1).coerceAtLeast(0)).apply()
    }

    companion object {
        private const val INITIAL_EVALUATION_MS = 45_000L
        private const val DWELL_MS = 75_000L
        private const val FIRST_NUDGE_MS = 3 * 60_000L
        private const val REPEAT_NUDGE_MS = 4 * 60_000L + 30_000L
        private const val SCROLL_WINDOW_MS = 30_000L
        private const val SCROLL_SETTLE_MS = 8_000L
        private const val SCROLL_BURST_THRESHOLD = 6
        private const val MIN_CAPTURE_GAP_MS = 45_000L
        private const val MAX_PER_SESSION = 3
        private const val MAX_PER_HOUR = 6
        private const val HASH_DISTANCE = 6
        private const val HOUR_MS = 60 * 60_000L

        private val INTERACTION_EVENTS = setOf(
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        private val NO_AUTOMATIC_CAPTURE_PACKAGES = setOf(
            "com.openai.chatgpt", "dev.jlz.presence",
            "com.android.settings", "com.android.vending",
            "com.google.android.apps.authenticator2",
            "com.eg.android.AlipayGphone", "com.tencent.mm"
        )
        private val IME_PACKAGES = setOf(
            "com.sohu.inputmethod.sogou", "com.google.android.inputmethod.latin",
            "com.baidu.input", "com.iflytek.inputmethod"
        )
        private val SYSTEM_SURFACE_PACKAGES = setOf(
            "android", "com.android.systemui", "com.miui.home", "com.android.intentresolver",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller"
        )
        private val ATTENTION_PACKAGES = setOf(
            "com.xingin.xhs", "com.xunmeng.pinduoduo", "com.ss.android.ugc.aweme"
        )
    }
}
