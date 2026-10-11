package dev.jlz.presence.focus

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var root: LinearLayout
    private var tick: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun label(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(39, 50, 77)); setPadding(8, 14, 8, 14)
    }
    private fun button(text: String, click: () -> Unit) { root.addView(Button(this).apply { this.text = text; textSize = 13f; setOnClickListener { click() } }) }
    private fun change(mode: DailyMode, minutes: Int = 25) { scope.launch { if (mode in setOf(DailyMode.FOCUS, DailyMode.BREAK)) withContext(Dispatchers.IO) { LocalAppClassifier(applicationContext).refreshVisible() }; repo.setDailyMode(mode, minutes); FloatingPresenceService.refreshArtworkAfterImport(); render() } }
    private fun render() { scope.launch { if (repo.current().modeNow() == DailyMode.SLEEP && !intent.getBooleanExtra("settings", false)) renderSleep() else renderSettings() } }
    private fun renderSettings() {
        tick?.cancel()
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 44, 36, 36); setBackgroundColor(0xFFF5F3F8.toInt()) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("世界之间 · 作息", 22f))
        val stateLabel = label("读取本机状态…"); root.addView(stateLabel)
        tick = scope.launch {
            while (isActive) {
                val s = repo.current()
                val sec = s.remainingMs() / 1000
                stateLabel.text = "${s.modeNow()} · %02d:%02d".format(sec / 60, sec % 60) + if (!dev.jlz.presence.screen.ScreenObservationBus.isAvailable()) "\n无障碍未连接，门禁保护不可用" else ""
                delay(1000)
            }
        }
        root.addView(label("专注仅 GPT／粉笔／独立伴读；请先核对学习应用。"))
        for (m in listOf(25, 45, 60)) button("专注 $m 分钟") { change(DailyMode.FOCUS, m) }
        for (m in listOf(5, 10, 15)) button("短休 $m 分钟 · 游戏不放行") { change(DailyMode.BREAK, m) }
        button("睡眠模式") { change(DailyMode.SLEEP) }
        button("结束当前模式") { change(DailyMode.NORMAL) }
        button("学习应用身份／分类纠正") { apps() }
        button("设置原生起床闹钟") { alarm() }
        button("选择系统／Google 同步日历") { calendars() }
        button("换装") {
            val packs = dev.jlz.presence.overlay.QAvatarAssetImporter.listPacks(this)
            AlertDialog.Builder(this).setTitle("本机角色库").setItems(packs.map { it.label }.toTypedArray()) { _, i ->
                dev.jlz.presence.overlay.QAvatarAssetImporter.selectPack(this, packs[i].id)
                FloatingPresenceService.refreshArtworkAfterImport(); render()
            }.show()
        }
        button("返回") { finish() }
    }
    private fun renderSleep() {
        tick?.cancel()
        window.attributes = window.attributes.apply { screenBrightness = 0.08f }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(36, 48, 36, 36)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF101828.toInt(), 0xFF253048.toInt()))
        }
        setContentView(root)
        root.addView(label("世界之间 · 今夜", 16f).apply { setTextColor(0xFFD4C7AF.toInt()) })
        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        val sleeper = ChibiPresenceView(this).apply { setSizeDp(126); setMood("sleep") }
        root.addView(sleeper)
        root.addView(label("安心睡。电话和闹钟照常。", 13f).apply { setTextColor(0xFFBAC4D5.toInt()) })
        button("我醒了") {
            scope.launch {
                repo.setDailyMode(DailyMode.NORMAL)
                dev.jlz.presence.data.LocalLifeStore(applicationContext).recordTimeline("wake_confirmed", "我醒了", "用户手动确认")
                sleeper.react("wake", "idle")
                FloatingPresenceService.wakeReaction()
                Toast.makeText(this@DailyModeActivity, "早安，音音。醒来时间已记录。", Toast.LENGTH_SHORT).show()
                delay(1500); finish()
            }
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
