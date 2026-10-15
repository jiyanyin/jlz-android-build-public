package dev.jlz.presence.life

import dev.jlz.presence.place.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalDailyReminderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 15, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun rollingAlarmSelectsFixedAndHourlySlots() {
        assertEquals("weather_morning", LocalDailyReminderSchedule.next(at(7, 59)).key)
        assertEquals("hourly", LocalDailyReminderSchedule.next(at(8, 1)).key)
        assertEquals("nap_followup_1340", LocalDailyReminderSchedule.next(at(13, 31)).key)
        assertEquals("hourly", LocalDailyReminderSchedule.next(at(23, 30)).key)
        assertEquals(at(23, 20) + 60 * 60_000L,
            LocalDailyReminderSchedule.next(at(23, 30)).atMs)
    }

    @Test fun localPhraseMatrixIsLargeAndDeterministic() {
        assertTrue(LocalDailyCopy.candidateCount() >= 10_000)
        val a = LocalDailyCopy.choose("lunch", 4, at(12, 0))
        assertEquals(a, LocalDailyCopy.choose("lunch", 4, at(12, 0)))
        assertTrue(a.length > 10)
        assertFalse(a.contains("用户"))
    }

    @Test fun weatherNeverInventsTemperatureWhenUnavailable() {
        assertTrue(LocalDailyCopy.weather(null, true).contains("没读到实时数据"))
        val wet = WeatherState(11.0, 8.0, 1.2, 1.2, 61, 28.0, at(8, 0))
        val text = LocalDailyCopy.weather(wet, true)
        assertTrue(text.contains("11℃"))
        assertTrue(text.contains("带伞"))
        assertTrue(text.contains("外套"))
    }

    @Test fun batteryBandsContinueEveryThreePercentBelowTwenty() {
        assertEquals(20, LocalLifeReminderPolicy.batteryBand(19))
        assertEquals(17, LocalLifeReminderPolicy.batteryBand(17))
        assertEquals(14, LocalLifeReminderPolicy.batteryBand(14))
        assertEquals(11, LocalLifeReminderPolicy.batteryBand(10))
        assertEquals(8, LocalLifeReminderPolicy.batteryBand(8))
        assertEquals(5, LocalLifeReminderPolicy.batteryBand(5))
        assertEquals(2, LocalLifeReminderPolicy.batteryBand(1))
    }
}
