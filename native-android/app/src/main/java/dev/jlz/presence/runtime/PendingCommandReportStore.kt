package dev.jlz.presence.runtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Transport outbox only: retries a completed result, never executes an action. */
class PendingCommandReportStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "jlz_command_reports_v1.db", null, 1
) {
    companion object { private val lock = Any() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reports (backend TEXT NOT NULL, device TEXT NOT NULL, command_id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(backend, device, command_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueue(backend: String, body: JSONObject) = synchronized(lock) {
        require(body.getString("command_id").isNotBlank())
        writableDatabase.execSQL("INSERT OR IGNORE INTO reports (backend, device, command_id, body) VALUES (?, ?, ?, ?)",
            arrayOf(backend.trim().trimEnd('/'), body.getString("device_id"),
                body.getString("command_id"), body.toString()))
        Unit
    }

    internal fun pending(backend: String, device: String): List<JSONObject> = synchronized(lock) {
        readableDatabase.query("reports", arrayOf("body"), "backend=? AND device=?",
            arrayOf(backend.trim().trimEnd('/'), device), null, null, "rowid ASC", "40").use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
        }
    }

    fun sync(backend: String, device: String, send: (JSONObject) -> JSONObject) = synchronized(lock) {
        for (body in pending(backend, device)) {
            val response = try { send(body) } catch (_: Exception) { break }
            val command = response.optJSONObject("command")
            // An echoed report alone is not proof that Runtime recorded the command result.
            if (!response.optBoolean("ok") || command == null || command.optString("id") != body.getString("command_id") ||
                command.optString("status") !in setOf("completed", "failed")) break
            writableDatabase.delete("reports", "backend=? AND device=? AND command_id=?",
                arrayOf(backend.trim().trimEnd('/'), device, body.getString("command_id")))
        }
    }
}
