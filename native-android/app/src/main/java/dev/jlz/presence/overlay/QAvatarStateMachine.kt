package dev.jlz.presence.overlay

enum class QAvatarState(val poolName: String) {
    IDLE("idle"), THINKING("thinking"), WORK("work"), ANNOYED("annoyed"),
    HAPPY("happy"), CLINGY("clingy"), SLEEPY("sleepy"), CALL("call"),
    STUDY("study"), POKE("poke"), RANDOM("random")
}

class QAvatarStateMachine {
    private var currentState: QAvatarState = QAvatarState.IDLE
    private val recentPicks = mutableListOf<String>()
    private val cooldownMs = 8000L
    private var lastPickAt = 0L

    fun transitionTo(state: QAvatarState) { currentState = state }
    fun current(): QAvatarState = currentState

    fun pickAsset(pool: List<String>): String? {
        if (pool.isEmpty()) return null
        val now = System.currentTimeMillis()
        val fresh = pool.filter { it !in recentPicks.takeLast(5) }
        val source = fresh.ifEmpty { pool }
        val pick = source.random()
        if (now - lastPickAt >= cooldownMs) { recentPicks.add(pick); lastPickAt = now }
        return pick
    }
}
