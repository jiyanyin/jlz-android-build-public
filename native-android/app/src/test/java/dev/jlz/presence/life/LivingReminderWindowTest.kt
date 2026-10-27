package dev.jlz.presence.life
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class LivingReminderWindowTest {
    private fun at(s:String)=Instant.parse(s).toEpochMilli()
    @Test fun afternoon_message_cannot_arrive_at_beijing_midnight() {
        assertFalse(LivingReminderWindow.allowed("afternoon",at("2026-10-27T08:30:00Z"),at("2026-10-27T16:01:00Z")))
        assertTrue(LivingReminderWindow.allowed("afternoon",at("2026-10-27T08:30:00Z"),at("2026-10-27T08:35:00Z")))
    }
    @Test fun departure_and_hourly_do_not_replay_stale_appointments() {
        val start=at("2026-10-27T00:40:00Z")
        assertTrue(LivingReminderWindow.allowed("depart_morning",start,start+300000))
        assertFalse(LivingReminderWindow.allowed("depart_morning",start,start+660000))
        assertFalse(LivingReminderWindow.allowed("hourly",start,start+3600000))
    }
}
