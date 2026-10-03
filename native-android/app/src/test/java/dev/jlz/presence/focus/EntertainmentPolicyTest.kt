package dev.jlz.presence.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntertainmentPolicyTest {
    @Test
    fun phoneFeedStartsNudgingAtFiveMinutes() {
        val t = EntertainmentPolicy.thresholds(false, EntertainmentTier.FEED)
        assertEquals(5 * 60_000L, t.nudgeMs)
        assertEquals(8 * 60_000L, t.firmMs)
        assertEquals(12 * 60_000L, t.lockMs)
        assertEquals(8, t.lockMinutes)
    }

    @Test
    fun tabletIsMoreLenientThanPhone() {
        val phone = EntertainmentPolicy.thresholds(false, EntertainmentTier.FEED)
        val tablet = EntertainmentPolicy.thresholds(true, EntertainmentTier.FEED)
        assertTrue(tablet.nudgeMs > phone.nudgeMs)
        assertTrue(tablet.lockMs > phone.lockMs)
    }

    @Test
    fun sentenceBankHasAtLeastFiveHundredDistinctCandidates() {
        val count = EntertainmentMessageBank.candidateCountForTest()
        assertTrue("candidate count was $count", count >= 500)
    }

    @Test
    fun deepseekAndChatgptAreNotEntertainmentProfiles() {
        assertEquals(null, EntertainmentPolicy.profile("com.deepseek.chat"))
        assertEquals(null, EntertainmentPolicy.profile("com.openai.chatgpt"))
    }
}
