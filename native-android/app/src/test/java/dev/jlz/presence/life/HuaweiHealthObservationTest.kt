package dev.jlz.presence.life

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HuaweiHealthObservationTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_huawei_observation_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("jlz_entertainment_daily_rest_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun parseOnlyLabeledSleepAndNeverInferFromUnlockTime() {
        assertEquals(452, HuaweiSleepTextParser.minutes("昨晚睡眠 7小时32分钟"))
        assertEquals(450, HuaweiSleepTextParser.minutes("睡眠时长：7.5小时"))
        assertNull(HuaweiSleepTextParser.minutes("建议每天睡眠 8小时"))
        assertNull(HuaweiSleepTextParser.minutes("23:30 07:20"))
        assertNull(HuaweiSleepTextParser.minutes("睡眠时长 25小时30分钟"))
    }

    @Test fun retainOriginalSourceAndReturnMissingIfUnseenOrStale() {
        val store = HuaweiHealthObservationStore(context)
        val now = System.currentTimeMillis()
        assertTrue(store.snapshot(now).isNull("sleep_minutes"))
        assertTrue(store.snapshot(now).isNull("steps"))
        store.recordNotificationSteps(3194, now)
        val steps = store.snapshot(now)
        assertEquals(3194, steps.getInt("steps"))
        assertEquals("huawei_health_visible_notification", steps.getString("steps_source"))
        assertFalse(steps.getBoolean("sleep_date_verified"))
        assertTrue(store.recordVisibleHuaweiPage("总睡眠：7小时10分钟", now))
        assertEquals(430, store.snapshot(now).getInt("sleep_minutes"))
        assertTrue(store.snapshot(now + 37L * 60 * 60_000L).isNull("sleep_minutes"))
        assertTrue(store.snapshot(now + 4L * 60 * 60_000L).isNull("steps"))
    }

    @Test fun timeScheduleUsesBeijingAndRollsToNextDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.of(2026,10,13,8,6).atZone(zone).toInstant().toEpochMilli()
        val next = DailyHealthSchedule.nextOccurrence(now,8,5)
        assertEquals(LocalDateTime.of(2026,10,14,8,5).atZone(zone).toInstant().toEpochMilli(), next)
    }

    @Test fun onlyExplicitPriorNightDateCanVerifySleep() {
        val now = LocalDateTime.of(2026,10,13,8,7)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val store = HuaweiHealthObservationStore(context)
        assertTrue(store.recordVisibleHuaweiPage("昨晚睡眠 7小时32分钟", now))
        assertFalse(store.snapshot(now).getBoolean("sleep_date_verified"))
        assertEquals("sleep_visible_date_unverified",
            store.snapshot(now).getString("sleep_parse_status"))

        assertTrue(store.recordVisibleHuaweiPage(
            "总睡眠：7小时32分钟 入睡日期：2026年10月12日", now))
        assertTrue(store.snapshot(now).getBoolean("sleep_date_verified"))
        assertEquals(452, store.snapshot(now).getInt("sleep_minutes"))
        assertEquals("explicit_prior_night_date_on_visible_page",
            store.snapshot(now).getString("sleep_date_semantics"))
        assertFalse(store.recordVisibleHuaweiPage("总睡眠：2小时15分钟", now + 1000L))
        assertEquals(452, store.snapshot(now + 1000L).getInt("sleep_minutes"))
        assertTrue(store.snapshot(now + 1000L).getBoolean("sleep_date_verified"))
        assertEquals("sleep_visible_unverified_ignored", store.mostRecentParseStatus())
        assertTrue(store.snapshot(now + 23L * 60 * 60_000L).isNull("sleep_minutes"))
    }

    @Test fun mismatchedOrNapDateCannotBeLabeledLastNight() {
        val now = LocalDateTime.of(2026,10,13,8,7)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val store = HuaweiHealthObservationStore(context)
        assertFalse(store.recordVisibleHuaweiPage(
            "总睡眠 8小时20分钟 入睡日期：2026年10月11日", now))
        assertTrue(store.snapshot(now).isNull("sleep_minutes"))
        assertEquals("visible_page_wrong_date", store.mostRecentParseStatus())

        assertTrue(store.recordVisibleHuaweiPage(
            "总睡眠 1小时 起床日期：2026-10-13", now))
        assertFalse(store.snapshot(now).getBoolean("sleep_date_verified"))
        assertEquals(HuaweiSleepDateEvidence.Result.MISMATCH,
            HuaweiSleepDateEvidence.check("昨晚睡眠 7小时30分钟 起床日期：2026-10-12", now))
    }

    @Test fun morningStatusNeverPretendsTheHealthAppWasOpened() {
        val store = HuaweiHealthObservationStore(context)
        store.markMorningAttempt()
        assertEquals("checking_visible_sources", store.snapshot().getString("morning_check_result"))
        store.markMorningResult("huawei_page_not_visible")
        val snap = store.snapshot()
        assertEquals("huawei_page_not_visible", snap.getString("morning_check_result"))
        assertTrue(snap.isNull("sleep_minutes"))
        assertFalse(snap.getBoolean("sleep_date_verified"))
    }

    @Test fun batteryAndEntertainmentThresholdsUseBounds() {
        assertEquals(0, LocalLifeReminderPolicy.batteryBand(41))
        assertEquals(40, LocalLifeReminderPolicy.batteryBand(40))
        assertEquals(20, LocalLifeReminderPolicy.batteryBand(20))
        assertEquals(10, LocalLifeReminderPolicy.batteryBand(10))
        assertEquals(0, LocalLifeReminderPolicy.entertainmentStage(29))
        assertEquals(30, LocalLifeReminderPolicy.entertainmentStage(30))
        assertEquals(45, LocalLifeReminderPolicy.entertainmentStage(45))
        val now = System.currentTimeMillis()
        assertFalse(LocalLifeReminderPolicy.lowSteps(1234,now - 4 * 60 * 60_000L,now))
        assertTrue(LocalLifeReminderPolicy.lowSteps(1234,now,now))
    }

    @Test fun localReminderCopyRotatesAndUsesWifeLanguage() {
        val keys = listOf("battery40","battery20","battery10","charging","light","shake",
            "screen","entertain30","entertain45","steps","weather","health")
        keys.forEach { key ->
            val variants = (0 until 8).map { LocalLifeCopy.choose(key, it) }
            assertEquals(8, variants.toSet().size)
            assertTrue(variants.none { it.contains("用户") })
        }
        assertEquals("reminder.low_steps", LocalLifeCopy.worldKey("steps"))
        assertEquals("reminder.weather", LocalLifeCopy.worldKey("weather"))
    }

    @Test fun oneFiveMinuteRestPerDayAndEmergencyRelease() {
        val guard = DailyEntertainmentRestGuard(context)
        val now = System.currentTimeMillis()
        assertEquals(0L, guard.beginIfDue(now))
        guard.requestOnceThresholdCrossed(now)
        assertEquals(300_000L,guard.beginIfDue(now))
        assertEquals(120_000L,guard.restRemainingMs(now+180_000L))
        guard.finish(now)
        assertEquals(0L,guard.beginIfDue(now))
        assertEquals("rest_complete",guard.status(now))
    }
}
