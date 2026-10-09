package dev.jlz.presence.runtime

import android.content.Context
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Text/theme only. Gate authorization and release rules never read this store. */
class WorldContentCache(context: Context) {
    private val prefs = context.getSharedPreferences("world_content_v1", Context.MODE_PRIVATE)
    fun displayed(keys: List<String>) {
        runCatching {
            val doc=JSONObject(prefs.getString("content","{}")!!)
            if(!doc.has("revision")) return
            val receipt=JSONObject().put("space_id","world-between-primary").put("surface","native")
                .put("device_scope",doc.optString("device_scope","shared")).put("revision",doc.getLong("revision"))
                .put("view_id",doc.getString("view_id")).put("applied_keys",org.json.JSONArray(keys))
            prefs.edit().putString("pending_receipt",receipt.toString()).apply()
        }
    }
    fun flushReceipt(api: RuntimeApiClient, deviceId:String) {
        val raw=prefs.getString("pending_receipt",null) ?: return
        val receipt=JSONObject(raw).put("device_id",deviceId)
        try { api.contentReceipt(receipt); if(prefs.getString("pending_receipt",null)==raw) prefs.edit().remove("pending_receipt").apply() }
        catch(e: IllegalStateException) { if(e.message?.contains("HTTP 409")==true) prefs.edit().remove("pending_receipt").apply() else throw e }
    }
    fun save(content: JSONObject) {
        if (!content.has("revision") || !content.has("entries")) return
        val old = runCatching { JSONObject(prefs.getString("content", "{}")!!) }.getOrDefault(JSONObject())
        if (content.optLong("revision") < old.optLong("revision")) return
        prefs.edit().putString("content", content.toString()).apply()
    }
    fun text(key: String, fallback: String): String = runCatching {
        val doc = JSONObject(prefs.getString("content", "{}")!!)
        val rows = doc.optJSONArray("entries") ?: return fallback
        val now = OffsetDateTime.now(ZoneOffset.ofHours(8))
        val part = when(now.hour) { in 5..10 -> "morning"; in 11..13 -> "noon"; in 14..17 -> "afternoon"; in 18..21 -> "evening"; else -> "night" }
        val scope = doc.optString("device_scope", "shared")
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
            it.optString("key") == key && it.optString("device_scope") in listOf("shared",scope) &&
            it.optString("effective_date") in listOf("",now.toLocalDate().toString()) &&
            it.optString("daypart") in listOf("",part) &&
            (it.optString("expires_at").isBlank() || OffsetDateTime.parse(it.getString("expires_at")).isAfter(now))
        }.sortedWith(compareBy<JSONObject> { it.optString("device_scope") != "shared" }
            .thenBy { it.optString("effective_date").isNotBlank() }.thenBy { it.optString("daypart").isNotBlank() }.thenBy { it.optLong("revision") })
            .lastOrNull()?.optString("value")?.takeIf { it.isNotBlank() } ?: fallback
    }.getOrDefault(fallback)
}
