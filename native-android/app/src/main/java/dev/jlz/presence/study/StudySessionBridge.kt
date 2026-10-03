package dev.jlz.presence.study

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Calendar

/**
 * Local-only bridge that lets the World Between WebShell present Study Session
 * controls while the canonical timer remains native and survives WebView/app
 * navigation.
 */
class StudySessionBridge(context: Context) {
    private val app = context.applicationContext
    private val repository = StudySessionRepository(app)
    private val metricsStore = StudyMetricsStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun version(): String = "study-bridge-2"

    @JavascriptInterface
    fun snapshot(): String = runBlocking {
        val now = System.currentTimeMillis()
        val recovery = repository.recoverStaleSession(now)
        if (recovery.recovered) {
            StudyTimerService.stop(app)
            publishRecovery(recovery, now)
        }
        snapshotJson(now, recovery)
    }

    @JavascriptInterface
    fun start(): String = runBlocking {
        val now = System.currentTimeMillis()
        val recovery = repository.recoverStaleSession(now)
        if (recovery.recovered) {
            StudyTimerService.stop(app)
            publishRecovery(recovery, now)
        }
        var state = repository.state.first()
        if (!state.active) {
            val sessionId = repository.start()
            StudyTimerService.sync(app)
            publish("start", sessionId, JSONObject(), now)
            state = repository.state.first()
        }
        snapshotJson(System.currentTimeMillis(), recovery)
    }

    @JavascriptInterface
    fun pause(): String = runBlocking {
        val state = repository.state.first()
        if (state.active && !state.paused) {
            repository.pause()
            publish("pause", state.sessionId)
        }
        StudyTimerService.sync(app)
        snapshotJson(System.currentTimeMillis())
    }

    @JavascriptInterface
    fun resume(): String = runBlocking {
        val state = repository.state.first()
        if (state.active && state.paused) {
            repository.resume()
            publish("resume", state.sessionId)
        }
        StudyTimerService.sync(app)
        snapshotJson(System.currentTimeMillis())
    }

    @JavascriptInterface
    fun finish(): String = runBlocking {
        val state = repository.state.first()
        if (state.active) {
            val metrics = repository.finish()
            StudyTimerService.stop(app)
            publish(
                "finish",
                metrics.sessionId,
                StudyRuntimeReporter.finishPayload(metrics)
            )
        } else {
            StudyTimerService.stop(app)
        }
        snapshotJson(System.currentTimeMillis())
    }

    @JavascriptInterface
    fun openBanduread(): String = StudyShortcuts.openBanduread(app)

    @JavascriptInterface
    fun openFenbi(): String = StudyShortcuts.openFenbi(app)

    fun close() {
        scope.cancel()
    }

    private suspend fun snapshotJson(
        nowMs: Long,
        recovery: StudyStaleRecovery = StudyStaleRecovery(false)
    ): String {
        val state = repository.state.first()
        val bounds = dayBounds(nowMs)
        val day = metricsStore.dayMetrics(bounds.first, bounds.second)
        val currentMs = if (state.active) state.effectiveElapsedMs(nowMs) else 0L
        val todayActiveMs =
            if (state.active && state.startedAtMs > 0L) {
                // The live UI is "today", so never display pre-midnight wall
                // clock as part of today's number. Finished sessions remain exact.
                currentMs.coerceAtMost(
                    (nowMs - maxOf(state.startedAtMs, bounds.first)).coerceAtLeast(0L)
                )
            } else 0L

        return JSONObject()
            .put("version", version())
            .put("captured_at_ms", nowMs)
            .put("active", state.active)
            .put("paused", state.paused)
            .put("session_id", state.sessionId)
            .put("started_at_ms", state.startedAtMs)
            .put("current_ms", currentMs)
            .put("today_ms", day.effectiveStudyMs + todayActiveMs)
            .put("finished_today_ms", day.effectiveStudyMs)
            .put("completed_sessions", day.completedSessions)
            .put("stale_recovered", recovery.recovered)
            .put("stale_reason", recovery.reason)
            .toString()
    }

    private fun publish(
        event: String,
        sessionId: String,
        extra: JSONObject = JSONObject(),
        eventAtMs: Long = System.currentTimeMillis()
    ) {
        if (sessionId.isBlank()) return
        scope.launch {
            StudyRuntimeReporter.post(app, event, sessionId, extra, eventAtMs)
        }
    }

    private fun publishRecovery(recovery: StudyStaleRecovery, atMs: Long) {
        if (!recovery.recovered || recovery.sessionId.isBlank()) return
        publish(
            "abandon",
            recovery.sessionId,
            JSONObject().put("reason", recovery.reason),
            atMs
        )
    }

    private fun dayBounds(atMs: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = atMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }
}
