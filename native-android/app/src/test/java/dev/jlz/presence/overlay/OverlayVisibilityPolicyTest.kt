package dev.jlz.presence.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayVisibilityPolicyTest {
    @Test fun desktopAndOrdinaryAppsDoNotHideQ() {
        for (pkg in listOf("com.android.launcher", "com.openai.chatgpt", "com.android.chrome", "com.fenbi.android.servant")) {
            assertFalse(OverlayVisibilityPolicy.protectedPackage(pkg))
        }
        assertFalse(OverlayVisibilityPolicy.hide(false, true, false, false))
    }
    @Test fun safetyAndCaptureHideUntilConditionEnds() {
        assertTrue(OverlayVisibilityPolicy.hide(true, true, false, false))
        assertTrue(OverlayVisibilityPolicy.hide(false, false, false, false))
        assertTrue(OverlayVisibilityPolicy.hide(false, true, true, false))
        assertTrue(OverlayVisibilityPolicy.hide(false, true, false, true))
        assertTrue(OverlayVisibilityPolicy.protectedPackage("com.google.android.permissioncontroller"))
        assertFalse(OverlayVisibilityPolicy.hide(false, true, false, false))
    }
    @Test fun opacityHasOneExactFullStrengthEndpoint() {
        assertEquals(1f, QOverlayPreferences.alpha(100), 0f)
        assertEquals(.25f, QOverlayPreferences.alpha(0), 0f)
        assertEquals(.5f, QOverlayPreferences.alpha(50), 0f)
        assertEquals(1f, QOverlayPreferences.alpha(150), 0f)
    }
}
