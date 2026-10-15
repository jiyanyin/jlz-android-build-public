package dev.jlz.presence.life

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalReminderDeliveryLedgerTest {
    private lateinit var prefs: android.content.SharedPreferences

    @Before fun clear() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("local_reminder_delivery_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun failedNotificationIsRetryableAndDoesNotAdvanceCopy() {
        val ledger = LocalReminderDeliveryLedger(prefs)
        var attempts = 0
        assertFalse(ledger.send("entertain30", "2026-10-13") { ordinal ->
            attempts++
            assertEquals(0, ordinal)
            false // e.g. POST_NOTIFICATIONS denied or channel disabled
        })
        assertEquals(1, attempts)
        assertEquals("", prefs.getString("sent:entertain30", ""))
        assertEquals(0, prefs.getInt("copy_count:entertain30", 0))

        assertTrue(ledger.send("entertain30", "2026-10-13") { ordinal ->
            attempts++
            assertEquals(0, ordinal)
            true
        })
        assertEquals(2, attempts)
        assertFalse(ledger.send("entertain30", "2026-10-13") {
            fail("duplicate must not dispatch")
            true
        })
        assertEquals(1, prefs.getInt("copy_count:entertain30", 0))
    }

    @Test fun exceptionNeverConsumesDeliveryAndNextLocalTickMayRetry() {
        val first = LocalReminderDeliveryLedger(prefs)
        assertFalse(first.send("steps", "2026-10-13") {
            throw SecurityException("notification permission revoked mid-send")
        })
        val afterServiceRestart = LocalReminderDeliveryLedger(prefs)
        assertTrue(afterServiceRestart.send("steps", "2026-10-13") { true })
        assertEquals("2026-10-13:steps", prefs.getString("sent:steps", ""))
    }

    @Test fun successiveDischargeScopesResetWithoutDailySpam() {
        val ledger = LocalReminderDeliveryLedger(prefs)
        assertTrue(ledger.send("battery40", "discharge:3") { true })
        assertFalse(ledger.send("battery40", "discharge:3") { true })
        assertTrue(ledger.send("battery40", "discharge:4") { ordinal ->
            assertEquals(1, ordinal)
            true
        })
        assertEquals(2, prefs.getInt("copy_count:battery40", 0))
    }

    @Test fun batteryWarningsOnlyEscalateUntilRealChargingRearm() {
        assertTrue(LocalLifeReminderPolicy.shouldWarnBattery(40, 0))
        assertTrue(LocalLifeReminderPolicy.shouldWarnBattery(20, 40))
        assertTrue(LocalLifeReminderPolicy.shouldWarnBattery(17, 20))
        assertTrue(LocalLifeReminderPolicy.shouldWarnBattery(14, 17))
        assertFalse(LocalLifeReminderPolicy.shouldWarnBattery(20, 17))
        assertFalse(LocalLifeReminderPolicy.shouldWarnBattery(40, 20))
        assertFalse(LocalLifeReminderPolicy.shouldWarnBattery(0, 40))
        // New discharge cycle resets lowest-notified to zero.
        assertTrue(LocalLifeReminderPolicy.shouldWarnBattery(40, 0))
    }
}
