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
        root.addView(TextView(this).apply { text = "5／10分钟屏幕陪看\n这是每20秒一张的屏幕采样，不是摄像头或双向实时视频。GPT需要主动读取才看见。\n仅分享你本次选择的游戏或 B 站；密码、支付、锁屏及其他应用不采样。断线即停止，最多30张，只暂存最新一帧。"; textSize = 13f; setPadding(0, 20, 0, 24) })
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
            .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName == "tv.danmaku.bili" || classifier.classify(it.activityInfo.packageName) == LocalAppCategory.GAME }
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
