package dev.jlz.presence.focus

import kotlin.random.Random

data class ArithmeticQuestion(val left: Int, val right: Int, val add: Boolean) {
    val answer: Int get() = if (add) left + right else left - right
    val text: String get() = "$left ${if (add) "+" else "−"} $right = ?"
}

object ArithmeticGatePolicy {
    fun questions(entertainment: Boolean, seed: Int): List<ArithmeticQuestion> {
        val random = Random(seed)
        val count = if (entertainment) 10 else 5
        val small = if (entertainment) 5 else 3
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
