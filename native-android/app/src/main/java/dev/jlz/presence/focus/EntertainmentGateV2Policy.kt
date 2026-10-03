package dev.jlz.presence.focus

enum class EntertainmentIntentChoice {
    PURPOSE,
    BREAK,
    SMALL_STEP,
    DIRECT
}

data class EntertainmentReleasePlan(
    val minutes: Int,
    val label: String
)

object EntertainmentGateV2Policy {
    const val GATE_COOLDOWN_MS = 4_000L
    const val WARNING_BEFORE_MS = 60_000L
    const val SMALL_STEP_REQUIRED_MS = 3 * 60_000L

    fun releasePlan(
        isTablet: Boolean,
        tier: EntertainmentTier,
        choice: EntertainmentIntentChoice
    ): EntertainmentReleasePlan = when (choice) {
        EntertainmentIntentChoice.PURPOSE -> when {
            isTablet && tier == EntertainmentTier.SHOPPING -> EntertainmentReleasePlan(12, "办完事就出来")
            isTablet -> EntertainmentReleasePlan(8, "有目的地看")
            tier == EntertainmentTier.SHOPPING -> EntertainmentReleasePlan(10, "买完就走")
            else -> EntertainmentReleasePlan(5, "只看要找的")
        }
        EntertainmentIntentChoice.BREAK -> when {
            isTablet && tier == EntertainmentTier.SHOPPING -> EntertainmentReleasePlan(10, "休息一小段")
            isTablet -> EntertainmentReleasePlan(8, "休息一小段")
            tier == EntertainmentTier.SHOPPING -> EntertainmentReleasePlan(6, "逛一小段")
            else -> EntertainmentReleasePlan(5, "休息一小段")
        }
        EntertainmentIntentChoice.DIRECT -> EntertainmentReleasePlan(
            if (isTablet) 5 else 3,
            "我现在就是想进去"
        )
        EntertainmentIntentChoice.SMALL_STEP -> EntertainmentReleasePlan(
            if (isTablet) 8 else 5,
            "先做一小步，再放行"
        )
    }

    fun releaseActive(untilMs: Long, nowMs: Long): Boolean = untilMs > nowMs

    fun shouldWarn(untilMs: Long, nowMs: Long): Boolean {
        val remaining = untilMs - nowMs
        return remaining in 1..WARNING_BEFORE_MS
    }

    fun smallStepComplete(
        baselineEffectiveMs: Long,
        currentEffectiveMs: Long,
        requiredMs: Long = SMALL_STEP_REQUIRED_MS
    ): Boolean = currentEffectiveMs - baselineEffectiveMs >= requiredMs

    fun remainingSmallStepMs(
        baselineEffectiveMs: Long,
        currentEffectiveMs: Long,
        requiredMs: Long = SMALL_STEP_REQUIRED_MS
    ): Long = (requiredMs - (currentEffectiveMs - baselineEffectiveMs)).coerceAtLeast(0L)
}
