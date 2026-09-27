package dev.jlz.presence.study

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.screen.AccessibilityActionGateway
import dev.jlz.presence.usage.ForegroundUsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide Study Push 25/5 controller.
 *
 * - 25 minute focus window, local watchdog every 5 minutes.
 * - A normal watchdog tick does NOT write Timeline and never uploads a large
 *   screenshot; only a sustained off-target episode writes ONE anomaly event.
 * - At 25 minutes (or manual finish) we return to HOME and open an 8 minute
 *   cooldown. We never force-stop a third-party app.
 *
 * State layering follows the contract: this controller only marks local
 * lifecycle (started / patrolled / anomaly / finished); any upstream delivery
 * is owned by RuntimeApiClient and is never assumed complete here.
 */
object StudyPatrol {

    data class State(
        val active: Boolean = false,
        val targetPackages: Set<String> = emptySet(),
        val startedAtMs: Long = 0L,
        val endsAtMs: Long = 0L,
        val lastPatrolAtMs: Long = 0L,
        val anomalyCount: Int = 0,
        val cooldownUntilMs: Long = 0L,
        val lastRejectReason: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var patrolJob: Job? = null
    private var finishJob: Job? = null
    private var offTargetEpisode = false

    fun inCooldown(now: Long = System.currentTimeMillis()): Boolean = now < _state.value.cooldownUntilMs

    /** Returns false (with a reason) when start is rejected; otherwise starts. */
    fun start(context: Context, targetPackages: Set<String> = emptySet()): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (_state.value.active) {
            _state.update { it.copy(lastRejectReason = "already_active") }
            return false
        }
        if (now < _state.value.cooldownUntilMs) {
            _state.update { it.copy(lastRejectReason = "cooldown") }
            return false
        }
        val policy = StudyPolicy()
        val targets = targetPackages.filter { it.isNotBlank() }.toSet().ifEmpty { policy.targetPackages }
        val endsAt = now + policy.studyDurationMin * 60_000L

        _state.value = State(
            active = true,
            targetPackages = targets,
            startedAtMs = now,
            endsAtMs = endsAt,
            cooldownUntilMs = _state.value.cooldownUntilMs
        )
        LocalLifeStore(app).recordTimeline(
            "study_start", "开始学习",
            targets.joinToString(",") + " · ${policy.studyDurationMin}分钟"
        )

        scope.launch {
            val repo = StudySessionRepository(app)
            runCatching { repo.start(targets) }
            // Owned quiet FGS notification (delivery only).
            runCatching {
                app.startForegroundService(
                    Intent(app, StudyTimerService::class.java).setAction(StudyTimerService.ACTION_REFRESH)
                )
            }
        }

        offTargetEpisode = false
        patrolJob = scope.launch {
            while (true) {
                delay(policy.patrolIntervalMin * 60_000L)
                patrol(app, targets)
            }
        }
        finishJob = scope.launch {
            delay(policy.studyDurationMin * 60_000L)
            finish(app, completed = true)
        }
        return true
    }

    private suspend fun patrol(app: Context, targets: Set<String>) {
        val now = System.currentTimeMillis()
        _state.update { it.copy(lastPatrolAtMs = now) }
        val current = ForegroundUsageTracker.currentPackageName()
        // Normal tick: our own UI / unknown foreground -> no event, no upload.
        if (current.isNullOrBlank() || current == app.packageName) {
            offTargetEpisode = false
            return
        }
        if (current in targets) {
            offTargetEpisode = false
            return
        }
        // Sustained off-target: record a single anomaly per off-target episode.
        if (!offTargetEpisode) {
            offTargetEpisode = true
            _state.update { it.copy(anomalyCount = it.anomalyCount + 1) }
            LocalLifeStore(app).recordTimeline(
                "study_anomaly", "学习时离开了目标应用", current
            )
        }
    }

    fun stopEarly(context: Context) {
        scope.launch { finish(context.applicationContext, completed = false) }
    }

    private suspend fun finish(app: Context, completed: Boolean) {
        val s = _state.value
        if (!s.active) return
        patrolJob?.cancel(); finishJob?.cancel()
        val now = System.currentTimeMillis()
        val policy = StudyPolicy()

        runCatching { StudySessionRepository(app).finish() }
        runCatching { StudyTimerService.stop(app) }
        // Return HOME; never force-stop the third-party app.
        runCatching { AccessibilityActionGateway.globalAction(AccessibilityService.GLOBAL_ACTION_HOME) }

        val durationMin = ((now - s.startedAtMs) / 60_000L).coerceAtLeast(0L)
        LocalLifeStore(app).recordTimeline(
            "study_finish",
            if (completed) "一段学习完成，已回 Home" else "学习已结束，已回 Home",
            "时长${durationMin}分钟 · 异常${s.anomalyCount}次"
        )

        _state.value = State(
            active = false,
            cooldownUntilMs = now + policy.breakCooldownMin * 60_000L,
            anomalyCount = s.anomalyCount
        )
    }
}
