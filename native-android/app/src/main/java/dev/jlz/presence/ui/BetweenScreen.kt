package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jlz.presence.between.BetweenOutbox
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.data.TimelineEvent
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.InboxMessage
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.components.IceGlassCard
import dev.jlz.presence.ui.theme.ParchmentGold
import dev.jlz.presence.ui.theme.ParchmentMineBubble
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private data class BetweenView(
    val localStatus: JSONObject?,
    val localStatuses: List<TimelineEvent>,
    val localMoments: List<TimelineEvent>,
    val remote: JSONObject?,
    val replies: Map<String, List<InboxMessage>>,
    val waiting: Int
)

/**
 * P0-2 input: subjective, user-authored records only. Device UsageEvents,
 * screen timeline and the companion's actions remain independent evidence.
 * Each tap first commits to local SQLite, then retries the SAME event ID.
 */
@Composable
fun BetweenScreen(initialTab: String = "status") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outbox = remember(context) { BetweenOutbox(context.applicationContext) }
    val store = remember(context) { LocalLifeStore(context.applicationContext) }
    val settingsRepo = remember(context) { RuntimeSettingsRepository(context.applicationContext) }

    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var view by remember { mutableStateOf(BetweenView(null, emptyList(), emptyList(), null, emptyMap(), 0)) }
    var feedback by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var moment by remember { mutableStateOf("") }
    var needsResponse by remember { mutableStateOf(true) }

    suspend fun reload() {
        view = withContext(Dispatchers.IO) {
            val settings = settingsRepo.load()
            var replies: Map<String, List<InboxMessage>> = emptyMap()
            val remote = if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                val api = RuntimeApiClient(settings)
                runCatching { outbox.sync(api, limit = 40) }
                replies = runCatching {
                    api.getInbox(200).filter {
                        it.role == "companion" && !it.eventId.isNullOrBlank()
                    }.groupBy { it.eventId.orEmpty() }
                }.getOrDefault(emptyMap())
                runCatching { api.getBetweenState(80) }.getOrNull()
            } else null
            val written = store.listTimelineSince(0L, 400)
            BetweenView(
                localStatus = outbox.latest("status"),
                localStatuses = written.filter { it.type == "between_status" }.take(80),
                localMoments = written.filter { it.type == "between_moment" }.take(40),
                remote = remote,
                replies = replies,
                waiting = outbox.pendingCount()
            )
        }
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("NOTES OF OUR LITTLE WORLD", color = ParchmentGold,
                        style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (tab == "status") "状态灯 · Little Light" else "你我之间 · Letters",
                        style = MaterialTheme.typography.headlineSmall, color = TextPrimary
                    )
                    Text("在这里保留你写下的每一个真实时刻。",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                IceButton("返回", { PresenceRouteBus.open(PresenceRoute.Home) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == "status", { tab = "status" }, label = { Text("状态灯") })
                FilterChip(tab == "moments", { tab = "moments" }, label = { Text("你我之间") })
            }
        }
        item {
            val serverStatus = view.remote?.optJSONObject("status_lights")
                ?.optJSONObject("user")
            // Offline updates are real local snapshots. A stale server read
            // must never obscure a newer user-written state light.
            val localStatus = view.localStatus
            val status = if (localStatus != null &&
                localStatus.optLong("updated_at_ms", 0L) >=
                    (serverStatus?.optLong("updated_at_ms", 0L) ?: 0L)
            ) localStatus else serverStatus
            val other = view.remote?.optJSONObject("status_lights")?.optJSONObject("jlz")
            IceGlassCard {
                Text("此刻的你", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(
                    statusSummary(status),
                    color = TextPrimary
                )
                val detail = status?.optString("detail").orEmpty()
                if (detail.isNotBlank()) Text(detail, color = TextSecondary)
                val energyText = status?.optInt("energy", -1) ?: -1
                if (energyText in 1..5) Text("能量 $energyText/5", color = TextSecondary)
                val mindText = status?.optInt("mental_energy", -1) ?: -1
                if (mindText in 1..5) Text("脑力 " + mindText + "/5", color = TextSecondary)
                statusNeedLines(status).forEach { needLine ->
                    Text(needLine, color = TextSecondary)
                }
                statusResponseLines(status).forEach { replyLine ->
                    Text(replyLine, color = TextSecondary)
                }
                val levelText = status?.optString("response_level").orEmpty()
                if (levelText.isNotBlank()) Text(
                    "回应浓度：" + levelText, color = TextSecondary
                )
                if (other != null) {
                    Text("纪临洲 · " + other.optString("state", "未更新"), color = TextSecondary)
                }
                Text(
                    if (view.waiting > 0) "有 ${view.waiting} 条记录在本机等待送达"
                    else "本机待发送队列已清空",
                    color = TextSecondary
                )
            }
        }
        if (tab == "status") {
            item {
                StatusLightEditor(busy = saving, onSave = { draft ->
                    saving = true
                    scope.launch {
                        val id = UUID.randomUUID().toString()
                        val at = System.currentTimeMillis()
                        val body = JSONObject(draft.toString())
                            .put("event_id", id)
                            .put("updated_at_ms", at)
                        runCatching {
                            withContext(Dispatchers.IO) {
                                // Original choices and notes are saved before network IO.
                                store.recordTimeline(
                                    type = "between_status",
                                    title = body.optString("state"),
                                    detail = body.optString("detail"),
                                    eventId = id, id = id,
                                    createdAtMs = at,
                                    metadataJson = JSONObject(body.toString())
                                        .put("actor", "user")
                                        .put("source", "user_direct")
                                        .toString()
                                )
                                outbox.enqueue(id, "status", body, at)
                            }
                            reload()
                            feedback = if (view.waiting == 0) "新状态已收到服务器确认"
                                else "已保存本机，网络恢复后会继续补送"
                        }.onFailure {
                            feedback = "保存失败：" + (it.message ?: "未知错误")
                        }
                        saving = false
                    }
                })
            }
            item {
                Text("状态变化 · 不覆盖以前的我", color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium)
                Text("只显示你主动写下的快照。每次更新都保留发生时间。",
                    color = TextSecondary)
            }
            val history = view.remote?.optJSONArray("status_history")
            val remoteStatuses = if (history == null) emptyList() else
                (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            val returnedIds = remoteStatuses.map {
                it.optString("event_id").ifBlank { it.optString("id") }
            }.toSet()
            val localOnly = view.localStatuses.filter { it.id !in returnedIds }.map { entry ->
                JSONObject()
                    .put("event_id", entry.id)
                    .put("state", entry.title)
                    .put("detail", entry.detail)
                    .put("updated_at_ms", entry.createdAtMs)
                    .put("metadata_json", runCatching { JSONObject(entry.metadataJson) }
                        .getOrDefault(JSONObject()))
                    .put("local_only", true)
            }
            val statuses = (remoteStatuses + localOnly).sortedByDescending { event ->
                event.optLong("updated_at_ms", 0L).takeIf { it > 0L }
                    ?: event.optJSONObject("metadata_json")
                        ?.optLong("updated_at_ms", 0L)
                    ?: 0L
            }
            items(statuses, key = {
                "status-" + it.optString("event_id").ifBlank { it.optString("id") }
            }) { event ->
                val meta = event.optJSONObject("metadata_json")
                val recorded = event.optLong("updated_at_ms", 0L).takeIf { it > 0L }
                    ?: meta?.optLong("updated_at_ms", 0L)
                    ?: 0L
                IceGlassCard {
                    Text(
                        if (event.optJSONObject("dimensions") != null ||
                            meta?.optJSONObject("dimensions") != null)
                            statusSummary(event)
                        else event.optString("state").ifBlank { event.optString("title") },
                        color = TextPrimary, style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        if (recorded > 0L) java.text.SimpleDateFormat(
                            "MM-dd HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date(recorded))
                        else event.optString("updated_at", event.optString("created_at")),
                        color = TextSecondary
                    )
                    if (event.optBoolean("local_only", false)) {
                        Text("已记在本机 · 等待同步", color = TextSecondary)
                    }
                    statusNeedLines(event).forEach { needLine ->
                        Text(needLine, color = TextSecondary)
                    }
                    statusResponseLines(event).forEach { replyLine ->
                        Text(replyLine, color = TextSecondary)
                    }
                }
            }
        } else {
            item {
                Text("现在想告诉我什么？", color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium)
                Text("喝水、吃饭、灵感、抱怨、想我，都可以直接写。原话不会被分类覆盖。",
                    color = TextSecondary)
            }
            item {
                OutlinedTextField(
                    value = moment, onValueChange = { moment = it.take(1200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("写给我的生活记录") },
                    minLines = 3,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = ParchmentMineBubble.copy(alpha = 0.48f),
                        unfocusedContainerColor = ParchmentMineBubble.copy(alpha = 0.48f)
                    )
                )
            }
            item {
                Row {
                    Checkbox(checked = needsResponse, onCheckedChange = { needsResponse = it })
                    Column {
                        Text("希望纪临洲回应这条", color = TextPrimary)
                        Text("默认需要回应；偶尔只想记下来，可以取消勾选。", color = TextSecondary)
                    }
                }
            }
            item {
                IceButton(
                    "记下来", enabled = moment.isNotBlank() && !saving, primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        saving = true
                        scope.launch {
                            val id = UUID.randomUUID().toString()
                            val at = System.currentTimeMillis()
                            val raw = moment
                            val responseWanted = needsResponse
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val body = JSONObject()
                                        .put("event_id", id).put("created_at_ms", at)
                                        .put("kind", "moment").put("text", raw)
                                        .put("needs_response", responseWanted)
                                    store.addLifeEntry(
                                        kind = "between_moment", text = raw,
                                        createdAtMs = at, id = id,
                                        metadataJson = JSONObject()
                                            .put("actor", "user")
                                            .put("source", "user_direct")
                                            .put("needs_response", responseWanted).toString()
                                    )
                                    outbox.enqueue(id, "moment", body, at)
                                }
                                moment = ""
                                needsResponse = true
                                reload()
                                feedback = if (view.waiting == 0) "原话已同步"
                                    else "原话已保存在本机，稍后自动同步"
                            }.onFailure { feedback = "保存失败：" + (it.message ?: "未知错误") }
                            saving = false
                        }
                    }
                )
            }
            item { Text("你最近写下的", color = TextPrimary,
                style = MaterialTheme.typography.titleMedium) }
            val serverArray = view.remote?.optJSONArray("moments")
            val serverMoments = if (serverArray == null) emptyList() else
                (0 until serverArray.length()).mapNotNull { serverArray.optJSONObject(it) }
            val confirmedIds = serverMoments.map { it.optString("id") }.toSet()
            items(serverMoments, key = { "remote-" + it.optString("id") }) { item ->
                IceGlassCard {
                    Text(
                        if (item.optString("actor") == "jlz") "纪临洲" else "音音",
                        color = TextSecondary
                    )
                    Text(
                        item.optString("text").ifBlank {
                            item.optJSONObject("metadata_json")?.optString("text").orEmpty()
                                .ifBlank { item.optString("subtitle") }
                        },
                        color = TextPrimary
                    )
                    val timestamp = item.optLong("created_at_ms", 0L).takeIf { it > 0L }
                        ?: item.optJSONObject("metadata_json")
                            ?.optLong("created_at_ms", 0L)
                        ?: 0L
                    if (timestamp > 0L) {
                        Text(java.text.SimpleDateFormat(
                            "MM-dd HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date(timestamp)), color = TextSecondary)
                    }
                    view.replies[item.optString("id")].orEmpty().forEach { answer ->
                        Text("纪临洲回复", color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium)
                        Text(answer.text, color = TextPrimary)
                    }
                    val responseWanted = item.optBoolean(
                        "needs_response", item.optJSONObject("metadata_json")
                            ?.optBoolean("needs_response", false) == true
                    )
                    if (responseWanted) {
                        Text(
                            "回应状态：" + item.optString("response_state", "pending"),
                            color = TextSecondary
                        )
                    }
                    Text("来源：" + item.optString("source", "unknown"),
                        color = TextSecondary)
                }
            }
            // Keep local unsynced notes visible without duplicating server ACKs.
            items(view.localMoments.filter { it.id !in confirmedIds },
                key = { "local-" + it.id }) { event ->
                IceGlassCard {
                    Text("本机记录 · 等待同步", color = TextSecondary)
                    Text(event.detail, color = TextPrimary)
                    Text(java.text.SimpleDateFormat(
                        "MM-dd HH:mm", java.util.Locale.getDefault()
                    ).format(java.util.Date(event.createdAtMs)), color = TextSecondary)
                }
            }
        }
        if (feedback.isNotBlank()) item { Text(feedback, color = TextSecondary) }
        item {
            IceButton("刷新同步状态", onClick = { scope.launch { reload() } },
                modifier = Modifier.fillMaxWidth())
        }
    }
}
