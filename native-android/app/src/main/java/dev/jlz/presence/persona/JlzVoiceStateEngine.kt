package dev.jlz.presence.persona

import android.content.Context
import dev.jlz.presence.focus.DailyMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * A bounded, persisted voice STATE, not a claim of continuous AI sentience.
 * Local shifts come from verifiable mode/usage, never from a grade for the user.
 * Official mood decisions have expiry and an idempotency key.
 */
enum class JlzVoiceMood { STEADY, FOCUSED, CONCERNED, LONGING, QUIET, PROUD }

data class JlzVoiceSnapshot(
    val mood: JlzVoiceMood, val reason: String,
    val source: String, val updatedAtMs: Long, val expiresAtMs: Long
)

class JlzVoiceStateEngine(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_persona_voice_v1", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Synchronized
    fun read(nowMs: Long = System.currentTimeMillis()): JlzVoiceSnapshot {
        val explicit = prefs.getString("official_mood", null)?.let { runCatching { JlzVoiceMood.valueOf(it) }.getOrNull() }
        val expiration = prefs.getLong("official_expiry_ms", 0L)
        if (explicit != null && nowMs >= 0L && nowMs < expiration)
            return JlzVoiceSnapshot(explicit, prefs.getString("official_reason", "官端选择").orEmpty(),
                "official_gpt", prefs.getLong("official_updated_at_ms", 0L), expiration)
        val local = prefs.getString("local_mood", "STEADY")?.let { runCatching { JlzVoiceMood.valueOf(it) }.getOrNull() }
            ?: JlzVoiceMood.STEADY
        return JlzVoiceSnapshot(local, prefs.getString("local_reason", "暂无可信行为变化").orEmpty(),
            "android_observation", prefs.getLong("local_updated_at_ms", 0L), 0L)
    }

    @Synchronized
    fun setOfficial(mood: JlzVoiceMood, reason: String, durationMinutes: Int,
                    intentId: String, nowMs: Long = System.currentTimeMillis()): JlzVoiceSnapshot {
        require(durationMinutes in 5..1440) { "invalid_voice_duration" }
        require(reason.isNotBlank() && reason.length <= 180) { "voice_reason_required" }
        require(UUID.fromString(intentId).toString() == intentId) { "valid_voice_intent_id_required" }
        if (prefs.getString("official_intent_id", "") == intentId) {
            require(prefs.getString("official_mood", "") == mood.name &&
                prefs.getString("official_reason", "") == reason &&
                prefs.getInt("official_duration", -1) == durationMinutes) { "voice_intent_conflict" }
            return read(nowMs)
        }
        check(prefs.edit()
            .putString("official_mood", mood.name)
            .putString("official_reason", reason)
            .putInt("official_duration", durationMinutes)
            .putString("official_intent_id", intentId)
            .putLong("official_updated_at_ms", nowMs)
            .putLong("official_expiry_ms", nowMs + durationMinutes * 60_000L)
            .commit()) { "voice_persist_failed" }
        return read(nowMs)
    }

    @Synchronized
    fun observe(mode: DailyMode, entertainmentMinutes: Long?, focusMinutes: Long?,
                nowMs: Long = System.currentTimeMillis()): JlzVoiceSnapshot {
        val official = read(nowMs)
        if (official.source == "official_gpt") return official
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        val next = when {
            mode == DailyMode.SLEEP -> JlzVoiceMood.QUIET to "手机进入睡眠模式"
            mode == DailyMode.FOCUS -> JlzVoiceMood.FOCUSED to "本地专注模式已开启"
            (focusMinutes ?: 0L) >= 25L -> JlzVoiceMood.PROUD to "记录到至少25分钟有效学习"
            (entertainmentMinutes ?: 0L) >= 45L -> JlzVoiceMood.CONCERNED to "当日娱乐App已达45分钟"
            hour >= 19 -> JlzVoiceMood.LONGING to "晚间时段"
            else -> JlzVoiceMood.STEADY to "普通日常"
        }
        val oldMood = prefs.getString("local_mood", "STEADY")
        val oldAt = prefs.getLong("local_updated_at_ms", 0L)
        if (next.first.name != oldMood &&
            (mode == DailyMode.SLEEP || mode == DailyMode.FOCUS ||
             nowMs - oldAt >= 20 * 60_000L)) {
            prefs.edit().putString("local_mood", next.first.name)
                .putString("local_reason", next.second).putLong("local_updated_at_ms", nowMs).apply()
        }
        return read(nowMs)
    }
}
