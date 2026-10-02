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
    private var longStayJob: Job? = null

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
        longStayJob?.cancel()
        longStayJob = null

        if (pkg in NO_AUTOMATIC_CAPTURE_PACKAGES || pkg in SYSTEM_SURFACE_PACKAGES) {
            return
        }

        longStayJob = scope.launch {
            val remaining = (observedAtMs + LONG_STAY_DELAY_MS - System.currentTimeMillis())
                .coerceAtLeast(0L)
            delay(remaining)
            if (!stillOn(pkg, myGeneration)) return@launch
            if (enabled() && traffic.canCapture(pkg)) {
                captureAndUpload(pkg, myGeneration, "automatic_rule_long_stay")
            }
            if (pkg in ATTENTION_PACKAGES && stillOn(pkg, myGeneration)) {
                FloatingPresenceService.showAttentionNudge(
                    appContext,
                    "已经在${appLabel(pkg)} 5 分钟啦，回来让我看一眼？"
                )
            }
        }
    }

    @Synchronized
    fun close() {
        longStayJob?.cancel()
        longStayJob = null
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
    ): String {
        if (!stillOn(packageName, expectedGeneration)) return "foreground_changed"

        val image = when (val capture = FloatingPresenceService.captureForRuntime(automatic = true)) {
            is ScreenshotCaptureResult.Captured -> capture
            is ScreenshotCaptureResult.Unavailable ->
                return "screenshot_unavailable:" + capture.reason.take(140)
        }

        // The screen can change during the screenshot callback. Do not label a
        // later app's pixels as the earlier package.
        if (!stillOn(packageName, expectedGeneration)) return "foreground_changed_after_capture"

        if (!traffic.reserve(packageName, image.bytes)) return "automatic_budget_or_duplicate"
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
            return "saved_on_phone:event_id=$eventId"
        }

        val sent = runCatching {
            outbox.sendPending(
                api = RuntimeApiClient(runtime),
                limit = 1,
                priorityEventId = eventId
            ).firstOrNull { it.eventId == eventId }
        }.getOrNull()

        return if (sent?.sent == true) {
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
        private const val LONG_STAY_DELAY_MS = 5 * 60_000L

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
