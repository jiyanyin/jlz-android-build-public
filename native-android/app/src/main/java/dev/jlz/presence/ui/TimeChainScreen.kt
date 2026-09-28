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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.components.IceGlassCard
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary
import dev.jlz.presence.usage.UnifiedPhoneTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * P0-3: display-only join of THREE EXISTING evidence streams.
 *
 * Never writes a derived fact into the source journals; does not silently
 * infer what a person intended by opening an app. Raw moment text is retained.
 */
private data class ChainItem(
    val key: String,
    val atMs: Long,
    val lane: String,
    val title: String,
    val detail: String,
    val provenance: String,
    val linkedId: String = "",
    val remoteVerified: Boolean = true
)

private data class ChainView(
    val items: List<ChainItem> = emptyList(),
    val pending: Int = 0,
    val remoteAvailable: Boolean = false,
    val phoneAvailable: Boolean = true,
    val error: String = ""
)

private fun dayStartMs(now: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun isoMillis(text: String?): Long =
    runCatching { Instant.parse(text.orEmpty()).toEpochMilli() }.getOrDefault(0L)

private fun statusDetail(status: JSONObject): String {
    val parts = mutableListOf<String>()
    val axes = statusSummary(status)
    if (status.optJSONObject("dimensions") != null ||
        status.optJSONObject("metadata_json")?.optJSONObject("dimensions") != null) {
        if (axes != "我的此刻") parts += axes
    }
    parts += statusNeedLines(status)
    parts += statusResponseLines(status)
    val note = status.optString("detail")
    if (note.isNotBlank()) parts += note
    val energy = status.optInt("energy", -1)
    if (energy in 1..5) parts += "精力 $energy/5"
    val mental = status.optInt("mental_energy", -1)
    if (mental in 1..5) parts += "脑力 $mental/5"
    val physical = status.optInt("physical_energy", -1)
    if (physical in 1..5) parts += "体力 $physical/5"
    val emotions = status.optJSONArray("emotions")
    if (emotions != null && emotions.length() > 0) {
        parts += "情绪：" + (0 until emotions.length())
            .map { emotions.optString(it) }.filter { it.isNotBlank() }.joinToString("、")
    }
    return parts.joinToString(" · ")
}

private suspend fun buildChain(
    context: android.content.Context,
    store: LocalLifeStore,
    outbox: BetweenOutbox,
    settings: RuntimeSettingsRepository,
    start: Long,
    end: Long,
    includePhone: Boolean = false
): ChainView {
    val merged = LinkedHashMap<String, ChainItem>()
    var error = ""
    // The everyday diary must not do heavy UsageEvents scans just to show
    // the user's own words. Load raw phone events only in the technical view.
    val phone = if (includePhone) {
        runCatching { UnifiedPhoneTimeline(context).today(end, 500) }
            .onFailure { error = "手机客观轨迹读取失败：" + (it.message ?: "未知") }
            .getOrNull()
    } else null

    // P0-1: use the actual on-device UsageEvents and screen journal. The
    // older UnifiedPhoneTimeline includes local life too, which we handle
    // separately so one user entry cannot appear twice.
    phone?.items.orEmpty().filter {
        (it.category == "PHONE" || it.category == "APP") &&
            it.atMs >= start && it.atMs <= end
    }.forEach { source ->
        val label = when {
            // Unlocks have an owner-default policy; no extra identity chain.
            source.title == "音音解锁手机" ||
                source.title == "音音进入手机" -> "音音 · 手机解锁记录"
            source.origin == "JLZ_RUNTIME" -> "手机记录 · Runtime 控制"
            source.origin == "USER_OR_NON_RUNTIME" ->
                "手机观测 · 非 Runtime 来源"
            source.origin == "WORK_TEST" -> "手机记录 · 测试"
            else -> "手机观测 · " + source.source.ifBlank { "来源未明" }
        }
        merged["phone:" + source.id] = ChainItem(
            key = "phone:" + source.id,
            atMs = source.atMs,
            lane = "phone",
            title = source.title,
            detail = source.detail,
            provenance = label
        )
    }

    // P0-2: read raw local snapshot records before network calls. Offline
    // records are real user-written facts even if the server is unavailable.
    store.listTimelineSince(start, 500)
        .filter { it.createdAtMs in start..end &&
            it.type in setOf("between_status", "between_moment") }
        .forEach { ev ->
            val isMoment = ev.type == "between_moment"
            val meta = runCatching { JSONObject(ev.metadataJson) }
                .getOrDefault(JSONObject())
            val actor = meta.optString("actor", "user")
            val lane = if (isMoment) "moment" else "status"
            val recordId = "user:" + lane + ":" + ev.id
            merged[recordId] = ChainItem(
                key = recordId,
                atMs = ev.createdAtMs,
                lane = lane,
                title = if (isMoment) "你我之间" else ev.title,
                detail = if (isMoment) ev.detail else
                    statusDetail(meta).ifBlank { ev.detail },
                provenance = if (actor == "assistant") "纪临洲 · 本机记录"
                    else "音音主动填写 · 本机待核对同步",
                remoteVerified = false
            )
        }

    val runtime = settings.load()
    var remoteAvailable = false
    if (runtime.baseUrl.isNotBlank() && runtime.token.isNotBlank()) {
        val api = RuntimeApiClient(runtime)
        // An older Runtime can reject extra status dimensions. Its outbox
        // retry must never erase a local source or block life moments.
        runCatching { outbox.sync(api, limit = 40) }
        val between = runCatching { api.getBetweenState(200) }.getOrNull()
        if (between != null) {
            remoteAvailable = true
            val statusHistory = between.optJSONArray("status_history")
            if (statusHistory != null) for (i in 0 until statusHistory.length()) {
                val snapshot = statusHistory.optJSONObject(i) ?: continue
                val at = snapshot.optLong("updated_at_ms", 0L)
                    .takeIf { it > 0L } ?: isoMillis(snapshot.optString("updated_at"))
                if (at !in start..end) continue
                val id = snapshot.optString("event_id")
                    .ifBlank { snapshot.optString("id") }
                if (id.isBlank()) continue
                val actor = snapshot.optString("actor")
                val origin = snapshot.optString("source")
                val lane = "status"
                merged["user:$lane:$id"] = ChainItem(
                    key = "user:$lane:$id", atMs = at, lane = lane,
                    title = snapshot.optString("state"),
                    detail = statusDetail(snapshot),
                    provenance = when {
                        actor == "jlz" -> "纪临洲主动更新"
                        origin == "user_direct" -> "音音主动填写 · Runtime 已收"
                        else -> "状态代记录 · 来源：" + origin.ifBlank { "未知" }
                    }
                )
            }

            val moments = between.optJSONArray("moments")
            if (moments != null) for (i in 0 until moments.length()) {
                val moment = moments.optJSONObject(i) ?: continue
                val id = moment.optString("id")
                val at = moment.optLong("created_at_ms", 0L)
                    .takeIf { it > 0L } ?: isoMillis(moment.optString("created_at"))
                if (id.isBlank() || at !in start..end) continue
                val actor = moment.optString("actor")
                val origin = moment.optString("source")
                merged["user:moment:$id"] = ChainItem(
                    key = "user:moment:$id", atMs = at, lane = "moment",
                    title = "你我之间", detail = moment.optString("text"),
                    provenance = when {
                        actor == "jlz" -> "纪临洲主动记录"
                        origin == "user_direct" -> "音音原话 · Runtime 已收"
                        else -> "代记录 · 来源：" + origin.ifBlank { "未知" }
                    }
                )
            }

            // Replies have independent timestamps and must not overwrite the
            // associated user note. A server-side receipt is NOT a popup proof.
            val visibleMomentIds = (merged.values
                .filter { it.lane == "moment" }
                .map { it.key.substringAfterLast(':') } +
                (0 until (moments?.length() ?: 0)).mapNotNull { index ->
                    moments?.optJSONObject(index)?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                }).toSet()
            val inbox = runCatching { api.getInbox(200) }.getOrDefault(emptyList())
            inbox.filter { it.role == "companion" &&
                it.eventId != null && visibleMomentIds.contains(it.eventId) }
                .forEach { reply ->
                    val at = isoMillis(reply.createdAt)
                    if (at in start..end) {
                        merged["reply:" + reply.id] = ChainItem(
                            key = "reply:" + reply.id,
                            atMs = at, lane = "reply",
                            title = "纪临洲回复",
                            detail = reply.text,
                            provenance = "Runtime 收件箱已保存 · 弹窗送达未单独确认",
                            linkedId = reply.eventId.orEmpty()
                        )
                    }
                }
        } else {
            error = if (error.isBlank()) "Runtime 暂不可用；下面保留手机本地记录"
                else error + "；Runtime 暂不可用"
        }
    }
    return ChainView(
        items = merged.values.sortedWith(
            compareByDescending<ChainItem> { it.atMs }.thenBy { it.key }
        ).take(350),
        pending = outbox.pendingCount(),
        remoteAvailable = remoteAvailable,
        phoneAvailable = phone != null,
        error = error
    )
}

@Composable
fun TimeChainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { LocalLifeStore(context.applicationContext) }
    val outbox = remember(context) { BetweenOutbox(context.applicationContext) }
    val settings = remember(context) { RuntimeSettingsRepository(context.applicationContext) }
    var view by remember { mutableStateOf(ChainView()) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("日记") }

    suspend fun reload() {
        loading = true
        view = withContext(Dispatchers.IO) {
            val end = System.currentTimeMillis()
            buildChain(context.applicationContext, store, outbox, settings,
                dayStartMs(end), end, includePhone = filter == "手机流水")
        }
        loading = false
    }
    LaunchedEffect(filter) { reload() }

    val tabs = listOf("日记", "状态灯", "手机流水")
    val shown = view.items.filter {
        when (filter) {
            "日记" -> it.lane == "moment" || it.lane == "reply"
            "状态灯" -> it.lane == "status"
            "手机流水" -> it.lane == "phone"
            else -> false
        }
    }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("你我之间 · 今日记录", color = TextPrimary,
                    style = MaterialTheme.typography.headlineSmall)
                IceButton("返回", onClick = { PresenceRouteBus.open(PresenceRoute.Home) })
            }
        }
        item {
            Text("今天 · 你的留言和我的回复", color = TextPrimary,
                style = MaterialTheme.typography.titleMedium)
            Text("默认只看我们写下的话；状态灯供我读取，手机流水放在技术页按需查看。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (view.pending > 0) Text(
                "另有 " + view.pending + " 条本机待确认同步，不会因刷新而删除。",
                color = TextSecondary
            )
            if (!view.remoteAvailable) Text(
                "云端暂不可用或尚未配置，已显示本机资料。",
                color = TextSecondary
            )
            if (view.error.isNotBlank()) Text(view.error, color = TextSecondary)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { value ->
                    FilterChip(selected = filter == value,
                        onClick = { filter = value }, label = { Text(value) })
                }
            }
            IceButton(if (loading) "读取中" else "刷新", onClick = {
                if (!loading) scope.launch { reload() }
            }, enabled = !loading)
        }
        item {
            Text(
                when (filter) {
                    "日记" -> "今天：你的留言 " +
                        shown.count { it.lane == "moment" } +
                        " 条 · 我的回复 " +
                        shown.count { it.lane == "reply" } + " 条"
                    "状态灯" -> "今天的状态更新 " + shown.size + " 条"
                    else -> "手机原始记录 " + shown.size +
                        " 条（后台只发送每小时聚合摘要）"
                },
                color = TextSecondary, style = MaterialTheme.typography.labelMedium
            )
        }
        if (shown.isEmpty()) item {
            IceGlassCard {
                Text(if (loading) "正在读取记录……" else "这个筛选下还没有记录。",
                    color = TextSecondary)
            }
        }
        items(shown, key = { it.key }) { item ->
            IceGlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        clock.format(Date(item.atMs)),
                        color = TextSecondary, style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        when (item.lane) {
                            "status" -> "状态灯"
                            "moment" -> "你我之间"
                            "reply" -> "纪临洲"
                            else -> "手机记录"
                        },
                        color = TextSecondary, style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(item.title, color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall)
                if (item.detail.isNotBlank()) Text(item.detail, color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium)
                if (item.linkedId.isNotBlank()) Text(
                    "↳ 回复留言：" + item.linkedId.take(8),
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall
                )
                Text(item.provenance, color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
