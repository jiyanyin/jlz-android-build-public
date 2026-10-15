package dev.jlz.presence.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyBehaviorPolicyTest {
    @Test fun tightensOnlyAfterFourReliableHighUseDays() {
        val high = BehaviorDay(50, 35, 23)
        val low = BehaviorDay(10, 5, 23)
        val partial = BehaviorDay(90, 60, 10)
        assertFalse(WeeklyBehaviorPolicy.tighten(listOf(high, high, high, partial)))
        assertFalse(WeeklyBehaviorPolicy.tighten(listOf(high, high, high, low)))
        assertTrue(WeeklyBehaviorPolicy.tighten(listOf(high, high, high, high)))
        assertEquals(3, WeeklyBehaviorPolicy.minutes(5, true))
        assertEquals(2, WeeklyBehaviorPolicy.minutes(3, true))
        assertEquals(5, WeeklyBehaviorPolicy.minutes(5, false))
    }
}
