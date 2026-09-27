package dev.jlz.presence.agency

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.presencePlanDataStore by preferencesDataStore(name = "jlz_presence_plan")

enum class PresencePlanStepStatus {
    PENDING,
    COMPLETED,
    FAILED,
    EXPIRED
}

data class PresencePlanStep(
    val stepId: String,
    val action: String,
    val packageName: String? = null,
    val appName: String? = null,
    val payloadJson: String = "{}",
    val executeAtMs: Long,
    val expiresAtMs: Long,
    val requiredSemanticPlace: String? = null,
    val requiredForegroundPackage: String? = null,
    val status: PresencePlanStepStatus = PresencePlanStepStatus.PENDING,
    val result: String? = null
)

data class PresencePlan(
    val planId: String,
    val label: String,
    val generatedAtMs: Long,
    val validUntilMs: Long,
    val steps: List<PresencePlanStep>
) {
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs <= validUntilMs && steps.any { it.status == PresencePlanStepStatus.PENDING }

    fun summaryJson(): JSONObject = JSONObject()
        .put("plan_id", planId)
        .put("label", label)
        .put("generated_at_ms", generatedAtMs)
        .put("valid_until_ms", validUntilMs)
        .put(
            "pending",
            steps.count { it.status == PresencePlanStepStatus.PENDING }
        )
        .put(
            "completed",
            steps.count { it.status == PresencePlanStepStatus.COMPLETED }
        )
        .put(
            "failed",
            steps.count { it.status == PresencePlanStepStatus.FAILED }
        )
        .put(
            "expired",
            steps.count { it.status == PresencePlanStepStatus.EXPIRED }
        )
}

class PresencePlanRepository(private val context: Context) {
    private object Keys {
        val planJson = stringPreferencesKey("plan_json")
    }

    val plan: Flow<PresencePlan?> = context.presencePlanDataStore.data.map { prefs ->
        prefs[Keys.planJson]?.takeIf { it.isNotBlank() }?.let(::decode)
    }

    suspend fun load(): PresencePlan? =
        context.presencePlanDataStore.data
            .map { prefs ->
                prefs[Keys.planJson]?.takeIf { it.isNotBlank() }?.let(::decode)
            }
            .first()

