package dev.jlz.presence.notification

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Bounded fingerprints only; notification text stays in the filtered timeline. */
class NotificationLifecycleIndex(context: Context) {
    private val prefs = context.getSharedPreferences("jlz_notification_lifecycle_v1", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun transition(previousFingerprint: String?, wasRemoved: Boolean, fingerprint: String): String =
            if (previousFingerprint == null || wasRemoved) "posted"
            else if (previousFingerprint == fingerprint) "duplicate" else "updated"
    }
    fun posted(key: String, safeContent: String, now: Long): String = synchronized(lock) {
        val doc = runCatching { JSONObject(prefs.getString("index", "{}")!!) }.getOrDefault(JSONObject())
        val id = fingerprint(key)
        val old = doc.optJSONObject(id)
        val fp = fingerprint(safeContent)
        val kind = transition(old?.optString("fingerprint"), old?.optBoolean("removed") ?: false, fp)
        if (kind == "duplicate") return@synchronized kind
        doc.put(id, JSONObject().put("fingerprint", fp).put("at", now).put("removed", false))
        save(doc, now)
        kind
    }
    fun removed(key: String, now: Long): Boolean = synchronized(lock) {
        val doc = runCatching { JSONObject(prefs.getString("index", "{}")!!) }.getOrDefault(JSONObject())
        val id = fingerprint(key)
        val old = doc.optJSONObject(id) ?: return@synchronized false
        if (old.optBoolean("removed")) return@synchronized false
        old.put("removed", true).put("at", now)
        save(doc, now)
        true
    }
    private fun save(doc: JSONObject, now: Long) {
        val keys = doc.keys().asSequence().toList()
        keys.filter { now-doc.optJSONObject(it).optLong("at") > 48L*60L*60L*1000L }.forEach { doc.remove(it) }
        doc.keys().asSequence().toList().sortedByDescending { doc.optJSONObject(it).optLong("at") }
            .drop(256).forEach { doc.remove(it) }
        check(prefs.edit().putString("index", doc.toString()).commit()) { "notification_index_save_failed" }
    }
}
