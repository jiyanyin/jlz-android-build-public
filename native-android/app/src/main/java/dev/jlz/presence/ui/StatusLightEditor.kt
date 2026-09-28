package dev.jlz.presence.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary
import org.json.JSONArray
import org.json.JSONObject

/** Subjective input only. These choices are self-reports, NOT inferred diagnoses. */
private val OVERALL_STATES = listOf(
    "开心", "轻松", "平静", "放空", "茫然", "烦躁",
    "焦虑", "难过", "生气", "疲惫", "期待", "说不清"
)
private val QUICK_NEEDS = listOf(
    "认真听我说", "主动问问我", "直接安排", "陪我启动",
    "贴贴安慰", "安静陪着", "提醒我休息", "暂时不知道"
)
private val EMOTION_TAGS = listOf(
    "愉快", "满足", "安心", "期待", "兴奋", "紧张", "烦躁",
    "生气", "委屈", "难过", "担心", "孤独", "无聊", "麻木", "没感觉", "混合着"
)
private val ATTENTION_STATES = listOf(
    "脑子清楚", "有点迟钝", "容易走神", "念头很乱",
    "启动很难", "已经投入", "不确定"
)
private val BODY_SIGNALS = listOf(
    "身体轻松", "犯困", "乏力", "饿", "渴", "疼",
    "紧绷", "坐不住", "不舒服", "说不清"
)
private val RESPONSE_STYLES = listOf(
    "温柔一点", "直接说重点", "强势管管我", "主动追问", "只陪伴", "先听我说"
)
private val AVOID_TAGS = listOf(
    "别催学习", "别讲大道理", "别长篇分析",
    "别打电话", "不要弹窗", "先别替我决定"
)

