package dev.jlz.presence.life

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToLong

data class CyclePeriod(
    val id: String,
    val startEpochDay: Long,
    val endEpochDay: Long?
)

data class CyclePrediction(
    val expectedStartEpochDay: Long,
    val source: String,
    val averageCycleDays: Long,
    val historyCount: Int
)

data class HydrationEntry(
    val id: String,
    val epochDay: Long,
    val amountMl: Int,
    val drinkType: String,
    val createdAtMs: Long
)

data class FoodEntry(
    val id: String,
    val epochDay: Long,
    val mealType: String,
    val text: String,
    val photoRef: String?,
    val createdAtMs: Long
)

class LifeHealthJournalStore(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DB_NAME,
        null,
        DB_VERSION
    ) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cycle_periods(
                id TEXT PRIMARY KEY,
                start_epoch_day INTEGER NOT NULL,
                end_epoch_day INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE hydration_entries(
                id TEXT PRIMARY KEY,
                epoch_day INTEGER NOT NULL,
                amount_ml INTEGER NOT NULL,
                drink_type TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE food_entries(
                id TEXT PRIMARY KEY,
                epoch_day INTEGER NOT NULL,
                meal_type TEXT NOT NULL,
                text TEXT NOT NULL,
                photo_ref TEXT,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE journal_events(
                key TEXT PRIMARY KEY,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_cycle_start ON cycle_periods(start_epoch_day DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_hydration_day ON hydration_entries(epoch_day)"
        )
        db.execSQL(
            "CREATE INDEX idx_food_day ON food_entries(epoch_day)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) = Unit

    @Synchronized
    fun recordPeriodStart(
        date: LocalDate = LocalDate.now()
    ): CyclePeriod {
        val open = currentOpenPeriod()
        if (open != null) return open

        val period = CyclePeriod(
            id = UUID.randomUUID().toString(),
            startEpochDay = date.toEpochDay(),
            endEpochDay = null
        )
        writableDatabase.insert(
            "cycle_periods",
            null,
            ContentValues().apply {
                put("id", period.id)
                put("start_epoch_day", period.startEpochDay)
                putNull("end_epoch_day")
            }
        )
        return period
    }

    @Synchronized
    fun recordPeriodEnd(
        date: LocalDate = LocalDate.now()
    ): CyclePeriod? {
        val open = currentOpenPeriod() ?: return null
        val safeEnd =
            date.toEpochDay().coerceAtLeast(open.startEpochDay)

        writableDatabase.update(
            "cycle_periods",
            ContentValues().apply {
                put("end_epoch_day", safeEnd)
            },
            "id = ?",
            arrayOf(open.id)
        )
        return open.copy(endEpochDay = safeEnd)
    }

    @Synchronized
    fun correctPeriod(
        id: String,
        start: LocalDate,
        end: LocalDate?
    ) {
        val startDay = start.toEpochDay()
        val endDay = end?.toEpochDay()
            ?.coerceAtLeast(startDay)
        writableDatabase.update(
            "cycle_periods",
            ContentValues().apply {
                put("start_epoch_day", startDay)
                if (endDay == null) putNull("end_epoch_day")
                else put("end_epoch_day", endDay)
            },
            "id = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun currentOpenPeriod(): CyclePeriod? =
        readableDatabase.query(
            "cycle_periods",
            null,
            "end_epoch_day IS NULL",
            null,
            null,
            null,
            "start_epoch_day DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            CyclePeriod(
                id = cursor.getString(
                    cursor.getColumnIndexOrThrow("id")
                ),
                startEpochDay = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        "start_epoch_day"
                    )
                ),
                endEpochDay = null
            )
        }

    @Synchronized
    fun recentPeriods(limit: Int = 8): List<CyclePeriod> {
        val result = mutableListOf<CyclePeriod>()
        readableDatabase.query(
            "cycle_periods",
            null,
            null,
            null,
            null,
            null,
            "start_epoch_day DESC",
            limit.coerceIn(2, 24).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val endIndex =
                    cursor.getColumnIndexOrThrow(
                        "end_epoch_day"
                    )
                result += CyclePeriod(
                    id = cursor.getString(
                        cursor.getColumnIndexOrThrow("id")
                    ),
                    startEpochDay = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            "start_epoch_day"
                        )
                    ),
                    endEpochDay =
                        if (cursor.isNull(endIndex)) null
                        else cursor.getLong(endIndex)
                )
            }
        }
        return result
    }

    @Synchronized
    fun prediction(): CyclePrediction? {
        if (currentOpenPeriod() != null) return null

        val starts = recentPeriods(6)
            .map { it.startEpochDay }
            .sorted()

        if (starts.size < 2) return null

        val intervals = starts.zipWithNext { a, b -> b - a }
            .filter { it in 15L..60L }

        if (intervals.isEmpty()) return null

        val recentIntervals = intervals.takeLast(4)
        val average = recentIntervals.average().roundToLong()
        val expected = starts.last() + average

        return CyclePrediction(
            expectedStartEpochDay = expected,
            source = "recent_history_average",
            averageCycleDays = average,
            historyCount = starts.size
        )
    }

    @Synchronized
    fun addHydration(
        amountMl: Int,
        drinkType: String = "water",
        date: LocalDate = LocalDate.now()
    ): HydrationEntry {
        require(amountMl > 0) { "amount_ml_must_be_positive" }
        val entry = HydrationEntry(
            id = UUID.randomUUID().toString(),
            epochDay = date.toEpochDay(),
            amountMl = amountMl,
            drinkType = drinkType.ifBlank { "water" },
            createdAtMs = System.currentTimeMillis()
        )
        writableDatabase.insert(
            "hydration_entries",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("epoch_day", entry.epochDay)
                put("amount_ml", entry.amountMl)
                put("drink_type", entry.drinkType)
                put("created_at_ms", entry.createdAtMs)
            }
        )
        return entry
    }

    @Synchronized
    fun hydrationTotal(
        date: LocalDate = LocalDate.now()
    ): Int =
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(amount_ml), 0)
            FROM hydration_entries
            WHERE epoch_day = ?
            """.trimIndent(),
            arrayOf(date.toEpochDay().toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0)
            else 0
        }

    @Synchronized
    fun deleteHydration(id: String) {
        writableDatabase.delete(
            "hydration_entries",
            "id = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun addFood(
        mealType: String,
        text: String,
        photoRef: String? = null,
        date: LocalDate = LocalDate.now()
    ): FoodEntry {
        val entry = FoodEntry(
            id = UUID.randomUUID().toString(),
            epochDay = date.toEpochDay(),
            mealType = mealType.ifBlank { "other" },
            text = text.trim(),
            photoRef = photoRef,
            createdAtMs = System.currentTimeMillis()
        )
        writableDatabase.insert(
            "food_entries",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("epoch_day", entry.epochDay)
                put("meal_type", entry.mealType)
                put("text", entry.text)
                put("photo_ref", entry.photoRef)
                put("created_at_ms", entry.createdAtMs)
            }
        )
        return entry
    }

    @Synchronized
    fun foods(
        date: LocalDate = LocalDate.now()
    ): List<FoodEntry> {
        val result = mutableListOf<FoodEntry>()
        readableDatabase.query(
            "food_entries",
            null,
            "epoch_day = ?",
            arrayOf(date.toEpochDay().toString()),
            null,
            null,
            "created_at_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val photoIndex =
                    cursor.getColumnIndexOrThrow("photo_ref")
                result += FoodEntry(
                    id = cursor.getString(
                        cursor.getColumnIndexOrThrow("id")
                    ),
                    epochDay = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            "epoch_day"
                        )
                    ),
                    mealType = cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            "meal_type"
                        )
                    ),
                    text = cursor.getString(
                        cursor.getColumnIndexOrThrow("text")
                    ),
                    photoRef =
                        if (cursor.isNull(photoIndex)) null
                        else cursor.getString(photoIndex),
                    createdAtMs = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            "created_at_ms"
                        )
                    )
                )
            }
        }
        return result
    }

    @Synchronized
    fun deleteFood(id: String) {
        writableDatabase.delete(
            "food_entries",
            "id = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun markEventOnce(key: String): Boolean {
        val inserted = writableDatabase.insertWithOnConflict(
            "journal_events",
            null,
            ContentValues().apply {
                put("key", key)
                put("created_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        return inserted != -1L
    }

    companion object {
        private const val DB_NAME = "jlz_life_health.db"
        private const val DB_VERSION = 1
    }
}
