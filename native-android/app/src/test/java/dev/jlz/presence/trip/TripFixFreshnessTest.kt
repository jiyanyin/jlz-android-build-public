package dev.jlz.presence.trip

import org.junit.Assert.*
import org.junit.Test

class TripFixFreshnessTest {
    @Test fun oldCachedOrFutureFixCannotBecomeLive() {
        assertFalse(TripFixFreshness.acceptable(1000, 1, 200000, 200000000000, 500))
        assertFalse(TripFixFreshness.acceptable(1000, 1000000000, 1100, 1100000000, 1050))
        assertFalse(TripFixFreshness.acceptable(5000, 1000000000, 1100, 1100000000, 500))
        assertFalse(TripFixFreshness.acceptable(1000, 1200000000, 1100, 1100000000, 500))
    }
    @Test fun freshPostConsentFixKeepsItsOwnTimestamp() {
        assertTrue(TripFixFreshness.acceptable(1000, 1000000000, 1100, 1100000000, 500))
    }
}
