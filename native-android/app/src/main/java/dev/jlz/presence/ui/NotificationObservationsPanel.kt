package dev.jlz.presence.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.app.NotificationManagerCompat
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.data.TimelineEvent
import dev.jlz.presence.notification.HuaweiHealthNotificationReading
import dev.jlz.presence.notification.NotificationSourceRepository
import dev.jlz.presence.notification.PresenceNotificationListenerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Actual listener callback evidence, not a notification gateway or GPT read receipt. */
@Composable
fun NotificationObservationsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val life = remember { LocalLifeStore(context.applicationContext) }
    val sources = remember { NotificationSourceRepository(context.applicationContext) }
    var events by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    var allSources by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scopeMode by remember { mutableStateOf("all") }
    var message by remember { mutableStateOf("") }
    val listenerPermission =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    suspend fun reload() {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                Triple(
                    life.listNotificationObservations(
                        System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L, 200
                    ),
                    sources.globalEnabled(),
                    sources.allowedPackages()
                )
            }
            events = snapshot.first
            allSources = snapshot.second
            allowed = snapshot.third
            message = ""
        } catch (error: Exception) {
            message = "记录读取失败：" + (error.message ?: error.javaClass.simpleName)
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = events.filter { event ->
        val data = runCatching { JSONObject(event.metadataJson) }.getOrDefault(JSONObject())
        // Existing local audit rows remain intact, but the user-requested VPN
        // source never occupies the visible notification evidence panel.
        if (data.optString("package_name") == "org.ikuuu.vpn") return@filter false
        when (scopeMode) {
            "health" -> data.optString("package_name") ==
                HuaweiHealthNotificationReading.PACKAGE_NAME
            "filtered" -> event.type == "notification_observation" ||
                data.optString("audit_result") == "masked"
            else -> true
        }
    }
    val date = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("通知观察记录", style = MaterialTheme.typography.titleMedium)
            Text("这是手机实际收到的 Android 通知回调，不是通知必经通道，也不代表官端 GPT 已读。最近七天最多检索 200 条。")
            Text("通知监听授权：" +
                if (listenerPermission) "已开启" else "未开启；请先授予现实通知感知权限")
            Text("来源范围：" +
                if (allSources) "全部来源已开启" else "仅允许的 " + allowed.size + " 个 App")
            Text("本机记录：" + events.size + " 条；缺少记录不能证明某个 App 没发过通知。")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { scopeMode = "all" }) { Text("全部") }
                TextButton(onClick = { scopeMode = "health" }) { Text("运动健康") }
                TextButton(onClick = { scopeMode = "filtered" }) { Text("未记录正文") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { scope.launch { reload() } }) { Text("刷新记录") }
                TextButton(onClick = {
                    scope.launch {
                        val scanned = PresenceNotificationListenerService.rescanActive()
                        if (scanned < 0) {
                            message = "通知监听服务未连接，先到系统设置确认权限。"
                        } else {
                            delay(350L)
                            reload()
                            message = "已请求检查当前 " + scanned +
                                " 条系统通知；记录数可能较少，因为有些通知不可读、重复或不在允许范围。"
                        }
                    }
                }) { Text("检查当前通知") }
            }
            if (message.isNotBlank()) Text(message)
            if (filtered.isEmpty()) Text("此范围内还没有真实通知观察记录。")
            filtered.take(35).forEach { event ->
                val meta = remember(event.id, event.metadataJson) {
                    runCatching { JSONObject(event.metadataJson) }.getOrDefault(JSONObject())
                }
                val sourcePackage = meta.optString("package_name", "来源未知")
                val label = remember(sourcePackage) {
                    runCatching {
                        val info = context.packageManager.getApplicationInfo(sourcePackage, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(sourcePackage)
                }
                val outcome = meta.optString("audit_result",
                    if (event.type == "notification_observation") "filtered" else "recorded")
                val outcomeLabel = when (outcome) {
                    "masked" -> "收到 · 正文已遮蔽"
                    "filtered" -> "收到 · 已过滤"
                    "no_readable_text" -> "收到 · Android 未提供可读文字"
                    else -> "收到 · 正文已保存在手机"
                }
                val observed = meta.optLong("observed_at_ms", event.createdAtMs)
                val posted = meta.optLong("posted_at_ms", 0L)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label + " · " + outcomeLabel,
                            style = MaterialTheme.typography.titleSmall)
                        Text(sourcePackage, style = MaterialTheme.typography.bodySmall)
                        Text("观察：" + date.format(Date(observed)))
                        if (posted > 0L) Text("发布：" + date.format(Date(posted)))
                        if (event.type != "notification_observation") {
                            Text(event.title)
                            if (event.detail.isNotBlank()) Text(event.detail)
                        } else {
                            Text("未存储正文。原因：" +
                                meta.optString("policy_reason", "无可读文字"))
                        }
                        if (sourcePackage == HuaweiHealthNotificationReading.PACKAGE_NAME) {
                            val steps = meta.optInt("steps_from_visible_notification", -1)
                            val kcal = meta.optInt("kcal_from_visible_notification", -1)
                            if (steps >= 0) Text("通知可读步数：" + steps + " 步")
                            if (kcal >= 0) Text("通知可读热量：" + kcal + " 千卡")
                            if (steps < 0 && kcal < 0) {
                                Text("未解析出步数／千卡；自定义卡片可见的文字未必在通知接口中提供。")
                            }
                            Text("通知观察值，不是实时心率或传感器直连。")
                        }
                    }
                }
            }
            if (filtered.size > 35) Text("此筛选仅展开前 35 条；可切换范围查看。")
            Text("本页面只证明本机收录，不验证服务器接收或官端 GPT 读取。")
        }
    }
}
