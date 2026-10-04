package dev.jlz.presence.focus

import android.content.Context
import android.webkit.JavascriptInterface
import dev.jlz.presence.launcher.LauncherRepository
import dev.jlz.presence.study.StudyMetricsStore
import dev.jlz.presence.study.StudySessionRepository
import dev.jlz.presence.study.StudyTimerService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Calendar

/**
 * Local-only bridge for the WebShell entertainment gate.
 *
 * This keeps Gate V2's actual policy and persistence native while letting the
 * already-stable WebShell own the visual surface on OEMs that do not keep the
 * standalone gate Activity in front reliably.
 */
class EntertainmentGateBridge(context: Context) {
    private val app = context.applicationContext
    private val repository = EntertainmentGateRepository(app)
    private val launcher = LauncherRepository(app)
    private val studyRepository = StudySessionRepository(app)
    private val metricsStore = StudyMetricsStore(app)
    private val isTablet = app.resources.configuration.smallestScreenWidthDp >= 600

    @JavascriptInterface
    fun snapshot(packageName: String, reason: String): String {
        val profile = EntertainmentPolicy.profile(packageName)
            ?: return JSONObject().put("ok", false).put("error", "unknown_app").toString()

        val now = System.currentTimeMillis()
        val currentEffectiveMs = runCatching { effectiveToday(now) }.getOrDefault(0L)
        var pending = repository.smallStep(packageName)
        if (
            pending != null &&
            EntertainmentGateV2Policy.shouldResetSmallStep(
                baselineEffectiveMs = pending.baselineEffectiveMs,
                currentEffectiveMs = currentEffectiveMs,
                createdAtMs = pending.createdAtMs,
                nowMs = now
            )
        ) {
            repository.clearSmallStep(packageName)
            pending = null
        }
        val remainingMs = pending?.let {
            EntertainmentGateV2Policy.remainingSmallStepMs(
                it.baselineEffectiveMs,
                currentEffectiveMs,
                it.requiredMs
            )
        } ?: 0L
        val stepDone = pending != null && remainingMs <= 0L

        val purpose = EntertainmentGateV2Policy.releasePlan(
            isTablet,
            profile.tier,
            EntertainmentIntentChoice.PURPOSE
        )
        val rest = EntertainmentGateV2Policy.releasePlan(
            isTablet,
            profile.tier,
            EntertainmentIntentChoice.BREAK
        )
        val direct = EntertainmentGateV2Policy.releasePlan(
            isTablet,
            profile.tier,
            EntertainmentIntentChoice.DIRECT
        )
        val smallStep = EntertainmentGateV2Policy.releasePlan(
            isTablet,
            profile.tier,
            EntertainmentIntentChoice.SMALL_STEP
        )
        val seed = now xor packageName.hashCode().toLong()

        val incoming = if (reason == "expired") {
            EntertainmentGateCopy.expired(profile.appName, seed)
        } else {
            EntertainmentGateCopy.incoming(profile.appName, seed)
        }

        val stepText = when {
            pending == null -> ""
            stepDone -> EntertainmentGateCopy.smallStepDone(seed)
            else -> {
                val minutes = kotlin.math.ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
                EntertainmentGateCopy.smallStepPending(minutes, seed)
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("package_name", packageName)
            .put("app_name", profile.appName)
            .put("tier", profile.tier.name.lowercase())
            .put("device_type", if (isTablet) "tablet" else "phone")
            .put("incoming_text", incoming)
            .put("purpose_minutes", purpose.minutes)
            .put("break_minutes", rest.minutes)
            .put("direct_minutes", direct.minutes)
            .put("small_step_minutes", smallStep.minutes)
            .put("small_step_pending", pending != null)
            .put("small_step_done", stepDone)
            .put("small_step_remaining_ms", remainingMs)
            .put("small_step_text", stepText)
            .put("current_effective_ms", currentEffectiveMs)
            .toString()
    }

    @JavascriptInterface
    fun response(packageName: String, choiceName: String): String {
        if (EntertainmentPolicy.profile(packageName) == null) return ""
        val choice = parseChoice(choiceName) ?: return ""
        val seed = System.currentTimeMillis() xor packageName.hashCode().toLong()
        return EntertainmentGateCopy.response(choice, seed)
    }

    @JavascriptInterface
    fun grant(packageName: String, choiceName: String): String {
        val profile = EntertainmentPolicy.profile(packageName)
            ?: return JSONObject().put("ok", false).put("error", "unknown_app").toString()
        val choice = parseChoice(choiceName)
            ?: return JSONObject().put("ok", false).put("error", "unknown_choice").toString()
        val plan = EntertainmentGateV2Policy.releasePlan(isTablet, profile.tier, choice)
        val release = repository.grant(profile, choice, plan.minutes)
        val launched = launcher.launchApp(packageName)

        return JSONObject()
            .put("ok", launched)
            .put("minutes", plan.minutes)
            .put("until_ms", release.untilMs)
            .put("session_id", release.sessionId)
            .toString()
    }

    @JavascriptInterface
    fun startSmallStep(packageName: String): String {
        val profile = EntertainmentPolicy.profile(packageName)
            ?: return JSONObject().put("ok", false).put("error", "unknown_app").toString()
        val baseline = runCatching { effectiveToday(System.currentTimeMillis()) }.getOrDefault(0L)
        val step = repository.setSmallStep(profile, baseline)
        return JSONObject()
            .put("ok", true)
            .put("baseline_effective_ms", step.baselineEffectiveMs)
            .put("required_ms", step.requiredMs)
            .toString()
    }

    @JavascriptInterface
    fun cancelSmallStep(packageName: String): Boolean {
        if (EntertainmentPolicy.profile(packageName) == null) return false
        repository.clearSmallStep(packageName)
        return true
    }

    @JavascriptInterface
    fun decline(packageName: String, stage: String): Boolean {
        val profile = EntertainmentPolicy.profile(packageName) ?: return false
        repository.clearSmallStep(profile.packageName)
        repository.recordDecline(profile, stage.ifBlank { "web_gate" })
        return true
    }

    @JavascriptInterface
    fun enabled(): Boolean = repository.enabled()

    private fun parseChoice(value: String): EntertainmentIntentChoice? =
        when (value.trim().lowercase()) {
            "purpose" -> EntertainmentIntentChoice.PURPOSE
            "break", "rest" -> EntertainmentIntentChoice.BREAK
            "small_step", "small-step", "step" -> EntertainmentIntentChoice.SMALL_STEP
            "direct" -> EntertainmentIntentChoice.DIRECT
            else -> null
        }

    private fun effectiveToday(nowMs: Long): Long = runBlocking {
        val recovery = studyRepository.recoverStaleSession(nowMs)
        if (recovery.recovered) {
            StudyTimerService.stop(app)
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startAt = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val endAt = cal.timeInMillis

        val completed = metricsStore.dayMetrics(startAt, endAt).effectiveStudyMs
        val active = studyRepository.state.first().effectiveElapsedMs(nowMs)
        completed + active
    }
}
