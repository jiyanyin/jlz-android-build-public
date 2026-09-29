package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import dev.jlz.presence.ui.theme.ParchmentMineBubble
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Self-report dimensions. Null means not answered, never a score of 50. */
internal data class StatusAxis(
    val key: String, val title: String, val question: String,
    val low: String, val high: String
)

internal val STATUS_AXES = listOf(
    StatusAxis("self_presence", "自我在场感", "正在生活、说话、行动的人，像我自己吗？",
        "像在自动运行", "我就是我"),
    StatusAxis("emotion_access", "情绪可感度", "能不能接触到自己的情绪？不要求说出名字。",
        "摸不到情绪", "感受得很清楚"),
    StatusAxis("emotional_vividness", "情感鲜活度", "情绪和情感有没有温度和鲜活感？",
        "麻木、冷淡", "鲜活、有温度"),
    StatusAxis("agitation", "宁静—烦躁", "此刻内在有多躁动、紧绷？",
        "宁静、松弛", "烦躁、紧绷")
)
private val EMOTION_TAGS = listOf(
    "开心", "安心", "兴奋", "被打动", "满足", "好奇",
    "委屈", "失落", "难过", "孤独", "生气", "烦躁",
    "担心", "害怕", "茫然", "麻木", "没感觉", "说不清"
)
private val EXPRESSION_TAGS = listOf(
    "说话平静", "正在微笑", "正在哭", "不想说话",
    "机械应答", "表现烦躁", "正常交流但内心没感觉"
)
/** Relationship needs are not task orders. Use direct, evocative language. */
private val CLOSENESS_NEED_TAGS = listOf(
    "抱紧我，别急着放开", "主动亲亲我",
    "直白地说想靠近我", "主动向我讨亲亲",
    "让我感受到偏爱", "热烈一点，别太克制",
    "强势一点，但要宠我", "你也向我撒娇、有所求",
    "主动逗逗我", "和我亲密聊天",
    "先不聊任务，只陪我", "安静拥着我"
)
/** Keep practical support explicitly separate from closeness requests. */
private val PRACTICAL_NEED_TAGS = listOf(
    "认真听我说", "帮我识别", "主动问问我",
    "陪我启动", "直接安排", "提醒我休息",
    "暂时不用回应", "我还不知道"
)
private val BODY_AREAS = listOf(
    "头部" to "body_head", "眼睛" to "body_eyes",
    "喉咙" to "body_throat", "胸口" to "body_chest",
    "胃腹" to "body_stomach", "肩颈" to "body_shoulders",
    "四肢" to "body_limbs", "全身" to "body_whole"
)
private val BODY_SIGNALS = listOf(
    "疼痛", "紧绷", "发沉", "麻木", "乏力",
    "呼吸变化", "流泪", "发热", "发冷", "其他"
)

@Composable
internal fun StatusSlider(
    title: String, question: String, low: String, high: String,
    value: Int?, onChange: (Int?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(value?.let { it.toString() + "/100" } ?: "未填写",
                color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        if (question.isNotBlank())
            Text(question, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = (value ?: 50).toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 100)) },
            valueRange = 0f..100f, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(high, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        if (value != null) TextButton(onClick = { onChange(null) }) { Text("清空") }
    }
}

@Composable
internal fun StatusSliders(values: Map<String, Int>, onChange: (String, Int?) -> Unit) {
    STATUS_AXES.forEach { axis ->
        StatusSlider(axis.title, axis.question, axis.low, axis.high, values[axis.key]) {
            onChange(axis.key, it)
        }
    }
}

/** State is a neutral legacy API label, NOT an invented mood. */
internal fun statusDraft(axes: Map<String, Int>): JSONObject =
    JSONObject().put("state", "我的此刻").put("schema_version", 1)
        .put("dimensions", JSONObject().apply {
            axes.forEach { (key, value) ->
                if (STATUS_AXES.any { it.key == key } && value in 0..100) put(key, value)
            }
        })

internal fun statusSummary(status: JSONObject?): String {
    if (status == null) return "还没有更新状态灯"
    val dims = status.optJSONObject("dimensions")
        ?: status.optJSONObject("metadata_json")?.optJSONObject("dimensions")
    if (dims == null) return status.optString("state").ifBlank { "还没有更新状态灯" }
    val pieces = STATUS_AXES.mapNotNull { axis ->
        val value = dims.optInt(axis.key, -1)
        if (value in 0..100) axis.title + " " + value else null
    }
    return pieces.joinToString(" · ").ifBlank {
        status.optString("state").ifBlank { "我的此刻" }
    }
}

