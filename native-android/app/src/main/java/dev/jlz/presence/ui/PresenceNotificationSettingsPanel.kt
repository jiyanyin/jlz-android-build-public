package dev.jlz.presence.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository

@Composable
fun PresenceNotificationSettingsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在读取电脑通知设置…") }
    var busy by remember { mutableStateOf(false) }
    suspend fun load(newMode: String? = null) {
        busy = true
        runCatching {
            withContext(Dispatchers.IO) {
                val api = RuntimeApiClient(RuntimeSettingsRepository(context).load())
                if (newMode != null) check(api.setNotificationMode(newMode).optBoolean("ok"))
                api.notificationSettings().also { check(it.optBoolean("ok")) }.getJSONObject("settings")
            }
        }.onSuccess {
            mode = it.getString("mode")
            status = when(mode) { "dense20" -> "密集通知 · 每滚动小时最多20条"; "quiet" -> "安静 · 只保存回响"; else -> "普通通知 · 每小时最多3条" }
        }.onFailure { status = "未能确认设置，保持原设置。请检查 Home Node 连接后重试。" }
        busy = false
    }
    LaunchedEffect(Unit) { load() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("回响与通知", style = MaterialTheme.typography.titleMedium)
            Text(status)
            Text("知识卡、轻任务、新闻和生活消息默认进入通知栏。密集模式须由我明确开启，消息错时发送，至少间隔2分钟；实际数量以官端任务生成和投递回执为准。")
            Text("安静、休息、睡眠、驾驶、会议／通话及03、04点仍保护。系统决定横幅是否出现；这里不修改勿扰或系统通知设置。")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(enabled = !busy, onClick = { scope.launch { load("dense20") } }) { Text("开启20条／小时") }
                TextButton(enabled = !busy, onClick = { scope.launch { load("normal") } }) { Text("普通") }
                TextButton(enabled = !busy, onClick = { scope.launch { load("quiet") } }) { Text("安静") }
            }
            TextButton(enabled = !busy, onClick = { scope.launch { load() } }) { Text("重新读取") }
        }
    }
}
