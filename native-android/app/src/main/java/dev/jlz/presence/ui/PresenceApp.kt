package dev.jlz.presence.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import dev.jlz.presence.launcher.LauncherAppInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.launcher.LauncherRepository
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.permissions.PermissionDoctor
import dev.jlz.presence.permissions.PermissionItem
import dev.jlz.presence.ui.components.IceButton
import dev.jlz.presence.ui.components.IceGlassCard
import dev.jlz.presence.ui.components.SectionHeader
import dev.jlz.presence.ui.theme.*
import dev.jlz.presence.study.StudyPatrol
import dev.jlz.presence.trip.TripController
import dev.jlz.presence.usage.UnifiedPhoneTimeline
import dev.jlz.presence.usage.UnifiedTimelineItem
import dev.jlz.presence.usage.TimelineSegment
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PresenceApp() {
    val route by PresenceRouteBus.route.collectAsState()
    Scaffold(containerColor = BgDeep) { padding ->
        Box(Modifier.padding(padding)) {
            when (route) {
                is PresenceRoute.Welcome -> WelcomeScreen()
                is PresenceRoute.Home -> HomeScreen()
                is PresenceRoute.Drawer -> AppDrawerScreen()
                is PresenceRoute.Timeline -> TimelineScreen()
                is PresenceRoute.Between -> BetweenScreen((route as PresenceRoute.Between).tab)
                is PresenceRoute.Study -> StudyScreen()
                is PresenceRoute.Trip -> TripScreen()
                is PresenceRoute.PermissionDoctor -> PermissionDoctorScreen()
                is PresenceRoute.QuickCapture -> QuickCaptureScreen()
                is PresenceRoute.Diagnostics -> DiagnosticsScreen()
                else -> HomeScreen()
            }
        }
    }
}

