package dev.jlz.presence.usage
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ForegroundUsageWindowTest {
    @Test fun rollingSummaryClipsBothEdgesAndDoesNotIncludeOldSegments() {
        val ctx: Context=RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("jlz_usage.db")
        ForegroundUsageStore(ctx).use {
            it.record("left",1000,4000)
            it.record("right",5000,9000)
            it.record("old",100,500)
            val rows=it.totalsInWindow(3000,7000)
            assertEquals(listOf("right","left"),rows.map { r->r.packageName })
            assertEquals(listOf(2000L,1000L),rows.map { r->r.durationMs })
            assertEquals(1,it.totalsInWindow(3000,7000,1).size)
            assertTrue(it.totalsInWindow(7000,3000).isEmpty())
        }
    }
}
