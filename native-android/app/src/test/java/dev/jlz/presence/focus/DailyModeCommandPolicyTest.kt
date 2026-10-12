package dev.jlz.presence.focus

import org.junit.Assert.*
import org.junit.Test

class DailyModeCommandPolicyTest {
    @Test fun acceptsOnlySupportedModePresets() {
        assertEquals(DailyMode.FOCUS to 25, DailyModeCommandPolicy.parse("FOCUS", 25))
        assertEquals(DailyMode.FOCUS to 45, DailyModeCommandPolicy.parse("focus", 45))
        assertEquals(DailyMode.FOCUS to 60, DailyModeCommandPolicy.parse("FOCUS", 60))
        assertEquals(DailyMode.BREAK to 5, DailyModeCommandPolicy.parse("BREAK", 5))
        assertEquals(DailyMode.BREAK to 10, DailyModeCommandPolicy.parse("BREAK", 10))
        assertEquals(DailyMode.BREAK to 15, DailyModeCommandPolicy.parse("BREAK", 15))
        assertEquals(DailyMode.NORMAL to 0, DailyModeCommandPolicy.parse("NORMAL", 0))
        assertEquals(DailyMode.SLEEP to 0, DailyModeCommandPolicy.parse("SLEEP", 0))
        assertNull(DailyModeCommandPolicy.parse("FEED", 25))
        assertNull(DailyModeCommandPolicy.parse("FOCUS", 10))
        assertNull(DailyModeCommandPolicy.parse("BREAK", 25))
    }

    @Test fun repeatCommandsAreIdempotentUnlessRestarted() {
        assertTrue(DailyModeCommandPolicy.shouldKeepExisting(DailyMode.SLEEP, 0, DailyMode.SLEEP, false))
        assertTrue(DailyModeCommandPolicy.shouldKeepExisting(DailyMode.FOCUS, 100_000, DailyMode.FOCUS, false))
        assertFalse(DailyModeCommandPolicy.shouldKeepExisting(DailyMode.FOCUS, 0, DailyMode.FOCUS, false))
        assertFalse(DailyModeCommandPolicy.shouldKeepExisting(DailyMode.FOCUS, 100_000, DailyMode.FOCUS, true))
        assertFalse(DailyModeCommandPolicy.shouldKeepExisting(DailyMode.BREAK, 100_000, DailyMode.FOCUS, false))
    }
}
