package dev.jlz.presence.actions

import android.content.Context
import dev.jlz.presence.runtime.RuntimeCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs a short, fully-local action plan (recipes such as
 * "open ChatGPT -> wait -> home -> open 粉笔"). No network round-trip per
 * step. Status is layered honestly: queued/accepted are local, `executed`
 * reflects the gesture/action result; delivered/observed/handled are upstream
 * states and are never claimed here.
 */
class LocalActionPlanExecutor(private val context: Context) {
    private val executor = DeviceActionExecutor(context)

    suspend fun executePlan(plan: JSONObject): JSONObject {
        val steps = plan.optJSONArray("steps")
            ?: return JSONObject().put("ok", false).put("error", "no_steps")
        val results = JSONArray()
        var allOk = true
        val startedAt = System.currentTimeMillis()
        var executed = 0
        for (i in 0 until minOf(steps.length(), 30)) {
            val step = steps.optJSONObject(i) ?: continue
            val stepStart = System.currentTimeMillis()
            val (ok, detail) = executor.execute(
                RuntimeCommand(
                    "plan:$i",
                    step.optString("action"),
                    step,
                    step.optString("package").ifBlank { step.optString("package_name") }
                        .takeIf { it.isNotBlank() },
                    step.optString("app").takeIf { it.isNotBlank() }
                )
            )
            val elapsed = System.currentTimeMillis() - stepStart
            results.put(
                JSONObject()
                    .put("index", i + 1)
                    .put("action", step.optString("action"))
                    .put("queued", true)
                    .put("accepted", true)
                    .put("executed", ok)
                    .put("elapsed_ms", elapsed)
                    .put("detail", detail.take(500))
            )
            executed++
            if (!ok) {
                allOk = false
                if (plan.optBoolean("stop_on_error", true)) break
            }
        }
        return JSONObject()
            .put("ok", allOk)
            .put("executed_steps", executed)
            .put("total_elapsed_ms", System.currentTimeMillis() - startedAt)
            .put("stop_on_error", plan.optBoolean("stop_on_error", true))
            .put("results", results)
    }
}
