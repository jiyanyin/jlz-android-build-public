package dev.jlz.presence.cowatch

import dev.jlz.presence.focus.LocalAppCategory
import org.junit.Assert.*
import org.junit.Test

class CoWatchTargetPolicyTest {
    @Test fun userSelectedContentAppsCanBeSampled() {
        assertTrue(CoWatchTargetPolicy.allowed("com.xingin.xhs", LocalAppCategory.OTHER))
        assertTrue(CoWatchTargetPolicy.allowed("tv.danmaku.bili", LocalAppCategory.OTHER))
        assertTrue(CoWatchTargetPolicy.allowed("example.game", LocalAppCategory.GAME))
        assertTrue(CoWatchTargetPolicy.allowed("example.feed", LocalAppCategory.FEED))
    }
    @Test fun protectedAppsCannotBeSelectedEvenIfMislabeled() {
        for (pkg in listOf("com.tencent.mm", "com.eg.android.AlipayGphone",
            "com.android.systemui", "com.openai.chatgpt", "com.bank.mobile")) {
            assertFalse(pkg, CoWatchTargetPolicy.allowed(pkg, LocalAppCategory.GAME))
        }
        assertFalse(CoWatchTargetPolicy.allowed("", LocalAppCategory.FEED))
    }
}
