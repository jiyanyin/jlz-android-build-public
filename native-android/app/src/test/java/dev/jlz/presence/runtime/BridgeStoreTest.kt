package dev.jlz.presence.runtime
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeStoreTest {
    @Test fun urlRestrictions() {
        assertTrue(BridgeStore.validUrl("http://192.168.1.2:17860"))
        assertTrue(BridgeStore.validUrl("http://100.78.16.38:17860"))
        assertTrue(BridgeStore.validUrl("https://example.com"))
        assertFalse(BridgeStore.validUrl("http://example.com"))
        assertFalse(BridgeStore.validUrl("http://192.168.1.999"))
        assertFalse(BridgeStore.validUrl("https://user:secret@example.com"))
        assertFalse(BridgeStore.validUrl("https://example.com?token=secret"))
    }
    @Test fun failoverOnlyAfterThresholdAndPreservesFallback() {
        val ctx: Context=RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("home_bridge",Context.MODE_PRIVATE).edit().clear().commit()
        val store=BridgeStore(ctx)
        val old=RuntimeSettings("https://existing.example","old-token")
        assertEquals(old.baseUrl,store.select(old).baseUrl)
        store.save(listOf(BridgeEndpoint("Home Node","http://10.0.0.2:17860","home"),
            BridgeEndpoint("Railway Standby","https://standby.example","standby",priority=1)))
        repeat(2) { store.pollResult("Home Node",false) }
        assertEquals("Home Node",store.select(old).bridgeName)
        assertTrue(RuntimeApiClient(store.select(old)).canTransferCapture())
        store.pollResult("Home Node",false)
        assertEquals("Railway Standby",store.select(old).bridgeName)
        assertFalse(RuntimeApiClient(store.select(old)).canTransferCapture())
        repeat(4) { store.pollResult("Railway Standby",false) }
        assertEquals("Railway Standby",store.select(old).bridgeName)
        assertFalse(store.diagnostics().toString().contains("old-token"))
    }

    @Test fun preferredHomeTailscaleRecoversWhenLanIsUnavailable() {
        val ctx: Context=RuntimeEnvironment.getApplication()
        val prefs=ctx.getSharedPreferences("home_bridge",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=BridgeStore(ctx)
        store.save(listOf(BridgeEndpoint("Home LAN","http://192.168.1.2:17860","a"),
            BridgeEndpoint("Home Node","http://100.78.16.38:17860","b",priority=1),
            BridgeEndpoint("Railway","https://standby.example","c",priority=2)))
        prefs.edit().putString("active","Railway").commit()
        store.probePreferred { it.name == "Home Node" }
        assertEquals("Railway",store.select(RuntimeSettings("https://old.example","x")).bridgeName)
        prefs.edit().putLong("probe_at",0).commit()
        store.probePreferred { it.name == "Home Node" }
        assertEquals("Home Node",store.select(RuntimeSettings("https://old.example","x")).bridgeName)
    }
}
