package dev.jlz.presence.study

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StudyActiveSegment(
    val startedAtMs: Long,
    val endedAtMs: Long
)

data class StudySessionMetrics(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val totalSessionMs: Long,
    val unpausedMs: Long,
    val effectiveStudyMs: Long,
    val targetPackages: Set<String>
)

class StudyMetricsStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE study_segments(
                session_id TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                ended_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE study_sessions(
                session_id TEXT PRIMARY KEY,
                started_at_ms INTEGER NOT NULL,
                ended_at_ms INTEGER NOT NULL,
                total_session_ms INTEGER NOT NULL,
                unpaused_ms INTEGER NOT NULL,
                effective_study_ms INTEGER NOT NULL,
                target_packages TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_study_segments_session ON study_segments(session_id, started_at_ms)"
        )
        db.execSQL(
            "CREATE INDEX idx_study_sessions_end ON study_sessions(ended_at_ms DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun recordSegment(
        sessionId: String,
        startedAtMs: Long,
        endedAtMs: Long
    ) {
        if (sessionId.isBlank()) return
        if (endedAtMs - startedAtMs < 250L) return
        writableDatabase.insert(
            "study_segments",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("started_at_ms", startedAtMs)
                put("ended_at_ms", endedAtMs)
            }
        )
    }

    @Synchronized
    fun segments(sessionId: String): List<StudyActiveSegment> {
        val result = mutableListOf<StudyActiveSegment>()
        readableDatabase.query(
            "study_segments",
            arrayOf("started_at_ms", "ended_at_ms"),
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "started_at_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StudyActiveSegment(
                    startedAtMs = cursor.getLong(0),
                    endedAtMs = cursor.getLong(1)
                )
            }
        }
        return result
    }

    @Synchronized
    fun save(metrics: StudySessionMetrics) {
        writableDatabase.insertWithOnConflict(
            "study_sessions",
            null,
            ContentValues().apply {
                put("session_id", metrics.sessionId)
                put("started_at_ms", metrics.startedAtMs)
                put("ended_at_ms", metrics.endedAtMs)
                put("total_session_ms", metrics.totalSessionMs)
                put("unpaused_ms", metrics.unpausedMs)
                put("effective_study_ms", metrics.effectiveStudyMs)
                put("target_packages", metrics.targetPackages.joinToString(","))
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    companion object {
        private const val DB_NAME = "jlz_study_metrics.db"
        private const val DB_VERSION = 1
    }
}
