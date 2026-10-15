package dev.jlz.presence.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/** One arithmetic pass per app entry, then the existing entertainment gate. */
class ArithmeticGateRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_arithmetic_gate_v1", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun revoke(pkg: String) { prefs.edit().remove("stage:$pkg").remove("until:$pkg")
        .remove("left:$pkg").remove("entered:$pkg").apply() }
    fun passed(pkg: String, entertainment: Boolean, atMs: Long) {
        prefs.edit().putString("stage:$pkg", if (entertainment) "waiting_entertainment" else "active")
            .putLong("until:$pkg", atMs + 4 * 60 * 60_000L)
            .putLong("left:$pkg", 0L).putBoolean("entered:$pkg", false).apply()
    }
    fun entertainmentGranted(pkg: String) {
        if (prefs.getString("stage:$pkg", "") == "waiting_entertainment")
            prefs.edit().putString("stage:$pkg", "active").putLong("left:$pkg", 0L)
                .putBoolean("entered:$pkg", false).apply()
    }
    fun left(pkg: String, atMs: Long) {
        if (prefs.getBoolean("entered:$pkg", false) &&
            prefs.getString("stage:$pkg", "") == "active" &&
            prefs.getLong("left:$pkg", 0L) == 0L)
            prefs.edit().putLong("left:$pkg", atMs).apply()
    }
    fun allowed(pkg: String, atMs: Long): Boolean {
        val stage = prefs.getString("stage:$pkg", "")
        if (stage.isNullOrBlank() || atMs >= prefs.getLong("until:$pkg", 0L)) return false
        if (stage == "active") {
            val left = prefs.getLong("left:$pkg", 0L)
            if (left > 0L && atMs - left > 5_000L) return false
        }
        if (!prefs.getBoolean("entered:$pkg", false) || prefs.getLong("left:$pkg", 0L) != 0L)
            prefs.edit().putBoolean("entered:$pkg", true).putLong("left:$pkg", 0L).apply()
        return true
    }
}

class ArithmeticGateCoordinator(private val service: AccessibilityService) {
    private val app = service.applicationContext
    private val prefs = ArithmeticGateRepository(app)
    private val classifier = LocalAppClassifier(app)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>()
    private var lastPackage: String? = null

    fun intercept(pkg: String?, eventType: Int, nowMs: Long): Boolean {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        if (lastPackage != pkg) lastPackage?.let { prefs.left(it, nowMs) }
        lastPackage = pkg
        if (pkg.isNullOrBlank() || !prefs.enabled()) return false
        if (classifier.classify(pkg) == LocalAppCategory.SYSTEM_SAFE ||
            pkg in CRITICAL_BYPASS ||
            app.packageManager.getLaunchIntentForPackage(pkg) == null) return false
        if (prefs.allowed(pkg, nowMs)) return false
        if (!pending.add(pkg)) return true
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        handler.postDelayed({
            pending.remove(pkg)
            if (prefs.enabled()) runCatching {
                val profile = EntertainmentPolicy.profile(pkg)
                ArithmeticGateActivity.show(app, pkg,
                    profile?.tier != EntertainmentTier.SHOPPING &&
                        (profile != null || classifier.classify(pkg) in
                            setOf(LocalAppCategory.GAME, LocalAppCategory.FEED)))
            }
        }, 350L)
        return true
    }

    fun close() { handler.removeCallbacksAndMessages(null); pending.clear() }

    private companion object {
        val CRITICAL_BYPASS = setOf(
            "com.google.android.apps.maps", "com.autonavi.minimap", "com.baidu.BaiduMap",
            "com.android.camera", "com.huawei.camera", "com.huawei.systemmanager"
        )
    }
}
