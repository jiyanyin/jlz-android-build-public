package dev.jlz.presence.overlay

import android.content.Context
import java.time.LocalDateTime
import kotlin.random.Random

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

/** Small persisted preference model; it adapts weights without pretending to be AI learning. */
class QAvatarBehaviorEngine(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_avatar_life_v1", Context.MODE_PRIVATE)
    private var nextAutonomousAtMs = 0L

    fun observeUserActive(nowMs: Long = System.currentTimeMillis()) {
        val hour = LocalDateTime.now().hour
        prefs.edit().putInt("last_active_hour", hour).putLong("last_active_at", nowMs).apply()
    }

    fun react(signal: AvatarBehaviorSignal): AvatarBehaviorDecision = when (signal) {
        AvatarBehaviorSignal.ENTERTAINMENT_ENTER -> AvatarBehaviorDecision(
            QAvatarState.TEASE, setOf("app_entertainment", "app_xiaohongshu"), "entertainment_enter")
        AvatarBehaviorSignal.SCROLL_BURST -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("scroll_burst", "app_entertainment", "app_xiaohongshu"),
            "scroll_burst", choices = listOf("你猜我在看什么", "再给我两分钟", "带我去学习"))
        AvatarBehaviorSignal.LONG_DWELL -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("long_dwell", "app_entertainment"), "long_dwell")
        AvatarBehaviorSignal.LONG_SESSION -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("caught_slacking", "app_entertainment"), "long_session",
            choices = listOf("再给我两分钟", "带我去学习", "抱抱我，不想解释"))
        AvatarBehaviorSignal.LONG_SESSION_REPEAT -> AvatarBehaviorDecision(
            QAvatarState.CATCH_MONITOR, setOf("caught_slacking", "app_entertainment", "teasing"),
            "long_session_repeat", choices = listOf("现在就回去", "继续盯着我看", "先别说我"))
        AvatarBehaviorSignal.RETURNED_TO_STUDY -> AvatarBehaviorDecision(
            QAvatarState.STUDY, setOf("study_mode", "returned_to_study"), "returned_to_study")
        AvatarBehaviorSignal.STUDY_MILESTONE -> AvatarBehaviorDecision(
            QAvatarState.STUDY, setOf("study_mode", "study_milestone", "gentle"), "study_milestone")
        AvatarBehaviorSignal.POKE -> AvatarBehaviorDecision(
            QAvatarState.TEASE, setOf("being_poked", "teasing"), "poke", proactive = false)
        AvatarBehaviorSignal.MULTI_POKE -> AvatarBehaviorDecision(
            QAvatarState.WOKE_UP, setOf("being_poked", "woke_up", "teasing"), "poke_many", proactive = false)
    }

    fun autonomous(
        status: AvatarStatusContext,
        studyActive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): AvatarBehaviorDecision? {
        if (nowMs < nextAutonomousAtMs) return null
        val hour = LocalDateTime.now().hour
        val adaptedLate = prefs.getInt("last_active_hour", 23) in 0..4
        nextAutonomousAtMs = nowMs + Random.nextLong(4 * 60_000L, 9 * 60_000L)
        return when {
            status.needsHug -> AvatarBehaviorDecision(QAvatarState.CLINGY,
                setOf("status_need_hug", "clingy"), "status_hug")
            status.lowEnergy -> AvatarBehaviorDecision(QAvatarState.GENTLE,
                setOf("status_low_energy", "gentle"), "status_energy")
            status.menstrual -> AvatarBehaviorDecision(QAvatarState.GENTLE,
                setOf("period_menstrual", "gentle"), "status_period")
            status.bodyUnwell -> AvatarBehaviorDecision(QAvatarState.GENTLE,
                setOf("body_unwell", "gentle"), "status_unwell")
            status.numb -> AvatarBehaviorDecision(QAvatarState.GENTLE,
                setOf("status_numb", "gentle"), "status_numb")
            studyActive -> AvatarBehaviorDecision(QAvatarState.STUDY,
                setOf("study_mode", "autonomous"), "autonomous_study")
            hour in 2..5 && !adaptedLate -> AvatarBehaviorDecision(QAvatarState.SLEEPING,
                setOf("sleepy", "autonomous"), "autonomous_sleep")
            hour >= 23 || hour <= 5 -> AvatarBehaviorDecision(QAvatarState.NIGHT_COMPANION,
                setOf("time_night", "night_companion", "sleepy"), "autonomous_night")
            Random.nextInt(100) < 35 -> AvatarBehaviorDecision(QAvatarState.CLINGY,
                setOf("clingy", "autonomous"), "autonomous_clingy", moveEdge = true)
            else -> AvatarBehaviorDecision(QAvatarState.IDLE,
                setOf("time_day", "gentle", "autonomous"), "autonomous_idle")
        }
    }
}
