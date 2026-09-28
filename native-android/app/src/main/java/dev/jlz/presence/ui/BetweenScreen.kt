package dev.jlz.presence.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jlz.presence.between.BetweenOutbox
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.data.TimelineEvent
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.components.IceGlassCard
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private data class BetweenView(
    val localStatus: JSONObject?,
    val localMoments: List<TimelineEvent>,
    val remote: JSONObject?,
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
    var view by remember { mutableStateOf(BetweenView(null, emptyList(), null, 0)) }
    var feedback by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var mood by remember { mutableStateOf("") }
    var energy by remember { mutableStateOf(3) }
    var need by remember { mutableStateOf("") }
    var intensity by remember { mutableStateOf("正常") }
    var note by remember { mutableStateOf("") }
    var moment by remember { mutableStateOf("") }
    var needsResponse by remember { mutableStateOf(false) }

    suspend fun reload() {
        view = withContext(Dispatchers.IO) {
            val settings = settingsRepo.load()
            val remote = if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                val api = RuntimeApiClient(settings)
                runCatching { outbox.sync(api, limit = 40) }
                runCatching { api.getBetweenState(80) }.getOrNull()
            } else null
            BetweenView(
                localStatus = outbox.latest("status"),
                localMoments = store.listTimelineSince(0L, 200)
                    .filter { it.type == "between_moment" }.take(30),
                remote = remote,
                waiting = outbox.pendingCount()
            )
        }
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("世界之间", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
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
                    status?.optString("state").orEmpty().ifBlank { "还没更新状态灯" },
                    color = TextPrimary
                )
                val detail = status?.optString("detail").orEmpty()
                if (detail.isNotBlank()) Text(detail, color = TextSecondary)
                val energyText = status?.optInt("energy", -1) ?: -1
                if (energyText in 1..5) Text("能量 $energyText/5", color = TextSecondary)
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
                Text("更新状态灯", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("只写你愿意告诉我的状态；我不会替你改灯。", color = TextSecondary)
            }
            item {
                OutlinedTextField(
                    value = mood, onValueChange = { mood = it.take(80) },
                    label = { Text("整体感受 / 主要情绪") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Text("现在的能量 · $energy/5", color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { number ->
                        FilterChip(energy == number, { energy = number }, label = { Text("$number") })
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = need, onValueChange = { need = it.take(120) },
                    label = { Text("现在需要什么？") }, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text("希望我怎样回应", color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("轻", "正常", "高").forEach { choice ->
                        FilterChip(
                            intensity == choice, { intensity = choice },
                            label = { Text(choice) }
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(240) },
                    label = { Text("补充一句（可选）") }, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                IceButton(
                    "记下我的状态", enabled = mood.isNotBlank() && !saving, primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        saving = true
                        scope.launch {
                            val id = UUID.randomUUID().toString()
                            val at = System.currentTimeMillis()
                            val state = mood.trim()
                            val detail = note.trim()
                            val needs = need.trim()
                            val density = intensity
                            val level = energy
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val body = JSONObject()
                                        .put("event_id", id).put("updated_at_ms", at)
                                        .put("state", state).put("detail", detail)
                                        .put("energy", level).put("need", needs)
                                        .put("response_level", density)
                                    // The local evidence and outbound event share one identity.
                                    store.recordTimeline(
                                        type = "between_status", title = state,
                                        detail = detail, eventId = id, id = id,
                                        createdAtMs = at,
                                        metadataJson = JSONObject()
                                            .put("actor", "user").put("source", "user_direct")
                                            .put("energy", level).put("need", needs)
                                            .put("response_level", density).toString()
                                    )
                                    outbox.enqueue(id, "status", body, at)
                                }
                                reload()
                                feedback = if (view.waiting == 0) "状态灯已同步"
                                    else "状态已保存在本机，等待连接恢复"
                            }.onFailure { feedback = "保存失败：" + (it.message ?: "未知错误") }
                            saving = false
                        }
                    }
                )
            }
            item {
                val arr = view.remote?.optJSONArray("status_history")
                if (arr != null && arr.length() > 0) {
                    Text("状态变化 · 最近 ${arr.length()} 条", color = TextSecondary)
                    (0 until minOf(5, arr.length())).forEach { index ->
                        val event = arr.optJSONObject(index) ?: return@forEach
                        Text(event.optString("title"), color = TextSecondary)
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
                    minLines = 3
                )
            }
            item {
                Row {
                    Checkbox(checked = needsResponse, onCheckedChange = { needsResponse = it })
                    Column {
                        Text("希望纪临洲回应这条", color = TextPrimary)
                        Text("不勾选就只是一条记录，不会变成待回复留言。", color = TextSecondary)
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
                                needsResponse = false
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
                    Text(item.optString("text"), color = TextPrimary)
                    if (item.optBoolean("needs_response", false)) {
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
