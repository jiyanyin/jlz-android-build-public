package dev.jlz.presence.trip

import org.json.JSONObject

/** A batch is durable only when Runtime acknowledges the exact same point IDs.
 * A mere HTTP 200, ok=true, or equal count is insufficient.
 */
internal object TripUploadPolicy {
    const val MAX_SESSION_MS = 24L * 60 * 60_000L
    const val POINT_LIMIT = 2000
    fun dueToExpire(nowMs: Long, startedAtMs: Long): Boolean =
        startedAtMs > 0L && nowMs - startedAtMs >= MAX_SESSION_MS

    fun accepted(reply: JSONObject, expectedPointIds: List<String>): Boolean {
        if (!reply.optBoolean("ok", false)) return false
        val echoed = reply.optJSONArray("point_ids") ?: return false
        if (echoed.length() != expectedPointIds.size) return false
        return expectedPointIds.indices.all { index ->
            echoed.optString(index, "") == expectedPointIds[index]
        }
    }
}
