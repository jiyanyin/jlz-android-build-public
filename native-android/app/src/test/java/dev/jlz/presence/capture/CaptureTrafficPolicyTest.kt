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
