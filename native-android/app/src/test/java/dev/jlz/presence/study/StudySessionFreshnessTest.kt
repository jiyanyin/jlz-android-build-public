package dev.jlz.presence.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudySessionFreshnessTest {
    @Test
    fun inactiveSessionIsNeverRecovered() {
        assertNull(
            StudySessionFreshness.staleReason(
                StudySessionState(active = false, startedAtMs = 1L),
                nowMs = 999_999L
            )
        )
    }

    @Test
    fun sixteenHourBoundaryKeepsReasonableLongSession() {
        val now = StudySessionFreshness.MAX_ACTIVE_SESSION_MS
        assertNull(
            StudySessionFreshness.staleReason(
                StudySessionState(
                    sessionId = "long-but-valid",
                    active = true,
                    startedAtMs = 0L + 1L
                ),
                nowMs = now
            )
        )
    }

    @Test
    fun veryOldActiveSessionIsRejected() {
        val now = StudySessionFreshness.MAX_ACTIVE_SESSION_MS + 2L
        assertEquals(
            "too_old",
            StudySessionFreshness.staleReason(
                StudySessionState(
                    sessionId = "orphan",
                    active = true,
                    startedAtMs = 1L
                ),
                nowMs = now
            )
        )
    }

    @Test
    fun impossibleFutureStartIsRejected() {
        val now = 1_000_000L
        assertEquals(
            "future_start",
            StudySessionFreshness.staleReason(
                StudySessionState(
                    sessionId = "future",
                    active = true,
                    startedAtMs = now + StudySessionFreshness.FUTURE_TOLERANCE_MS + 1L
                ),
                nowMs = now
            )
        )
    }
}
