package dev.jlz.presence.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/** Persisted per-app arithmetic windows, independent of entertainment releases. */
class ArithmeticGateRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_arithmetic_gate_v1", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun revoke(pkg: String) { prefs.edit().remove("kind:$pkg").remove("passed:$pkg")
        .remove("quiz_until:$pkg").remove("resume:$pkg").apply() }
    fun passed(pkg: String, kind: ArithmeticGateKind, atMs: Long) {
        prefs.edit().putString("kind:$pkg", kind.name).putLong("passed:$pkg", atMs)
            .putLong("quiz_until:$pkg", atMs + kind.exemptionMs)
            .remove("resume:$pkg").apply()
    }
    fun entertainmentGranted(pkg: String, atMs: Long) {
        // One immediate return from the time gate continues the existing visit.
        // It neither renews the arithmetic window nor grants another app access.
        if (prefs.contains("passed:$pkg")) prefs.edit().putLong("resume:$pkg", atMs).apply()
    }
    fun allowed(pkg: String, kind: ArithmeticGateKind, atMs: Long): Boolean {
        // Old four-hour/leave-five-seconds grants are deliberately not reused.
        if (prefs.getString("kind:$pkg", "") != kind.name) return false
        val passedAt = prefs.getLong("passed:$pkg", Long.MAX_VALUE)
        if (atMs < passedAt) return false
        val resumeAt = prefs.getLong("resume:$pkg", Long.MIN_VALUE)
        prefs.edit().remove("resume:$pkg").apply()
        if (resumeAt != Long.MIN_VALUE && atMs >= resumeAt && atMs - resumeAt < 30_000L) return true
        return atMs < prefs.getLong("quiz_until:$pkg", 0L)
    }
}

class ArithmeticGateCoordinator(private val service: AccessibilityService) {
    private val app = service.applicationContext
    private val prefs = ArithmeticGateRepository(app)
    private val classifier = LocalAppClassifier(app)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>()
    private val visits = ArithmeticVisitTracker()

    fun observeWindow(pkg: String?): Boolean =
        visits.enter(pkg, pkg != null && classifier.isTransientWindow(pkg))

    fun intercept(pkg: String?, eventType: Int, nowMs: Long, entry: Boolean): Boolean {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        if (pkg.isNullOrBlank() || !prefs.enabled()) return false
        if (classifier.isTransientWindow(pkg)) return false
        if (classifier.arithmeticExempt(pkg) ||
            app.packageManager.getLaunchIntentForPackage(pkg) == null) return false
        if (pkg in pending) return true
        // Content/dialog window events inside a visit must not become a new quiz.
        if (!entry) return false
        val kind = ArithmeticGatePolicy.kind(EntertainmentPolicy.profile(pkg), classifier.classify(pkg))
        if (prefs.allowed(pkg, kind, nowMs)) return false
        if (!pending.add(pkg)) return true
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        handler.postDelayed({
            pending.remove(pkg)
            if (prefs.enabled()) runCatching {
                ArithmeticGateActivity.show(app, pkg)
            }
        }, 350L)
        return true
    }

    fun close() { handler.removeCallbacksAndMessages(null); pending.clear() }

}
