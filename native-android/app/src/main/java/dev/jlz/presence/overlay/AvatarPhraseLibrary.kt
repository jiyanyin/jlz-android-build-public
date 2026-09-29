package dev.jlz.presence.overlay

import android.content.Context
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.random.Random

data class AvatarPhrase(val id: String, val text: String, val tags: Set<String>, val cooldownMs: Long)

/** Tag-based offline phrase library with global, event and same-line cooldowns. */
class AvatarPhraseLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jlz_avatar_phrase_v1", Context.MODE_PRIVATE)
    private val recent = ArrayDeque<String>()
    private val phrases: List<AvatarPhrase> by lazy {
        val root = JSONObject(appContext.assets.open("avatar_phrases_zh.json").bufferedReader().use { it.readText() })
        val array = root.getJSONArray("phrases")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val tags = item.getJSONArray("tags")
            AvatarPhrase(
                id = item.getString("id"), text = item.getString("text"),
                tags = (0 until tags.length()).map { tags.getString(it) }.toSet(),
                cooldownMs = item.optLong("cooldown_sec", 120L) * 1_000L
            )
        }
    }

    @Synchronized
    fun pick(
        requestedTags: Set<String>,
        eventKey: String,
        proactive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): AvatarPhrase? {
        if (requestedTags.isEmpty()) return null
        val globalGap = if (proactive) 75_000L else 1_200L
        if (nowMs - prefs.getLong("global:$proactive", 0L) < globalGap) return null
        if (nowMs - prefs.getLong("event:$eventKey", 0L) < eventCooldown(eventKey)) return null
        val candidates = phrases.filter { phrase ->
            phrase.tags.any(requestedTags::contains) && phrase.id !in recent &&
                nowMs - prefs.getLong("phrase:${phrase.id}", 0L) >= phrase.cooldownMs
        }.ifEmpty {
            phrases.filter { phrase ->
                phrase.tags.any(requestedTags::contains) &&
                    nowMs - prefs.getLong("phrase:${phrase.id}", 0L) >= phrase.cooldownMs
            }
        }
        val chosen = candidates.randomOrNull(Random) ?: return null
        recent.addLast(chosen.id)
        while (recent.size > 8) recent.removeFirst()
        prefs.edit()
            .putLong("global:$proactive", nowMs)
            .putLong("event:$eventKey", nowMs)
            .putLong("phrase:${chosen.id}", nowMs)
            .apply()
        return chosen
    }

    private fun eventCooldown(key: String): Long = when {
        key.startsWith("poke") -> 1_200L
        key.startsWith("scroll") -> 150_000L
        key.startsWith("hour") -> 50 * 60_000L
        else -> 45_000L
    }
}
