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
        assertTrue(BridgeStore.validUrl(BridgeStore.RECOMMENDED_HOME_URL))
        assertTrue(BridgeStore.validUrl(BridgeStore.RECOMMENDED_HOME_URL+"/"))
        assertFalse(BridgeStore.validUrl("https://example.com/runtime/api"))
        assertFalse(BridgeStore.validUrl("https://example.com/%72untime"))
        assertFalse(BridgeStore.validUrl("https://example.com/runtime/../oauth"))
        assertFalse(BridgeStore.validUrl("http://10.0.0.1/runtime"))
    }
    @Test fun funnelMountIsKeptOnceForNativeAndWebRequests() {
        val base=BridgeStore.RECOMMENDED_HOME_URL
        assertEquals(base+"/api/poll?device_id=phone&wait_ms=25000",BridgeStore.apiUrl(base+"/","/api/poll?device_id=phone&wait_ms=25000"))
        assertEquals(base+"/api/web/state",BridgeStore.apiUrl(base,"/api/web/state"))
        assertEquals(base+"/health",BridgeStore.apiUrl(base,"/health"))
        assertEquals("Funnel HTTPS",BridgeStore.connectionMode(base))
        assertEquals("Tailscale private (optional)",BridgeStore.connectionMode("http://100.78.16.38:17860"))
        assertEquals("HTTPS",BridgeStore.connectionMode("https://100.example.com"))
        val ctx: Context=RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("home_bridge",Context.MODE_PRIVATE).edit().clear().commit()
        val store=BridgeStore(ctx)
        store.save(listOf(BridgeEndpoint("Home Node",base+"/","pairing-secret")))
        val selected=store.select(RuntimeSettings())
        assertEquals(base,selected.baseUrl)
        assertEquals("pairing-secret",selected.token)
        assertTrue(RuntimeApiClient(selected).canTransferCapture())
        assertEquals("Funnel HTTPS",store.diagnostics().getString("mode"))
        assertFalse(store.diagnostics().toString().contains("pairing-secret"))
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
