package dev.jlz.presence.between

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.jlz.presence.runtime.RuntimeApiClient
import org.json.JSONObject

/**
 * Local-first transport outbox for P0-2 status/moment events.
 * This is not chat/inbox storage: ordinary life moments must not become
 * unanswered messages just because the phone was offline.
 */
class BetweenOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jlz_between_outbox_v1.db", null, 1) {

    data class Entry(
        val id: String,
        val kind: String,
        val payloadJson: String,
        val createdAtMs: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pending_between (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_between_pending ON pending_between(delivered, created_at_ms)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun enqueue(id: String, kind: String, payload: JSONObject, createdAtMs: Long) {
        require(id.isNotBlank()) { "between_event_id_required" }
        require(kind == "status" || kind == "moment") { "between_kind_invalid" }
        writableDatabase.insertWithOnConflict(
            "pending_between",
            null,
            ContentValues().apply {
                put("id", id)
                put("kind", kind)
                put("payload_json", payload.toString())
                put("created_at_ms", createdAtMs)
                put("delivered", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    @Synchronized
    fun pending(limit: Int = 40): List<Entry> {
        val rows = mutableListOf<Entry>()
        readableDatabase.query(
            "pending_between", null, "delivered = 0",
            null, null, null, "created_at_ms ASC, rowid ASC",
            limit.coerceIn(1, 80).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Entry(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                    payloadJson = cursor.getString(cursor.getColumnIndexOrThrow("payload_json")),
                    createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms"))
                )
            }
        }
        return rows
    }

    /** Latest local snapshot, including a record already acknowledged remotely. */
    @Synchronized
    fun latest(kind: String): JSONObject? {
        readableDatabase.query(
            "pending_between", arrayOf("payload_json"), "kind = ?",
            arrayOf(kind), null, null, "created_at_ms DESC, rowid DESC", "1"
        ).use { rows ->
            if (!rows.moveToFirst()) return null
            return runCatching { JSONObject(rows.getString(0)) }.getOrNull()
        }
    }

    @Synchronized
    fun pendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM pending_between WHERE delivered = 0", null
        ).use { rows ->
            return if (rows.moveToFirst()) rows.getInt(0) else 0
        }
    }

    @Synchronized
    fun sync(api: RuntimeApiClient, limit: Int = 40): List<String> {
        val confirmed = mutableListOf<String>()
        for (item in pending(limit)) {
            val payload = try { JSONObject(item.payloadJson) } catch (_: Exception) { break }
            val response = try {
                when (item.kind) {
                    "status" -> api.postBetweenStatus(payload)
                    "moment" -> api.postBetweenMoment(payload)
                    else -> break
                }
            } catch (_: Exception) {
                break
            }

            val eventId = response.optJSONObject("event")?.optString("id").orEmpty()
            if (eventId != item.id) break
            if (item.kind == "status") {
                // Legacy Runtime accepted the record ID but silently DROPPED
                // the new emotion/energy/need dimensions. Do not call this
                // delivered until the durable server echoes every supplied
                // field from its actually persisted snapshot.
                val saved = response.optJSONObject("snapshot") ?: break
                val fields = listOf(
                    "state", "detail", "energy", "need", "response_level",
                    "emotions", "mental_energy", "physical_energy", "attention",
                    "body_signals", "response_style", "avoid"
                )
                val allFieldsPersisted = fields.all { field ->
                    if (!payload.has(field)) true else {
                        val original = payload.opt(field)
                        val confirmed = saved.opt(field)
                        when {
                            original is org.json.JSONArray &&
                                confirmed is org.json.JSONArray ->
                                    original.toString() == confirmed.toString()
                            original == null || confirmed == null -> false
                            else -> original.toString() == confirmed.toString()
                        }
                    }
                }
                if (!allFieldsPersisted) break
            }

            writableDatabase.update(
                "pending_between",
                ContentValues().apply { put("delivered", 1) },
                "id = ?",
                arrayOf(item.id)
            )
            confirmed += item.id
        }
        return confirmed
    }
}
