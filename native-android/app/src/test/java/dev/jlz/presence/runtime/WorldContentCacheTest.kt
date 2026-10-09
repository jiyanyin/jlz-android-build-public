package dev.jlz.presence.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
class WorldContentCacheTest {
    @Test fun effectiveCachePreservesOfflineFallbackAndRejectsOlderSnapshots() {
        val context:Context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("world_content_v1",Context.MODE_PRIVATE).edit().clear().commit()
        val cache=WorldContentCache(context)
        fun row(value:String,scope:String="shared",expiry:String="")=JSONObject().put("key","gate.incoming").put("value",value)
            .put("device_scope",scope).put("effective_date","").put("daypart","").put("expires_at",expiry).put("revision",1)
        val doc=JSONObject().put("revision",2).put("device_scope","tablet").put("view_id","test")
            .put("entries",JSONArray().put(row("base")).put(row("expired","tablet",OffsetDateTime.now().minusDays(1).toString())))
        cache.save(doc)
        assertEquals("base",cache.text("gate.incoming","built-in"))
        cache.save(JSONObject().put("revision",1).put("entries",JSONArray().put(row("stale"))))
        assertEquals("base",WorldContentCache(context).text("gate.incoming","built-in"))
        assertEquals("safe",cache.text("gate.connected","safe"))
        cache.displayed(listOf("gate.incoming"))
        val receipt=JSONObject(context.getSharedPreferences("world_content_v1",Context.MODE_PRIVATE).getString("pending_receipt","{}")!!)
        assertEquals(2,receipt.getInt("revision"))
        assertEquals("gate.incoming",receipt.getJSONArray("applied_keys").getString(0))
    }
}
