package dev.jlz.presence.cowatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import dev.jlz.presence.focus.LocalAppCategory
import dev.jlz.presence.focus.LocalAppClassifier

/** UI consent followed by Android's per-session screen-sharing consent. */
class CoWatchActivity : Activity() {
    private var minutes = 5
    private var target = ""
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var status: TextView
    private val update = object : Runnable {
        override fun run() {
            status.text = CoWatchState.summary()
            tick.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 60, 36, 36); setBackgroundColor(0xFFF5F3F8.toInt()) }
        root.addView(TextView(this).apply { text = "视频通话"; textSize = 24f })
        root.addView(TextView(this).apply { text = "5／10分钟屏幕陪看\n这是约每5秒一张的屏幕采样，不是摄像头或双向实时视频。先在官端Chat里叫我开始看，再回这里启动；我必须在通话进行时通过MCP逐帧读取。不要等挂断后才叫我，画面只暂存最近90秒。\n只分享本次选定的应用，包括小红书等内容应用；锁屏、输入框与检测到的敏感页面会暂停采样。部分内嵌隐私画面无法完整识别，请避开聊天、支付及身份信息；可随时挂断。最长10分钟，最多120次采样，挂断立即清除。"; textSize = 13f; setPadding(0, 20, 0, 24) })
        status = TextView(this).apply { textSize = 13f }; root.addView(status)
        for (m in listOf(5, 10)) root.addView(Button(this).apply { text = "开始 $m 分钟"; textSize = 13f; setOnClickListener { minutes = m; chooseTarget() } })
        root.addView(Button(this).apply { text = "挂断"; setOnClickListener { stopService(Intent(this@CoWatchActivity, CoWatchService::class.java)); CoWatchState.active = false } })
        root.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(root); tick.post(update)
    }
    private fun chooseTarget() {
        if (CoWatchState.active) { Toast.makeText(this, "请先挂断当前会话", Toast.LENGTH_SHORT).show(); return }
        if (!dev.jlz.presence.screen.ScreenObservationBus.isAvailable()) { Toast.makeText(this, "需要连接无障碍来避让隐私页面；请先检查权限", Toast.LENGTH_LONG).show(); return }
        val classifier = LocalAppClassifier(this)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { CoWatchTargetPolicy.allowed(it.activityInfo.packageName, classifier.classify(it.activityInfo.packageName)) }
            .sortedBy { it.loadLabel(packageManager).toString() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "没有适合屏幕陪看的应用；请先检查安装和无障碍权限。", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("只分享本次选定应用")
            .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, i ->
                target = apps[i].activityInfo.packageName
                startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 692)
            }.setNegativeButton("取消", null).show()
    }
    @Deprecated("Activity result for platform consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 692 && resultCode == RESULT_OK && data != null && target.isNotBlank()) {
            startForegroundService(Intent(this, CoWatchService::class.java).putExtra("consent", data).putExtra("result", resultCode).putExtra("minutes", minutes).putExtra("target", target))
            CoWatchState.reason = "系统已授权，正在连接私人 Runtime…"
        } else if (requestCode == 692) CoWatchState.reason = "系统授权已拒绝；未采样"
    }
    override fun onDestroy() { tick.removeCallbacks(update); super.onDestroy() }
}
