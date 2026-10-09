package dev.jlz.presence.usage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class AppUsageTotal(
    val packageName: String,
    val durationMs: Long
)

class ForegroundUsageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE usage_segments(
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                ended_at_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_usage_time ON usage_segments(started_at_ms, ended_at_ms)")
        db.execSQL("CREATE INDEX idx_usage_package ON usage_segments(package_name, started_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(packageName: String, startMs: Long, endMs: Long) {
        val duration = (endMs - startMs).coerceAtLeast(0L)
        if (packageName.isBlank() || duration < 250L) return
        writableDatabase.insert(
            "usage_segments",
            null,
            ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("package_name", packageName)
                put("started_at_ms", startMs)
                put("ended_at_ms", endMs)
                put("duration_ms", duration)
            }
        )
    }

    @Synchronized
    fun totalsSince(sinceMs: Long, limit: Int = 30): List<AppUsageTotal> {
        val result = mutableListOf<AppUsageTotal>()
        readableDatabase.rawQuery(
            """
            SELECT package_name, SUM(duration_ms) AS total_ms
            FROM usage_segments
            WHERE ended_at_ms >= ?
            GROUP BY package_name
            ORDER BY total_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(sinceMs.toString(), limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AppUsageTotal(
                    packageName = cursor.getString(0),
                    durationMs = cursor.getLong(1)
                )
            }
        }
        return result
    }

    @Synchronized
    fun totalsInWindow(startMs: Long, endMs: Long, limit: Int = 12): List<AppUsageTotal> {
        if (endMs <= startMs) return emptyList()
        val result = mutableListOf<AppUsageTotal>()
        readableDatabase.rawQuery(
            """SELECT package_name, SUM(MAX(0, MIN(ended_at_ms, CAST(? AS INTEGER)) - MAX(started_at_ms, CAST(? AS INTEGER)))) AS total_ms
               FROM usage_segments WHERE started_at_ms < ? AND ended_at_ms > ?
               GROUP BY package_name ORDER BY total_ms DESC LIMIT ?""",
            arrayOf(endMs.toString(), startMs.toString(), endMs.toString(), startMs.toString(), limit.coerceIn(1,100).toString())
        ).use { cursor -> while(cursor.moveToNext()) result += AppUsageTotal(cursor.getString(0),cursor.getLong(1)) }
        return result
    }

    @Synchronized
    fun durationBetween(
        startMs: Long,
        endMs: Long,
        packages: Set<String>
    ): Long {
        if (packages.isEmpty()) return 0L
        val placeholders = packages.joinToString(",") { "?" }
        val args = mutableListOf(startMs.toString(), endMs.toString())
        args.addAll(packages)
        readableDatabase.rawQuery(
            """
            SELECT SUM(
                MAX(0, MIN(ended_at_ms, ?) - MAX(started_at_ms, ?))
            )
            FROM usage_segments
            WHERE started_at_ms < ?
              AND ended_at_ms > ?
              AND package_name IN ($placeholders)
            """.trimIndent(),
            arrayOf(
                endMs.toString(),
                startMs.toString(),
                endMs.toString(),
                startMs.toString(),
                *packages.toTypedArray()
            )
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        }
    }

    companion object {
        private const val DB_NAME = "jlz_usage.db"
        private const val DB_VERSION = 1
    }
}

object ForegroundUsageTracker {
    private var store: ForegroundUsageStore? = null
    private var currentPackage: String? = null
    private var segmentStartedAtMs: Long = 0L

    @Synchronized
    fun bind(context: Context) {
        if (store == null) store = ForegroundUsageStore(context.applicationContext)
    }

    @Synchronized
    fun observe(packageName: String?, atMs: Long = System.currentTimeMillis()) {
        if (packageName.isNullOrBlank()) return
        if (store == null) return
        if (packageName == currentPackage) return

        closeCurrent(atMs)
        currentPackage = packageName
        segmentStartedAtMs = atMs
    }

    @Synchronized
    fun flush(atMs: Long = System.currentTimeMillis()) {
        val pkg = currentPackage ?: return
        val start = segmentStartedAtMs
        if (start > 0L) {
            store?.record(pkg, start, atMs)
            segmentStartedAtMs = atMs
        }
    }

    @Synchronized
    fun unbind(atMs: Long = System.currentTimeMillis()) {
        closeCurrent(atMs)
        currentPackage = null
        segmentStartedAtMs = 0L
    }

    @Synchronized
    fun currentPackageName(): String? = currentPackage

    private fun closeCurrent(atMs: Long) {
        val pkg = currentPackage
        val start = segmentStartedAtMs
        if (pkg != null && start > 0L) store?.record(pkg, start, atMs)
    }
}
