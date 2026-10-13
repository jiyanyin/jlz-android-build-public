package dev.jlz.presence.capture

import android.content.Context
import android.os.PowerManager
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Structured app observation first. Only explicit allowlisted long-stay rules may capture. */
class AutomaticCaptureCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "jlz_presence_auto_capture_v2", Context.MODE_PRIVATE
    )
    private val traffic = CaptureTrafficPolicy(appContext)
    private val outbox = PendingScreenshotQueue(appContext)
    private val settings = RuntimeSettingsRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentPackage: String? = null
    @Volatile private var generation: Long = 0L
    private var shortStayJob: Job? = null
    private var longStayJob: Job? = null
    private var scrollJob: Job? = null
    private var lastScrollAtMs: Long = 0L
    private val captureMutex = Mutex()

    fun enabled(): Boolean = preferences.getBoolean("enabled", true)

    fun setEnabled(value: Boolean): Boolean =
        preferences.edit().putBoolean("enabled", value).commit()

    /**
     * Call this only from real window/package observations.
     * IME packages are ignored because opening the keyboard is not an app switch.
     * System surfaces cancel the prior app timer but do not create screenshots.
     */
    @Synchronized
    fun onForegroundPackage(packageName: String?, observedAtMs: Long = System.currentTimeMillis()) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank()) return
        if (pkg in IME_PACKAGES) return
        if (pkg == currentPackage) return

        currentPackage = pkg
        generation += 1L
        val myGeneration = generation
        shortStayJob?.cancel()
        shortStayJob = null
        longStayJob?.cancel()
        longStayJob = null
        scrollJob?.cancel()
        scrollJob = null
        lastScrollAtMs = 0L

        if (pkg in NO_AUTOMATIC_CAPTURE_PACKAGES || pkg in SYSTEM_SURFACE_PACKAGES) {
            return
        }

        shortStayJob = scope.launch {
            delay((observedAtMs + SHORT_STAY_DELAY_MS - System.currentTimeMillis()).coerceAtLeast(0L))
            if (stillOn(pkg, myGeneration) && enabled() && traffic.canCapture(pkg)) {
                captureAndUpload(pkg, myGeneration, "automatic_rule_app_switch_6s")
            }
        }

        longStayJob = scope.launch {
            // Observe a long session, then sample sparingly until the app
            // changes. Scroll events can independently request bounded samples.
            for (delayMs in listOf(30_000L, 2 * 60_000L, 5 * 60_000L)) {
                delay((observedAtMs + delayMs - System.currentTimeMillis()).coerceAtLeast(0L))
                if (!stillOn(pkg, myGeneration)) return@launch
                if (enabled() && traffic.canCapture(pkg)) {
                    captureAndUpload(pkg, myGeneration, "automatic_rule_stay_${delayMs / 1000}s")
                }
                if (delayMs == 5 * 60_000L && pkg in ATTENTION_PACKAGES) {
                    FloatingPresenceService.showAttentionNudge(
                        appContext, "在${appLabel(pkg)}停留了一阵。眼睛休息两分钟。"
                    )
                }
            }
            while (stillOn(pkg, myGeneration)) {
                delay(5 * 60_000L)
                if (!stillOn(pkg, myGeneration)) break
                if (enabled() && traffic.canCapture(pkg)) {
                    captureAndUpload(pkg, myGeneration, "automatic_rule_stay_periodic")
                }
            }
        }
    }

    /**
     * Observed real scroll activity in the selected foreground app. We sample
     * after UI settles, at most one scroll-triggered attempt per 20 seconds.
     * Never capture every accessibility event or infer what pixels contain.
     */
    @Synchronized
    fun onScroll(packageName: String?, observedAtMs: Long = System.currentTimeMillis()) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank() || pkg != currentPackage || !enabled() ||
            dev.jlz.presence.cowatch.CoWatchState.active) return
        if (pkg in NO_AUTOMATIC_CAPTURE_PACKAGES || pkg in SYSTEM_SURFACE_PACKAGES) return
        if (observedAtMs - lastScrollAtMs < SCROLL_TRIGGER_COOLDOWN_MS) return
        if (!traffic.canCapture(pkg, observedAtMs)) return
        lastScrollAtMs = observedAtMs
        val myGeneration = generation
        scrollJob?.cancel()
        scrollJob = scope.launch {
            delay(SCROLL_SETTLE_DELAY_MS)
            if (stillOn(pkg, myGeneration) && traffic.canCapture(pkg)) {
                captureAndUpload(pkg, myGeneration, "automatic_rule_scrolled")
            }
        }
    }

    @Synchronized
    fun close() {
        shortStayJob?.cancel()
        shortStayJob = null
        longStayJob?.cancel()
        longStayJob = null
        scrollJob?.cancel()
        scrollJob = null
        currentPackage = null
        generation += 1L
        scope.cancel()
    }

    private fun stillOn(packageName: String, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration || currentPackage != packageName) return false
        val power = appContext.getSystemService(PowerManager::class.java)
        return power?.isInteractive == true
    }

    private suspend fun captureAndUpload(
        packageName: String,
        expectedGeneration: Long,
        origin: String
    ): String = captureMutex.withLock {
        if (!stillOn(packageName, expectedGeneration) ||
            dev.jlz.presence.cowatch.CoWatchState.active) return@withLock "foreground_changed_or_cowatch_active"
        // Fail closed if accessibility sees an editable, keyboard, payment,
        // password, foreign application or otherwise uncertain hierarchy.
        if (!dev.jlz.presence.screen.AccessibilityActionGateway.safeForCoWatch(packageName)) {
            return@withLock "sensitive_or_unverified_foreground"
        }

        val image = when (val capture = FloatingPresenceService.captureForRuntime(automatic = true)) {
            is ScreenshotCaptureResult.Captured -> capture
            is ScreenshotCaptureResult.Unavailable ->
                return@withLock "screenshot_unavailable:" + capture.reason.take(140)
        }

        // The screen can change during the screenshot callback. Do not label a
        // later app's pixels as the earlier package.
        if (!stillOn(packageName, expectedGeneration) ||
            dev.jlz.presence.cowatch.CoWatchState.active ||
            !dev.jlz.presence.screen.AccessibilityActionGateway.safeForCoWatch(packageName)) {
            return@withLock "foreground_changed_after_capture"
        }

        if (!traffic.reserve(packageName, image.bytes)) return@withLock "automatic_budget_or_duplicate"
        val eventId = UUID.randomUUID().toString()
        outbox.enqueue(
            capture = image,
            eventId = eventId,
            sourcePackage = packageName,
            studySessionId = null,
            origin = origin
        )

        val runtime = settings.load()
        if (runtime.baseUrl.isBlank() || runtime.token.isBlank()) {
            return@withLock "saved_on_phone:event_id=$eventId"
        }

        val sent = runCatching {
            outbox.sendPending(
                api = RuntimeApiClient(runtime),
                limit = 1,
                priorityEventId = eventId
            ).firstOrNull { it.eventId == eventId }
        }.getOrNull()

        if (sent?.sent == true) {
            "uploaded:event_id=$eventId"
        } else {
            "saved_on_phone:event_id=$eventId"
        }
    }

    private fun appLabel(packageName: String): String = when (packageName) {
        "com.xingin.xhs" -> "小红书"
        "com.xunmeng.pinduoduo" -> "拼多多"
        "com.ss.android.ugc.aweme" -> "抖音"
        else -> "这个 App"
    }

    companion object {
        private const val SHORT_STAY_DELAY_MS = 6_000L
        private const val SCROLL_SETTLE_DELAY_MS = 1_500L
        private const val SCROLL_TRIGGER_COOLDOWN_MS = 20_000L

        private val NO_AUTOMATIC_CAPTURE_PACKAGES = setOf(
            "com.openai.chatgpt"
        )

        private val IME_PACKAGES = setOf(
            "com.sohu.inputmethod.sogou",
            "com.google.android.inputmethod.latin",
            "com.baidu.input",
            "com.iflytek.inputmethod"
        )

        private val SYSTEM_SURFACE_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.miui.home",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        // Easy to extend later; the rule itself stays centralized here.
        private val ATTENTION_PACKAGES = setOf(
            "com.xingin.xhs",
            "com.xunmeng.pinduoduo",
            "com.ss.android.ugc.aweme"
        )
    }
}
