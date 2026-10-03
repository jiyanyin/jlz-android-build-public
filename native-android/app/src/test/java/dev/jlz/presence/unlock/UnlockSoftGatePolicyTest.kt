package dev.jlz.presence.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockSoftGatePolicyTest {
    private fun input(
        enabled: Boolean = true,
        nowMs: Long = 20_000L,
        lastPresentedAtMs: Long = 0L,
        foregroundPackage: String? = "com.example.other",
        ownPackage: String = "dev.jlz.presence",
        interactive: Boolean = true,
        keyguardLocked: Boolean = false
    ) = UnlockSoftGateDecisionInput(
        enabled = enabled,
        nowMs = nowMs,
        lastPresentedAtMs = lastPresentedAtMs,
        foregroundPackage = foregroundPackage,
        ownPackage = ownPackage,
        interactive = interactive,
        keyguardLocked = keyguardLocked
    )

    @Test
    fun ordinaryUnlockPresentsOnce() {
        assertTrue(UnlockSoftGatePolicy.shouldPresent(input()))
    }

    @Test
    fun disabledOrStillLockedNeverPresents() {
        assertFalse(UnlockSoftGatePolicy.shouldPresent(input(enabled = false)))
        assertFalse(UnlockSoftGatePolicy.shouldPresent(input(keyguardLocked = true)))
        assertFalse(UnlockSoftGatePolicy.shouldPresent(input(interactive = false)))
    }

    @Test
    fun cooldownPreventsDuplicateUserPresentDelivery() {
        assertFalse(
            UnlockSoftGatePolicy.shouldPresent(
                input(nowMs = 20_000L, lastPresentedAtMs = 15_000L)
            )
        )
        assertTrue(
            UnlockSoftGatePolicy.shouldPresent(
                input(nowMs = 24_000L, lastPresentedAtMs = 15_000L)
            )
        )
    }

    @Test
    fun doesNotRelaunchWhenWorldBetweenIsAlreadyForeground() {
        assertFalse(
            UnlockSoftGatePolicy.shouldPresent(
                input(foregroundPackage = "dev.jlz.presence")
            )
        )
    }

    @Test
    fun safetySensitiveSurfacesAreSkipped() {
        for (pkg in listOf(
            "com.android.dialer",
            "com.android.incallui",
            "com.miui.camera",
            "com.android.emergency",
            "com.android.deskclock"
        )) {
            assertFalse(
                "expected safety skip for $pkg",
                UnlockSoftGatePolicy.shouldPresent(input(foregroundPackage = pkg))
            )
        }
    }

    @Test
    fun systemUiDoesNotBlockTheNormalUnlockPath() {
        assertTrue(
            UnlockSoftGatePolicy.shouldPresent(
                input(foregroundPackage = "com.android.systemui")
            )
        )
    }
}
