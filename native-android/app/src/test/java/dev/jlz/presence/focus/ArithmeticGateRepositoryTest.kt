package dev.jlz.presence.focus

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
class ArithmeticGateRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: ArithmeticGateRepository
    private val start = 1_000_000L

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_arithmetic_gate_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("jlz_entertainment_gate_v2", Context.MODE_PRIVATE).edit().clear().commit()
        repo = ArithmeticGateRepository(context)
    }

    @Test fun switchingAwayAndRecreatingRepositoryDoesNotRevokeOrExtendWindows() {
        for (kind in ArithmeticGateKind.entries) {
            val pkg = "test.${kind.name}"
            repo.passed(pkg, kind, start)
            assertTrue(repo.allowed(pkg, kind, start + 6_000L))
            val recreated = ArithmeticGateRepository(context)
            assertTrue(recreated.allowed(pkg, kind, start + kind.exemptionMs - 1))
            assertFalse(recreated.allowed(pkg, kind, start + kind.exemptionMs))
        }
    }

    @Test fun workUsesRollingHourRatherThanClockHourAndCannotUnlockAnotherApp() {
        val at1043 = 10 * 3_600_000L + 43 * 60_000L
        repo.passed("com.openai.chatgpt", ArithmeticGateKind.WORK, at1043)
        assertTrue(repo.allowed("com.openai.chatgpt", ArithmeticGateKind.WORK, 11 * 3_600_000L))
        assertFalse(repo.allowed("com.tencent.mm", ArithmeticGateKind.WORK, at1043 + 1))
        assertFalse(repo.allowed("com.openai.chatgpt", ArithmeticGateKind.WORK, at1043 + 3_600_000L))
    }

    @Test fun oldFourHourGrantDoesNotOverrideNewPolicy() {
        context.getSharedPreferences("jlz_arithmetic_gate_v1", Context.MODE_PRIVATE).edit()
            .putString("stage:com.xingin.xhs", "active").putLong("until:com.xingin.xhs", Long.MAX_VALUE)
            .putBoolean("entered:com.xingin.xhs", true).commit()
        assertFalse(repo.allowed("com.xingin.xhs", ArithmeticGateKind.ENTERTAINMENT, start))
    }

    @Test fun timeGateResumeIsSingleUseAndDoesNotRenewQuizWindow() {
        val pkg = "com.xingin.xhs"
        val kind = ArithmeticGateKind.ENTERTAINMENT
        repo.passed(pkg, kind, start)
        val expiry = start + kind.exemptionMs
        EntertainmentGateRepository(context).grant(EntertainmentPolicy.profile(pkg)!!,
            EntertainmentIntentChoice.PURPOSE, minutes = 5, nowMs = expiry)
        assertTrue(repo.allowed(pkg, kind, expiry + 350L))
        assertFalse(repo.allowed(pkg, kind, expiry + 1_000L))
    }

    @Test fun shoppingTenMinuteTimeGatesDoNotShortenOrExtendThirtyMinuteQuizWindow() {
        val pkg = "com.taobao.taobao"
        val kind = ArithmeticGateKind.SHOPPING
        repo.passed(pkg, kind, start)
        val timeGate = EntertainmentGateRepository(context)
        for (minutes in listOf(0, 10, 20)) {
            val at = start + minutes * 60_000L
            val release = timeGate.grant(EntertainmentPolicy.profile(pkg)!!,
                EntertainmentIntentChoice.PURPOSE, minutes = 10, nowMs = at)
            assertEquals(at + 10 * 60_000L, release.untilMs)
            assertTrue(repo.allowed(pkg, kind, at + 350L))
        }
        assertTrue(repo.allowed(pkg, kind, start + 30 * 60_000L - 1))
        assertFalse(repo.allowed(pkg, kind, start + 30 * 60_000L))
    }

    @Test fun staleResumeCannotBypassNextEntryAndCannotCreateUnansweredGrant() {
        val pkg = "com.xingin.xhs"
        val kind = ArithmeticGateKind.ENTERTAINMENT
        repo.entertainmentGranted(pkg, start)
        assertFalse(repo.allowed(pkg, kind, start + 1))
        repo.passed(pkg, kind, start)
        repo.entertainmentGranted(pkg, start + kind.exemptionMs)
        assertFalse(repo.allowed(pkg, kind, start + kind.exemptionMs + 30_000L))
    }

    @Test fun revokeClockRollbackAndClassificationChangeRequireNewQuiz() {
        val pkg = "com.xingin.xhs"
        repo.passed(pkg, ArithmeticGateKind.ENTERTAINMENT, start)
        assertFalse(repo.allowed(pkg, ArithmeticGateKind.ENTERTAINMENT, start - 1))
        assertFalse(repo.allowed(pkg, ArithmeticGateKind.SHOPPING, start + 1))
        repo.revoke(pkg)
        assertFalse(repo.allowed(pkg, ArithmeticGateKind.ENTERTAINMENT, start + 1))
    }
}
