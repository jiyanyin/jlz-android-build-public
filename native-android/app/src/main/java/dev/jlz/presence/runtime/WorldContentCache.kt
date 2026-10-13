package dev.jlz.presence.runtime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Content/appearance only. Gate permission and release policy never read this store.
 * Receiving a version is NOT proof of rendering; only native screens that
 * actually compose their text/theme may queue an applied-key receipt.
 */
class WorldContentCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("world_content_v1", Context.MODE_PRIVATE)

    private fun pending(): MutableList<JSONObject> {
        val rows = runCatching { JSONArray(prefs.getString("pending_receipts", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.toMutableList()
    }
    private fun encode(rows: List<JSONObject>) =
        JSONArray().apply { rows.forEach { put(it) } }.toString()

    /**
     * One receipt per visible surface and effective content view, not one slot
     * for the whole app. Offline callback -> Gate -> study must not overwrite.
     */
    fun displayed(keys: List<String>) {
        synchronized(RECEIPT_LOCK) {
            runCatching {
                val doc = JSONObject(prefs.getString("content", "{}")!!)
                if (!doc.has("revision") || !doc.has("view_id")) return
                val applied = keys.distinct().filter { text(it, "").isNotBlank() }
                if (applied.isEmpty()) return
                val id = doc.optLong("revision").toString() + ":" +
                    doc.optString("view_id") + ":" + applied.sorted().joinToString(",")
                val acknowledged = prefs.getStringSet("acknowledged_receipts", emptySet())
                    .orEmpty()
                if (id in acknowledged) return
                val backlog = pending()
                if (backlog.any { it.optString("local_receipt_id") == id }) return
                if (backlog.size >= MAX_PENDING) {
                    // Do not silently evict an unsent proof. Expose overflow in
                    // diagnostics for final device acceptance instead.
                    prefs.edit().putInt("receipt_overflow_count",
                        prefs.getInt("receipt_overflow_count", 0) + 1).commit()
                    return
                }
                val receipt = JSONObject()
                    .put("space_id", "world-between-primary")
                    .put("surface", "native")
                    .put("device_scope", doc.optString("device_scope", "shared"))
                    .put("revision", doc.getLong("revision"))
                    .put("view_id", doc.getString("view_id"))
                    .put("applied_keys", JSONArray(applied))
                    .put("local_receipt_id", id)
                backlog += receipt
                check(prefs.edit().putString("pending_receipts", encode(backlog)).commit()) {
                    "content_receipt_persist_failed"
                }
            }
        }
    }

    /** Flush only the first queued observation and only remove it on server ACK.
     * A concurrent UI surface may append while the network call is in flight.
     */
    fun flushReceipt(api: RuntimeApiClient, deviceId: String) =
        flushPending(deviceId) { api.contentReceipt(it).optBoolean("ok", false) }

    /** Testable network boundary; failures retain the same queued receipt. */
    internal fun flushPending(deviceId: String, send: (JSONObject) -> Boolean) {
        val row = synchronized(RECEIPT_LOCK) { pending().firstOrNull()?.let { JSONObject(it.toString()) } }
            ?: return
        val id = row.optString("local_receipt_id")
        val request = JSONObject(row.toString()).put("device_id", deviceId)
        request.remove("local_receipt_id")
        var staleVersion = false
        val confirmed = try {
            send(request)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("HTTP 409") != true) throw e
            staleVersion = true
            false
        }
        if (!confirmed && !staleVersion) return
        synchronized(RECEIPT_LOCK) {
            val backlog = pending()
            if (backlog.firstOrNull()?.optString("local_receipt_id") != id) return
            backlog.removeAt(0)
            val edit = prefs.edit().putString("pending_receipts", encode(backlog))
            if (confirmed) {
                val seen = prefs.getStringSet("acknowledged_receipts", emptySet())
                    .orEmpty().toMutableSet()
                // Bounded device-local dedupe; eventual redisplay of an old
                // version is harmless but an unacknowledged record never counts.
                if (seen.size >= MAX_ACKNOWLEDGED) seen.clear()
                seen += id
                edit.putStringSet("acknowledged_receipts", seen)
            }
            edit.commit()
        }
    }

    fun receiptDiagnostics(): JSONObject = synchronized(RECEIPT_LOCK) {
        JSONObject().put("pending_native_receipts", pending().size)
            .put("receipt_overflow_count", prefs.getInt("receipt_overflow_count", 0))
    }

    fun save(content: JSONObject): Boolean = synchronized(RECEIPT_LOCK) {
        if (!content.has("revision") || !content.has("entries") ||
            !content.has("view_id") || content.optString("view_id").isBlank()) return@synchronized false
        val old = runCatching { JSONObject(prefs.getString("content", "{}")!!) }
            .getOrDefault(JSONObject())
        if (content.optLong("revision") < old.optLong("revision")) return@synchronized false
        prefs.edit().putString("content", content.toString()).commit()
    }

    fun text(key: String, fallback: String): String = runCatching {
        val doc = JSONObject(prefs.getString("content", "{}")!!)
        val rows = doc.optJSONArray("entries") ?: return fallback
        val now = OffsetDateTime.now(ZoneOffset.ofHours(8))
        val part = when(now.hour) {
            in 5..10 -> "morning"; in 11..13 -> "noon"
            in 14..17 -> "afternoon"; in 18..21 -> "evening"
            else -> "night"
        }
        val scope = doc.optString("device_scope", "shared")
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
            it.optString("key") == key &&
                it.optString("device_scope") in listOf("shared", scope) &&
                it.optString("effective_date") in listOf("", now.toLocalDate().toString()) &&
                it.optString("daypart") in listOf("", part) &&
                (it.optString("expires_at").isBlank() ||
                    OffsetDateTime.parse(it.getString("expires_at")).isAfter(now))
        }.sortedWith(compareBy<JSONObject> { it.optString("device_scope") != "shared" }
            .thenBy { it.optString("effective_date").isNotBlank() }
            .thenBy { it.optString("daypart").isNotBlank() }
            .thenBy { it.optLong("revision") })
            .lastOrNull()?.optString("value")?.takeIf { it.isNotBlank() } ?: fallback
    }.getOrDefault(fallback)

    private companion object {
        const val MAX_PENDING = 64
        const val MAX_ACKNOWLEDGED = 128
        val RECEIPT_LOCK = Any()
    }
}
