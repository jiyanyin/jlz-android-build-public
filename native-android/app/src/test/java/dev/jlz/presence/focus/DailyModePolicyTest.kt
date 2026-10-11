package dev.jlz.presence.focus

import org.junit.Assert.*
import org.junit.Test

class DailyModePolicyTest {
    @Test fun focusOnlyAllowsLearningAndSafety() {
        for (category in LocalAppCategory.values()) assertEquals(
            category !in setOf(LocalAppCategory.LEARNING, LocalAppCategory.SYSTEM_SAFE),
            DailyModePolicy.blocks(DailyMode.FOCUS, category, "some.new.app"))
    }
    @Test fun breakBlocksNewGamesButAllowsBiliAndOrdinaryApps() {
        assertTrue(DailyModePolicy.blocks(DailyMode.BREAK, LocalAppCategory.GAME, "new.game"))
        assertFalse(DailyModePolicy.blocks(DailyMode.BREAK, LocalAppCategory.FEED, "tv.danmaku.bili"))
        assertTrue(DailyModePolicy.bypassEntertainment(DailyMode.BREAK, "tv.danmaku.bili"))
        assertFalse(DailyModePolicy.bypassEntertainment(DailyMode.BREAK, "com.xingin.xhs"))
        assertFalse(DailyModePolicy.blocks(DailyMode.BREAK, LocalAppCategory.UNKNOWN, "ordinary.app"))
    }
    @Test fun expiryPauseAndSafetyCannotBeConfusedWithSleep() {
        val state = FocusState(active=true, startedAtMs=100, endsAtMs=1000, dailyMode=DailyMode.FOCUS)
        assertEquals(DailyMode.NORMAL, state.modeNow(1001))
        assertEquals(DailyMode.FOCUS, state.copy(pausedAtMs=500).modeNow(2000))
        assertEquals(500L, state.copy(pausedAtMs=500).remainingMs(2000))
        assertFalse(DailyModePolicy.blocks(DailyMode.SLEEP, LocalAppCategory.SYSTEM_SAFE, "clock"))
        assertFalse(DailyModePolicy.blocks(DailyMode.FOCUS, LocalAppCategory.SYSTEM_SAFE, "phone"))
    }
}
