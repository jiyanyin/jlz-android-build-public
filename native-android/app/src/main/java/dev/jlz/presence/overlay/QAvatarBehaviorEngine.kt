package dev.jlz.presence.overlay

import android.content.Context
import java.time.LocalDateTime

enum class AvatarBehaviorSignal {
    ENTERTAINMENT_ENTER, SCROLL_BURST, LONG_DWELL, LONG_SESSION,
    LONG_SESSION_REPEAT, RETURNED_TO_STUDY, STUDY_MILESTONE, POKE, MULTI_POKE
}

data class AvatarStatusContext(
    val lowEnergy: Boolean = false,
    val needsHug: Boolean = false,
    val menstrual: Boolean = false,
    val bodyUnwell: Boolean = false,
    val numb: Boolean = false
)

data class AvatarBehaviorDecision(
    val state: QAvatarState,
    val tags: Set<String>,
    val eventKey: String,
    val proactive: Boolean = true,
    val choices: List<String> = emptyList(),
    val moveEdge: Boolean = false
)

/**
 * Minimal privacy-safe PUBLIC BUILDER placeholder.
 * The private app retains its full persona-specific avatar behaviour engine.
 * Use this only to compile the parchment UI preview without publishing that engine.
 */
class QAvatarBehaviorEngine(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("jlz_avatar_public_build", Context.MODE_PRIVATE)
    private var nextAtMs = 0L

    fun observeUserActive(nowMs: Long = System.currentTimeMillis()) {
        preferences.edit().putLong("last_active_ms", nowMs).apply()
    }

    fun react(signal: AvatarBehaviorSignal): AvatarBehaviorDecision = when (signal) {
        AvatarBehaviorSignal.ENTERTAINMENT_ENTER -> AvatarBehaviorDecision(
            QAvatarState.IDLE, setOf("app_entertainment"), "entertainment_enter")
        AvatarBehaviorSignal.SCROLL_BURST -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("scroll_burst"), "scroll_burst")
        AvatarBehaviorSignal.LONG_DWELL -> AvatarBehaviorDecision(
            QAvatarState.IDLE, setOf("long_dwell"), "long_dwell")
        AvatarBehaviorSignal.LONG_SESSION -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("caught_slacking"), "long_session")
        AvatarBehaviorSignal.LONG_SESSION_REPEAT -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("caught_slacking"), "long_session_repeat")
        AvatarBehaviorSignal.RETURNED_TO_STUDY -> AvatarBehaviorDecision(
            QAvatarState.STUDY, setOf("study_mode"), "returned_to_study")
        AvatarBehaviorSignal.STUDY_MILESTONE -> AvatarBehaviorDecision(
            QAvatarState.STUDY, setOf("study_milestone"), "study_milestone")
        AvatarBehaviorSignal.POKE -> AvatarBehaviorDecision(
            QAvatarState.TEASE, setOf("being_poked"), "poke", proactive = false)
        AvatarBehaviorSignal.MULTI_POKE -> AvatarBehaviorDecision(
            QAvatarState.WOKE_UP, setOf("being_poked"), "poke_many", proactive = false)
    }

    fun autonomous(
        status: AvatarStatusContext,
        studyActive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): AvatarBehaviorDecision? {
        if (nowMs < nextAtMs) return null
        nextAtMs = nowMs + 10 * 60_000L
        val night = LocalDateTime.now().hour in 0..5
        return when {
            studyActive -> AvatarBehaviorDecision(QAvatarState.STUDY,
                setOf("study_mode"), "autonomous_study")
            status.lowEnergy || status.bodyUnwell || status.menstrual || status.numb ->
                AvatarBehaviorDecision(QAvatarState.GENTLE,
                    setOf("gentle"), "autonomous_rest")
            status.needsHug -> AvatarBehaviorDecision(QAvatarState.CLINGY,
                setOf("gentle"), "autonomous_closeness")
            night -> AvatarBehaviorDecision(QAvatarState.SLEEPING,
                setOf("sleepy"), "autonomous_sleep")
            else -> AvatarBehaviorDecision(QAvatarState.IDLE,
                setOf("time_day"), "autonomous_idle")
        }
    }
}
