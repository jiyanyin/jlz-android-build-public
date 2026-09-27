package dev.jlz.presence.overlay

object QAvatarActionPool {
    private val pools: Map<QAvatarState, List<String>> = mapOf(
        QAvatarState.IDLE to listOf("idle_01", "idle_02", "idle_03"),
        QAvatarState.THINKING to listOf("think_01", "think_02"),
        QAvatarState.WORK to listOf("work_01"),
        QAvatarState.ANNOYED to listOf("annoyed_01"),
        QAvatarState.HAPPY to listOf("happy_01", "happy_02"),
        QAvatarState.CLINGY to listOf("clingy_01"),
        QAvatarState.SLEEPY to listOf("sleepy_01"),
        QAvatarState.CALL to listOf("call_01"),
        QAvatarState.STUDY to listOf("study_01"),
        QAvatarState.POKE to listOf("poke_01"),
        QAvatarState.RANDOM to listOf("idle_01", "happy_01", "think_01")
    )
    fun poolFor(state: QAvatarState): List<String> = pools[state] ?: emptyList()
}