@Composable
fun WelcomeScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("世界之间", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(16.dp))
        Text("你和纪临洲之间，只隔一个电话。", color = TextSecondary)
        Spacer(Modifier.height(48.dp))
        IceButton("进入", onClick = {
            // Daily welcome shown -> one left-side (assistant) event.
            runCatching {
                LocalLifeStore(context).recordTimeline(
                    "daily_welcome", "老公在", "欢迎页已展示",
                    metadataJson = org.json.JSONObject().put("actor", "assistant").put("source", "app").toString()
                )
            }
            PresenceRouteBus.open(PresenceRoute.Home)
        }, primary = true)
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val launcherRepo = remember { LauncherRepository(context) }
    val apps = remember { launcherRepo.loadLaunchableApps().filter { !it.hidden } }
    val pinned = apps.filter { it.pinned }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("\u4e16\u754c\u4e4b\u95f4", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Row {
                Text("\u65f6\u95f4\u7ebf", color = TextSecondary, modifier = Modifier.clickable { PresenceRouteBus.open(PresenceRoute.Timeline) }.padding(8.dp))
                Text("\u8bca\u65ad", color = TextTertiary, modifier = Modifier.clickable { PresenceRouteBus.open(PresenceRoute.Diagnostics) }.padding(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionHeader("\u5b66\u4e60\u4e0e\u5b98\u7aef")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pinned.forEach { app ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { launcherRepo.launchApp(app.packageName) }) {
                    Box(Modifier.size(56.dp).background(GlassFill, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text(app.label.take(1), color = TextPrimary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(app.label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionHeader("你我之间")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IceButton("状态灯", onClick = {
                PresenceRouteBus.open(PresenceRoute.Between("status"))
            }, modifier = Modifier.weight(1f))
            IceButton("你我之间", onClick = {
                PresenceRouteBus.open(PresenceRoute.Between("moments"))
            }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        SectionHeader("\u5feb\u6377")
        IceGlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IceButton("\u5f00\u59cb\u5b66\u4e60", onClick = { PresenceRouteBus.open(PresenceRoute.Study) }, modifier = Modifier.weight(1f))
                IceButton("\u5168\u90e8\u5e94\u7528", onClick = { PresenceRouteBus.open(PresenceRoute.Drawer) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IceButton("\u540c\u884c", onClick = { PresenceRouteBus.open(PresenceRoute.Trip) }, modifier = Modifier.weight(1f))
                IceButton("\u6743\u9650\u68c0\u67e5", onClick = { PresenceRouteBus.open(PresenceRoute.PermissionDoctor) }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionHeader("\u6700\u8fd1")
        val store = remember { LocalLifeStore(context) }
        val events = remember { store.listTimelineSince(System.currentTimeMillis() - 86400000L, 20) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { ev ->
                IceGlassCard {
                    Text(ev.title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    if (ev.detail.isNotBlank()) Text(ev.detail.take(100), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun AppDrawerScreen() {
    val context = LocalContext.current
    val launcherRepo = remember { LauncherRepository(context) }
    var refresh by remember { mutableStateOf(0) }
    val apps = remember(refresh) { launcherRepo.loadLaunchableApps() }
    var selected by remember { mutableStateOf<LauncherAppInfo?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("全部应用", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Text("返回", color = BlueGlow, modifier = Modifier.clickable { PresenceRouteBus.open(PresenceRoute.Home) }.padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("长按应用可固定到首页或隐藏", color = TextTertiary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(GlassFill, RoundedCornerShape(12.dp))
                        .pointerInput(app.packageName) {
                            detectTapGestures(
                                onTap = { launcherRepo.launchApp(app.packageName) },
                                onLongPress = { selected = app }
                            )
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(40.dp).background(GlassBorder, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(app.label.take(1), color = TextPrimary) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = TextPrimary)
                        Text(app.category + when {
                            app.pinned && app.hidden -> " · 已固定 · 已隐藏"
                            app.pinned -> " · 已固定首页"
                            app.hidden -> " · 已从首页隐藏"
                            else -> ""
                        }, color = TextTertiary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label) },
            text = {
                Column {
                    TextButton(onClick = {
                        launcherRepo.setPinned(app.packageName, !app.pinned); selected = null; refresh++
                    }) { Text(if (app.pinned) "取消首页固定" else "固定到首页") }
                    TextButton(onClick = {
                        launcherRepo.setHidden(app.packageName, !app.hidden); selected = null; refresh++
                    }) { Text(if (app.hidden) "从首页恢复显示" else "从首页隐藏（如小红书）") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }
        )
    }
}

// -1 = left (纪临洲/assistant), 0 = center (system), 1 = right (user)
private fun timelineSide(type: String, metadataJson: String?): Int {
    val actor = runCatching {
        if (metadataJson.isNullOrBlank()) null else org.json.JSONObject(metadataJson).optString("actor").ifBlank { null }
    }.getOrNull()
    when (actor) {
        "assistant" -> return -1
        "user" -> return 1
        "system" -> return 0
    }
    return when {
        type in setOf(
            "presence_call", "call_outgoing", "call", "daily_welcome",
            "focus_gate", "app_gate", "gate", "study_anomaly", "study_push",
            "gpt_message", "notification", "notification_observation",
            "screen_check", "presence_plan"
        ) -> -1
        type in setOf(
            "share_text", "share_image", "quick_note", "pending_thought",
            "trip_summary", "study_start", "study_finish", "husband_proxy",
            "user_reply", "life_entry"
        ) -> 1
        else -> 0
    }
}

@Composable
fun TimelineScreen() {
    val context = LocalContext.current
    val repository = remember { UnifiedPhoneTimeline(context) }
    var snapshot by remember { mutableStateOf<dev.jlz.presence.usage.UnifiedTimelineSnapshot?>(null) }
    var filter by remember { mutableStateOf("ALL") }
    var mode by remember { mutableStateOf("SEGMENTS") }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshNonce) {
        while (true) {
            loading = snapshot == null
            snapshot = withContext(Dispatchers.IO) { repository.today(limit = 700) }
            loading = false
            delay(30_000L)
        }
    }

    fun originLabel(item: UnifiedTimelineItem): String = when (item.origin) {
        "JLZ_RUNTIME" -> "纪临洲 · Runtime"
        "USER" -> "你"
        "USER_OR_NON_RUNTIME" -> "手机侧 · 非 Runtime"
        "WORK_TEST" -> "Work 测试"
        "SYSTEM" -> "系统"
        "LOCAL" -> "本地"
        "MIXED" -> "混合来源"
        else -> item.origin.ifBlank { "未知来源" }
    }

    fun categoryLabel(category: String): String = when (category) {
        "PHONE" -> "屏幕"
        "APP" -> "App"
        "RELATION" -> "你我"
        else -> category
    }

    fun formatMinutes(minutes: Long): String = when {
        minutes >= 60L -> "${minutes / 60L}小时${minutes % 60L}分"
        else -> "${minutes}分"
    }

    fun formatClock(atMs: Long?): String {
        if (atMs == null || atMs <= 0L) return "—"
        return java.text.SimpleDateFormat(
            "HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date(atMs))
    }

    fun segmentOrigin(segment: TimelineSegment): String = when (segment.origin) {
        "JLZ_RUNTIME" -> "含 Runtime 操作"
        "USER_OR_NON_RUNTIME" -> "手机侧 · 非 Runtime"
        "MIXED" -> "混合来源"
        "WORK_TEST" -> "Work 测试"
        "SYSTEM" -> "系统"
        else -> ""
    }

    val visibleItems = snapshot?.items.orEmpty().filter {
        filter == "ALL" || it.category == filter
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("今天发生了什么", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                Text("把系统日志收成你真正能读的一天", color = TextTertiary, style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (loading) "读取中…" else "刷新",
                    color = if (loading) TextTertiary else BlueGlow,
                    modifier = Modifier.clickable(enabled = !loading) { refreshNonce++ }.padding(8.dp)
                )
                Text(
                    "返回",
                    color = BlueGlow,
                    modifier = Modifier.clickable { PresenceRouteBus.open(PresenceRoute.Home) }.padding(8.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        snapshot?.let { snap ->
            IceGlassCard {
                Text("今日手机摘要", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "第一次解锁 ${formatClock(snap.firstUnlockAtMs)} · 最近活动 ${formatClock(snap.latestActivityAtMs)}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "亮屏 ${snap.screenOnCount} 次 · 解锁 ${snap.unlockCount} 次 · App 会话 ${snap.appSessionCount} 段",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "可见 App 前台约 ${formatMinutes(snap.foregroundMinutes)} · 我触发/关联 ${snap.runtimeAttributedCount} 个事件",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (snap.topApps.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text("今天主要停留", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                    snap.topApps.take(3).forEachIndexed { index, app ->
                        Text(
                            "${index + 1}. ${app.label} · ${formatMinutes(app.durationMs / 60_000L)} · ${app.sessionCount} 段",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(5.dp))
                Text(
                    "“手机侧 · 非 Runtime”只表示排除了已知 Runtime/Work，不把它冒充成确定的本人操作。",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == "SEGMENTS",
                onClick = { mode = "SEGMENTS" },
                label = { Text("生活片段") }
            )
            FilterChip(
                selected = mode == "DETAILS",
                onClick = { mode = "DETAILS" },
                label = { Text("事件明细") }
            )
        }

        Spacer(Modifier.height(10.dp))

        when {
            loading && snapshot == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            mode == "SEGMENTS" -> {
                val segments = snapshot?.segments.orEmpty()
                if (segments.isEmpty()) {
                    IceGlassCard {
                        Text("今天还没有足够的 App 活动组成生活片段。", color = TextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(segments, key = { it.id }) { segment ->
                            IceGlassCard {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        segment.periodLabel,
                                        color = VioletGlow,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        "${formatClock(segment.startAtMs)}–${formatClock(segment.endAtMs)}",
                                        color = TextTertiary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    segment.title,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    segment.detail,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (segment.appLabels.size > 1) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        segment.appLabels.take(5).joinToString(" · "),
                                        color = TextTertiary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                segmentOrigin(segment).takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        it,
                                        color = if (segment.origin == "JLZ_RUNTIME") VioletGlow else TextTertiary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "ALL" to "全部",
                        "PHONE" to "屏幕",
                        "APP" to "App",
                        "RELATION" to "你我"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { filter = value },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (visibleItems.isEmpty()) {
                    IceGlassCard {
                        Text("这一栏今天还没有记录。", color = TextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(visibleItems, key = { it.id }) { item ->
                            val time = formatClock(item.atMs)
                            val side = item.side
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = when (side) {
                                    -1 -> Arrangement.Start
                                    1 -> Arrangement.End
                                    else -> Arrangement.Center
                                }
                            ) {
                                if (side == -1) {
                                    Box(
                                        Modifier.size(30.dp).background(
                                            GlassFillPressed,
                                            RoundedCornerShape(9.dp)
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("纪", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Spacer(Modifier.width(7.dp))
                                }

                                Box(Modifier.widthIn(max = if (side == 0) 340.dp else 286.dp)) {
                                    IceGlassCard {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(item.title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                                            Text(time, color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (item.detail.isNotBlank()) {
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                item.detail.take(180),
                                                color = TextSecondary,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            "${categoryLabel(item.category)} · ${originLabel(item)}",
                                            color = when (item.origin) {
                                                "JLZ_RUNTIME" -> VioletGlow
                                                "USER" -> BlueGlow
                                                else -> TextTertiary
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }

                                if (side == 1) {
                                    Spacer(Modifier.width(7.dp))
                                    Box(
                                        Modifier.size(30.dp).background(
                                            GlassBorder,
                                            RoundedCornerShape(9.dp)
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("你", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StudyScreen() {
    val context = LocalContext.current
    val patrol by StudyPatrol.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L) } }
    fun mmss(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(s / 60L, s % 60L)
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("学习", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(32.dp))
        when {
            patrol.active -> {
                Text("专注中 剩余 ${mmss(patrol.endsAtMs - now)}", style = MaterialTheme.typography.headlineLarge, color = VioletGlow)
                Spacer(Modifier.height(16.dp))
                Text("每 5 分钟本地巡检 · 异常 ${patrol.anomalyCount} 次", color = TextSecondary)
                Spacer(Modifier.height(40.dp))
                IceButton("提前结束并回 Home", onClick = { StudyPatrol.stopEarly(context) }, primary = true)
            }
            now < patrol.cooldownUntilMs -> {
                Text("休息一下", style = MaterialTheme.typography.headlineLarge, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Text("冷却中 ${mmss(patrol.cooldownUntilMs - now)}", color = TextTertiary)
                Spacer(Modifier.height(40.dp))
                IceButton("冷却中…", onClick = { }, primary = false)
            }
            else -> {
                Text("25 分钟专注", style = MaterialTheme.typography.headlineLarge, color = VioletGlow)
                Spacer(Modifier.height(16.dp))
                Text("本地巡检，正常状态不上传大图；结束回 Home，不强制关闭应用。", color = TextSecondary)
                Spacer(Modifier.height(40.dp))
                IceButton("开始 25 分钟", onClick = { StudyPatrol.start(context) }, primary = true)
            }
        }
        Spacer(Modifier.height(16.dp))
        IceButton("返回 Home", onClick = { PresenceRouteBus.open(PresenceRoute.Home) })
    }
}

@Composable
fun TripScreen() {
    val context = LocalContext.current
    val active by TripController.active.collectAsState()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Text("带着老公", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(24.dp))
        IceGlassCard {
            Text("同行 GPS 行程", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("开始后本地记录位置，自动过滤漂移，Timeline 只写摘要，不自动上传。", color = TextSecondary)
            Spacer(Modifier.height(16.dp))
            if (active) {
                Text("行程进行中…", color = BlueGlow)
                Spacer(Modifier.height(12.dp))
                IceButton("结束行程", onClick = { TripController.stop(context) }, primary = true)
            } else {
                IceButton("开始行程", onClick = { TripController.start(context) }, primary = true)
            }
        }
    }
}

@Composable
fun PermissionDoctorScreen() {
    val context = LocalContext.current
    val doctor = remember { PermissionDoctor(context) }
    val items = remember { doctor.checkAll() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("\u6743\u9650\u533b\u751f", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Text("\u8fd4\u56de", color = BlueGlow, modifier = Modifier.clickable { PresenceRouteBus.open(PresenceRoute.Home) }.padding(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DeviceUnlockSettingsPanel() }
            items(items) { item ->
                IceGlassCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                            Text(item.group, color = TextTertiary, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(when(item.status) {
                            PermissionItem.Status.OK -> "\u6b63\u5e38"
                            PermissionItem.Status.MISSING -> "\u7f3a\u5931"
                            PermissionItem.Status.LIMITED -> "\u53d7\u9650"
                            PermissionItem.Status.NOT_APPLICABLE -> "\u2014"
                        }, color = when(item.status) {
                            PermissionItem.Status.OK -> Success
                            PermissionItem.Status.MISSING -> Danger
                            PermissionItem.Status.LIMITED -> Warning
                            PermissionItem.Status.NOT_APPLICABLE -> TextTertiary
                        })
                    }
                    if (item.intent != null && item.status != PermissionItem.Status.OK) {
                        Spacer(Modifier.height(8.dp))
                        IceButton("\u53bb\u8bbe\u7f6e", onClick = { context.startActivity(item.intent) })
                    }
                }
            }
        }
    }
}

@Composable
fun QuickCaptureScreen() {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Text("\u968f\u624b\u7559\u7ed9\u8001\u516c", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("\u60f3\u8bf4\u4ec0\u4e48...", color = TextTertiary) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        IceButton("\u7559\u4e0b", onClick = { if (text.isNotBlank()) { dev.jlz.presence.share.SharedItemRepository(context).recordQuickNote(text); PresenceRouteBus.open(PresenceRoute.Home) } }, primary = true)
    }
}

@Composable
fun DiagnosticsScreen() {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("\u5f00\u53d1\u4e0e\u8bca\u65ad", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Text("\u8fd4\u56de", color = BlueGlow, modifier = Modifier.clickable { PresenceRouteBus.open(PresenceRoute.Home) }.padding(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Runtime \u72b6\u6001\u3001\u7248\u672c\u3001\u6700\u8fd1\u9519\u8bef\u65e5\u5fd7", color = TextSecondary)
    }
}
