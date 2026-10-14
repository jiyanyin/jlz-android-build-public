package dev.jlz.presence.focus

enum class DailyMode { NORMAL, FOCUS, BREAK, SLEEP }
enum class LocalAppCategory { SYSTEM_SAFE, LEARNING, GAME, FEED, OTHER, UNKNOWN }

/** Device-local policy. A legacy temporary entertainment release cannot bypass it. */
object DailyModePolicy {
    fun blocks(mode: DailyMode, category: LocalAppCategory, packageName: String): Boolean {
        if (category == LocalAppCategory.SYSTEM_SAFE) return false
        return when (mode) {
            DailyMode.FOCUS -> category != LocalAppCategory.LEARNING
            DailyMode.BREAK -> category == LocalAppCategory.GAME
            else -> false
        }
    }
    fun bypassEntertainment(mode: DailyMode, packageName: String): Boolean =
        mode == DailyMode.SLEEP || (mode == DailyMode.BREAK && packageName == "tv.danmaku.bili")
}
