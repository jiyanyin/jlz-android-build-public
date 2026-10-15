package dev.jlz.presence.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalStatusLampTest {
    @Test fun onlyRecentBoundedAxesChangeLocalCopy() {
        val context = RuntimeEnvironment.getApplication()
        val store = LocalStatusLamp(context)
        val now = 1_800_000_000_000L
        assertTrue(store.record("""{"at_ms":$now,"axes":{"self_presence":25,"agitation":80}}""", now))
        assertEquals(25, store.fresh(now)?.selfPresence)
        assertNull(store.fresh(now + 4 * 60 * 60_000L + 1))
        assertFalse(store.record("""{"at_ms":$now,"axes":{"agitation":120}}""", now))
        assertFalse(store.record("""{"at_ms":$now,"axes":{"private_text":"secret"}}""", now))
    }
}
