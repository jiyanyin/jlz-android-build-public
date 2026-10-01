package dev.jlz.presence.usage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.jlz.presence.runtime.RuntimeApiClient
import org.json.JSONObject

/**
 * One durable outbox for lightweight structured activity events.
 *
 * The database name/table stay compatible with the former notification-only
 * queue so upgrades retain unsent notification observations. Images, inbox
 * messages and command reports keep their own payload-specific durable stores.
 */
class PendingActivityEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE pending_notifications (
                id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                package_name TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_notifications_pending ON pending_notifications(delivered, observed_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN source TEXT NOT NULL DEFAULT 'android_notification'")
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN observed_at_ms INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized
    fun enqueue(
        id: String,
        source: String,
        kind: String,
        title: String,
        body: String,
        packageName: String = "",
        metadata: JSONObject = JSONObject(),
        observedAtMs: Long = System.currentTimeMillis()
    ) {
        val existing = readableDatabase.rawQuery(
            "SELECT delivered FROM pending_notifications WHERE id = ?",
            arrayOf(id)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
        if (existing != null) return
        val data = ContentValues().apply {
            put("id", id)
            put("source", source.take(40))
            put("kind", kind.take(40))
            put("title", title.take(100))
            put("body", body.take(1800))
            put("package_name", packageName.take(180))
            put("metadata_json", JSONObject(metadata.toString())
                .put("observed_at_ms", observedAtMs).toString())
            put("observed_at_ms", observedAtMs)
            put("delivered", 0)
        }
        writableDatabase.insertWithOnConflict(
            "pending_notifications", null, data, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    @Synchronized
    fun sync(api: RuntimeApiClient, limit: Int = 50): Int {
        var count = 0
        readableDatabase.query(
            "pending_notifications", null, "delivered = 0",
            null, null, null, "observed_at_ms ASC, rowid ASC",
            limit.coerceIn(1, 100).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun field(key: String): String =
                    cursor.getString(cursor.getColumnIndexOrThrow(key))
                val id = field("id")
                val response = try {
                    api.postActivityEvent(
                        source = field("source"),
                        type = field("kind"),
                        title = field("title"),
                        subtitle = field("body"),
                        metadata = JSONObject(field("metadata_json")),
                        eventId = id,
                        sourcePackage = field("package_name").takeIf { it.isNotBlank() }
                    )
                } catch (_: Exception) { break }
                if (!response.optBoolean("ok", false) ||
                    response.optJSONObject("event")?.optString("id") != id) break
                writableDatabase.update(
                    "pending_notifications",
                    ContentValues().apply { put("delivered", 1) },
                    "id = ?", arrayOf(id)
                )
                count++
            }
        }
        return count
    }

    companion object {
        private const val DB_NAME = "jlz_notification_outbox_v1.db"
        private const val DB_VERSION = 2
    }
}
