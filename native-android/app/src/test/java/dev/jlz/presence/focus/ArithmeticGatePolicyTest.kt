package dev.jlz.presence.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArithmeticGatePolicyTest {
    @Test fun keyboardNotificationShadeAndSameAppDialogsDoNotStartAnotherVisit() {
        val visits = ArithmeticVisitTracker()
        assertTrue(visits.enter("com.xingin.xhs"))
        assertFalse(visits.enter("keyboard", transient = true))
        assertFalse(visits.enter("com.xingin.xhs"))
        assertFalse(visits.enter("com.android.systemui", transient = true))
        assertFalse(visits.enter(null))
        assertFalse(visits.enter("com.xingin.xhs"))
        assertTrue(visits.enter("com.tencent.mm"))
        assertTrue(visits.enter("com.xingin.xhs"))
    }

    @Test fun entertainmentRequiresFiveMixedRangeQuestions() {
        val questions = ArithmeticGatePolicy.questions(ArithmeticGateKind.ENTERTAINMENT, 20261015)
        assertEquals(5, questions.size)
        assertEquals(3, questions.count { it.left <= 100 && it.right <= 100 && it.answer <= 100 })
        assertEquals(2, questions.count { it.left > 100 })
        assertTrue(questions.all { it.answer >= 0 })
        assertEquals(questions, ArithmeticGatePolicy.questions(ArithmeticGateKind.ENTERTAINMENT, 20261015))
    }

    @Test fun workAppsRequireThreeCorrectAnswers() {
        val questions = ArithmeticGatePolicy.questions(ArithmeticGateKind.WORK, 7)
        assertEquals(3, questions.size)
        assertEquals(1, questions.count { it.left > 100 })
        assertFalse(questions.any { it.text.isBlank() })
    }

    @Test fun shoppingUsesFiveQuestionsAndSeparateThirtyMinuteWindow() {
        assertEquals(5, ArithmeticGatePolicy.questions(ArithmeticGateKind.SHOPPING, 7).size)
        assertEquals(30 * 60_000L, ArithmeticGateKind.SHOPPING.exemptionMs)
        assertEquals(10 * 60_000L, ArithmeticGateKind.ENTERTAINMENT.exemptionMs)
        assertEquals(60 * 60_000L, ArithmeticGateKind.WORK.exemptionMs)
        assertEquals(ArithmeticGateKind.SHOPPING, ArithmeticGatePolicy.kind(
            EntertainmentPolicy.profile("com.taobao.taobao"), LocalAppCategory.FEED))
        assertEquals(ArithmeticGateKind.ENTERTAINMENT, ArithmeticGatePolicy.kind(
            EntertainmentPolicy.profile("com.zhihu.android"), LocalAppCategory.UNKNOWN))
        assertEquals(ArithmeticGateKind.ENTERTAINMENT,
            ArithmeticGatePolicy.kind(null, LocalAppCategory.GAME))
        assertEquals(ArithmeticGateKind.WORK,
            ArithmeticGatePolicy.kind(null, LocalAppCategory.UNKNOWN))
    }
}
