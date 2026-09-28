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
    RUNNING,
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
        .put("running", steps.count { it.status == PresencePlanStepStatus.RUNNING })
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
        require(validUntil > now && validUntil <= now + 70 * 60_000L) {
            "invalid_plan_window"
        }

        val previous = load()
        // Retrying an already accepted plan must never reset completed
        // steps to PENDING, causing the phone to ring twice.
        if (previous?.planId == planId) return previous

        val array = root.optJSONArray("steps") ?: JSONArray()
        require(array.length() in 1..12) { "invalid_plan_step_count" }
        val stepIds = mutableSetOf<String>()
        val steps = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val stepId = item.optString("step_id")
                    .takeIf { it.isNotBlank() }
                    ?: "step-" + index
                require(stepIds.add(stepId)) { "duplicate_plan_step_id" }
                val action = item.optString("action").trim()
                require(action == "send_notification" || action == "presence_callback") {
                    "unsupported_plan_step_action"
                }

                val condition = item.optJSONObject("condition")
                val executeAt = item.optLong("execute_at_ms", generatedAt)
                val expiresAt = item.optLong("expires_at_ms", validUntil)
                require(executeAt >= now - 30_000L && executeAt <= validUntil &&
                    expiresAt >= executeAt && expiresAt <= validUntil) {
                    "invalid_plan_step_window"
                }

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

    /**
     * Claim steps BEFORE side effects. Both the command loop and the 15-second
     * plan loop can wake at the same time; DataStore.edit makes one claimant
     * the sole executor. A crash while RUNNING is reported as unknown at
     * expiry, never retried blindly (which could duplicate a call).
     */
    suspend fun claimDueSteps(
        nowMs: Long,
        semanticPlace: String?,
        foregroundPackage: String?
    ): Pair<PresencePlan?, List<PresencePlanStep>> {
        var selected: Pair<PresencePlan?, List<PresencePlanStep>> = null to emptyList()
        context.presencePlanDataStore.edit { prefs ->
            val current = prefs[Keys.planJson]?.let(::decode) ?: return@edit
            var changed = false
            val claimed = mutableListOf<PresencePlanStep>()
            val updatedSteps = current.steps.map { step ->
                when {
                    step.status == PresencePlanStepStatus.RUNNING &&
                        (nowMs > current.validUntilMs || nowMs > step.expiresAtMs) -> {
                        changed = true
                        step.copy(status = PresencePlanStepStatus.FAILED,
                            result = "outcome_unknown_after_interruption")
                    }
                    step.status == PresencePlanStepStatus.PENDING &&
                        (nowMs > current.validUntilMs || nowMs > step.expiresAtMs) -> {
                        changed = true
                        step.copy(status = PresencePlanStepStatus.EXPIRED, result = "expired_without_execution")
                    }
                    step.status == PresencePlanStepStatus.PENDING &&
                        nowMs >= step.executeAtMs && nowMs <= step.expiresAtMs &&
                        matches(step.requiredSemanticPlace, semanticPlace) &&
                        matches(step.requiredForegroundPackage, foregroundPackage) -> {
                        changed = true
                        val running = step.copy(status = PresencePlanStepStatus.RUNNING,
                            result = "claimed_before_execution")
                        claimed += running
                        running
                    }
                    else -> step
                }
            }
            val next = if (changed) current.copy(steps = updatedSteps) else current
            if (changed) prefs[Keys.planJson] = encode(next).toString()
            selected = next to claimed
        }
        return selected
    }

    suspend fun markResult(
        planId: String,
        stepId: String,
        ok: Boolean,
        result: String
    ): PresencePlan? {
        var actual: PresencePlan? = null
        context.presencePlanDataStore.edit { prefs ->
            val current = prefs[Keys.planJson]?.let(::decode) ?: return@edit
            if (current.planId != planId) {
                // Old in-flight work must not overwrite a replacement plan.
                actual = current
                return@edit
            }
            val updatedSteps = current.steps.map { step ->
                if (step.stepId != stepId || step.status != PresencePlanStepStatus.RUNNING) step
                else step.copy(
                    status = if (ok) PresencePlanStepStatus.COMPLETED
                        else PresencePlanStepStatus.FAILED,
                    result = result.take(500)
                )
            }
            val next = current.copy(steps = updatedSteps)
            prefs[Keys.planJson] = encode(next).toString()
            actual = next
        }
        return actual
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
