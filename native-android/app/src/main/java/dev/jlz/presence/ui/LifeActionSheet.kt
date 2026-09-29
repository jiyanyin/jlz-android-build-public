package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.jlz.presence.life.ActiveLifeAction
import dev.jlz.presence.life.LifeActionCatalog
import dev.jlz.presence.life.LifeActionChoice
import dev.jlz.presence.life.LifeActionRecorder
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.theme.GlassBorder
import dev.jlz.presence.ui.theme.ParchmentBackground
import dev.jlz.presence.ui.theme.ParchmentGlass
import dev.jlz.presence.ui.theme.ParchmentGold
import dev.jlz.presence.ui.theme.ParchmentMineBubble
import dev.jlz.presence.ui.theme.TextPrimary
import dev.jlz.presence.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Half-screen manual event capture. An explicit tap is the ONLY trigger:
 * not a device inference, not a GPT message, not an automatic GPS session.
 * Stored in the existing local Timeline with provenance and no migrations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeActionSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var categoryIndex by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<ActiveLifeAction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        active = withContext(Dispatchers.IO) { LifeActionRecorder.active(context) }
    }
    LaunchedEffect(active) {
        while (active != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    fun save(choice: LifeActionChoice) {
        if (busy || (active != null && choice.timed)) return
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (choice.timed) LifeActionRecorder.start(context, choice.name)
                    else LifeActionRecorder.recordInstant(context, choice.name)
                }
            }
            result.onSuccess {
                onSaved()
                onDismiss()
            }.onFailure { error ->
                feedback = "没有保存成功：" + (error.message ?: "未知原因")
                active = withContext(Dispatchers.IO) { LifeActionRecorder.active(context) }
            }
            busy = false
        }
    }

    fun finish() {
        if (busy || active == null) return
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { LifeActionRecorder.finish(context) }
            }
            result.onSuccess {
                onSaved()
                onDismiss()
            }.onFailure { error ->
                feedback = "结束记录失败：" + (error.message ?: "未知原因")
            }
            busy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = ParchmentBackground
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (config.screenHeightDp * 0.69f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("A LITTLE MOMENT  ·  ✦", color = ParchmentGold,
                style = MaterialTheme.typography.labelMedium)
            Text("记一下此刻", color = TextPrimary,
                style = MaterialTheme.typography.headlineSmall)
            Text("点一个动作就会自动记录时间。生活流水和写给我的留言分开保存。",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)

            if (active != null) {
                val session = active!!
                val elapsed = (now - session.startAtMs).coerceAtLeast(0L) / 1_000L
                val startAt = remember(session.startAtMs) {
                    SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(session.startAtMs))
                }
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(ParchmentMineBubble.copy(alpha = 0.60f))
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("正在" + session.name + "  ·  " +
                        (elapsed / 60L).toString() + "分" + (elapsed % 60L) + "秒",
                        color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("开始于 " + startAt + "。只有点击结束后才会写入结束时间。",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    IceButton(
                        "结束这段记录", onClick = { finish() },
                        enabled = !busy, primary = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LifeActionCatalog.categories.forEachIndexed { index, group ->
                        FilterChip(
                            selected = index == categoryIndex,
                            onClick = { categoryIndex = index },
                            label = { Text(group.name) },
                            enabled = !busy
                        )
                    }
                }
                LifeActionCatalog.categories[categoryIndex].choices.chunked(3).forEach { choices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        choices.forEach { choice ->
                            Column(
                                modifier = Modifier.weight(1f)
                                    .heightIn(min = 87.dp)
                                    .clip(RoundedCornerShape(19.dp))
                                    .background(ParchmentGlass)
                                    .border(0.5.dp, GlassBorder, RoundedCornerShape(19.dp))
                                    .clickable(enabled = !busy && (active == null || !choice.timed)) {
                                        save(choice)
                                    }
                                    .padding(horizontal = 5.dp, vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(choice.mark, color = ParchmentGold,
                                    style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(3.dp))
                                Text(choice.name, color = TextPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (choice.timed && active != null) "先结束当前"
                                    else if (choice.timed) "开始计时" else "立即记录",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        repeat(3 - choices.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

            if (feedback.isNotBlank()) {
                Text(feedback, color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("仅保存你主动点击的事实；目前是手机本地记录，不代表官端 GPT 已读取。",
                color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}
