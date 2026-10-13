package dev.jlz.presence.life

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.jlz.presence.focus.LocalAppCategory
import java.time.Instant
import java.time.ZoneId

/** Daily entertainment rest: a recoverable five-minute APP gate, not Android PIN lock. */
class DailyEntertainmentRestGuard(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jlz_entertainment_daily_rest_v1", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun day(now: Long) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
    fun requestOnceThresholdCrossed(now: Long = System.currentTimeMillis()) {
        val today = day(now)
        if (prefs.getString("done_day", "") != today && prefs.getString("pending_day", "") != today)
            prefs.edit().putString("pending_day", today).apply()
    }
    fun restRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val end = prefs.getLong("rest_ends_at_ms", 0L)
        if (prefs.getString("rest_day", "") != day(now)) return 0L
        val remaining = end - now
        if (end > 0 && remaining <= 0) finish(now)
        return remaining.coerceAtLeast(0L)
    }
    fun beginIfDue(now: Long = System.currentTimeMillis()): Long {
        val active = restRemainingMs(now)
        if (active > 0) return active
        if (prefs.getString("pending_day", "") != day(now)) return 0L
        val end = now + 5 * 60_000L
        prefs.edit().putString("pending_day", "").putString("rest_day", day(now))
            .putLong("rest_ends_at_ms", end).apply()
        return end - now
    }
    fun finish(now: Long = System.currentTimeMillis()) {
        prefs.edit().putString("done_day", day(now))
            .remove("rest_ends_at_ms").putString("pending_day", "").apply()
    }
    fun status(now: Long = System.currentTimeMillis()): String = when {
        restRemainingMs(now) > 0 -> "rest_active"
        prefs.getString("pending_day", "") == day(now) -> "rest_pending"
        prefs.getString("done_day", "") == day(now) -> "rest_complete"
        else -> "not_due"
    }
}

/** A visible, cancellable rest surface; the OS lock screen is not impersonated. */
class DailyEntertainmentRestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private val ticker = object : Runnable {
        override fun run() {
            val remain = DailyEntertainmentRestGuard(applicationContext).restRemainingMs()
            if (remain == 0L) { finish(); return }
            clock.text = "休息 %02d:%02d".format(remain / 60_000L, (remain / 1_000L) % 60)
            handler.postDelayed(this, 1_000L)
        }
    }
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 50, 48, 50)
            setBackgroundColor(0xFFF2ECF8.toInt())
        }
        root.addView(TextView(this).apply {
            text = "世界之间 · 休息五分钟"
            textSize = 24f
            setTextColor(0xFF50405F.toInt())
        })
        clock = TextView(this).apply {
            textSize = 32f
            setTextColor(0xFF50405F.toInt())
        }
        root.addView(clock)
        root.addView(TextView(this).apply {
            text = "老婆，今天娱乐时间已经够了。先离开屏幕，喝点水，活动一下。"
            textSize = 16f
            setTextColor(0xFF635577.toInt())
        })
        root.addView(Button(this).apply {
            text = "返回桌面休息"
            setOnClickListener { finish() }
        })
        root.addView(Button(this).apply {
            text = "紧急放行 · 结束这次休息"
            setOnClickListener { DailyEntertainmentRestGuard(applicationContext).finish(); finish() }
        })
        setContentView(root)
        handler.post(ticker)
    }
    override fun onDestroy() { handler.removeCallbacks(ticker); super.onDestroy() }
    companion object {
        fun show(context: Context) = runCatching {
            context.startActivity(Intent(context, DailyEntertainmentRestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.isSuccess
    }
}