@Composable
private fun ChoiceGrid(
    title: String, choices: List<String>, selected: Set<String>,
    hint: String = "", onChoice: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        if (hint.isNotBlank())
            Text(hint, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        choices.chunked(2).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.forEach { option ->
                    FilterChip(
                        selected = option in selected, onClick = { onChoice(option) },
                        modifier = Modifier.weight(1f),
                        label = { Text(option, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

private fun jsonArray(values: Set<String>) = JSONArray().apply { values.forEach { put(it) } }

/** Display categorized needs while retaining old mixed-need snapshots. */
internal fun statusNeedLines(status: JSONObject?): List<String> {
    if (status == null) return emptyList()
    val metadata = status.optJSONObject("metadata_json")
    val dimensions = status.optJSONObject("dimensions")
        ?: metadata?.optJSONObject("dimensions")
    fun tags(array: JSONArray?): String =
        if (array == null) "" else (0 until array.length())
            .map { array.optString(it) }
            .filter { it.isNotBlank() }.joinToString("、")
    val closeness = tags(dimensions?.optJSONArray("closeness_needs"))
    val practical = tags(dimensions?.optJSONArray("practical_needs"))
    if (dimensions?.has("closeness_needs") == true ||
        dimensions?.has("practical_needs") == true) {
        return listOfNotNull(
            closeness.takeIf { it.isNotBlank() }?.let { "亲密愿望：$it" },
            practical.takeIf { it.isNotBlank() }?.let { "生活协助：$it" }
        )
    }
    val legacy = tags(dimensions?.optJSONArray("needs"))
        .ifBlank { status.optString("need").ifBlank {
            metadata?.optString("need").orEmpty()
        } }
    return legacy.takeIf { it.isNotBlank() }
        ?.let { listOf("当时需要：$it") } ?: emptyList()
}

/** Human-readable multi-select reply preferences, with historical single-select fallback. */
internal fun statusResponseLines(status: JSONObject?): List<String> {
    if (status == null) return emptyList()
    val metadata = status.optJSONObject("metadata_json")
    val dimensions = status.optJSONObject("dimensions")
        ?: metadata?.optJSONObject("dimensions")
    val styles = dimensions?.optJSONArray("response_styles")
    if (styles != null && styles.length() > 0) {
        val selected = (0 until styles.length()).map { styles.optString(it) }
            .filter { it.isNotBlank() }
        if (selected.isNotEmpty()) return listOf("希望我这样回应：" + selected.joinToString("、"))
    }
    val oldStyle = dimensions?.optString("response_style").orEmpty()
        .ifBlank { status.optString("response_style").ifBlank {
            metadata?.optString("response_style").orEmpty()
        } }
    return oldStyle.takeIf { it.isNotBlank() }
        ?.let { listOf("希望我这样回应：$it") } ?: emptyList()
}

/**
 * V3: four independent optional scales + expandable emotion/expression/body/needs.
 * All inputs are user-authored, without automatic psychiatric labels.
 */
@Composable
fun StatusLightEditor(busy: Boolean, onSave: (JSONObject) -> Unit) {
    val axes = remember { mutableStateMapOf<String, Int>() }
    var expanded by remember { mutableStateOf(false) }
    var emotions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expressions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var closenessNeeds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var practicalNeeds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bodySignals by remember { mutableStateOf<Set<String>>(emptySet()) }
    val body = remember { mutableStateMapOf<String, Int?>() }
    var responseStyles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var note by remember { mutableStateOf("") }

    // The form is taller than a phone screen. In P0-3 the surrounding page
    // is already a LazyColumn; do not leave this whole form as a single
    // unbounded, difficult-to-drag LazyColumn item. Give the editor a finite
    // height and its own vertical scroll area, independent of horizontal
    // Material Slider gestures. Other page/history scrolling is unchanged.
    val viewportDp = (LocalConfiguration.current.screenHeightDp - 180)
        .coerceIn(280, 640)
    val formScrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxWidth()
            .heightIn(max = viewportDp.dp)
            .verticalScroll(formScrollState),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("状态灯 · 记录此刻的我", color = TextPrimary,
            style = MaterialTheme.typography.titleMedium)
        Text("只填你知道的。说不清就留空；指针居中不代表自动填写50分。",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text("在表单内上下滑动可继续填写；滑杆左右拖动才改变分数。",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        StatusSliders(axes) { key, value ->
            if (value == null) axes.remove(key) else axes[key] = value
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起选填项 ↑" else "选填：情绪 / 对外表达 / 身体 / 需要 ↓")
        }
        if (expanded) {
            ChoiceGrid("我能辨认出的情绪", EMOTION_TAGS, emotions,
                hint = "可多选，也可以完全不选") { v ->
                emotions = if (v in emotions) emotions - v
                    else if (emotions.size < 5) emotions + v else emotions
            }
            ChoiceGrid("我表现出来的样子", EXPRESSION_TAGS, expressions,
                hint = "外在表达可以和内心感受不一致") { v ->
                expressions = if (v in expressions) expressions - v
                    else if (expressions.size < 5) expressions + v else expressions
            }
            ChoiceGrid("身体哪里不舒服？", BODY_AREAS.map { it.first },
                body.keys.mapNotNull { key ->
                    BODY_AREAS.find { it.second == key }?.first
                }.toSet(), hint = "点选部位，再按需调整不适程度") { label ->
                val key = BODY_AREAS.first { it.first == label }.second
                if (body.containsKey(key)) body.remove(key) else body[key] = null
            }
            BODY_AREAS.forEach { (label, key) ->
                if (body.containsKey(key))
                    StatusSlider(label + " · 不适程度", "", "没有明显不适",
                        "明显不适", body[key]) { value -> body[key] = value }
            }
            ChoiceGrid("身体的具体信号", BODY_SIGNALS, bodySignals) { v ->
                bodySignals = if (v in bodySignals) bodySignals - v
                    else if (bodySignals.size < 5) bodySignals + v else bodySignals
            }
            ChoiceGrid("我想怎样和你亲近", CLOSENESS_NEED_TAGS, closenessNeeds,
                hint = "只记录亲密愿望；最多五项，不占用生活协助的名额。") { v ->
                closenessNeeds = if (v in closenessNeeds) closenessNeeds - v
                    else if (closenessNeeds.size < 5) closenessNeeds + v else closenessNeeds
            }
            ChoiceGrid("生活里的帮助（选填）", PRACTICAL_NEED_TAGS, practicalNeeds,
                hint = "独立记录生活协助；即使亲密愿望已经选满也可以选择。") { v ->
                practicalNeeds = if (v in practicalNeeds) practicalNeeds - v
                    else if (practicalNeeds.size < 5) practicalNeeds + v else practicalNeeds
            }
            ChoiceGrid(
                "想听我怎样回应",
                listOf(
                    "热烈直白一点", "温柔地宠着我",
                    "强势一点但疼我", "主动向我讨亲近",
                    "多逗逗我", "先别分析",
                    "冷静简短就好", "现在先不用回复"
                ),
                responseStyles,
                hint = "可以多选，最多五项；「现在先不用回复」与其他回应方式互斥。"
            ) { v ->
                responseStyles = when {
                    v in responseStyles -> responseStyles - v
                    v == "现在先不用回复" -> setOf(v)
                    "现在先不用回复" in responseStyles -> setOf(v)
                    responseStyles.size < 5 -> responseStyles + v
                    else -> responseStyles
                }
            }
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(240) },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("一句原话（选填）") },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = ParchmentMineBubble.copy(alpha = 0.48f),
                unfocusedContainerColor = ParchmentMineBubble.copy(alpha = 0.48f)
            )
        )
        val canSave = axes.isNotEmpty() || emotions.isNotEmpty() ||
            expressions.isNotEmpty() || closenessNeeds.isNotEmpty() ||
            practicalNeeds.isNotEmpty() || body.isNotEmpty() ||
            bodySignals.isNotEmpty() || responseStyles.isNotEmpty() || note.isNotBlank()
        IceButton(
            text = "记下这个时刻", primary = true, enabled = !busy && canSave,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val draft = statusDraft(axes)
                val dimensions = draft.getJSONObject("dimensions")
                if (emotions.isNotEmpty()) dimensions.put("emotions", jsonArray(emotions))
                if (expressions.isNotEmpty())
                    dimensions.put("external_expression", jsonArray(expressions))
                // Category-specific fields let the companion distinguish wanting
                // affection from requesting help. The union is for old readers.
                if (closenessNeeds.isNotEmpty())
                    dimensions.put("closeness_needs", jsonArray(closenessNeeds))
                if (practicalNeeds.isNotEmpty())
                    dimensions.put("practical_needs", jsonArray(practicalNeeds))
                if (closenessNeeds.isNotEmpty() || practicalNeeds.isNotEmpty())
                    dimensions.put("needs", jsonArray(closenessNeeds + practicalNeeds))
                if (bodySignals.isNotEmpty())
                    dimensions.put("body_signals", jsonArray(bodySignals))
                if (responseStyles.isNotEmpty()) {
                    dimensions.put("response_styles", jsonArray(responseStyles))
                    // Old clients expect exactly one string; keep a compatible
                    // first choice while the full set lives in response_styles.
                    dimensions.put("response_style", responseStyles.first())
                }
                if (body.isNotEmpty())
                    dimensions.put("body_areas", jsonArray(body.keys.toSet()))
                body.forEach { (key, value) ->
                    if (value != null) dimensions.put(key, value)
                }
                if (note.isNotBlank()) draft.put("detail", note)
                onSave(draft)
            }
        )
        Text("只保存实际填写的项目；保留原话和历史。我的猜测不会覆盖你的记录。",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp))
    }
}
