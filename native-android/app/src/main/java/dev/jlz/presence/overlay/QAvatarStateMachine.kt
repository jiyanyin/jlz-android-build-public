package dev.jlz.presence.overlay

/** One explicit state vocabulary shared by behavior, artwork and phrases. */
enum class QAvatarState(val poolName: String, val priority: Int) {
    IDLE("idle", 10), GENTLE("gentle", 20), CLINGY("clingy", 25),
    TEASE("tease", 35), CATCH_MONITOR("catch_monitor", 70),
    STUDY("study", 60), SLEEPY("sleepy", 40), SLEEPING("sleeping", 50),
    WOKE_UP("woke_up", 80), NIGHT_COMPANION("night_companion", 45),
    HIDDEN_EDGE("hidden_edge", 90), SUSPENDED("suspended", 100)
}

data class QAvatarTransition(
    val from: QAvatarState,
    val to: QAvatarState,
    val reason: String,
    val changedAtMs: Long
)

/** Priority state machine: an idle tick cannot interrupt a held reaction. */
class QAvatarStateMachine {
    private var currentState: QAvatarState = QAvatarState.IDLE
    private var holdUntilMs: Long = 0L

    @Synchronized
    fun transitionTo(
        state: QAvatarState,
        reason: String,
        holdMs: Long = 0L,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): QAvatarTransition? {
        if (!force && nowMs < holdUntilMs && state.priority < currentState.priority) return null
        val prior = currentState
        currentState = state
        holdUntilMs = nowMs + holdMs.coerceAtLeast(0L)
        return QAvatarTransition(prior, state, reason, nowMs)
    }

    @Synchronized fun current(): QAvatarState = currentState
    @Synchronized fun isHeld(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < holdUntilMs
}
