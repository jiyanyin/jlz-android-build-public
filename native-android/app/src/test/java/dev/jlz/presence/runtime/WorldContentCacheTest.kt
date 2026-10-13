package dev.jlz.presence.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorldContentCacheTest {
    private lateinit var context: Context
    private lateinit var cache: WorldContentCache

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("world_content_v1", Context.MODE_PRIVATE).edit().clear().commit()
        cache = WorldContentCache(context)
    }

    private fun doc(revision: Int, viewId: String): JSONObject {
        val keys = listOf("gate.incoming", "callback.incoming", "callback.connected", "theme.id")
        val entries = JSONArray()
        keys.forEach { key ->
            entries.put(JSONObject().put("key", key)
                .put("value", if (key == "theme.id") "mist" else key)
                .put("device_scope", "shared").put("effective_date", "")
                .put("daypart", "").put("expires_at", "").put("revision", revision))
        }
        return JSONObject().put("revision", revision).put("device_scope", "phone")
            .put("view_id", viewId).put("entries", entries)
    }

    @Test fun effectiveCacheKeepsFallbackAndRefusesStaleVersion() {
        cache.save(doc(2, "v2"))
        assertEquals("gate.incoming", cache.text("gate.incoming", "default"))
        cache.save(doc(1, "v1"))
        assertEquals("gate.incoming", cache.text("gate.incoming", "default"))
        assertEquals("fallback", cache.text("not.an.entry", "fallback"))
        assertEquals(0, cache.receiptDiagnostics().getInt("pending_native_receipts"))
    }

    @Test fun offlineMultipleScreensRetainIndependentReceiptsAndDeduplicateByView() {
        cache.save(doc(8, "v8"))
        cache.displayed(listOf("callback.incoming", "theme.id"))
        cache.displayed(listOf("gate.incoming", "theme.id"))
        cache.displayed(listOf("callback.incoming", "theme.id"))
        assertEquals(2, cache.receiptDiagnostics().getInt("pending_native_receipts"))
        var calls = 0
        cache.flushPending("android-phone-native-n0") { request ->
            calls++
            assertEquals("callback.incoming", request.getJSONArray("applied_keys").getString(0))
            assertEquals("android-phone-native-n0", request.getString("device_id"))
            assertFalse(request.has("local_receipt_id"))
            false // offline, not a successful device receipt
        }
        assertEquals(1, calls)
        assertEquals(2, cache.receiptDiagnostics().getInt("pending_native_receipts"))
        cache.flushPending("android-phone-native-n0") { true }
        assertEquals(1, cache.receiptDiagnostics().getInt("pending_native_receipts"))
        cache.flushPending("android-phone-native-n0") { true }
        assertEquals(0, cache.receiptDiagnostics().getInt("pending_native_receipts"))
        cache.displayed(listOf("callback.incoming", "theme.id"))
        assertEquals(0, cache.receiptDiagnostics().getInt("pending_native_receipts"))
    }

    @Test fun receiptInFlightDoesNotDiscardAnotherScreen() {
        cache.save(doc(9, "v9"))
        cache.displayed(listOf("callback.incoming"))
        cache.flushPending("android-phone-native-n0") {
            cache.displayed(listOf("gate.incoming"))
            true
        }
        assertEquals(1, cache.receiptDiagnostics().getInt("pending_native_receipts"))
        cache.flushPending("android-phone-native-n0") { request ->
            assertEquals("gate.incoming", request.getJSONArray("applied_keys").getString(0))
            true
        }
        assertEquals(0, cache.receiptDiagnostics().getInt("pending_native_receipts"))
    }

    @Test fun oldViewConflictCanDropOnlyTheStaleReceiptAndNotNewerOne() {
        cache.save(doc(10, "v10"))
        cache.displayed(listOf("callback.incoming"))
        cache.save(doc(11, "v11"))
        cache.displayed(listOf("gate.incoming"))
        cache.flushPending("android-phone-native-n0") {
            throw IllegalStateException("HTTP 409 stale view")
        }
        assertEquals(1, cache.receiptDiagnostics().getInt("pending_native_receipts"))
        cache.flushPending("android-phone-native-n0") { request ->
            assertEquals(11, request.getInt("revision"))
            true
        }
        assertEquals(0, cache.receiptDiagnostics().getInt("pending_native_receipts"))
    }

    @Test fun expirySelectionNeverReportsAnExpiredContentKey() {
        val rows = JSONArray()
            .put(JSONObject().put("key", "gate.incoming").put("value", "expired")
                .put("device_scope", "phone").put("effective_date", "").put("daypart", "")
                .put("expires_at", OffsetDateTime.now().minusDays(2).toString())
                .put("revision", 12))
        cache.save(JSONObject().put("revision", 12).put("view_id", "v12")
            .put("device_scope", "phone").put("entries", rows))
        assertEquals("fallback", cache.text("gate.incoming", "fallback"))
        cache.displayed(listOf("gate.incoming"))
        assertEquals(0, cache.receiptDiagnostics().getInt("pending_native_receipts"))
    }
}
