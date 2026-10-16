package dev.jlz.presence.focus

import kotlin.random.Random

data class ArithmeticQuestion(val left: Int, val right: Int, val add: Boolean) {
    val answer: Int get() = if (add) left + right else left - right
    val text: String get() = "$left ${if (add) "+" else "−"} $right = ?"
}

enum class ArithmeticGateKind(val questionCount: Int, val exemptionMs: Long) {
    WORK(3, 60 * 60_000L),
    ENTERTAINMENT(5, 10 * 60_000L),
    SHOPPING(5, 30 * 60_000L)
}

/** Track visits rather than each dialog/window event emitted by Android. */
class ArithmeticVisitTracker {
    private var foregroundPackage: String? = null

    fun enter(pkg: String?, transient: Boolean = false): Boolean {
        if (pkg.isNullOrBlank() || transient) return false
        val changed = foregroundPackage != pkg
        foregroundPackage = pkg
        return changed
    }
}

object ArithmeticGatePolicy {
    fun kind(profile: EntertainmentProfile?, category: LocalAppCategory): ArithmeticGateKind = when {
        profile?.tier == EntertainmentTier.SHOPPING -> ArithmeticGateKind.SHOPPING
        profile != null || category in setOf(LocalAppCategory.GAME, LocalAppCategory.FEED) ->
            ArithmeticGateKind.ENTERTAINMENT
        else -> ArithmeticGateKind.WORK
    }

    fun questions(kind: ArithmeticGateKind, seed: Int): List<ArithmeticQuestion> {
        val random = Random(seed)
        val count = kind.questionCount
        val small = if (kind == ArithmeticGateKind.WORK) 2 else 3
        return (0 until count).map { index ->
            val large = index >= small
            val add = index % 2 == 0
            when {
                !large && add -> {
                    val left = random.nextInt(8, 80)
                    ArithmeticQuestion(left, random.nextInt(1, 101 - left), true)
                }
                !large -> {
                    val left = random.nextInt(20, 101)
                    ArithmeticQuestion(left, random.nextInt(1, left + 1), false)
                }
                add -> {
                    val left = random.nextInt(101, 850)
                    ArithmeticQuestion(left, random.nextInt(10, 1001 - left), true)
                }
                else -> {
                    val left = random.nextInt(150, 1000)
                    ArithmeticQuestion(left, random.nextInt(1, left), false)
                }
            }
        }.shuffled(random)
    }
}
