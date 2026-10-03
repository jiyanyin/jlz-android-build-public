package dev.jlz.presence.unlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.jlz.presence.WebShellActivity
import dev.jlz.presence.data.LocalLifeStore
import org.json.JSONObject

data class UnlockSoftGateDecisionInput(
    val enabled: Boolean,
    val nowMs: Long,
    val lastPresentedAtMs: Long,
    val foregroundPackage: String?,
    val ownPackage: String,
    val interactive: Boolean,
    val keyguardLocked: Boolean
)

object UnlockSoftGatePolicy {
    const val COOLDOWN_MS = 8_000L
    const val PRESENT_DELAY_MS = 520L

    fun shouldPresent(input: UnlockSoftGateDecisionInput): Boolean {
        if (!input.enabled) return false
        if (!input.interactive || input.keyguardLocked) return false
        if (input.nowMs - input.lastPresentedAtMs < COOLDOWN_MS) return false

        val foreground = input.foregroundPackage.orEmpty()
        if (foreground == input.ownPackage) return false
        if (isSafetySensitivePackage(foreground)) return false
        return true
    }

    fun isSafetySensitivePackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg.isBlank()) return false
        return pkg.contains("incallui") ||
            pkg.contains("emergency") ||
            pkg in setOf(
                "com.android.dialer",
                "com.google.android.dialer",
                "com.miui.camera",
                "com.android.camera",
                "com.android.camera2",
                "com.google.android.googlecamera",
                "com.android.deskclock",
                "com.google.android.deskclock",
                "com.miui.clock",
                "com.sec.android.app.clockpackage"
            )
    }
}

class UnlockSoftGatePreferences(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun lastPresentedAtMs(): Long = prefs.getLong(KEY_LAST_PRESENTED, 0L)

    fun markPresented(nowMs: Long) {
        prefs.edit().putLong(KEY_LAST_PRESENTED, nowMs).apply()
    }

    fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(":").any {
            it.startsWith(app.packageName + "/") || it == app.packageName
        }
    }

    companion object {
        private const val PREFS = "jlz_unlock_soft_gate"
        private const val KEY_ENABLED = "enabled_v1"
        private const val KEY_LAST_PRESENTED = "last_presented_at_ms"
    }
}

/**
 * One-shot unlock interception.
 *
 * This deliberately does not observe foreground-app changes to keep dragging
 * World Between back to the front. It reacts only to ACTION_USER_PRESENT, once
 * per unlock, then gets out of the user's way.
 */
class UnlockSoftGateCoordinator(
    private val service: AccessibilityService
) {
    private val app = service.applicationContext
    private val preferences = UnlockSoftGatePreferences(app)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var foregroundPackage: String? = null
    private var registered = false
    private var pending: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> cancelPending()
                Intent.ACTION_USER_PRESENT -> schedulePresentation()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            service.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun observeForeground(packageName: String?) {
        if (!packageName.isNullOrBlank()) foregroundPackage = packageName
    }

    fun close() {
        cancelPending()
        if (registered) {
            runCatching { service.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun schedulePresentation() {
        cancelPending()
        val task = Runnable { presentIfAllowed() }
        pending = task
        handler.postDelayed(task, UnlockSoftGatePolicy.PRESENT_DELAY_MS)
    }

    private fun cancelPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun presentIfAllowed() {
        pending = null
        val now = System.currentTimeMillis()
        val power = service.getSystemService(PowerManager::class.java)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val input = UnlockSoftGateDecisionInput(
            enabled = preferences.enabled(),
            nowMs = now,
            lastPresentedAtMs = preferences.lastPresentedAtMs(),
            foregroundPackage = foregroundPackage,
            ownPackage = app.packageName,
            interactive = power?.isInteractive == true,
            keyguardLocked = keyguard?.isKeyguardLocked == true
        )
        if (!UnlockSoftGatePolicy.shouldPresent(input)) return

        val uri = Uri.parse(
            "https://between-worlds-prod.onrender.com/?shell=android&entry=unlock"
        )
        val launched = runCatching {
            service.startActivity(
                Intent(service, WebShellActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
            true
        }.getOrDefault(false)

        if (!launched) return
        preferences.markPresented(now)
        runCatching {
            LocalLifeStore(app).recordTimeline(
                "unlock_soft_gate",
                "解锁后先来世界之间",
                foregroundPackage.orEmpty(),
                metadataJson = JSONObject()
                    .put("actor", "assistant")
                    .put("source", "unlock_soft_gate")
                    .put("previous_package", foregroundPackage.orEmpty())
                    .put("soft", true)
                    .toString()
            )
        }
    }
}
