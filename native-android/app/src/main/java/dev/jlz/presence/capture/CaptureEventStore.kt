package dev.jlz.presence.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.UUID

/**
 * Phone-side source-of-truth for capture actions until the durable Runtime
 * event/discussion API exists. Do not mix this journal with Identity/Memory.
 */
data class CaptureEvent(
    val id: String,
    val kind: String,
    val rawText: String,
    val observedAtMs: Long,
    val originPackage: String?,
    val studySessionId: String?,
    val mode: String,
    val suggestedDomainsJson: String,
    val deliveryStatus: String,
    val discussionStatus: String,
    val remoteFilename: String?,
    val detail: String
)

object CaptureEventClassifier {
    fun suggestions(kind: String, text: String, studySessionId: String?): List<String> {
        val domains = linkedSetOf<String>()
        if (kind == "screenshot") domains += "screenshot"
        if (kind == "distraction") domains += "self_reported_distraction"
        if (!studySessionId.isNullOrBlank()) domains += "study_experience"
        if (Regex("学习|刷题|做题|错题|行测|申论|复盘|资料分析").containsMatchIn(text)) {
            domains += "study_experience"
        }
        if (Regex("吃了|吃饭|早餐|午饭|晚饭|午餐|晚餐|喝了").containsMatchIn(text)) {
            domains += "life_meal"
        }
        if (Regex("花了|买了|消费|付款|支出|元|块钱").containsMatchIn(text)) {
            domains += "spending_candidate"
        }
        if (Regex("想聊|想说|讨论|以后聊|记一下").containsMatchIn(text)) {
            domains += "to_discuss"
        }
        if (domains.isEmpty()) domains += "unclassified"
        return domains.toList()
    }
}

class CaptureEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE capture_events(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                origin_package TEXT,
                study_session_id TEXT,
                mode TEXT NOT NULL,
                suggested_domains_json TEXT NOT NULL,
                delivery_status TEXT NOT NULL,
                discussion_status TEXT NOT NULL,
                remote_filename TEXT,
                detail TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_capture_time ON capture_events(observed_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun add(
        kind: String,
        text: String = "",
        originPackage: String? = null,
        studySessionId: String? = null,
        mode: String = "LIFE",
        deliveryStatus: String = "local_only",
        detail: String = "",
        id: String = UUID.randomUUID().toString(),
        observedAtMs: Long = System.currentTimeMillis()
    ): CaptureEvent {
        val domains = CaptureEventClassifier.suggestions(kind, text, studySessionId)
        val event = CaptureEvent(
            id = id,
            kind = kind,
            rawText = text,
            observedAtMs = observedAtMs,
            originPackage = originPackage,
            studySessionId = studySessionId,
            mode = mode,
            suggestedDomainsJson = JSONArray(domains).toString(),
            deliveryStatus = deliveryStatus,
            discussionStatus = "new",
            remoteFilename = null,
            detail = detail
        )
        writableDatabase.insertWithOnConflict(
            "capture_events", null,
            ContentValues().apply {
                put("id", event.id)
                put("kind", event.kind)
                put("raw_text", event.rawText)
                put("observed_at_ms", event.observedAtMs)
                put("origin_package", event.originPackage)
                put("study_session_id", event.studySessionId)
                put("mode", event.mode)
                put("suggested_domains_json", event.suggestedDomainsJson)
                put("delivery_status", event.deliveryStatus)
                put("discussion_status", event.discussionStatus)
                put("remote_filename", event.remoteFilename)
                put("detail", event.detail)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        return event
    }

    @Synchronized
    fun updateDelivery(id: String, status: String, remoteFilename: String? = null, detail: String = "") {
        require(status in setOf("local_only", "upload_pending", "upload_confirmed", "upload_failed", "inbox_confirmed", "local_transport_released"))
        writableDatabase.update(
            "capture_events",
            ContentValues().apply {
                put("delivery_status", status)
                put("remote_filename", remoteFilename)
                put("detail", detail.take(240))
            },
            "id = ?",
            arrayOf(id)
        )
    }

    /**
     * A note is stored locally first, then mirrored to Runtime inbox with
     * the exact same UUID. Retrying after a timeout is idempotent on the
     * existing Runtime server; do not discard a note on network failure.
     */
    @Synchronized
    fun pendingNotes(limit: Int = 20): List<CaptureEvent> {
        val result = mutableListOf<CaptureEvent>()
        readableDatabase.query(
            "capture_events", null,
            "kind = ? AND delivery_status IN (?, ?, ?)",
            arrayOf("note", "local_only", "upload_pending", "upload_failed"),
            null, null,
            "observed_at_ms ASC, rowid ASC",
            limit.coerceIn(1, 50).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun str(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
                result += CaptureEvent(
                    id = str("id"),
                    kind = str("kind"),
                    rawText = str("raw_text"),
                    observedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")),
                    originPackage = str("origin_package"),
                    studySessionId = str("study_session_id"),
                    mode = str("mode"),
                    suggestedDomainsJson = str("suggested_domains_json"),
                    deliveryStatus = str("delivery_status"),
                    discussionStatus = str("discussion_status"),
                    remoteFilename = str("remote_filename"),
                    detail = str("detail")
                )
            }
        }
        return result
    }

    @Synchronized
    fun recent(limit: Int = 30): List<CaptureEvent> {
        val result = mutableListOf<CaptureEvent>()
        readableDatabase.query(
            "capture_events", null, null, null, null, null,
            "observed_at_ms DESC, rowid DESC", limit.coerceIn(1, 200).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun str(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
                result += CaptureEvent(
                    id = str("id"),
                    kind = str("kind"),
                    rawText = str("raw_text"),
                    observedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")),
                    originPackage = str("origin_package"),
                    studySessionId = str("study_session_id"),
                    mode = str("mode"),
                    suggestedDomainsJson = str("suggested_domains_json"),
                    deliveryStatus = str("delivery_status"),
                    discussionStatus = str("discussion_status"),
                    remoteFilename = str("remote_filename"),
                    detail = str("detail")
                )
            }
        }
        return result
    }

    /** Owner-authorized test-image reset; do not touch text notes or focus records. */
    @Synchronized
    fun deleteScreenshotRecordsBefore(cutoffMs: Long): Int =
        writableDatabase.delete(
            "capture_events",
            "kind = ? AND observed_at_ms < ?",
            arrayOf("screenshot", cutoffMs.toString())
        )

    companion object {
        private const val DB_NAME = "jlz_presence_capture_v1.db"
    }
}
