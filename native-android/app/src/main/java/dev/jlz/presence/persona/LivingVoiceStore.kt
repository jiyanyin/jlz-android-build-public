package dev.jlz.presence.persona

import android.content.Context
import android.util.AtomicFile
import dev.jlz.presence.runtime.RuntimeApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Inert private content, one shared displayed-history ledger, no remote code. */
class LivingVoiceStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("living_voice_v2", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(app.filesDir, "living-voice-bank-v2.json"))
    private val zone = ZoneId.of("Asia/Shanghai")

    fun beginVisit(): Long = synchronized(LOCK) {
        val n = prefs.getLong("visit", 0L) + 1
        check(prefs.edit().putLong("visit", n).commit())
        n
    }

    private fun rawBank(): String? {
        val cached=runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull()
        if(cached!=null && validBank(cached)!=null)return cached
        return runCatching { app.assets.open("voice/voice-bank.json").bufferedReader().use { it.readText() } }
            .getOrNull()?.takeIf { validBank(it)!=null }
    }

    fun sync(api: RuntimeApiClient) {
        val now=System.currentTimeMillis()
        val allowed=synchronized(LOCK) {
            if(now-prefs.getLong("sync_attempt_at",0L) in 0 until 60_000L) false
            else {prefs.edit().putLong("sync_attempt_at",now).apply();true}
        }
        if(!allowed)return
        val raw = rawBank()
        val response = api.voiceBank(raw?.let(::digest).orEmpty())
        if (!response.optBoolean("ok") || response.optBoolean("unchanged")) return
        accept(response.getJSONObject("manifest"), response.getString("bank_json"))
    }

    internal fun accept(manifest: JSONObject, raw: String): Boolean = synchronized(LOCK) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES || manifest.optInt("bytes") != bytes.size ||
            manifest.optString("sha256") != digest(raw)) return false
        val bank = validBank(raw) ?: return false
        if (manifest.optString("id") != bank.optString("id") ||
            manifest.optInt("version") != bank.optInt("version") ||
            manifest.optInt("entry_count") != bank.getJSONArray("entries").length()) return false
        val prior = rawBank()?.let(::validBank)
        if (prior != null && (bank.optInt("version") < prior.optInt("version") ||
            (bank.optInt("version") == prior.optInt("version") && rawBank()?.let(::digest) != digest(raw)))) return false
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        true
    }

    fun observeUsage(usage: JSONObject, now: Long = System.currentTimeMillis()) {
        val observed = usage.optLong("observed_at_ms")
        if (now - observed !in 0..180_000L || !usage.optBoolean("usage_permission_ready")) return
        val totals = usage.optJSONArray("totals") ?: return
        var entertainment = 0L
        val classifier = dev.jlz.presence.focus.LocalAppClassifier(app)
        for (i in 0 until totals.length()) {
            val row = totals.optJSONObject(i) ?: continue
            val pkg = row.optString("package_name")
            val profile = dev.jlz.presence.focus.EntertainmentPolicy.profile(pkg)
            val category = classifier.classify(pkg)
            if (profile?.tier != dev.jlz.presence.focus.EntertainmentTier.SHOPPING &&
                (profile?.tier == dev.jlz.presence.focus.EntertainmentTier.FEED ||
                 category in setOf(dev.jlz.presence.focus.LocalAppCategory.FEED, dev.jlz.presence.focus.LocalAppCategory.GAME)))
                entertainment += row.optLong("duration_ms").coerceAtLeast(0L)
        }
        prefs.edit().putLong("usage_at", observed).putLong("entertainment_ms", entertainment)
            .putString("usage_day", Instant.ofEpochMilli(observed).atZone(zone).toLocalDate().toString()).apply()
    }

    /** Facts come from observed state or explicit user activity, never arbitrary JS receipt flags. */
    fun select(surface: String, input: JSONObject = JSONObject(), now: Long = System.currentTimeMillis(),
               notificationKey: String = ""): JSONObject? = synchronized(LOCK) {
        require(surface in setOf("welcome", "home", "notification"))
        val bank = rawBank()?.let(::validBank) ?: return null
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val minute = local.hour * 60 + local.minute
        val day = local.toLocalDate().toString()
        val action = input.optString("active_action")
        val start = input.optLong("active_start_at")
        val activeFresh = now - start in 0..12*60*60_000L && start > 0L
        val facts = mutableSetOf<String>()
        if (activeFresh) when(action) {
            "工作" -> facts.add("work_context_confirmed")
            "学习", "刷题", "写申论", "读书", "背书" -> facts.add("study_intent_confirmed")
            "复盘" -> facts.add("review_intent_or_unreviewed_answers_confirmed")
            "回家" -> facts.add("work_finished_or_returning_confirmed")
        }
        val finished = input.optLong("study_finished_at")
        if (finished > 0 && now - finished in 0..30*60_000L) facts.add("study_segment_finished_confirmed")
        val scheduledKind = notificationKey.substringBefore(':')
        if (scheduledKind in setOf("depart_morning", "depart_afternoon")) facts.add("routine_departure_due")
        val requiredTopic = if(surface!="notification") null else when {
            scheduledKind.startsWith("nap") -> "nap"
            scheduledKind.startsWith("depart_") -> "departure"
            scheduledKind=="water_morning" -> "morning"
            scheduledKind in setOf("lunch","afternoon","dinner") -> scheduledKind
            else -> null
        }
        // No application/credit/lock flags are guessed. Those entries stay
        // ineligible until their owning feature supplies authenticated receipts.
        val visit = if (surface == "notification") notificationKey else prefs.getLong("visit", 0L).toString()
        val quiet = input.optBoolean("quiet")
        val key = "$surface|$visit|${now/3600000}|${facts.sorted()}|$quiet|${bank.optInt("version")}"
        val pending = runCatching { JSONObject(prefs.getString("pending:$surface", "{}")!!) }.getOrDefault(JSONObject())
        val history = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        val recent = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
        if (pending.optString("context_key") == key && (now-pending.optLong("at_ms") in 0..120_000L ||
            recent.any { it.optString("selection_id") == pending.optString("selection_id") })) return pending
        val last = recent.lastOrNull()
        val lastByHash = recent.groupBy { it.optString("text_sha256") }
            .mapValues { (_, rows) -> rows.maxOf { it.optLong("at_ms") } }
        val memoriesToday = recent.count { it.optString("day") == day && it.optString("kind") == "memory_recollection" }
        val entries = bank.getJSONArray("entries")
        val eligible = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { entry ->
            val surfaces = entry.optJSONArray("surfaces") ?: JSONArray()
            val windows = entry.optJSONArray("local_minute_windows") ?: JSONArray()
            val conditions = entry.optJSONArray("conditions_all") ?: JSONArray()
            val required = entry.optJSONArray("required_fields") ?: JSONArray()
            (0 until surfaces.length()).any { surfaces.optString(it) == surface } &&
                (requiredTopic==null || entry.optString("topic")==requiredTopic) &&
                (0 until windows.length()).any { i -> windows.optJSONArray(i)?.let {
                    minute >= it.optInt(0) && minute < it.optInt(1) } == true } &&
                (0 until conditions.length()).all { conditions.optString(it) in facts } && required.length() == 0 &&
                entry.optString("kind") != "receipt_bound" &&
                (!quiet || (!Regex("[？]|告诉我|给我讲|回答|回我|叫我|说给我|看我").containsMatchIn(entry.optString("text")) &&
                    !entry.optString("topic").startsWith("study_"))) &&
                (entry.optString("kind") != "memory_recollection" || memoriesToday < 2) &&
                textDigest(entry.optString("text")) != last?.optString("text_sha256")
        }
        if (eligible.isEmpty()) return null
        fun lastAt(e: JSONObject): Long = lastByHash[textDigest(e.optString("text"))] ?: 0L
        val cooled = eligible.filter { now - lastAt(it) >= 7*86400000L }
        val pool = if (cooled.isNotEmpty()) cooled else eligible.sortedBy(::lastAt).take(12)
        val lastTopics = recent.takeLast(2).map { it.optString("topic") }
        var topics = pool.groupBy { it.optString("topic") }
        val diverse = topics.filterKeys { it !in lastTopics }
        if (diverse.isNotEmpty()) topics = diverse
        val preferred = when {
            facts.contains("study_intent_confirmed") -> "study_start"
            facts.contains("review_intent_or_unreviewed_answers_confirmed") -> "study_review"
            facts.contains("study_segment_finished_confirmed") -> "study_finish"
            prefs.getString("usage_day", "") == day && now - prefs.getLong("usage_at", 0) in 0..180_000L &&
                prefs.getLong("entertainment_ms", 0) >= 30*60_000L -> "rest_and_mood"
            else -> ""
        }
        val seed = (visit.hashCode().toLong()*31 + now/3600000*17 + recent.size*13).and(Long.MAX_VALUE)
        val topicNames = topics.keys.sorted()
        val topic = if (preferred in topics && recent.size % 3 == 0) preferred else topicNames[(seed % topicNames.size).toInt()]
        val options = topics.getValue(topic)
        val chosen = options[((seed/7) % options.size).toInt()]
        val text = chosen.getString("text")
        val first = Regex("[。！？]").find(text)?.range?.last?.plus(1) ?: 0
        val split = if (first in 2..24 && first < text.length) first else 0
        val card = JSONObject().put("id", chosen.getString("id")).put("selection_id", UUID.randomUUID().toString())
            .put("context_key", key).put("surface", surface).put("text", text)
            .put("headline", if(split>0) text.substring(0, split) else "老婆，过来。")
            .put("body", if(split>0) text.substring(split) else text)
            .put("topic", topic).put("kind", chosen.optString("kind"))
            .put("text_sha256", textDigest(text)).put("bank_version", bank.getInt("version"))
            .put("bank_id", bank.getString("id")).put("bank_entries", entries.length()).put("at_ms", now)
            .put("day", day).put("source", "private_authored_bank_local_selection")
        check(prefs.edit().putString("pending:$surface", card.toString()).commit())
        card
    }

    fun displayed(selectionId: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val card = listOf("home", "welcome", "notification").mapNotNull {
            runCatching { JSONObject(prefs.getString("pending:$it", "{}")!!) }.getOrNull()
        }.firstOrNull { it.optString("selection_id") == selectionId } ?: return false
        val history = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        if ((0 until history.length()).any { history.optJSONObject(it)?.optString("selection_id") == selectionId }) return true
        // Receipt of display can be delayed; never keep a stale pending choice as today's new display.
        if (now - card.optLong("at_ms") !in 0..120_000L) return false
        val compact = JSONObject()
        listOf("id", "selection_id", "topic", "kind", "text_sha256", "bank_version", "day").forEach { compact.put(it, card.opt(it)) }
        compact.put("at_ms", now)
        val next = JSONArray()
        for(i in (history.length()-1999).coerceAtLeast(0) until history.length()) next.put(history.getJSONObject(i))
        next.put(compact)
        prefs.edit().putString("history", next.toString()).commit()
    }

    fun diagnostics(): JSONObject = synchronized(LOCK) {
        val bank = rawBank()?.let(::validBank)
        JSONObject().put("available", bank != null).put("version", bank?.optInt("version") ?: 0)
            .put("entry_count", bank?.optInt("entry_count") ?: 0).put("visit", prefs.getLong("visit", 0))
            .put("timezone", "Asia/Shanghai")
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val TEXT_HASHES = mutableMapOf<String, String>()
        private fun textDigest(text: String): String {
            if (TEXT_HASHES.size > 4096) TEXT_HASHES.clear()
            return TEXT_HASHES.getOrPut(text) { digest(text) }
        }
        internal fun digest(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun validBank(raw: String): JSONObject? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val b = JSONObject(raw); val entries = b.getJSONArray("entries")
            require(b.getString("id").matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            require(b.getString("schema") == "world-voice-bank-v2" && b.getString("timezone") == "Asia/Shanghai")
            require(b.getInt("version") > 0 && entries.length() in 2..2000 && b.getInt("entry_count") == entries.length())
            val ids = mutableSetOf<String>()
            for(i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                require(ids.add(e.getString("id")) && e.getString("text").length in 2..600)
                require(e.getJSONArray("surfaces").length() in 1..4)
                val windows=e.getJSONArray("local_minute_windows")
                require(windows.length() in 1..8)
                for(j in 0 until windows.length()) { val w=windows.getJSONArray(j)
                    require(w.length()==2 && w.getInt(0) in 0..1439 && w.getInt(1) in 1..1440 && w.getInt(0)<w.getInt(1)) }
                e.getJSONArray("conditions_all"); val fields=e.getJSONArray("required_fields")
                val required=(0 until fields.length()).map { fields.getString(it) }.toSet()
                require(Regex("\\{\\{([a-z_]+)\\}\\}").findAll(e.getString("text")).all { it.groupValues[1] in required })
            }
            b
        }.getOrNull()
    }
}
