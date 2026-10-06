package dev.jlz.presence.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ForegroundUsageStoreWindowTest {
    @Test
    fun totalsBetweenClipsSegmentsToRequestedWindow() {
        val context = RuntimeEnvironment.getApplication()
        val store = ForegroundUsageStore(context)
        store.writableDatabase.delete("usage_segments", null, null)

        store.record("app.a", 1_000L, 5_000L)
        store.record("app.a", 5_500L, 7_500L)
        store.record("app.b", 3_500L, 4_500L)

        val totals = store.totalsBetween(3_000L, 6_000L, 10)
            .associate { it.packageName to it.durationMs }

        assertEquals(2_500L, totals["app.a"])
        assertEquals(1_000L, totals["app.b"])
    }

    @Test
    fun totalsBetweenReturnsLargestPackagesFirstAndRespectsLimit() {
        val context = RuntimeEnvironment.getApplication()
        val store = ForegroundUsageStore(context)
        store.writableDatabase.delete("usage_segments", null, null)

        store.record("app.small", 1_000L, 2_000L)
        store.record("app.large", 1_000L, 5_000L)

        val totals = store.totalsBetween(0L, 6_000L, 1)
        assertEquals(1, totals.size)
        assertEquals("app.large", totals.first().packageName)
        assertEquals(4_000L, totals.first().durationMs)
    }
}
