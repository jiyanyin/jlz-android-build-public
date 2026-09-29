package dev.jlz.presence.overlay

/** Canonical action names. Missing files are resolved inside the same pack. */
object QAvatarActionPool {
    private val pools: Map<QAvatarState, List<String>> = mapOf(
        QAvatarState.IDLE to listOf("idle", "idle_crouch", "idle_think", "idle_smile", "idle_wave", "idle_arms"),
        QAvatarState.GENTLE to listOf("react_reach", "react_shy", "idle_smile", "sleep_hug"),
        QAvatarState.CLINGY to listOf("react_reach", "react_shy", "sleep_hug"),
        QAvatarState.TEASE to listOf("react_tease", "react_proud", "react_feisty", "react_surprised"),
        QAvatarState.CATCH_MONITOR to listOf("react_angry", "react_feisty", "react_tease", "study_arms"),
        QAvatarState.STUDY to listOf("study_watch", "study_read", "study_note", "study_think", "study_read_sheet", "study_arms", "study_encourage"),
        QAvatarState.SLEEPY to listOf("sleep_drowsy", "sleep_rubeye", "sleep_sitting"),
        QAvatarState.SLEEPING to listOf("sleep_hug", "sleep_blanket", "sleep_curl", "sleep_nest"),
        QAvatarState.WOKE_UP to listOf("sleep_rubeye", "react_surprised", "sleep_drowsy"),
        QAvatarState.NIGHT_COMPANION to listOf("sleep_sitting", "sleep_hug", "sleep_wave", "sleep_nest"),
        QAvatarState.HIDDEN_EDGE to listOf("idle", "study_watch", "sleep_drowsy"),
        QAvatarState.SUSPENDED to listOf("sleep_drowsy", "sleep_sitting", "idle")
    )
    fun poolFor(state: QAvatarState): List<String> = pools[state].orEmpty()
}
