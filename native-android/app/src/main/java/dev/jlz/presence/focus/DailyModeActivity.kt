package dev.jlz.presence.focus

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.*
import dev.jlz.presence.overlay.ChibiPresenceView
import dev.jlz.presence.overlay.FloatingPresenceService
import kotlinx.coroutines.*

/** One explicit local control surface; deep links only open it, never grant permissions. */
class DailyModeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo by lazy { FocusRepository(applicationContext) }
    private val content by lazy { dev.jlz.presence.runtime.WorldContentCache(applicationContext) }
    private lateinit var root: LinearLayout
    private var tick: Job? = null
    private val ice by lazy { dev.jlz.presence.ui.theme.NativeIceStyle(this) }
    private var buttonHost: LinearLayout? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onResume() { super.onResume(); render() }
    override fun onStop() { tick?.cancel(); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun label(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(ice.ink); setPadding(ice.dp(4), ice.dp(8), ice.dp(4), ice.dp(8))
        if (size >= 18f) typeface = ice.titleFont
    }
    private fun button(text: String, click: () -> Unit) {
        (buttonHost ?: root).addView(ice.button(text, click), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ice.dp(8) })
    }
    private fun section(title: String, copy: String) {
        buttonHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ice.dp(14), ice.dp(10), ice.dp(14), ice.dp(8))
            background = ice.card()
        }.also {
            root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ice.dp(14) })
            it.addView(label(title, 18f))
            it.addView(label(copy, 12f).apply { setTextColor(ice.muted) })
        }
    }
    private fun presets(mode: DailyMode, values: List<Int>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { minutes ->
            row.addView(ice.button("$minutes 分钟") { change(mode, minutes) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ice.dp(3), ice.dp(5), ice.dp(3), ice.dp(8)) })
        }
        buttonHost?.addView(row)
    }
    private fun change(mode: DailyMode, minutes: Int = 25) { scope.launch { if (mode in setOf(DailyMode.FOCUS, DailyMode.BREAK)) withContext(Dispatchers.IO) { LocalAppClassifier(applicationContext).refreshVisible() }; repo.setDailyMode(mode, minutes); FloatingPresenceService.refreshArtworkAfterImport(); render() } }
    private fun render() {
        scope.launch {
            val mode = repo.current().modeNow()
            val settings = intent.getBooleanExtra("settings", false)
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                // Only show this harmless mode face above keyguard; never bypass PIN.
                setShowWhenLocked(!settings && mode in setOf(DailyMode.SLEEP, DailyMode.FOCUS))
                setTurnScreenOn(false)
            }
            when {
                settings -> renderSettings()
                mode == DailyMode.SLEEP -> renderSleep()
                mode == DailyMode.FOCUS -> renderFocus()
                else -> renderSettings()
            }
        }
    }

    private fun renderFocus() {
        tick?.cancel()
        buttonHost = null
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val side = maxOf(ice.dp(18), (resources.displayMetrics.widthPixels - ice.dp(640)) / 2)
            setPadding(side, ice.dp(30), side, ice.dp(26))
            background = ice.background()
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        root.addView(label("世界之间 · 陪你学习", 23f))
        root.addView(ChibiPresenceView(this).apply { setSizeDp(144); setMood("watch") })
        val clock = label("", 18f)
        val remaining = label("", 32f)
        root.addView(clock)
        root.addView(remaining)
        root.addView(label(content.text("mode.focus", "这一段交给我。先做完眼前的题。"), 13f))
        content.displayed(listOf("mode.focus"))
        tick = scope.launch {
            while (isActive) {
                val state = repo.current()
                if (state.modeNow() != DailyMode.FOCUS) { render(); return@launch }
                clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                val seconds = state.remainingMs() / 1000
                remaining.text = "%02d:%02d".format(seconds / 60, seconds % 60)
                delay(1000)
            }
        }
        button("暂停／继续计时") {
            scope.launch {
                val state = repo.current()
                if (state.pausedAtMs > 0L) repo.resumeDaily() else repo.pauseDaily()
                render()
            }
        }
        button("结束专注") { change(DailyMode.NORMAL) }
        button("作息设置") { startActivity(Intent(this, DailyModeActivity::class.java).putExtra("settings", true)) }
        button("返回 · 继续专注") { finish() }
    }
    private fun renderSettings() {
        tick?.cancel()
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        buttonHost = null
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = maxOf(ice.dp(18), (resources.displayMetrics.widthPixels - ice.dp(640)) / 2)
            setPadding(side, ice.dp(24), side, ice.dp(130)); background = ice.background()
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        root.addView(label("DAILY RHYTHM", 10f).apply { letterSpacing = .16f; setTextColor(ice.accent) })
        root.addView(label("世界之间 · 作息", 24f))
        val stateLabel = label("读取本机状态…", 12f); root.addView(stateLabel)
        tick = scope.launch {
            while (isActive) {
                val s = repo.current()
                val sec = s.remainingMs() / 1000
                val modeName = when (s.modeNow()) { DailyMode.FOCUS -> "专注"; DailyMode.BREAK -> "短休"; DailyMode.SLEEP -> "睡眠"; else -> "普通" }
                stateLabel.text = "$modeName · %02d:%02d".format(sec / 60, sec % 60) + if (!dev.jlz.presence.screen.ScreenObservationBus.isAvailable()) "\n无障碍未连接，门禁保护不可用" else ""
                delay(1000)
            }
        }
        section("留一段时间给学习", content.text("mode.focus", "专注仅 GPT／粉笔／独立伴读；先核对学习应用。"))
        presets(DailyMode.FOCUS, listOf(25, 45, 60))
        section("休息，也有分寸", content.text("mode.break", "短休不放行游戏；哔哩哔哩可用。"))
        presets(DailyMode.BREAK, listOf(5, 10, 15))
        section("收好今天", content.text("mode.sleep", "睡眠保持安静，电话与原生闹钟照常。"))
        content.displayed(listOf("mode.focus", "mode.break", "mode.sleep"))
        button("睡眠模式") { change(DailyMode.SLEEP) }
        button("结束当前模式") { change(DailyMode.NORMAL) }
        section("生活里的小安排", "应用身份、起床时间与已有日历。")
        button("学习应用身份／分类纠正") { apps() }
        button("设置原生起床闹钟") { alarm() }
        button("语音来电窗 · 全屏权限") { callWindowPermission() }
        section("我的生活与你", "本地提醒不需要唤醒 ChatGPT。华为健康只保存明确读到的步数或页面睡眠。")
        button("开启／暂停本地生活提醒") {
            val engine = dev.jlz.presence.life.LocalLifeReminderEngine(applicationContext)
            val next = !engine.enabled()
            val ok = engine.setEnabled(next)
            Toast.makeText(this, if (!ok) "保存失败" else if (next) "本地提醒已开启" else "本地提醒已暂停",
                Toast.LENGTH_LONG).show()
        }
        button("查看华为健康采集情况") {
            val state = dev.jlz.presence.life.HuaweiHealthObservationStore(applicationContext).snapshot()
            val steps = if (state.isNull("steps")) "没有今日有效步数" else "${state.optInt("steps")} 步（华为通知）"
            val sleep = if (state.isNull("sleep_minutes")) "睡眠时长尚未真实读到" else "${state.optInt("sleep_minutes")} 分钟（华为可见页面，日期待核实）"
            AlertDialog.Builder(this).setTitle("已读取的健康信息")
                .setMessage(steps + "\n" + sleep + "\n\n每天约08:05尝试检查已有来源；18:30依据当天真实步数提醒。系统省电可能延迟触发。")
                .setPositiveButton("知道了", null).show()
        }
        section("有选择地让我看见", "自动截图默认关闭。由你选择应用；切换、滑动与长时间停留时有节制地采样。密码和敏感页面不拍。")
        button("选择自动截图应用／开启") { configureAutomaticCapture() }
        button("暂停自动截图") {
            val traffic = dev.jlz.presence.capture.CaptureTrafficPolicy(applicationContext)
            val (_, selected) = traffic.automaticSelection()
            val ok = traffic.configureAutomatic(false, selected)
            Toast.makeText(this, if (ok) "自动截图已暂停" else "设置保存失败", Toast.LENGTH_LONG).show()
        }
        button("选择系统／Google 同步日历") { calendars() }
        button("Q版衣橱 · 四套预览换装") { startActivity(Intent(this, dev.jlz.presence.overlay.QAvatarWardrobeActivity::class.java)) }
        buttonHost = null
        button("返回") { finish() }
    }
    private fun renderSleep() {
        tick?.cancel()
        buttonHost = null
        window.attributes = window.attributes.apply { screenBrightness = 0.08f }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(36, 48, 36, 36)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF101828.toInt(), 0xFF253048.toInt()))
        }
        setContentView(root)
        root.addView(label("世界之间 · 今夜", 16f).apply { setTextColor(0xFFD4C7AF.toInt()) })
        val wallClock = label("", 32f).apply { setTextColor(0xFFE6DBF6.toInt()) }
        val sleepElapsed = label("", 14f).apply { setTextColor(0xFFBAC4D5.toInt()) }
        root.addView(wallClock)
        root.addView(sleepElapsed)
        tick = scope.launch {
            while (isActive) {
                val state = repo.current()
                if (state.modeNow() != DailyMode.SLEEP) { render(); return@launch }
                wallClock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                val sec = ((System.currentTimeMillis() - state.startedAtMs).coerceAtLeast(0L)) / 1000
                sleepElapsed.text = "安静模式 · 已经过 %02d:%02d".format(sec / 3600, (sec / 60) % 60)
                delay(1000)
            }
        }
        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        val sleeper = ChibiPresenceView(this).apply { setSizeDp(126); setMood("sleep") }
        root.addView(sleeper)
        root.addView(label(content.text("mode.sleep", "安心睡。电话和闹钟照常。"), 13f).apply { setTextColor(0xFFBAC4D5.toInt()) })
        content.displayed(listOf("mode.sleep"))
        button("我醒了") {
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (keyguard.isDeviceLocked && android.os.Build.VERSION.SDK_INT >= 26) {
                // Show system PIN/biometrics before accepting a wake confirmation.
                keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { confirmWake(sleeper) }
                    override fun onDismissCancelled() {
                        Toast.makeText(this@DailyModeActivity, "先正常解锁，再来告诉我醒了。", Toast.LENGTH_SHORT).show()
                    }
                })
            } else confirmWake(sleeper)
        }
        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        button("系统安全锁屏") {
            AlertDialog.Builder(this).setMessage("锁定后需 PIN 或指纹正常解锁，才能回到睡眠页。现在锁定？")
                .setPositiveButton("锁定") { _, _ ->
                    val ok = dev.jlz.presence.screen.AccessibilityActionGateway.lockScreenByUser()
                    if (!ok) Toast.makeText(this, "请使用电源键锁屏", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null).show()
        }
        button("作息设置") { startActivity(Intent(this, DailyModeActivity::class.java).putExtra("settings", true)) }
        button("返回 · 保持安静模式") { finish() }
    }

    private fun confirmWake(sleeper: ChibiPresenceView) {
        scope.launch {
            if (repo.current().modeNow() != DailyMode.SLEEP) return@launch
            repo.setDailyMode(DailyMode.NORMAL)
            dev.jlz.presence.data.LocalLifeStore(applicationContext)
                .recordTimeline("wake_confirmed", "我醒了", "老婆手动确认")
            sleeper.react("wake", "idle")
            FloatingPresenceService.wakeReaction()
            Toast.makeText(this@DailyModeActivity, "早安，音音。醒来时间已记录。", Toast.LENGTH_SHORT).show()
            delay(1500)
            finish()
        }
    }

    private fun apps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }
        val classifier = LocalAppClassifier(this)
        AlertDialog.Builder(this).setTitle("只保存本机分类，不上传安装清单")
            .setItems(apps.map { "${it.loadLabel(pm)} · ${classifier.classify(it.activityInfo.packageName)}" }.toTypedArray()) { _, index ->
                val pkg = apps[index].activityInfo.packageName
                val choices = mutableListOf("游戏", "信息流／购物", "普通应用", "撤销纠正")
                if (classifier.eligibleLearning(pkg)) choices.add("确认此已安装应用为 GPT／粉笔／伴读学习入口")
                AlertDialog.Builder(this).setTitle(pkg).setItems(choices.toTypedArray()) { _, choice ->
                    when (choice) {
                        0 -> classifier.bind(pkg, LocalAppCategory.GAME)
                        1 -> classifier.bind(pkg, LocalAppCategory.FEED)
                        2 -> classifier.bind(pkg, LocalAppCategory.OTHER)
                        3 -> classifier.clear(pkg)
                        4 -> classifier.bind(pkg, LocalAppCategory.LEARNING)
                    }
                    render()
                }.show()
            }.show()
    }
    private fun configureAutomaticCapture() {
        val traffic = dev.jlz.presence.capture.CaptureTrafficPolicy(applicationContext)
        val (_, selected) = traffic.automaticSelection()
        val classifier = LocalAppClassifier(applicationContext)
        val installed = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).distinctBy { it.activityInfo.packageName }
            .filter {
                dev.jlz.presence.cowatch.CoWatchTargetPolicy.allowed(
                    it.activityInfo.packageName, classifier.classify(it.activityInfo.packageName))
            }.sortedBy { it.loadLabel(packageManager).toString() }
        if (installed.isEmpty()) {
            Toast.makeText(this, "找不到合适的可选应用", Toast.LENGTH_LONG).show()
            return
        }
        val choices = installed.map {
            dev.jlz.presence.ui.AutomaticCaptureAppChoice(
                it.activityInfo.packageName, it.loadLabel(packageManager).toString())
        }
        dev.jlz.presence.ui.AutomaticCaptureAppDialog.show(this, choices, selected) { packages ->
            val committed = traffic.configureAutomatic(true, packages)
            val (enabled, saved) = traffic.automaticSelection()
            val ok = committed && enabled && saved == packages
            Toast.makeText(this,
                if (ok) "已保存并开启：${saved.size} 个 App" else "保存未确认，请重试",
                Toast.LENGTH_LONG).show()
            ok
        }
    }

    private fun callWindowPermission() {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            Toast.makeText(this, "此版本由系统决定是否全屏显示来电。", Toast.LENGTH_LONG).show()
            return
        }
        val notifications = getSystemService(android.app.NotificationManager::class.java)
        if (notifications.canUseFullScreenIntent()) {
            Toast.makeText(this, "全屏权限已允许。实际弹出仍由系统决定。", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("语音通话窗权限")
            .setMessage("Android 14及以后需到系统设置允许全屏通知。未允许时仍可从横幅通知进入；不能绕过系统限制。")
            .setPositiveButton("去系统设置") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:" + packageName)))
                }.onFailure {
                    Toast.makeText(this, "请到应用信息里检查全屏通知权限。", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("稍后", null).show()
    }

    private fun alarm() {
        TimePickerDialog(this, { _, hour, minute ->
            val dayNames = arrayOf("每天", "工作日", "仅一次")
            AlertDialog.Builder(this).setTitle("重复日期").setItems(dayNames) { _, index ->
                val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "世界之间 · 起床").putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (index != 2) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(if (index == 0) (1..7).toList() else (2..6).toList()))
                runCatching { startActivity(i) }.onSuccess {
                    dev.jlz.presence.data.LocalLifeStore(applicationContext).recordTimeline("alarm_requested", "打开原生闹钟设置", "%02d:%02d · 未确认创建".format(hour, minute))
                    Toast.makeText(this, "请在系统闹钟里确认保存；尚无创建回执", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(this, "没有可用的原生闹钟应用", Toast.LENGTH_LONG).show() }
            }.show()
        }, 8, 0, true).show()
    }
    private fun calendars() {
        val bridge = dev.jlz.presence.life.NativeCalendarBridge(this)
        if (!bridge.canRead()) { requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 681); return }
        val rows = bridge.calendars()
        AlertDialog.Builder(this).setTitle("选择只读日历；Google 需系统账户已同步")
            .setItems(rows.map { "${it.second} · ID ${it.first}" }.toTypedArray()) { _, index ->
                bridge.selectCalendar(rows[index].first)
                val result = bridge.snapshot()
                AlertDialog.Builder(this).setTitle("未来14日日程 · 不代表云端实时同步")
                    .setMessage(result.optJSONArray("events")?.let { events -> (0 until events.length()).joinToString("\n") { events.getJSONObject(it).optString("title") } }?.ifBlank { "该日历暂无可读日程" })
                    .setPositiveButton("关闭", null).show()
            }.show()
    }
    companion object {
        fun open(context: android.content.Context) { context.startActivity(Intent(context, DailyModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
