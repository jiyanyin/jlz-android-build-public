package dev.jlz.presence.ui

import dev.jlz.presence.ui.components.WorldText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jlz.presence.chat.PresenceChatRepository
import dev.jlz.presence.runtime.InboxMessage
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.components.IceGlassCard
import dev.jlz.presence.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Actual on-device inbox, NOT the demo messages from the HTML mockup.
 * The repository owns local-first persistence and sync behavior. This
 * presentation never treats a synthetic echo as a GPT response.
 */
@Composable
internal fun ParchmentChatScreen(
    eventId: String? = null,
    intentId: String? = null
) {
    val context = LocalContext.current
    val repository = remember(context) {
        PresenceChatRepository(
            context.applicationContext,
            RuntimeSettingsRepository(context.applicationContext)
        )
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf<List<InboxMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        runCatching { repository.loadMessages() }
            .onSuccess { messages = it }
            .onFailure { feedback = "暂时无法读取消息：" + (it.message ?: "未知原因") }
        loading = false
    }

    LaunchedEffect(eventId, intentId) { refresh() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        scope.launch {
            runCatching { repository.sendUserMessage(text, eventId, intentId) }
                .onSuccess { result ->
                    draft = ""
                    feedback = if (result.read)
                        "发送请求已获服务器确认，不代表纪临洲已经读到。"
                    else "已保存在手机，等待网络恢复后同步。"
                    refresh()
                }
                .onFailure {
                    feedback = "未能完成保存，请重试：" + (it.message ?: "未知错误")
                }
            sending = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding()
            // One screen-level horizontal margin; bubbles/cards do not stack 16.dp margins.
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text("IN OUR OWN WORDS", color = ParchmentGold,
            style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text("聊天  Letters", color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        IceGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp)
                        .background(ParchmentCarbon, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("J", fontFamily = WorldFonts.playfairDisplay,
                        color = ParchmentPaper, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("纪临洲 / JLZ", color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("这里显示真实收件箱；不预设在线状态",
                        color = TextSecondary, style = MaterialTheme.typography.labelSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IceButton(if (loading) "…" else "刷新", onClick = {
                    if (!loading) scope.launch { refresh() }
                }, modifier = Modifier.wrapContentWidth(), enabled = !loading)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 18.dp)
        ) {
            item {
                Text("✧   A LITTLE LETTER   ✧",
                    modifier = Modifier.fillMaxWidth(),
                    color = ParchmentGold,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        if (loading) "正在读取消息……"
                        else "还没有可显示的消息。你写下的话会先保存在手机。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                val mine = message.role == "user"
                val companion = message.role == "companion"
                val shape = RoundedCornerShape(20.dp)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (!mine) {
                            Box(
                                Modifier.size(25.dp)
                                    .background(ParchmentCarbon, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (companion) "J" else "·", color = ParchmentPaper,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = WorldFonts.playfairDisplay)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                        ) {
                            if (!mine && !companion) {
                                Text("系统来源：" + message.role, color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (mine) Alignment.CenterEnd
                                    else Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier.widthIn(max = maxWidth * 0.88f)
                                        .background(
                                            if (mine) ParchmentMineBubble
                                            else ParchmentCompanionBubble,
                                            shape
                                        )
                                        .border(0.5.dp,
                                            ParchmentGold.copy(alpha = 0.18f), shape)
                                        .padding(horizontal = 15.dp, vertical = 12.dp)
                                ) {
                                    // A real conversation must never be cut at two lines.
                                    Text(
                                        message.text, color = TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        softWrap = true
                                    )
                                }
                            }
                            val clock = message.createdAt?.let { raw ->
                                runCatching {
                                    SimpleDateFormat("HH:mm", Locale.getDefault())
                                        .format(Date.from(Instant.parse(raw)))
                                }.getOrDefault("")
                            }.orEmpty()
                            if (clock.isNotBlank())
                                Text(clock, color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall)
                        }
                        if (mine) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(25.dp)
                                    .background(ParchmentMineBubble, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("音", color = ParchmentInk,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        if (feedback.isNotBlank()) {
            Text(feedback, color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            val shape = RoundedCornerShape(24.dp)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(1500) },
                modifier = Modifier.weight(1f)
                    .background(ParchmentMineBubble.copy(alpha = 0.5f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = TextStyle(
                    color = TextPrimary, fontSize = 14.sp,
                    fontFamily = WorldFonts.inter, lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(ParchmentInk),
                maxLines = 4,
                decorationBox = { innerTextField ->
                    Box {
                        if (draft.isEmpty()) {
                            Text("say something to me...",
                                color = TextSecondary,
                                fontFamily = WorldFonts.greatVibes, fontSize = 22.sp,
                                softWrap = true, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        innerTextField()
                    }
                }
            )
            IceButton(
                "➤", onClick = { send() },
                enabled = draft.isNotBlank() && !sending,
                primary = true, modifier = Modifier.wrapContentWidth()
            )
        }
        Spacer(Modifier.height(7.dp))
        Text("消息来自真实本机与 Runtime 记录；服务器保存不等于实际已读。",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
