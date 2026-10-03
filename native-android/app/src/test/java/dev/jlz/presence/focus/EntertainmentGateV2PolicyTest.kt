package dev.jlz.presence.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntertainmentGateV2PolicyTest {
    @Test
    fun gateWaitsForHomeTransitionToSettle() {
        assertTrue(EntertainmentGateV2Policy.GATE_PRESENT_DELAY_MS >= 250L)
        assertTrue(
            EntertainmentGateV2Policy.GATE_PRESENT_DELAY_MS <
                EntertainmentGateV2Policy.GATE_COOLDOWN_MS
        )
    }

    @Test
    fun phoneFeedUsesShortIntentionalWindows() {
        assertEquals(
            5,
            EntertainmentGateV2Policy.releasePlan(
                false, EntertainmentTier.FEED, EntertainmentIntentChoice.PURPOSE
            ).minutes
        )
        assertEquals(
            5,
            EntertainmentGateV2Policy.releasePlan(
                false, EntertainmentTier.FEED, EntertainmentIntentChoice.BREAK
            ).minutes
        )
        assertEquals(
            3,
            EntertainmentGateV2Policy.releasePlan(
                false, EntertainmentTier.FEED, EntertainmentIntentChoice.DIRECT
            ).minutes
        )
    }

    @Test
    fun tabletIsMorePermissiveThanPhone() {
        val phone = EntertainmentGateV2Policy.releasePlan(
            false, EntertainmentTier.FEED, EntertainmentIntentChoice.PURPOSE
        ).minutes
        val tablet = EntertainmentGateV2Policy.releasePlan(
            true, EntertainmentTier.FEED, EntertainmentIntentChoice.PURPOSE
        ).minutes
        assertTrue(tablet > phone)
    }

    @Test
    fun shoppingPurposeGetsMoreTimeThanFeedOnPhone() {
        val feed = EntertainmentGateV2Policy.releasePlan(
            false, EntertainmentTier.FEED, EntertainmentIntentChoice.PURPOSE
        ).minutes
        val shopping = EntertainmentGateV2Policy.releasePlan(
            false, EntertainmentTier.SHOPPING, EntertainmentIntentChoice.PURPOSE
        ).minutes
        assertTrue(shopping > feed)
    }

    @Test
    fun smallStepRequiresThreeAdditionalEffectiveMinutes() {
        val baseline = 10 * 60_000L
        assertFalse(
            EntertainmentGateV2Policy.smallStepComplete(
                baseline, baseline + 179_999L
            )
        )
        assertTrue(
            EntertainmentGateV2Policy.smallStepComplete(
                baseline, baseline + 180_000L
            )
        )
        assertEquals(
            60_000L,
            EntertainmentGateV2Policy.remainingSmallStepMs(
                baseline, baseline + 120_000L
            )
        )
    }

    @Test
    fun gateSentenceMatrixHasAtLeastFiveHundredDistinctLines() {
        val count = EntertainmentGateCopy.candidateCountForTest()
        assertTrue("candidate count was $count", count >= 500)
    }

    @Test
    fun warningOnlyAppearsInsideLastMinute() {
        val now = 1_000_000L
        assertFalse(EntertainmentGateV2Policy.shouldWarn(now + 61_000L, now))
        assertTrue(EntertainmentGateV2Policy.shouldWarn(now + 60_000L, now))
        assertTrue(EntertainmentGateV2Policy.shouldWarn(now + 1L, now))
        assertFalse(EntertainmentGateV2Policy.shouldWarn(now, now))
    }
}
