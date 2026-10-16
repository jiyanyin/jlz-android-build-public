package dev.jlz.presence.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jlz.presence.life.HuaweiHealthObservationStore
import kotlinx.coroutines.delay

@Composable
fun SleepCollectionPanel() {
    val context = LocalContext.current
    val store = remember { HuaweiHealthObservationStore(context) }
    var state by remember { mutableStateOf(store.snapshot()) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { state = store.snapshot(); delay(3000) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("昨夜睡眠 · 采集状态", style = MaterialTheme.typography.titleMedium)
            Text("点晨间通知只会打开华为运动健康。继续进入「健康 → 睡眠 → 昨夜／当天夜间详情」，让日期和总睡眠时长同时显示，停留约十秒。回来后在这里检查。")
            val verified = state.optBoolean("sleep_date_verified") && state.optBoolean("sleep_fresh") && !state.isNull("sleep_minutes")
            Text(if (verified) "已读到：${state.optInt("sleep_minutes") / 60}小时${state.optInt("sleep_minutes") % 60}分钟 · 日期已核验"
                else when (state.optString("sleep_parse_status")) {
                    "sleep_duration_missing" -> "还没读到总时长。请进入睡眠详情，展开显示日期与总时长。"
                    "visible_page_wrong_date" -> "页面日期不匹配。请切换到本次昨夜睡眠。"
                    else -> "昨夜睡眠尚未核实。确认无障碍已开启；首页、图表或没有日期的文字可能无法读取。"
                })
            Text("来源：华为可见页面文字；不是后台读取华为数据库，也不把锁屏时间算成睡眠。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage("com.huawei.health")
                    message = if (launch != null && runCatching { context.startActivity(launch) }.isSuccess)
                        "进入睡眠详情后，返回这里查看结果。" else "未找到可打开的华为运动健康，请从桌面打开。"
                }) { Text("打开华为运动健康") }
                TextButton(onClick = { state = store.snapshot() }) { Text("重新检查") }
            }
            if (message.isNotBlank()) Text(message)
        }
    }
}