@Composable
private fun ChoiceGrid(
    title: String,
    choices: List<String>,
    selected: Set<String>,
    columns: Int = 3,
    hint: String = "",
    onChoice: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        if (hint.isNotBlank()) Text(hint, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        choices.chunked(columns).forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.forEach { option ->
                    FilterChip(
                        selected = option in selected,
                        onClick = { onChoice(option) },
                        label = { Text(option, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EnergyChoice(
    title: String,
    value: Int?,
    onChange: (Int?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title + " · " + (value?.toString()?.plus("/5") ?: "未填写"),
            color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { number ->
                FilterChip(
                    selected = value == number,
                    onClick = { onChange(if (value == number) null else number) },
                    label = { Text(number.toString()) }
                )
            }
        }
    }
}

private fun asJsonArray(values: Set<String>): JSONArray =
    JSONArray().apply { values.forEach { put(it) } }

/**
 * The top half is intentionally quick; the expanded half makes room for
 * mental clarity, body state, multiple emotions and explicit "don't" rules.
 *
 * Never silently invent an energy score or replace a prior snapshot:
 * each save emits a fresh immutable event from the caller.
 */
@Composable
fun StatusLightEditor(
    busy: Boolean,
    onSave: (JSONObject) -> Unit
) {
    var state by remember { mutableStateOf("") }
    var energy by remember { mutableStateOf<Int?>(null) }
    var needs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var responseLevel by remember { mutableStateOf("") }
    var detailsExpanded by remember { mutableStateOf(false) }

    var emotions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var attention by remember { mutableStateOf("") }
    var mentalEnergy by remember { mutableStateOf<Int?>(null) }
    var physicalEnergy by remember { mutableStateOf<Int?>(null) }
    var bodySignals by remember { mutableStateOf<Set<String>>(emptySet()) }
    var responseStyle by remember { mutableStateOf("") }
    var avoid by remember { mutableStateOf<Set<String>>(emptySet()) }
    var needOther by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("更新状态灯", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text("不用把自己分析明白才能填写。你说不清，也是一条有效的状态。",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall)

        ChoiceGrid(
            title = "① 这一刻整体是什么感觉？", choices = OVERALL_STATES,
            selected = setOfNotNull(state.takeIf { it.isNotBlank() }),
            onChoice = { state = if (state == it) "" else it }
        )
        EnergyChoice("② 现在总体还能量多少？", energy) { energy = it }
        ChoiceGrid(
            title = "③ 现在想要我做什么？",
            hint = "可选 1–3 个；不知道就留空",
            choices = QUICK_NEEDS, columns = 2, selected = needs,
            onChoice = { choice ->
                needs = if (choice in needs) needs - choice
                    else if (needs.size < 3) needs + choice else needs
            }
        )
        ChoiceGrid(
            title = "④ 我的回应浓度", choices = listOf("轻", "正常", "高"),
            selected = setOfNotNull(responseLevel.takeIf { it.isNotBlank() }),
            onChoice = { responseLevel = if (responseLevel == it) "" else it }
        )
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) "收起细项 ↑" else "展开：情绪 / 脑力 / 身体 / 我该怎么回应 ↓")
        }
        if (detailsExpanded) {
            ChoiceGrid(
                title = "情绪可以不止一种", hint = "最多选 5 个，不需要勉强确定",
                choices = EMOTION_TAGS, selected = emotions,
                onChoice = { choice ->
                    emotions = if (choice in emotions) emotions - choice
                        else if (emotions.size < 5) emotions + choice else emotions
                }
            )
            EnergyChoice("脑力 / 思考的余量", mentalEnergy) { mentalEnergy = it }
            EnergyChoice("身体还有多少力气", physicalEnergy) { physicalEnergy = it }
            ChoiceGrid(
                title = "注意力与启动状态", choices = ATTENTION_STATES,
                selected = setOfNotNull(attention.takeIf { it.isNotBlank() }),
                columns = 2,
                onChoice = { attention = if (attention == it) "" else it }
            )
            ChoiceGrid(
                title = "身体此刻的感受", choices = BODY_SIGNALS,
                selected = bodySignals,
                onChoice = { choice ->
                    bodySignals = if (choice in bodySignals) bodySignals - choice
                        else if (bodySignals.size < 4) bodySignals + choice else bodySignals
                }
            )
            ChoiceGrid(
                title = "希望我的说话方式", choices = RESPONSE_STYLES,
                selected = setOfNotNull(responseStyle.takeIf { it.isNotBlank() }),
                columns = 2,
                onChoice = { responseStyle = if (responseStyle == it) "" else it }
            )
            ChoiceGrid(
                title = "现在不要我做什么", choices = AVOID_TAGS,
                selected = avoid, columns = 2,
                hint = "只约束这次状态，不自动改变永久设置",
                onChoice = { choice ->
                    avoid = if (choice in avoid) avoid - choice
                        else if (avoid.size < 3) avoid + choice else avoid
                }
            )
            OutlinedTextField(
                value = needOther,
                onValueChange = { needOther = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("还有什么特别需要我做的？（可选）") }
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(240) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("想补充的一句话（原话保存）") },
                minLines = 2
            )
        }
        IceButton(
            text = "更新此刻状态",
            primary = true,
            enabled = !busy && state.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val body = JSONObject()
                    .put("state", state)
                    .put("need", (needs.toList() + needOther.trim().takeIf { it.isNotBlank() }.orEmpty()
                        .let { if (it.isBlank()) emptyList() else listOf(it) })
                        .joinToString("；"))
                    .put("response_level", responseLevel)
                    .put("emotions", asJsonArray(emotions))
                    .put("attention", attention)
                    .put("body_signals", asJsonArray(bodySignals))
                    .put("response_style", responseStyle)
                    .put("avoid", avoid.joinToString("；"))
                    .put("detail", note)
                energy?.let { body.put("energy", it) }
                mentalEnergy?.let { body.put("mental_energy", it) }
                physicalEnergy?.let { body.put("physical_energy", it) }
                onSave(body)
            }
        )
        Text("每点一次「更新」都会生成一条新快照。选中的内容属于你的主动报告，不是我的推断。",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp))
    }
}
