package dev.jlz.presence.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArithmeticGatePolicyTest {
    @Test fun entertainmentRequiresTenMixedRangeQuestions() {
        val questions = ArithmeticGatePolicy.questions(true, 20261015)
        assertEquals(10, questions.size)
        assertEquals(5, questions.count { it.left <= 100 && it.right <= 100 && it.answer <= 100 })
        assertEquals(5, questions.count { it.left > 100 })
        assertTrue(questions.all { it.answer >= 0 })
        assertEquals(questions, ArithmeticGatePolicy.questions(true, 20261015))
    }

    @Test fun ordinaryAppsRequireFiveCorrectAnswers() {
        val questions = ArithmeticGatePolicy.questions(false, 7)
        assertEquals(5, questions.size)
        assertEquals(2, questions.count { it.left > 100 })
        assertFalse(questions.any { it.text.isBlank() })
    }
}
