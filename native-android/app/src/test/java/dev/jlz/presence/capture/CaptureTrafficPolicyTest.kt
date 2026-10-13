package dev.jlz.presence.capture

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
class CaptureTrafficPolicyTest {
    private lateinit var context: Context
    private lateinit var policy: CaptureTrafficPolicy
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_capture_traffic_v1", Context.MODE_PRIVATE).edit().clear().commit()
        policy = CaptureTrafficPolicy(context)
    }
    private fun allow() {
        context.getSharedPreferences("jlz_capture_traffic_v1", Context.MODE_PRIVATE).edit()
            .putBoolean("automatic_enabled", true).putStringSet("automatic_packages", setOf("test.app", "com.openai.chatgpt")).commit()
    }
    @Test fun defaultClosedAndExplicitWhitelist() {
        assertFalse(policy.canCapture("test.app")); allow()
        assertTrue(policy.canCapture("test.app"))
        assertFalse(policy.canCapture("other.app")); assertFalse(policy.canCapture("com.openai.chatgpt"))
    }
    @Test fun exactBudgetCooldownDedupeAndRestart() {
        allow(); val now = System.currentTimeMillis()
        val image = ByteArray(1024) { 3 }
        assertTrue(policy.reserve("test.app", image, now))
        assertFalse(CaptureTrafficPolicy(context).canCapture("test.app", now + 1))
        assertFalse(policy.reserve("test.app", image, now + CaptureTrafficPolicy.COOLDOWN_MS))
        assertFalse(policy.reserve("test.app", ByteArray(CaptureTrafficPolicy.DAILY_BYTES.toInt()+1), now + CaptureTrafficPolicy.COOLDOWN_MS))
    }
    @Test fun noFixedHourlyScreenshotCountCapButBytesRemainBounded() {
        assertTrue(policy.configureAutomatic(true, setOf("test.app", "com.tencent.mm", "com.openai.chatgpt")))
        assertTrue(policy.canCapture("test.app"))
        assertFalse(policy.canCapture("com.tencent.mm"))
        assertFalse(policy.canCapture("com.openai.chatgpt"))
        val now = System.currentTimeMillis()
        for (i in 0 until 27) {
            val bytes = ByteArray(512) { i.toByte() }
            assertTrue("capture $i should not hit a count cap",
                policy.reserve("test.app", bytes, now + i * CaptureTrafficPolicy.COOLDOWN_MS))
        }
        assertEquals(27L, policy.snapshot().getLong("auto_bytes_today") / 512)
        assertTrue(policy.configureAutomatic(false, setOf("test.app")))
        assertFalse(policy.canCapture("test.app"))
    }

    @Test fun retryBytesAreChargedBeforeSendingAndExplicitTrafficStillRecorded() {
        assertTrue(policy.reserveAutomaticTransfer(CaptureTrafficPolicy.DAILY_BYTES.toInt()))
        assertFalse(CaptureTrafficPolicy(context).reserveAutomaticTransfer(1))
        policy.record(upload=999,download=20,screenshot=999)
        assertEquals(999L, policy.snapshot().getLong("upload_bytes_today"))
        assertEquals(20L, policy.snapshot().getLong("download_bytes_today"))
    }
    @Test fun monthlyWarningStopsAutomaticButNotExplicitAccounting() {
        allow(); policy.record(upload=CaptureTrafficPolicy.MONTHLY_SCREENSHOT_WARNING, screenshot=CaptureTrafficPolicy.MONTHLY_SCREENSHOT_WARNING)
        assertFalse(policy.canCapture("test.app")); assertFalse(policy.reserveAutomaticTransfer(1))
        policy.record(upload=123,screenshot=123)
        assertEquals(CaptureTrafficPolicy.MONTHLY_SCREENSHOT_WARNING+123, policy.snapshot().getLong("screenshot_bytes_month"))
    }
}
