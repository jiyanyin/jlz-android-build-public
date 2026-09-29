package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jlz.presence.capture.CaptureEvent
import dev.jlz.presence.capture.CaptureEventStore
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.data.PendingThought
import dev.jlz.presence.data.TimelineEvent
import dev.jlz.presence.usage.AppUsageTotal
import dev.jlz.presence.usage.ForegroundUsageStore
import dev.jlz.presence.usage.ForegroundUsageTracker
import dev.jlz.presence.usage.SystemUsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only overview of source events. Automatic first-pass topic hints are
 * displayed here; they are not a GPT summary or verified financial facts.
 * Existing local databases are untouched and old manual entries stay in
 * Timeline. Refresh is explicit because capture may occur in another app.
 */
@Composable
fun LifeTodayScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalLifeStore(context.applicationContext) }
    val captureStore = remember { CaptureEventStore(context.applicationContext) }
    val events = remember { mutableStateListOf<TimelineEvent>() }
    val pending = remember { mutableStateListOf<PendingThought>() }
    val captures = remember { mutableStateListOf<CaptureEvent>() }
    val usage = remember { mutableStateListOf<AppUsageTotal>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun refresh() {
        loading = true
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        runCatching {
            ForegroundUsageTracker.flush()
            withContext(Dispatchers.IO) {
                listOf(
                    store.listTimelineSince(start, 80),
                    store.listOpenPending(50),
                    captureStore.recent(200).filter { it.observedAtMs >= start },
                    if (SystemUsageSnapshot.hasPermission(context)) {
                        val system = SystemUsageSnapshot.today(context)
                        val totals = system.optJSONArray("totals") ?: JSONArray()
                        (0 until totals.length()).mapNotNull { index ->
                            totals.optJSONObject(index)?.let { row ->
                                AppUsageTotal(
                                    row.optString("package_name"),
                                    row.optLong("duration_ms")
                                )
                            }
                        }.filter { it.packageName.isNotBlank() }.take(10)
                    } else {
                        ForegroundUsageStore(context.applicationContext).totalsSince(start, 10)
                    }
                )
            }
        }.onSuccess {
            @Suppress("UNCHECKED_CAST")
            val timeline = it[0] as List<TimelineEvent>
            @Suppress("UNCHECKED_CAST")
            val oldPending = it[1] as List<PendingThought>
            @Suppress("UNCHECKED_CAST")
            val captured = it[2] as List<CaptureEvent>
            @Suppress("UNCHECKED_CAST")
            val appUsage = it[3] as List<AppUsageTotal>
            events.clear(); events.addAll(timeline)
            pending.clear(); pending.addAll(oldPending)
            captures.clear(); captures.addAll(captured)
            usage.clear(); usage.addAll(appUsage)
            error = ""
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    val groups = captures.groupBy { it.kind }
    val orderedGroups = listOf(
        "note" to "你说过的话",
        "screenshot" to "让我看看 · 截图",
        "distraction" to "我在摸鱼 · 走神记录"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("今日", style = MaterialTheme.typography.headlineSmall)
                Text("你只需要随手记；我把真实记录按来源放好，不强迫你选择分类。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { scope.launch { refresh() } }) {
                        Text(if (loading) "正在刷新" else "刷新记录")
                    }
                }
                if (error.isNotBlank()) Text("记录读取失败：" + error)
            }
        }

        for ((kind, title) in orderedGroups) {
            val sectionItems = groups[kind].orEmpty()
            if (sectionItems.isNotEmpty()) {
                item { Text(title + " · " + sectionItems.size, style = MaterialTheme.typography.titleMedium) }
                items(sectionItems, key = { "capture-" + it.id }) { event ->
                    val domains = runCatching {
                        JSONArray(event.suggestedDomainsJson).let { array ->
                            (0 until array.length()).map { array.optString(it) }
                        }
                    }.getOrDefault(emptyList())
                    val candidateTags = domains.mapNotNull {
                        when (it) {
                            "study_experience" -> "学习相关（初步）"
                            "life_meal" -> "饮食相关（初步）"
                            "spending_candidate" -> "消费相关（待核实）"
                            "to_discuss" -> "待聊"
                            "self_reported_distraction" -> "主动报告走神"
                            else -> null
                        }
                    }.distinct()
                    val label = when (event.deliveryStatus) {
                        "inbox_confirmed" -> "文字已送达 Runtime · 等我读取"
                        "upload_confirmed" -> "图片已上传 · 等我查看内容"
                        "upload_pending" -> "本机已保存 · 正在等待上传"
                        "upload_failed" ->
                            if (event.kind == "screenshot")
                                "截图尚未确认上传 · 请检查本机暂存状态"
                            else "留言已保存在本机 · 等待补送"
                        else -> "本机已记下 · 尚未同步"
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            if (event.rawText.isNotBlank()) Text(event.rawText)
                            if (candidateTags.isNotEmpty()) {
                                Text("初步线索：" + candidateTags.joinToString(" · "))
                            }
                            event.originPackage?.let { Text("当时所在 APP：" + it) }
                            if (!event.studySessionId.isNullOrBlank()) {
                                Text("已关联当时学习时段")
                            }
                            Text("讨论状态：等待官端处理；上传成功不等于已经看过。")
                        }
                    }
                }
            }
        }

        if (captures.isEmpty()) {
            item { Text("今天还没有悬浮纪临洲的留言、截图或摸鱼记录。") }
        }
        if (pending.isNotEmpty()) {
            item { Text("此前留存的待聊事项", style = MaterialTheme.typography.titleMedium) }
            items(pending.take(10), key = { "pending-" + it.id }) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(event.text)
                        Text("本机旧版待聊事项 · 尚未自动同步官端")
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.resolvePending(event.id) }
                                refresh()
                            }
                        }) { Text("我已经聊过这条") }
                    }
                }
            }
        }
        if (usage.isNotEmpty()) {
            item {
                Text("今天的 APP 使用时间", style = MaterialTheme.typography.titleMedium)
                Text(if (SystemUsageSnapshot.hasPermission(context))
                    "来源：Android 使用情况（系统授权）"
                else "来源：无障碍观察（未授予系统使用情况权限，可能漏记）")
            }
            items(usage.take(6), key = { "usage-" + it.packageName }) {
                Text(it.packageName + " · " + (it.durationMs / 60_000L) + " 分钟")
            }
        }
        item { LifeHealthJournalPanel() }
        item { Text("今天的轨迹", style = MaterialTheme.typography.titleMedium) }
        items(events, key = { "timeline-" + it.id }) { event ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall)
                    if (event.detail.isNotBlank()) Text(event.detail)
                }
            }
        }
        item {
            Text(
                "原话与记录保留在本机；分类只是初步线索。" +
                    "截图即使显示已上传，也不等于官端 GPT 已经读过。",
                modifier = Modifier.padding(bottom = 22.dp)
            )
        }
    }
}
