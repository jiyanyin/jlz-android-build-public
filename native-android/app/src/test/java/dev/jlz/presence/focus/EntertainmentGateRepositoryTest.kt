package dev.jlz.presence.focus

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntertainmentGateRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: EntertainmentGateRepository
    private val profile = EntertainmentProfile(
        "com.xingin.xhs",
        "小红书",
        EntertainmentTier.FEED
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_entertainment_gate_v2", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = EntertainmentGateRepository(context)
    }

    @Test
    fun enabledDefaultsOnAndCanBeDisabled() {
        assertTrue(repo.enabled())
        repo.setEnabled(false)
        assertFalse(repo.enabled())
    }

    @Test
    fun grantCreatesExpiringRelease() {
        val release = repo.grant(
            profile,
            EntertainmentIntentChoice.BREAK,
            minutes = 5,
            nowMs = 1_000L
        )
        assertEquals(301_000L, release.untilMs)
        assertNotNull(repo.activeRelease(profile.packageName, 300_999L))
        assertNull(repo.activeRelease(profile.packageName, 301_000L))
    }

    @Test
    fun smallStepReplacesReleaseAndPersistsBaseline() {
        repo.grant(
            profile,
            EntertainmentIntentChoice.DIRECT,
            minutes = 3,
            nowMs = 1_000L
        )
        val step = repo.setSmallStep(
            profile,
            baselineEffectiveMs = 600_000L,
            nowMs = 2_000L
        )
        assertNull(repo.release(profile.packageName))
        assertEquals(600_000L, step.baselineEffectiveMs)
        assertEquals(
            EntertainmentGateV2Policy.SMALL_STEP_REQUIRED_MS,
            repo.smallStep(profile.packageName)?.requiredMs
        )
    }

    @Test
    fun grantingReleaseClearsPendingSmallStep() {
        repo.setSmallStep(profile, baselineEffectiveMs = 0L, nowMs = 1_000L)
        repo.grant(
            profile,
            EntertainmentIntentChoice.SMALL_STEP,
            minutes = 5,
            nowMs = 200_000L
        )
        assertNull(repo.smallStep(profile.packageName))
        assertNotNull(repo.release(profile.packageName))
    }
}
