package dev.jlz.presence.life

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Android system calendar adapter; never modifies events without calendar permission. */
class NativeCalendarBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("jlz_calendar_links_v1", Context.MODE_PRIVATE)
    fun selectCalendar(id: Long) { prefs.edit().putLong("selected_calendar", id).apply() }
    fun calendars(): List<Pair<Long, String>> {
        if (!canRead()) return emptyList()
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            CalendarContract.Calendars.VISIBLE + "=1", null, null).use { rows ->
            buildList { while (rows != null && rows.moveToNext()) add(rows.getLong(0) to (rows.getString(1).orEmpty() + " / " + rows.getString(2).orEmpty())) }
        }
    }

    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun canWrite(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    /** Calendar summaries, capped to 60 entries over next 14 days. */
    fun snapshot(fromMs: Long = System.currentTimeMillis(), toMs: Long = fromMs + 14L * 86_400_000L): JSONObject {
        val now = System.currentTimeMillis()
        val result = JSONObject()
            .put("source", "android_calendar_provider")
            .put("observed_at_ms", now)
            .put("read_permission", canRead())
            .put("write_permission", canWrite())
            .put("events", JSONArray())
            .put("range_start_ms", fromMs).put("range_end_ms", toMs)
        if (toMs <= fromMs || toMs - fromMs > 42L * 86_400_000L) return result.put("ok", false).put("reason", "calendar_invalid_range")
        if (!canRead()) return result.put("reason", "calendar_read_permission_required")
        val selected = prefs.getLong("selected_calendar", 0L)
        if (selected <= 0L) return result.put("reason", "select_calendar_first")
        val events = JSONArray()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val fields = arrayOf(CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_ID)
        try {
            context.contentResolver.query(uri, fields, CalendarContract.Instances.CALENDAR_ID + "=?", arrayOf(selected.toString()),
                CalendarContract.Instances.BEGIN + " ASC").use { rows ->
                while (rows != null && rows.moveToNext() && events.length() < 60) {
                    events.put(JSONObject()
                        .put("event_id", rows.getLong(0))
                        .put("calendar_id", rows.getLong(6))
                        .put("provider_sync_id", providerSyncId(rows.getLong(0)))
                        .put("stable_instance_key", "android:" + rows.getLong(6) + ":" + rows.getLong(0) + ":" + rows.getLong(2))
                        .put("source", "android_calendar_provider")
                        .put("title", rows.getString(1).orEmpty())
                        .put("start_ms", rows.getLong(2))
                        .put("end_ms", rows.getLong(3))
                        .put("location", rows.getString(4).orEmpty())
                        .put("all_day", rows.getInt(5) != 0))
                }
            }
            return result.put("events", events).put("ok", true).put("possibly_truncated", events.length() == 60)
        } catch (error: Exception) {
            return result.put("ok", false)
                .put("reason", "calendar_provider_error:" + error.javaClass.simpleName)
        }
    }

    private fun providerSyncId(id: Long): String = runCatching {
        context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events._SYNC_ID), null, null, null).use { rows ->
                if (rows != null && rows.moveToFirst()) rows.getString(0).orEmpty() else ""
            }
    }.getOrDefault("")

    fun add(payload: JSONObject): Pair<Boolean, String> {
        if (!canWrite()) return false to "calendar_write_permission_required"
        val title = payload.optString("title").trim()
        val start = payload.optLong("start_ms", 0L)
        val end = payload.optLong("end_ms", 0L)
        if (title.isBlank() || title.length > 160 ||
            start < System.currentTimeMillis() - 365L * 86_400_000L ||
            end <= start || end - start > 90L * 86_400_000L) {
            return false to "calendar_title_or_time_invalid"
        }
        val calendarId = payload.optLong("calendar_id", 0L)
            .takeIf { it > 0L } ?: prefs.getLong("selected_calendar", 0L).takeIf { it > 0L }
            ?: return false to "no_writable_android_calendar"
        if (payload.optBoolean("write_confirmed", false) != true) return false to "calendar_single_write_confirmation_required"
        val requestId = payload.optString("request_id").take(100)
        if (requestId.isBlank()) return false to "calendar_request_id_required"
        val mapped = prefs.getLong("event:$calendarId:$requestId", 0L)
        if (mapped > 0L) return true to JSONObject().put("ok", true).put("event_id", mapped).put("calendar_id", calendarId).put("deduplicated", true).toString()
        val data = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, payload.optString("description").take(2000))
            put(CalendarContract.Events.EVENT_LOCATION, payload.optString("location").take(200))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (payload.optBoolean("all_day")) 1 else 0)
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, data)
                ?: return false to "calendar_insert_failed"
            prefs.edit().putLong("event:$calendarId:$requestId", ContentUris.parseId(uri)).commit()
            true to JSONObject().put("ok", true)
                .put("event_id", ContentUris.parseId(uri))
                .put("calendar_id", calendarId)
                .put("title", title).toString()
        } catch (error: Exception) {
            false to "calendar_insert_exception:" + error.javaClass.simpleName
        }
    }

    private fun writableCalendarId(): Long? {
        if (!canRead()) return null
        val fields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            fields, CalendarContract.Calendars.VISIBLE + "=1", null, null).use { rows ->
            var id: Long? = null
            while (rows != null && rows.moveToNext()) {
                if (rows.getInt(1) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    id = rows.getLong(0)
                    break
                }
            }
            id
        }
    }
}
