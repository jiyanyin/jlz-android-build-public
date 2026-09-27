package dev.jlz.presence.notification

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.jlz.presence.runtime.RuntimeApiClient
import org.json.JSONObject

/** Small durable Android-notification transport outbox, not GPT memory. */
class PendingNotificationEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jlz_notification_outbox_v1.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE pending_notifications (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                package_name TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_notifications_pending ON pending_notifications(delivered)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun enqueue(
        id: String, kind: String, title: String, body: String,
        packageName: String, metadata: JSONObject
    ) {
        val existing = readableDatabase.rawQuery(
            "SELECT delivered FROM pending_notifications WHERE id = ?",
            arrayOf(id)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
        if (existing != null) return
        val data = ContentValues().apply {
            put("id", id)
            put("kind", kind)
            put("title", title.take(100))
            put("body", body.take(1800))
            put("package_name", packageName.take(180))
            put("metadata_json", metadata.toString())
            put("delivered", 0)
        }
        writableDatabase.insertWithOnConflict(
            "pending_notifications", null, data, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    @Synchronized
    fun sync(api: RuntimeApiClient, limit: Int = 25): Int {
        var count = 0
        readableDatabase.query(
            "pending_notifications", null, "delivered = 0",
            null, null, null, "rowid ASC", limit.coerceIn(1, 50).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun field(key: String): String =
                    cursor.getString(cursor.getColumnIndexOrThrow(key))
                val id = field("id")
                val response = try {
                    api.postActivityEvent(
                        source = "android_notification",
                        type = field("kind"),
                        title = field("title"),
                        subtitle = field("body"),
                        metadata = JSONObject(field("metadata_json")),
                        eventId = id,
                        sourcePackage = field("package_name")
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
}
