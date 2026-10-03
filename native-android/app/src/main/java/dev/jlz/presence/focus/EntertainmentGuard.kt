package dev.jlz.presence.focus

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.util.LinkedHashSet

data class EntertainmentDecision(
    val profile: EntertainmentProfile,
    val stage: EntertainmentStage,
    val title: String,
    val message: String,
    val lockMinutes: Int = 0
) {
    val shouldLock: Boolean get() = stage == EntertainmentStage.LOCK && lockMinutes > 0
}

class EntertainmentGuard(context: Context) {
    private data class Session(
        var startedAtMs: Long,
        var lastSeenAtMs: Long,
        val fired: MutableSet<EntertainmentStage> = linkedSetOf()
    )

    private val appContext = context.applicationContext
    private val isTablet =
        appContext.resources.configuration.smallestScreenWidthDp >= 600
    private val sessions = mutableMapOf<String, Session>()
    private val prefs = appContext.getSharedPreferences(
        "jlz_entertainment_guard_v1",
        Context.MODE_PRIVATE
    )

    fun observe(
        packageName: String?,
        eventType: Int,
        nowMs: Long = System.currentTimeMillis()
    ): EntertainmentDecision? {
        val profile = EntertainmentPolicy.profile(packageName) ?: return null
        val pkg = profile.packageName
        val previous = sessions[pkg]
        val session = if (previous == null || nowMs - previous.lastSeenAtMs > SESSION_RESET_MS) {
            Session(nowMs, nowMs).also { sessions[pkg] = it }
        } else {
            previous.apply { lastSeenAtMs = nowMs }
        }

        val thresholds = EntertainmentPolicy.thresholds(isTablet, profile.tier)
        val elapsed = (nowMs - session.startedAtMs).coerceAtLeast(0L)

        val stage = when {
            elapsed >= thresholds.lockMs && EntertainmentStage.LOCK !in session.fired ->
                EntertainmentStage.LOCK
            elapsed >= thresholds.firmMs && EntertainmentStage.FIRM !in session.fired ->
                EntertainmentStage.FIRM
            elapsed >= thresholds.nudgeMs && EntertainmentStage.NUDGE !in session.fired ->
                EntertainmentStage.NUDGE
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                EntertainmentStage.ENTER !in session.fired ->
                EntertainmentStage.ENTER
            else -> null
        } ?: return null

        session.fired += stage
        val recent = recentMessages()
        val message = EntertainmentMessageBank.pick(
            appName = profile.appName,
            isTablet = isTablet,
            stage = stage,
            seed = nowMs xor pkg.hashCode().toLong(),
            recent = recent
        )
        remember(message)

        return EntertainmentDecision(
            profile = profile,
            stage = stage,
            title = EntertainmentMessageBank.title(stage),
            message = message,
            lockMinutes = if (stage == EntertainmentStage.LOCK) thresholds.lockMinutes else 0
        )
    }

    private fun recentMessages(): Set<String> {
        val raw = prefs.getString(KEY_RECENT, "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    private fun remember(message: String) {
        val current = LinkedHashSet<String>()
        prefs.getString(KEY_RECENT, "").orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .forEach(current::add)
        current.remove(message)
        current.add(message)
        val trimmed = current.toList().takeLast(RECENT_LIMIT)
        prefs.edit().putString(KEY_RECENT, trimmed.joinToString(SEPARATOR)).apply()
    }

    companion object {
        private const val SESSION_RESET_MS = 90_000L
        private const val RECENT_LIMIT = 160
        private const val KEY_RECENT = "recent_messages"
        private const val SEPARATOR = "\u001F"
    }
}
