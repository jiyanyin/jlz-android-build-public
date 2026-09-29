package dev.jlz.presence.overlay

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.life.LifeHealthJournalStore
import org.json.JSONArray
import org.json.JSONObject

/** Reads only the latest user-authored local status snapshot; it does not infer a diagnosis. */
class AvatarStatusContextResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(nowMs: Long = System.currentTimeMillis()): AvatarStatusContext {
        val latest = runCatching {
            LocalLifeStore(appContext)
                .listTimelineSince(nowMs - 14L * 24L * 60L * 60_000L, 200)
                .firstOrNull { it.type == "between_status" }
        }.getOrNull()
        val meta = latest?.let { runCatching { JSONObject(it.metadataJson) }.getOrNull() }
        val dimensions = meta?.optJSONObject("dimensions")
        val emotions = strings(dimensions?.optJSONArray("emotions"))
        val closeness = strings(dimensions?.optJSONArray("closeness_needs"))
        val bodySignals = strings(dimensions?.optJSONArray("body_signals"))
        val lowEnergy = listOf("self_presence", "emotional_vividness")
            .mapNotNull { key -> dimensions?.optInt(key, -1)?.takeIf { it in 0..100 } }
            .any { it <= 28 }
        val numb = dimensions?.optInt("emotion_access", -1)?.let { it in 0..25 } == true ||
            emotions.any { it == "麻木" || it == "没感觉" }
        val menstrual = runCatching {
            LifeHealthJournalStore(appContext).currentOpenPeriod() != null
        }.getOrDefault(false)
        return AvatarStatusContext(
            lowEnergy = lowEnergy,
            needsHug = closeness.isNotEmpty(),
            menstrual = menstrual,
            bodyUnwell = bodySignals.isNotEmpty(),
            numb = numb
        )
    }

    private fun strings(array: JSONArray?): Set<String> = if (array == null) emptySet() else
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
}
