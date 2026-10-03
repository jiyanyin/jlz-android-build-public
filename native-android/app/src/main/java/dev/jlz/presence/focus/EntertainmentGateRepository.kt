package dev.jlz.presence.focus

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore
import org.json.JSONObject
import java.util.UUID

data class EntertainmentRelease(
    val packageName: String,
    val sessionId: String,
    val choice: EntertainmentIntentChoice,
    val grantedAtMs: Long,
    val untilMs: Long
) {
    fun active(nowMs: Long = System.currentTimeMillis()): Boolean =
        EntertainmentGateV2Policy.releaseActive(untilMs, nowMs)
}

data class EntertainmentSmallStep(
    val packageName: String,
    val baselineEffectiveMs: Long,
    val requiredMs: Long,
    val createdAtMs: Long
)

class EntertainmentGateRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lifeStore = LocalLifeStore(app)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        lifeStore.recordTimeline(
            "entertainment_gate",
            if (value) "娱乐门禁已开启" else "娱乐门禁已暂停",
            "Entertainment Gate V2"
        )
    }

    fun release(packageName: String): EntertainmentRelease? {
        val raw = prefs.getString(RELEASE_PREFIX + packageName, null) ?: return null
        return decodeRelease(packageName, raw)
    }

    fun activeRelease(packageName: String, nowMs: Long = System.currentTimeMillis()): EntertainmentRelease? =
        release(packageName)?.takeIf { it.active(nowMs) }

    fun grant(
        profile: EntertainmentProfile,
        choice: EntertainmentIntentChoice,
        minutes: Int,
        nowMs: Long = System.currentTimeMillis()
    ): EntertainmentRelease {
        val session = EntertainmentRelease(
            packageName = profile.packageName,
            sessionId = UUID.randomUUID().toString(),
            choice = choice,
            grantedAtMs = nowMs,
            untilMs = nowMs + minutes.coerceIn(1, 60) * 60_000L
        )
        prefs.edit()
            .putString(RELEASE_PREFIX + profile.packageName, encodeRelease(session))
            .remove(STEP_PREFIX + profile.packageName)
            .apply()
        lifeStore.recordTimeline(
            "entertainment_gate",
            "放行 " + profile.appName,
            minutes.toString() + " 分钟 · " + choice.name.lowercase(),
            metadataJson = JSONObject()
                .put("actor", "user")
                .put("source", "entertainment_gate_v2")
                .put("package_name", profile.packageName)
                .put("app_name", profile.appName)
                .put("choice", choice.name.lowercase())
                .put("release_minutes", minutes)
                .put("release_until_ms", session.untilMs)
                .put("gate_session_id", session.sessionId)
                .toString()
        )
        return session
    }

    fun clearRelease(packageName: String) {
        prefs.edit().remove(RELEASE_PREFIX + packageName).apply()
    }

    fun setSmallStep(
        profile: EntertainmentProfile,
        baselineEffectiveMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        requiredMs: Long = EntertainmentGateV2Policy.SMALL_STEP_REQUIRED_MS
    ): EntertainmentSmallStep {
        val requirement = EntertainmentSmallStep(
            packageName = profile.packageName,
            baselineEffectiveMs = baselineEffectiveMs.coerceAtLeast(0L),
            requiredMs = requiredMs.coerceAtLeast(60_000L),
            createdAtMs = nowMs
        )
        prefs.edit()
            .remove(RELEASE_PREFIX + profile.packageName)
            .putString(STEP_PREFIX + profile.packageName, encodeStep(requirement))
            .apply()
        lifeStore.recordTimeline(
            "entertainment_gate",
            "先做一小步",
            profile.appName + " · 先学习 " + (requirement.requiredMs / 60_000L) + " 分钟",
            metadataJson = JSONObject()
                .put("actor", "user")
                .put("source", "entertainment_gate_v2")
                .put("package_name", profile.packageName)
                .put("app_name", profile.appName)
                .put("baseline_effective_ms", requirement.baselineEffectiveMs)
                .put("required_ms", requirement.requiredMs)
                .toString()
        )
        return requirement
    }

    fun smallStep(packageName: String): EntertainmentSmallStep? {
        val raw = prefs.getString(STEP_PREFIX + packageName, null) ?: return null
        return decodeStep(packageName, raw)
    }

    fun clearSmallStep(packageName: String) {
        prefs.edit().remove(STEP_PREFIX + packageName).apply()
    }

    fun recordDecline(profile: EntertainmentProfile, stage: String) {
        lifeStore.recordTimeline(
            "entertainment_gate",
            "这次没进去 " + profile.appName,
            stage,
            metadataJson = JSONObject()
                .put("actor", "user")
                .put("source", "entertainment_gate_v2")
                .put("package_name", profile.packageName)
                .put("app_name", profile.appName)
                .put("status", "declined")
                .put("stage", stage)
                .toString()
        )
    }

    fun recordExpired(profile: EntertainmentProfile, release: EntertainmentRelease?) {
        lifeStore.recordTimeline(
            "entertainment_gate",
            profile.appName + " 这一轮到点",
            release?.choice?.name?.lowercase().orEmpty(),
            metadataJson = JSONObject()
                .put("actor", "assistant")
                .put("source", "entertainment_gate_v2")
                .put("package_name", profile.packageName)
                .put("app_name", profile.appName)
                .put("status", "expired")
                .put("gate_session_id", release?.sessionId.orEmpty())
                .toString()
        )
    }

    private fun encodeRelease(value: EntertainmentRelease): String =
        listOf(
            value.sessionId,
            value.choice.name,
            value.grantedAtMs,
            value.untilMs
        ).joinToString("|")

    private fun decodeRelease(packageName: String, raw: String): EntertainmentRelease? {
        val parts = raw.split("|")
        if (parts.size < 4) return null
        val choice = runCatching { EntertainmentIntentChoice.valueOf(parts[1]) }.getOrNull() ?: return null
        val grantedAt = parts[2].toLongOrNull() ?: return null
        val until = parts[3].toLongOrNull() ?: return null
        return EntertainmentRelease(packageName, parts[0], choice, grantedAt, until)
    }

    private fun encodeStep(value: EntertainmentSmallStep): String =
        listOf(value.baselineEffectiveMs, value.requiredMs, value.createdAtMs).joinToString("|")

    private fun decodeStep(packageName: String, raw: String): EntertainmentSmallStep? {
        val parts = raw.split("|")
        if (parts.size < 3) return null
        return EntertainmentSmallStep(
            packageName = packageName,
            baselineEffectiveMs = parts[0].toLongOrNull() ?: return null,
            requiredMs = parts[1].toLongOrNull() ?: return null,
            createdAtMs = parts[2].toLongOrNull() ?: return null
        )
    }

    companion object {
        private const val PREFS = "jlz_entertainment_gate_v2"
        private const val KEY_ENABLED = "enabled_v2"
        private const val RELEASE_PREFIX = "release::"
        private const val STEP_PREFIX = "step::"
    }
}
