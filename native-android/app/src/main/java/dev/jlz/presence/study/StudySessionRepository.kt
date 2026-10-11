package dev.jlz.presence.study

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.usage.ForegroundUsageStore
import dev.jlz.presence.usage.ForegroundUsageTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.studyDataStore by preferencesDataStore(name = "jlz_study_session")

data class StudySessionState(
    val sessionId: String = "",
    val active: Boolean = false,
    val paused: Boolean = false,
    val startedAtMs: Long = 0L,
    val activeSegmentStartedAtMs: Long = 0L,
    val pauseStartedAtMs: Long = 0L,
    val pausedTotalMs: Long = 0L,
    val targetPackages: Set<String> = emptySet()
) {
    fun effectiveElapsedMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (!active || startedAtMs <= 0L) return 0L
        val runningUntil =
            if (paused && pauseStartedAtMs > 0L) pauseStartedAtMs else nowMs
        return (runningUntil - startedAtMs - pausedTotalMs).coerceAtLeast(0L)
    }
}

data class StudyStaleRecovery(
    val recovered: Boolean,
    val sessionId: String = "",
    val reason: String = ""
)

object StudySessionFreshness {
    const val MAX_ACTIVE_SESSION_MS = 16L * 60L * 60L * 1000L
    const val FUTURE_TOLERANCE_MS = 5L * 60L * 1000L

    fun staleReason(
        state: StudySessionState,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (!state.active) return null
        if (state.startedAtMs <= 0L) return "missing_start"
        if (state.startedAtMs > nowMs + FUTURE_TOLERANCE_MS) return "future_start"
        if (nowMs - state.startedAtMs > MAX_ACTIVE_SESSION_MS) return "too_old"
        return null
    }
}

class StudySessionRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val lifeStore = LocalLifeStore(appContext)
    private val metricsStore = StudyMetricsStore(appContext)
    private val usageStore = ForegroundUsageStore(appContext)

    private object Keys {
        val sessionId = stringPreferencesKey("session_id")
        val active = booleanPreferencesKey("active")
        val paused = booleanPreferencesKey("paused")
        val startedAt = longPreferencesKey("started_at_ms")
        val activeSegmentStartedAt = longPreferencesKey("active_segment_started_at_ms")
        val pauseStartedAt = longPreferencesKey("pause_started_at_ms")
        val pausedTotal = longPreferencesKey("paused_total_ms")
        val targetPackages = stringSetPreferencesKey("target_packages")
        val configuredTargets = stringSetPreferencesKey("configured_targets")
    }

    val state: Flow<StudySessionState> = context.studyDataStore.data.map { prefs ->
        StudySessionState(
            sessionId = prefs[Keys.sessionId].orEmpty(),
            active = prefs[Keys.active] ?: false,
            paused = prefs[Keys.paused] ?: false,
            startedAtMs = prefs[Keys.startedAt] ?: 0L,
            activeSegmentStartedAtMs = prefs[Keys.activeSegmentStartedAt] ?: 0L,
            pauseStartedAtMs = prefs[Keys.pauseStartedAt] ?: 0L,
            pausedTotalMs = prefs[Keys.pausedTotal] ?: 0L,
            targetPackages = prefs[Keys.targetPackages] ?: emptySet()
        )
    }

    /**
     * Clears an orphaned active session without saving bogus duration metrics.
     *
     * A Study Session is a deliberate foreground action. If an old process/version
     * left the active flag behind for more than 16 hours, carrying that wall-clock
     * gap into today's study total is always worse than discarding the orphan.
     */
    suspend fun recoverStaleSession(
        nowMs: Long = System.currentTimeMillis()
    ): StudyStaleRecovery {
        val current = state.first()
        val reason = StudySessionFreshness.staleReason(current, nowMs)
            ?: return StudyStaleRecovery(false)

        context.studyDataStore.edit { prefs ->
            // Re-check the same session so a brand-new start cannot be wiped by
            // a recovery racing with the user's tap.
            if (
                prefs[Keys.active] == true &&
                prefs[Keys.sessionId].orEmpty() == current.sessionId
            ) {
                prefs[Keys.active] = false
                prefs[Keys.paused] = false
                prefs[Keys.startedAt] = 0L
                prefs[Keys.activeSegmentStartedAt] = 0L
                prefs[Keys.pauseStartedAt] = 0L
                prefs[Keys.pausedTotal] = 0L
                prefs[Keys.targetPackages] = emptySet()
            }
        }

        lifeStore.recordTimeline(
            "study",
            "清理遗留学习 Session",
            "旧计时已作废 · " + reason,
            eventId = current.sessionId.ifBlank { UUID.randomUUID().toString() },
            createdAtMs = nowMs
        )
        return StudyStaleRecovery(
            recovered = true,
            sessionId = current.sessionId,
            reason = reason
        )
    }

    suspend fun setTargetPackages(packages: Set<String>) {
        val clean = packages.filter { it.isNotBlank() }.toSet()
        context.studyDataStore.edit { prefs ->
            prefs[Keys.configuredTargets] = clean
        }
        lifeStore.recordTimeline(
            "study",
            "更新学习 App",
            clean.joinToString(", ")
        )
    }

    suspend fun start(targetPackages: Set<String> = emptySet()): String {
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()

        context.studyDataStore.edit { prefs ->
            val resolvedTargets =
                if (targetPackages.isNotEmpty()) targetPackages
                else (prefs[Keys.configuredTargets] ?: emptySet())

            prefs[Keys.sessionId] = sessionId
            prefs[Keys.active] = true
            prefs[Keys.paused] = false
            prefs[Keys.startedAt] = now
            prefs[Keys.activeSegmentStartedAt] = now
            prefs[Keys.pauseStartedAt] = 0L
            prefs[Keys.pausedTotal] = 0L
            prefs[Keys.targetPackages] = resolvedTargets
        }

        lifeStore.recordTimeline(
            "study",
            "开始学习",
            "新的学习 Session 已开始",
            eventId = sessionId,
            createdAtMs = now
        )
        return sessionId
    }

    suspend fun pause() {
        val now = System.currentTimeMillis()
        var sessionId = ""
        var segmentStart = 0L
        var changed = false

        context.studyDataStore.edit { prefs ->
            if (prefs[Keys.active] == true && prefs[Keys.paused] != true) {
                sessionId = prefs[Keys.sessionId].orEmpty()
                segmentStart = prefs[Keys.activeSegmentStartedAt] ?: now
                prefs[Keys.paused] = true
                prefs[Keys.pauseStartedAt] = now
                prefs[Keys.activeSegmentStartedAt] = 0L
                changed = true
            }
        }

        if (changed) {
            metricsStore.recordSegment(sessionId, segmentStart, now)
            ForegroundUsageTracker.flush(now)
            lifeStore.recordTimeline(
                "study",
                "暂停学习",
                "这一段暂停不计入有效学习",
                eventId = sessionId,
                createdAtMs = now
            )
        }
    }

    suspend fun resume() {
        val now = System.currentTimeMillis()
        var sessionId = ""
        var changed = false

        context.studyDataStore.edit { prefs ->
            if (prefs[Keys.active] == true && prefs[Keys.paused] == true) {
                sessionId = prefs[Keys.sessionId].orEmpty()
                val pauseStart = prefs[Keys.pauseStartedAt] ?: now
                val prior = prefs[Keys.pausedTotal] ?: 0L
                prefs[Keys.pausedTotal] =
                    prior + (now - pauseStart).coerceAtLeast(0L)
                prefs[Keys.paused] = false
                prefs[Keys.pauseStartedAt] = 0L
                prefs[Keys.activeSegmentStartedAt] = now
                changed = true
            }
        }

        if (changed) {
            lifeStore.recordTimeline(
                "study",
                "继续学习",
                "学习 Session 已恢复",
                eventId = sessionId,
                createdAtMs = now
            )
        }
    }

    suspend fun finish(): StudySessionMetrics {
        val now = System.currentTimeMillis()
        var sessionId = ""
        var startedAt = 0L
        var segmentStart = 0L
        var paused = false
        var targets: Set<String> = emptySet()
        var wasActive = false

        context.studyDataStore.edit { prefs ->
            wasActive = prefs[Keys.active] == true
            sessionId = prefs[Keys.sessionId].orEmpty()
            startedAt = prefs[Keys.startedAt] ?: now
            segmentStart = prefs[Keys.activeSegmentStartedAt] ?: 0L
            paused = prefs[Keys.paused] ?: false
            targets = prefs[Keys.targetPackages] ?: emptySet()

            prefs[Keys.active] = false
            prefs[Keys.paused] = false
            prefs[Keys.pauseStartedAt] = 0L
            prefs[Keys.activeSegmentStartedAt] = 0L
        }

        if (wasActive && !paused && segmentStart > 0L) {
            metricsStore.recordSegment(sessionId, segmentStart, now)
        }
        ForegroundUsageTracker.flush(now)

        val segments = metricsStore.segments(sessionId)
        val unpausedMs = segments.sumOf {
            (it.endedAtMs - it.startedAtMs).coerceAtLeast(0L)
        }
        val targetAppMs =
            if (targets.isEmpty()) {
                unpausedMs
            } else {
                segments.sumOf { segment ->
                    usageStore.durationBetween(
                        segment.startedAtMs,
                        segment.endedAtMs,
                        targets
                    )
                }
            }

        val metrics = StudySessionMetrics(
            sessionId = sessionId.ifBlank { "unknown" },
            startedAtMs = startedAt,
            endedAtMs = now,
            totalSessionMs = (now - startedAt).coerceAtLeast(0L),
            unpausedMs = unpausedMs,
            effectiveStudyMs = targetAppMs.coerceAtMost(unpausedMs),
            targetPackages = targets
        )
        metricsStore.save(metrics)
        if (wasActive) android.os.Handler(android.os.Looper.getMainLooper()).post {
            dev.jlz.presence.overlay.FloatingPresenceService.localCelebration()
        }

        lifeStore.recordTimeline(
            "study",
            "结束学习",
            "总 " + (metrics.totalSessionMs / 60_000L) +
                " 分钟 · 未暂停 " + (metrics.unpausedMs / 60_000L) +
                " 分钟 · 有效 " + (metrics.effectiveStudyMs / 60_000L) + " 分钟",
            eventId = metrics.sessionId,
            createdAtMs = now
        )

        return metrics
    }
}
