package dev.jlz.presence.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class TimelineEvent(
    val id: String,
    val type: String,
    val title: String,
    val detail: String,
    val createdAtMs: Long,
    val eventId: String? = null,
    val intentId: String? = null,
    val metadataJson: String = "{}"
)

data class PendingThought(
    val id: String,
    val kind: String,
    val text: String,
    val status: String,
    val createdAtMs: Long,
    val resolvedAtMs: Long? = null
)

data class LifeEntry(
    val id: String,
    val kind: String,
    val text: String,
    val createdAtMs: Long
)

class LocalLifeStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE timeline_events(
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                event_id TEXT,
                intent_id TEXT,
                metadata_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE pending_thoughts(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                resolved_at_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE life_entries(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_timeline_created ON timeline_events(created_at_ms DESC)")
        db.execSQL("CREATE INDEX idx_pending_status ON pending_thoughts(status, created_at_ms DESC)")
        db.execSQL("CREATE INDEX idx_life_created ON life_entries(created_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun recordTimeline(
        type: String,
        title: String,
        detail: String = "",
        eventId: String? = null,
        intentId: String? = null,
        metadataJson: String = "{}",
        createdAtMs: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString()
    ): TimelineEvent {
        val event = TimelineEvent(
            id = id,
            type = type,
            title = title,
            detail = detail,
            createdAtMs = createdAtMs,
            eventId = eventId,
            intentId = intentId,
            metadataJson = metadataJson
        )
        writableDatabase.insertWithOnConflict(
            "timeline_events",
            null,
            ContentValues().apply {
                put("id", event.id)
                put("type", event.type)
                put("title", event.title)
                put("detail", event.detail)
                put("created_at_ms", event.createdAtMs)
                put("event_id", event.eventId)
                put("intent_id", event.intentId)
                put("metadata_json", event.metadataJson)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return event
    }

    @Synchronized
    fun listTimelineSince(sinceMs: Long, limit: Int = 200): List<TimelineEvent> {
        val result = mutableListOf<TimelineEvent>()
        readableDatabase.query(
            "timeline_events",
            null,
            "created_at_ms >= ?",
            arrayOf(sinceMs.toString()),
            null,
            null,
            "created_at_ms DESC",
            limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TimelineEvent(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    detail = cursor.getString(cursor.getColumnIndexOrThrow("detail")),
                    createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
                    eventId = cursor.getString(cursor.getColumnIndexOrThrow("event_id")),
                    intentId = cursor.getString(cursor.getColumnIndexOrThrow("intent_id")),
                    metadataJson = cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))
                )
            }
        }
        return result
    }

    /**
     * Audit evidence is queried separately from the general Timeline: a busy
     * study/chat history must not hide real Android notification observations.
     */
    @Synchronized
    fun listNotificationObservations(sinceMs: Long, limit: Int = 200): List<TimelineEvent> {
        val result = mutableListOf<TimelineEvent>()
        readableDatabase.query(
            "timeline_events", null,
            "created_at_ms >= ? AND type IN (?, ?, ?) AND metadata_json NOT LIKE ?",
            arrayOf(sinceMs.toString(), "notification",
                "health_notification", "notification_observation",
                "%\"package_name\":\"org.ikuuu.vpn\"%"),
            null, null, "created_at_ms DESC", limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TimelineEvent(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    detail = cursor.getString(cursor.getColumnIndexOrThrow("detail")),
                    createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
                    eventId = cursor.getString(cursor.getColumnIndexOrThrow("event_id")),
                    intentId = cursor.getString(cursor.getColumnIndexOrThrow("intent_id")),
                    metadataJson = cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))
                )
            }
        }
        return result
    }

    /** Remove screenshot-only timeline metadata from before a policy reset. */
    @Synchronized
    fun deleteScreenshotTimelineRecordsBefore(cutoffMs: Long): Int =
        writableDatabase.delete(
            "timeline_events",
            "created_at_ms < ? AND type IN (?, ?, ?)",
            arrayOf(
                cutoffMs.toString(),
                "study_screenshot",
                "life_screenshot",
                "manual_screenshot"
            )
        )

    @Synchronized
    fun addPendingThought(
        kind: String,
        text: String,
        createdAtMs: Long = System.currentTimeMillis()
    ): PendingThought {
        val thought = PendingThought(
            id = UUID.randomUUID().toString(),
            kind = kind,
            text = text,
            status = "open",
            createdAtMs = createdAtMs
        )
        writableDatabase.insert(
            "pending_thoughts",
            null,
            ContentValues().apply {
                put("id", thought.id)
                put("kind", thought.kind)
                put("text", thought.text)
                put("status", thought.status)
                put("created_at_ms", thought.createdAtMs)
            }
        )
        recordTimeline("pending_thought", "以后再聊", text)
        return thought
    }

    @Synchronized
    fun listOpenPending(limit: Int = 100): List<PendingThought> {
        val result = mutableListOf<PendingThought>()
        readableDatabase.query(
            "pending_thoughts",
            null,
            "status = ?",
            arrayOf("open"),
            null,
            null,
            "created_at_ms DESC",
            limit.coerceIn(1, 200).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PendingThought(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                    text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
                    resolvedAtMs = cursor.getColumnIndex("resolved_at_ms").let { index ->
                        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                    }
                )
            }
        }
        return result
    }

    @Synchronized
    fun resolvePending(id: String) {
        val now = System.currentTimeMillis()
        writableDatabase.update(
            "pending_thoughts",
            ContentValues().apply {
                put("status", "resolved")
                put("resolved_at_ms", now)
            },
            "id = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun addLifeEntry(
        kind: String,
        text: String,
        createdAtMs: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
        metadataJson: String = "{}"
    ): LifeEntry {
        val entry = LifeEntry(
            id = id,
            kind = kind,
            text = text,
            createdAtMs = createdAtMs
        )
        writableDatabase.insert(
            "life_entries",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("kind", entry.kind)
                put("text", entry.text)
                put("created_at_ms", entry.createdAtMs)
            }
        )
        recordTimeline(kind, kind, text, eventId = entry.id,
            metadataJson = metadataJson, createdAtMs = createdAtMs, id = entry.id)
        return entry
    }

    companion object {
        private const val DB_NAME = "jlz_presence_local.db"
        private const val DB_VERSION = 1
    }
}
