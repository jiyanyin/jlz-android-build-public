package dev.jlz.presence.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

/** Reuses the same four V3 scales as the full editor; no competing mood-chip schema. */
@Composable
fun StatusLightHomeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { LocalLifeStore(context.applicationContext) }
    val outbox = remember(context) { BetweenOutbox(context.applicationContext) }
    val repository = remember(context) { RuntimeSettingsRepository(context.applicationContext) }
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    val axes = remember { mutableStateMapOf<String, Int>() }
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
            if (local != null && local.optLong("updated_at_ms", 0L) >=
                (remote?.optLong("updated_at_ms", 0L) ?: 0L)
            ) local else remote
        }
    }

    IceGlassCard {
        Text("状态灯 · 此刻的音音", color = TextPrimary,
            style = MaterialTheme.typography.titleMedium)
        Text(statusSummary(latest), color = TextSecondary)
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
            Text("拖动你确定的数值；未触碰的中点不会被记录。",
                color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            StatusSliders(axes) { key, value ->
                if (value == null) axes.remove(key) else axes[key] = value
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IceButton(
                    text = "保存",
                    enabled = !saving && axes.isNotEmpty(),
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        saving = true
                        scope.launch {
                            val id = UUID.randomUUID().toString()
                            val at = System.currentTimeMillis()
                            val payload = statusDraft(axes)
                                .put("event_id", id)
                                .put("updated_at_ms", at)
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    store.recordTimeline(
                                        type = "between_status",
                                        title = payload.optString("state"),
                                        eventId = id, id = id, createdAtMs = at,
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
                                axes.clear()
                                expanded = false
                                message = if (withContext(Dispatchers.IO) { outbox.pendingCount() } == 0)
                                    "新状态已同步" else "已保存在本机，等待完整数据确认"
                            }.onFailure {
                                message = "保存失败：" + (it.message ?: "未知错误")
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
