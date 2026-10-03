package dev.jlz.presence.study

import android.content.Context
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Sends canonical local Study Session lifecycle events to Runtime. */
object StudyRuntimeReporter {
    private fun localDate(nowMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))

    private fun timezoneOffsetMinutes(nowMs: Long): Int =
        TimeZone.getDefault().getOffset(nowMs) / 60_000

    suspend fun post(
        context: Context,
        event: String,
        sessionId: String,
        extra: JSONObject = JSONObject(),
        eventAtMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val settings = RuntimeSettingsRepository(context.applicationContext).load()
            if (settings.baseUrl.isBlank() || settings.token.isBlank()) return@runCatching false
            val metadata = JSONObject(extra.toString())
                .put("session_id", sessionId)
                .put("event_at_ms", eventAtMs)
                .put("local_date", localDate(eventAtMs))
                .put("timezone_offset_minutes", timezoneOffsetMinutes(eventAtMs))
                .put("source_surface", "native_study_session")
            RuntimeApiClient(settings).postStudyEvent(event, metadata)
            true
        }.getOrDefault(false)
    }

    fun finishPayload(metrics: StudySessionMetrics): JSONObject =
        JSONObject()
            .put("started_at_ms", metrics.startedAtMs)
            .put("ended_at_ms", metrics.endedAtMs)
            .put("total_session_ms", metrics.totalSessionMs)
            .put("unpaused_ms", metrics.unpausedMs)
            .put("effective_study_ms", metrics.effectiveStudyMs)
}
