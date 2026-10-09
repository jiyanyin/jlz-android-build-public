package dev.jlz.presence.runtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** At-most-once device execution across endpoint failover and process restarts.
 * A crash after reservation is reported as uncertain, never blindly replayed. */
class CommandExecutionLedger(context: Context): SQLiteOpenHelper(context.applicationContext,"bridge_execution.db",null,1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE executions (id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, ok INTEGER, result TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun key(c: RuntimeCommand): String {
        // Inbox command ID is the stable message ID. A multi-message batch intentionally shares intent_id.
        val identity = if(c.action == "leave_inbox_message") c.id else c.intentId?.takeIf { it.isNotBlank() } ?: c.id
        return c.deviceId+":"+identity+":"+c.action
    }
    @Synchronized fun reserve(c: RuntimeCommand): Pair<Boolean,String>? {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT ok,result FROM executions WHERE id=?",arrayOf(key(c))).use {
                if(it.moveToFirst()) return Pair(!it.isNull(0) && it.getInt(0)==1,
                    if(it.isNull(1)) "execution_uncertain_after_restart; issue a new intent only after review" else it.getString(1))
            }
            db.execSQL("INSERT INTO executions(id,at_ms) VALUES(?,?)",arrayOf(key(c),System.currentTimeMillis()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return null
    }
    @Synchronized fun finish(c: RuntimeCommand, result: Pair<Boolean,String>) {
        writableDatabase.execSQL("UPDATE executions SET ok=?,result=? WHERE id=?",arrayOf(if(result.first) 1 else 0,result.second.take(65536),key(c)))
    }
}
