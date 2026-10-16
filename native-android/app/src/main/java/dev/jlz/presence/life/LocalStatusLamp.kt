package dev.jlz.presence.life

import android.content.Context
import org.json.JSONObject

data class LocalStatusAxes(val selfPresence: Int?, val agitation: Int?, val observedAtMs: Long)

/** Explicit status-light values only. Free text and private details stay out of copy selection. */
class LocalStatusLamp(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_local_status_lamp_v1", Context.MODE_PRIVATE)
    fun record(raw: String, nowMs: Long = System.currentTimeMillis()): Boolean = runCatching {
        require(raw.length <= 500)
        val input = JSONObject(raw)
        val at = input.optLong("at_ms", 0L)
        require(nowMs - at in -5 * 60_000L..(4 * 60 * 60_000L))
        val axes = input.optJSONObject("axes") ?: JSONObject()
        val allowed = setOf("self_presence", "emotion_access", "emotional_vividness", "agitation")
        require(axes.keys().asSequence().all { it in allowed })
        val clean = JSONObject()
        allowed.forEach { key ->
            if (axes.has(key)) {
                val value = axes.getInt(key)
                require(value in 0..100)
                clean.put(key, value)
            }
        }
        prefs.edit().putString("axes", clean.toString()).putLong("at_ms", at).commit()
    }.getOrDefault(false)

    fun fresh(nowMs: Long = System.currentTimeMillis()): LocalStatusAxes? {
        val at = prefs.getLong("at_ms", 0L)
        if (at <= 0L || nowMs - at !in 0..(4 * 60 * 60_000L)) return null
        val axes = prefs.getString("axes", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return LocalStatusAxes(
            axes.optInt("self_presence", -1).takeIf { it in 0..100 },
            axes.optInt("agitation", -1).takeIf { it in 0..100 }, at)
    }
}
