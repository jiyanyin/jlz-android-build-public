package dev.jlz.presence.persona

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class LivingVoiceStoreTest {
    private lateinit var context:Context
    private val now=Instant.parse("2026-10-27T05:15:00Z").toEpochMilli()
    @Before fun reset() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("living_voice_v2",Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir,"living-voice-bank-v2.json").delete()
    }
    private fun entry(id:String,topic:String="affection",kind:String="authored_companion_voice",
                      conditions:JSONArray=JSONArray(),windows:JSONArray=JSONArray("[[0,1440]]")):JSONObject =
        JSONObject().put("id",id).put("topic",topic).put("text","A complete distinct message $id.")
            .put("kind",kind).put("surfaces",JSONArray("[\"home\",\"welcome\",\"notification\"]"))
            .put("conditions_all",conditions).put("required_fields",JSONArray()).put("local_minute_windows",windows)
    private fun bank(version:Int=100, rows:List<JSONObject> = (1..6).map {entry("entry-$it")}):String =
        JSONObject().put("id","private-test-bank").put("schema","world-voice-bank-v2")
            .put("timezone","Asia/Shanghai").put("version",version).put("entry_count",rows.size)
            .put("entries",JSONArray(rows)).toString()
    private fun install(store:LivingVoiceStore,raw:String):Boolean {
        val b=JSONObject(raw)
        return store.accept(JSONObject().put("id",b.getString("id")).put("version",b.getInt("version"))
            .put("entry_count",b.getInt("entry_count")).put("bytes",raw.toByteArray().size)
            .put("sha256",LivingVoiceStore.digest(raw)),raw)
    }
    @Test fun reopening_same_hour_rotates_and_reopen_of_store_keeps_history() {
        val store=LivingVoiceStore(context);assertTrue(install(store,bank()))
        var last=""
        repeat(20) {
            val current=LivingVoiceStore(context);current.beginVisit()
            val card=current.select("home",now=now)!!
            assertNotEquals(last,card.getString("text"))
            assertEquals(card.getString("selection_id"),current.select("home",now=now)!!.getString("selection_id"))
            assertTrue(current.displayed(card.getString("selection_id"),now))
            assertTrue(current.displayed(card.getString("selection_id"),now))
            last=card.getString("text")
        }
    }
    @Test fun hour_boundary_rotates_without_new_visit_and_unrendered_is_not_counted() {
        val store=LivingVoiceStore(context);assertTrue(install(store,bank()))
        store.beginVisit();val a=store.select("home",now=now)!!
        assertTrue(store.displayed(a.getString("selection_id"),now))
        val b=store.select("home",now=now+3600000)!!
        assertNotEquals(a.getString("text"),b.getString("text"))
    }
    @Test fun bogus_receipts_unknown_predicates_and_placeholders_never_render() {
        val store=LivingVoiceStore(context)
        assertTrue(install(store,bank(rows=listOf(entry("safe-a"),entry("safe-b"),
            entry("receipt",kind="receipt_bound"),entry("unknown",conditions=JSONArray("[\"not_a_real_fact\"]")),
            entry("fields").put("text","App {{app}} locked").put("required_fields",JSONArray("[\"app\"]"))))))
        repeat(12) {
            store.beginVisit();val c=store.select("home",JSONObject("{\"gate_block_receipt_confirmed\":true}"),now)!!
            assertTrue(c.getString("id").startsWith("safe-"));store.displayed(c.getString("selection_id"),now)
        }
    }
    @Test fun shanghai_filter_does_not_depend_on_system_timezone() {
        val old=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val store=LivingVoiceStore(context)
            assertTrue(install(store,bank(rows=listOf(entry("safe-a"),entry("safe-b"),
                entry("afternoon",windows=JSONArray("[[840,1080]]"))))))
            val midnight=Instant.parse("2026-10-27T16:10:00Z").toEpochMilli()
            repeat(8) {store.beginVisit();val c=store.select("home",now=midnight)!!
                assertNotEquals("afternoon",c.getString("id"));store.displayed(c.getString("selection_id"),midnight)}
        } finally {TimeZone.setDefault(old)}
    }
    @Test fun corrupt_and_older_downloads_keep_the_complete_cached_bank() {
        val store=LivingVoiceStore(context);val raw=bank()
        assertTrue(install(store,raw));assertFalse(install(store,bank(99)))
        assertFalse(store.accept(JSONObject().put("sha256","incorrect").put("bytes",raw.toByteArray().size),raw))
        assertEquals(100,store.diagnostics().getInt("version"))
        assertEquals(6,store.diagnostics().getInt("entry_count"))
    }
}
