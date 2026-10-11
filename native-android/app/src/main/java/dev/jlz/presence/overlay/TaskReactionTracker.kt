package dev.jlz.presence.overlay

import android.content.Context
import org.json.JSONObject

/** A bounded observation cursor only; DailyPlan remains the sole task store. */
class TaskReactionTracker(context: Context) {
    private val prefs = context.getSharedPreferences("jlz_q_task_cursor_v1", Context.MODE_PRIVATE)
    fun accept(plan: JSONObject) {
        val date = plan.optString("date")
        if (date.isBlank()) return
        val tasks = plan.optJSONArray("tasks") ?: return
        val done = (0 until tasks.length()).map { tasks.getJSONObject(it) }
            .filter { it.optString("status") == "done" }.map { it.optString("task_id") }.filter { it.isNotBlank() }.toSet()
        val previous = prefs.getStringSet("done", emptySet()).orEmpty()
        val celebrate = prefs.getString("date", "") == date && (done - previous).isNotEmpty()
        prefs.edit().putString("date", date).putStringSet("done", done).apply()
        if (celebrate) android.os.Handler(android.os.Looper.getMainLooper()).post { FloatingPresenceService.localCelebration() }
    }
}