    suspend fun replaceFromPayload(payload: JSONObject): PresencePlan {
        val root = payload.optJSONObject("plan") ?: payload
        val now = System.currentTimeMillis()
        val generatedAt = root.optLong("generated_at_ms", now)
        val validUntil = root.optLong(
            "valid_until_ms",
            generatedAt + DEFAULT_PLAN_WINDOW_MS
        )
        val planId = root.optString("plan_id")
            .takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val label = root.optString("label")
            .takeIf { it.isNotBlank() }
            ?: "接下来一小时"

        val array = root.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val stepId = item.optString("step_id")
                    .takeIf { it.isNotBlank() }
                    ?: "step-" + index
                val action = item.optString("action").trim()
                if (action.isBlank()) continue

                val condition = item.optJSONObject("condition")
                val executeAt = item.optLong("execute_at_ms", generatedAt)
                val expiresAt = item.optLong("expires_at_ms", validUntil)
                    .coerceAtMost(validUntil)

                add(
                    PresencePlanStep(
                        stepId = stepId,
                        action = action,
                        packageName = item.optString("package").takeIf { it.isNotBlank() },
                        appName = item.optString("app").takeIf { it.isNotBlank() },
                        payloadJson = (item.optJSONObject("payload") ?: JSONObject()).toString(),
                        executeAtMs = executeAt,
                        expiresAtMs = expiresAt,
                        requiredSemanticPlace = (
                            condition?.optString("semantic_place")
                                ?: item.optString("semantic_place")
                            ).takeIf { !it.isNullOrBlank() }?.uppercase(),
                        requiredForegroundPackage = (
                            condition?.optString("foreground_package")
                                ?: item.optString("foreground_package")
                            ).takeIf { !it.isNullOrBlank() },
                        status = PresencePlanStepStatus.PENDING
                    )
                )
            }
        }

        val plan = PresencePlan(
            planId = planId,
            label = label,
            generatedAtMs = generatedAt,
            validUntilMs = validUntil,
            steps = steps
        )
        persist(plan)
        return plan
    }

    suspend fun clear() {
        context.presencePlanDataStore.edit { it.remove(Keys.planJson) }
    }

    suspend fun dueSteps(
        nowMs: Long,
        semanticPlace: String?,
        foregroundPackage: String?
    ): Pair<PresencePlan?, List<PresencePlanStep>> {
        val current = load() ?: return null to emptyList()
        var changed = false

        val normalized = current.steps.map { step ->
            if (
                step.status == PresencePlanStepStatus.PENDING &&
                (nowMs > current.validUntilMs || nowMs > step.expiresAtMs)
            ) {
                changed = true
                step.copy(status = PresencePlanStepStatus.EXPIRED, result = "expired")
            } else {
                step
            }
        }

        val plan = if (changed) current.copy(steps = normalized) else current
        if (changed) persist(plan)

        if (nowMs > plan.validUntilMs) return plan to emptyList()

        val due = plan.steps.filter { step ->
            step.status == PresencePlanStepStatus.PENDING &&
                nowMs >= step.executeAtMs &&
                nowMs <= step.expiresAtMs &&
                matches(step.requiredSemanticPlace, semanticPlace) &&
                matches(step.requiredForegroundPackage, foregroundPackage)
        }
        return plan to due
    }

    suspend fun markResult(
        stepId: String,
        ok: Boolean,
        result: String
    ): PresencePlan? {
        val current = load() ?: return null
        val updated = current.copy(
            steps = current.steps.map { step ->
                if (step.stepId != stepId) step
                else step.copy(
                    status = if (ok) {
                        PresencePlanStepStatus.COMPLETED
                    } else {
                        PresencePlanStepStatus.FAILED
                    },
                    result = result.take(500)
                )
            }
        )
        persist(updated)
        return updated
    }

    suspend fun summaryJson(): JSONObject? = load()?.summaryJson()

    private suspend fun persist(plan: PresencePlan) {
        context.presencePlanDataStore.edit { prefs ->
            prefs[Keys.planJson] = encode(plan).toString()
        }
    }

    private fun matches(required: String?, actual: String?): Boolean {
        if (required.isNullOrBlank()) return true
        return required.equals(actual, ignoreCase = true)
    }

    private fun encode(plan: PresencePlan): JSONObject = JSONObject()
        .put("plan_id", plan.planId)
        .put("label", plan.label)
        .put("generated_at_ms", plan.generatedAtMs)
        .put("valid_until_ms", plan.validUntilMs)
        .put(
            "steps",
            JSONArray().apply {
                plan.steps.forEach { step ->
                    put(
                        JSONObject()
                            .put("step_id", step.stepId)
                            .put("action", step.action)
                            .put("package", step.packageName)
                            .put("app", step.appName)
                            .put("payload", JSONObject(step.payloadJson))
                            .put("execute_at_ms", step.executeAtMs)
                            .put("expires_at_ms", step.expiresAtMs)
                            .put("semantic_place", step.requiredSemanticPlace)
                            .put("foreground_package", step.requiredForegroundPackage)
                            .put("status", step.status.name)
                            .put("result", step.result)
                    )
                }
            }
        )

    private fun decode(raw: String): PresencePlan? = runCatching {
        val root = JSONObject(raw)
        val stepsJson = root.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(index) ?: continue
                add(
                    PresencePlanStep(
                        stepId = item.optString("step_id"),
                        action = item.optString("action"),
                        packageName = item.optString("package").takeIf { it.isNotBlank() },
                        appName = item.optString("app").takeIf { it.isNotBlank() },
                        payloadJson = (item.optJSONObject("payload") ?: JSONObject()).toString(),
                        executeAtMs = item.optLong("execute_at_ms"),
                        expiresAtMs = item.optLong("expires_at_ms"),
                        requiredSemanticPlace = item.optString("semantic_place")
                            .takeIf { it.isNotBlank() && it != "null" },
                        requiredForegroundPackage = item.optString("foreground_package")
                            .takeIf { it.isNotBlank() && it != "null" },
                        status = runCatching {
                            PresencePlanStepStatus.valueOf(item.optString("status"))
                        }.getOrDefault(PresencePlanStepStatus.PENDING),
                        result = item.optString("result")
                            .takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
        PresencePlan(
            planId = root.optString("plan_id"),
            label = root.optString("label", "接下来一小时"),
            generatedAtMs = root.optLong("generated_at_ms"),
            validUntilMs = root.optLong("valid_until_ms"),
            steps = steps
        )
    }.getOrNull()


    companion object {
        private const val DEFAULT_PLAN_WINDOW_MS = 60 * 60_000L
    }
}
