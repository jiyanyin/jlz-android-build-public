package dev.jlz.presence.runtime

import android.content.Context
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Text/theme only. Gate authorization and release rules never read this store. */
class WorldContentCache(context: Context) {
    private val prefs = context.getSharedPreferences("world_content_v1", Context.MODE_PRIVATE)
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
