package dev.jlz.presence.overlay

enum class QAvatarState(val poolName: String) {
    IDLE("idle"), THINKING("thinking"), WORK("work"), ANNOYED("annoyed"),
    HAPPY("happy"), CLINGY("clingy"), SLEEPY("sleepy"), CALL("call"),
    STUDY("study"), POKE("poke"), RANDOM("random"),
    BREAK("break"), SLEEP("sleep"), WAKE("wake"), NIGHT("night"),
    WATCHING("watching"), OFFLINE("offline"), HIDDEN("hidden"), CELEBRATE("celebrate")
}

class QAvatarStateMachine {
    private var currentState: QAvatarState = QAvatarState.IDLE
    private val recentPicks = mutableListOf<String>()
    private val cooldownMs = 8000L
    private var lastPickAt = 0L
    private var lastPick: String? = null

    /** Priorities are driven by observed local state, never by clothing/skin. */
    fun resolve(protected: Boolean, sleep: Boolean, watching: Boolean, focus: Boolean,
                gate: Boolean, nightEntertainment: Boolean, rest: Boolean, offline: Boolean): QAvatarState {
        currentState = when {
            protected -> QAvatarState.HIDDEN
            sleep -> QAvatarState.SLEEP
            watching -> QAvatarState.WATCHING
            focus -> QAvatarState.STUDY
            gate -> QAvatarState.ANNOYED
            nightEntertainment -> QAvatarState.NIGHT
            rest -> QAvatarState.BREAK
            offline -> QAvatarState.OFFLINE
            else -> QAvatarState.IDLE
        }
        return currentState
    }

    fun transitionTo(state: QAvatarState) { currentState = state }
    fun current(): QAvatarState = currentState

    fun pickAsset(pool: List<String>): String? {
        if (pool.isEmpty()) return null
        val now = System.currentTimeMillis()
        if (now - lastPickAt < cooldownMs && lastPick in pool) return lastPick
        val fresh = pool.filter { it !in recentPicks.takeLast(5) }
        val source = fresh.ifEmpty { pool }
        val pick = source.random()
        recentPicks.add(pick)
        while (recentPicks.size > 5) recentPicks.removeAt(0)
        lastPickAt = now; lastPick = pick
        return pick
    }
}
