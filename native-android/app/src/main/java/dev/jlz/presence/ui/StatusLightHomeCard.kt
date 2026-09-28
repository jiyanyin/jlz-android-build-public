package dev.jlz.presence.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** A real lightweight home-screen update, not just a link to the editor. */
@Composable
fun StatusLightHomeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { LocalLifeStore(context.applicationContext) }
    val outbox = remember(context) { BetweenOutbox(context.applicationContext) }
    val repository = remember(context) { RuntimeSettingsRepository(context.applicationContext) }
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf("") }
    var energy by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        latest = withContext(Dispatchers.IO) {
            val local = outbox.latest("status")
            val settings = repository.load()
            val remote = if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                runCatching {
                    RuntimeApiClient(settings).getBetweenState(1)
                        .optJSONObject("status_lights")?.optJSONObject("user")
                }.getOrNull()
            } else null
            if (local != null &&
                local.optLong("updated_at_ms", 0L) >=
                    (remote?.optLong("updated_at_ms", 0L) ?: 0L)
            ) local else remote
        }
    }

    IceGlassCard {
        Text("状态灯 · 此刻的音音",
            color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            latest?.optString("state").orEmpty().ifBlank { "还没有更新" },
            color = TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IceButton(
                text = if (expanded) "收起" else "快速更新",
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f)
            )
            IceButton(
                text = "随手记",
                onClick = { PresenceRouteBus.open(PresenceRoute.Between("moments")) },
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
        Text("选一个最接近的感觉，十秒钟就能记下来。", color = TextSecondary,
            style = MaterialTheme.typography.labelSmall)
        listOf(
            listOf("平静", "开心", "放空"),
            listOf("茫然", "烦躁", "疲惫")
        ).forEach { choices ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { item ->
                    FilterChip(
                        selected = selected == item,
                        onClick = { selected = if (selected == item) "" else item },
                        label = { Text(item, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { level ->
                FilterChip(
                    selected = energy == level,
                    onClick = { energy = if (energy == level) null else level },
                    label = { Text(level.toString()) }
                )
            }
        }
        Text("精力分数可留空；没感觉不需要硬编一个分数。",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IceButton(
                text = "更新",
                enabled = !saving && selected.isNotBlank(),
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    saving = true
                    scope.launch {
                        val id = UUID.randomUUID().toString()
                        val at = System.currentTimeMillis()
                        val state = selected
                        val level = energy
                        val payload = JSONObject()
                            .put("event_id", id)
                            .put("updated_at_ms", at)
                            .put("state", state)
                        level?.let { payload.put("energy", it) }
                        runCatching {
                            withContext(Dispatchers.IO) {
                                store.recordTimeline(
                                    type = "between_status",
                                    title = state, eventId = id,
                                    id = id, createdAtMs = at,
                                    metadataJson = JSONObject(payload.toString())
                                        .put("actor", "user")
                                        .put("source", "user_direct").toString()
                                )
                                outbox.enqueue(id, "status", payload, at)
                                val settings = repository.load()
                                if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                                    runCatching {
                                        outbox.sync(RuntimeApiClient(settings), 20)
                                    }
                                }
                            }
                            latest = payload
                            selected = ""
                            energy = null
                            expanded = false
                            message = if (outbox.pendingCount() == 0) "状态已同步"
                                else "状态已保存本机，等待完整字段同步"
                        }.onFailure {
                            message = "本次保存失败：" + (it.message ?: "未知错误")
                        }
                        saving = false
                    }
                }
            )
            IceButton(
                text = "更多状态",
                modifier = Modifier.weight(1f),
                onClick = { PresenceRouteBus.open(PresenceRoute.Between("status")) }
            )
        }
        }
        if (message.isNotBlank()) Text(message, color = TextSecondary)
    }
}
