package dev.jlz.presence.focus

/**
 * One policy for both official MCP commands and the existing local DailyMode UI.
 * Reject invalid mode/duration instead of silently choosing a new activity.
 * This class is side-effect-free so CI can validate the contract without a phone.
 */
object DailyModeCommandPolicy {
    fun parse(mode: String, minutes: Int): Pair<DailyMode, Int>? {
        val kind = DailyMode.entries.firstOrNull { it.name == mode.trim().uppercase() } ?: return null
        return when (kind) {
            DailyMode.FOCUS -> if (minutes in setOf(25, 45, 60)) kind to minutes else null
            DailyMode.BREAK -> if (minutes in setOf(5, 10, 15)) kind to minutes else null
            DailyMode.NORMAL, DailyMode.SLEEP -> kind to 0
        }
    }

    fun defaultMinutes(mode: DailyMode): Int =
        when (mode) {
            DailyMode.FOCUS -> 25
            DailyMode.BREAK -> 5
            else -> 0
        }

    fun shouldKeepExisting(mode: DailyMode, remainingMs: Long, activeMode: DailyMode, restart: Boolean): Boolean =
        !restart && mode == activeMode && (mode in setOf(DailyMode.NORMAL, DailyMode.SLEEP) || remainingMs > 0L)
}
