package dev.jlz.presence.usage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhoneRestWindowPolicyTest {
    private fun at(s: String) = OffsetDateTime.parse(s + "+08:00").toInstant().toEpochMilli()
    private val now = at("2026-10-17T14:00:00")
    private fun event(time: String, type: String, origin: String = "USER_OR_NON_RUNTIME") =
        JSONObject().put("wall_clock_timestamp", at(time)).put("event_type", type).put("origin", origin)

    @Test fun afternoonQueryKeepsNightStartAndFirstMorningOnSeparateFromUnlock() {
        val off = event("2026-10-16T23:00:00", "SCREEN_OFF")
        val on = event("2026-10-17T07:00:00", "SCREEN_ON")
        val unlock = event("2026-10-17T07:03:00", "KEYGUARD_HIDDEN")
        val noon = event("2026-10-17T13:00:00", "SCREEN_OFF")
        val result = PhoneRestWindowPolicy.estimate(listOf(noon, unlock, on, off), emptyList(), now, true)
        assertEquals(off.getLong("wall_clock_timestamp"), result.getLong("quiet_window_start"))
        assertEquals(480L, result.getLong("unused_phone_window_minutes"))
        assertEquals(unlock.getLong("wall_clock_timestamp"), result.getLong("first_morning_unlock_at"))
        assertTrue(result.isNull("sleep_duration_minutes"))
    }

    @Test fun automaticWakeAndUnknownUnlockAreNeverPersonalMorningUse() {
        val events = listOf(event("2026-10-16T23:00:00", "SCREEN_OFF"),
            event("2026-10-17T06:00:00", "SCREEN_ON", "JLZ_RUNTIME"),
            event("2026-10-17T06:01:00", "KEYGUARD_HIDDEN", "UNKNOWN"))
        val result = PhoneRestWindowPolicy.estimate(events, emptyList(), now, true)
        assertTrue(result.isNull("first_morning_unlock_at"))
        assertTrue(result.isNull("quiet_window_end"))
        assertEquals(1, result.getJSONArray("unknown_origin_events").length())
    }

    @Test fun intermediateUseAndCoverageGapsRemainExplicit() {
        val events = listOf(event("2026-10-16T23:00:00", "SCREEN_OFF"),
            event("2026-10-17T02:00:00", "USER_PRESENT"),
            event("2026-10-17T02:10:00", "SCREEN_OFF"),
            event("2026-10-17T04:00:00", "DATA_GAP_START", "SYSTEM"),
            event("2026-10-17T07:00:00", "SCREEN_ON"))
        val result = PhoneRestWindowPolicy.estimate(events, emptyList(), now, false)
        assertEquals(at("2026-10-17T02:10:00"), result.getLong("quiet_window_start"))
        assertEquals(1, result.getJSONArray("night_intermediate_use").length())
        assertFalse(result.getBoolean("coverage_complete"))
        assertTrue(result.isNull("unused_phone_window_minutes"))
    }

    @Test fun noonLockWithoutNightEvidenceCannotInventRest() {
        val result = PhoneRestWindowPolicy.estimate(listOf(event("2026-10-17T12:30:00", "SCREEN_OFF")), emptyList(), now, true)
        assertTrue(result.isNull("quiet_window_start"))
        assertTrue(result.isNull("unused_phone_window_minutes"))
    }

    @Test fun foregroundSessionInsideCandidatePreventsConfidentUnusedDuration() {
        val events = listOf(event("2026-10-16T23:00:00", "SCREEN_OFF"), event("2026-10-17T07:00:00", "SCREEN_ON"))
        val session = JSONObject().put("origin", "USER_OR_NON_RUNTIME")
            .put("session_start", at("2026-10-17T02:00:00")).put("session_end", at("2026-10-17T02:10:00"))
        val result = PhoneRestWindowPolicy.estimate(events, listOf(session), now, true)
        assertTrue(result.getBoolean("foreground_interruption"))
        assertTrue(result.isNull("unused_phone_window_minutes"))
    }
}
