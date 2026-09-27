package dev.jlz.presence.notification

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.jlz.presence.runtime.RuntimeApiClient
import java.util.UUID

/** Durable, small, app-private queue for Android notification inline replies. */
class PendingReplyStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jlz_inline_replies_v1.db", null, 1) {

    data class Entry(
        val id: String, val text: String, val observedAtMs: Long,
        val parentEventId: String?, val intentId: String?
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE pending_replies (
                id TEXT PRIMARY KEY,
                text TEXT NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                parent_event_id TEXT,
                intent_id TEXT,
                delivered INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_reply_pending ON pending_replies(delivered, observed_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun keep(
        text: String,
        parentEventId: String?,
        intentId: String?,
        observedAtMs: Long = System.currentTimeMillis()
    ): Entry {
        require(text.isNotBlank() && text.length <= 1200) { "reply_text_length_invalid" }
        val reply = Entry(
            UUID.randomUUID().toString(), text, observedAtMs, parentEventId, intentId
        )
        val row = ContentValues().apply {
            put("id", reply.id)
            put("text", reply.text)
            put("observed_at_ms", reply.observedAtMs)
            put("parent_event_id", reply.parentEventId)
            put("intent_id", reply.intentId)
            put("delivered", 0)
        }
        check(writableDatabase.insertOrThrow("pending_replies", null, row) > 0) {
            "reply_local_save_failed"
        }
        return reply
    }

    @Synchronized
    fun pending(limit: Int = 20): List<Entry> {
        val rows = mutableListOf<Entry>()
        readableDatabase.query(
            "pending_replies", null, "delivered = 0", null, null, null,
            "observed_at_ms ASC, rowid ASC", limit.coerceIn(1, 40).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun field(key: String) =
                    cursor.getString(cursor.getColumnIndexOrThrow(key))
                rows += Entry(
                    id = field("id"), text = field("text"),
                    observedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")),
                    parentEventId = field("parent_event_id"),
                    intentId = field("intent_id")
                )
            }
        }
        return rows
    }

    /** Return ids confirmed by Runtime; network errors leave all remaining replies pending. */
    @Synchronized
    fun sync(api: RuntimeApiClient, limit: Int = 20): List<String> {
        val confirmed = mutableListOf<String>()
        for (item in pending(limit)) {
            val response = try {
                api.postInboxMessage(
                    text = item.text, role = "user",
                    eventId = item.parentEventId, intentId = item.intentId,
                    notify = false, messageId = item.id,
                    createdAtMs = item.observedAtMs
                )
            } catch (_: Exception) { break }
            if (response.id != item.id || response.text != item.text) break
            writableDatabase.update("pending_replies",
                ContentValues().apply { put("delivered", 1) },
                "id = ?", arrayOf(item.id))
            confirmed += item.id
        }
        return confirmed
    }
}
