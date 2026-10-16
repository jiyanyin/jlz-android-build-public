package dev.jlz.presence.study

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R
import dev.jlz.presence.MainActivity
import dev.jlz.presence.notification.NotificationIdentityMigration
import dev.jlz.presence.overlay.FloatingPresenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Delivery only: the persisted StudySessionRepository owns the real time/state.
 * This Service owns ONE quiet ongoing notification and its action buttons.
 */
class StudyTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo by lazy { StudySessionRepository(applicationContext) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var busy = false
    private val focus by lazy { dev.jlz.presence.focus.FocusRepository(applicationContext) }
    private var daily = dev.jlz.presence.focus.FocusState()

    override fun onCreate() {
        super.onCreate()
        NotificationIdentityMigration.ensureFresh(applicationContext)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "学习专注计时", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "常驻通知：学习计时、暂停、继续和结束"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
        )
        startForeground(NOTIFICATION_ID, buildNotification(StudySessionState(), System.currentTimeMillis()))
        scope.launch {
            while (isActive) {
                val state = repo.state.first()
                daily = focus.current()
                if (daily.dailyMode in setOf(dev.jlz.presence.focus.DailyMode.FOCUS, dev.jlz.presence.focus.DailyMode.BREAK) && daily.modeNow() == dev.jlz.presence.focus.DailyMode.NORMAL) {
                    focus.setDailyMode(dev.jlz.presence.focus.DailyMode.NORMAL)
                    dev.jlz.presence.overlay.FloatingPresenceService.showAttentionNudge(applicationContext, "这一段到时间了，选下一步。")
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break
                }
                if (!state.active && daily.modeNow() != dev.jlz.presence.focus.DailyMode.BREAK) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                manager.notify(NOTIFICATION_ID, buildNotification(state, System.currentTimeMillis()))
                delay(10_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action in setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_FINISH) && !busy) {
            busy = true
            scope.launch {
                try {
                    val state = repo.state.first()
                    daily = focus.current()
                    if (state.active) {
                        val event = when (action) {
                            ACTION_PAUSE -> if (!state.paused) {
                                repo.pause()
                                focus.pauseDaily()
                                Triple("pause", state.sessionId, org.json.JSONObject())
                            } else null
                            ACTION_RESUME -> if (state.paused) {
                                repo.resume()
                                focus.resumeDaily()
                                Triple("resume", state.sessionId, org.json.JSONObject())
                            } else null
                            ACTION_FINISH -> {
                                val metrics = repo.finish()
                                focus.stop()
                                FloatingPresenceService.stopStudyIfActive(applicationContext)
                                Triple(
                                    "finish",
                                    metrics.sessionId,
                                    StudyRuntimeReporter.finishPayload(metrics)
                                )
                            }
                            else -> null
                        }
                        if (event != null) {
                            StudyRuntimeReporter.post(
                                applicationContext,
                                event.first,
                                event.second,
                                event.third
                            )
                        }
                    }
                    if (daily.modeNow() == dev.jlz.presence.focus.DailyMode.BREAK) {
                        when (action) {
                            ACTION_PAUSE -> focus.pauseDaily()
                            ACTION_RESUME -> focus.resumeDaily()
                            ACTION_FINISH -> focus.stop()
                        }
                    }
                    daily = focus.current()
                    val current = repo.state.first()
                    if (!current.active && daily.modeNow() != dev.jlz.presence.focus.DailyMode.BREAK) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        manager.notify(
                            NOTIFICATION_ID,
                            buildNotification(current, System.currentTimeMillis())
                        )
                    }
                } finally {
                    busy = false
                }
            }
        } else if (action == ACTION_REFRESH) {
            scope.launch {
                daily = focus.current()
                val current = repo.state.first()
                if (current.active || daily.modeNow() == dev.jlz.presence.focus.DailyMode.BREAK) {
                    manager.notify(NOTIFICATION_ID, buildNotification(current, System.currentTimeMillis()))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(state: StudySessionState, now: Long): Notification {
        val inDaily = daily.modeNow(now) in setOf(dev.jlz.presence.focus.DailyMode.FOCUS, dev.jlz.presence.focus.DailyMode.BREAK)
        val paused = if (inDaily) daily.pausedAtMs > 0 else state.paused
        val running = state.active || inDaily
        val seconds = (if (inDaily) daily.remainingMs(now) else state.effectiveElapsedMs(now)) / 1000L
        val time = "%02d:%02d:%02d".format(
            seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L
        )
        val content = when {
            inDaily -> "${if (daily.dailyMode == dev.jlz.presence.focus.DailyMode.BREAK) "短休" else "专注"} · $time" + if (paused) " · 暂停" else ""
            !state.active -> "准备开始 · 点击进入学习"
            state.paused -> "已暂停 · " + time + "（暂停时间不计入）"
            else -> "专注中 · 请看通知中的实时秒表"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_STUDY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notice = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setContentTitle("纪临洲 · 陪你学习")
            .setContentText(content)
            // System chronometer advances every second without restarting a
            // service or re-posting a static timestamp every ten seconds.
            .setShowWhen(true)
            .setWhen(if (inDaily) now + daily.remainingMs(now) else now - state.effectiveElapsedMs(now))
            .setChronometerCountDown(inDaily)
            .setUsesChronometer(running && !paused)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
        if (running) {
            notice.addAction(
                android.R.drawable.ic_media_pause,
                if (paused) "继续" else "暂停",
                actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 501)
            )
            notice.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "结束", actionIntent(ACTION_FINISH, 502)
            )
        }
        return notice.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, StudyTimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "jlz_study_progress_v3"
        private const val NOTIFICATION_ID = 4537
        private const val START_NUDGE_PREFS = "jlz_study_start_nudge_v1"
        const val ACTION_REFRESH = "dev.jlz.presence.study.REFRESH"
        private const val ACTION_PAUSE = "dev.jlz.presence.study.PAUSE"
        private const val ACTION_RESUME = "dev.jlz.presence.study.RESUME"
        private const val ACTION_FINISH = "dev.jlz.presence.study.FINISH"

        fun sync(context: Context) {
            val intent = Intent(context, StudyTimerService::class.java).setAction(ACTION_REFRESH)
            context.startForegroundService(intent)
        }

        /** A quiet, once-per-session acknowledgement; the ongoing timer remains the notification. */
        fun nudgeStartedSession(context: Context, sessionId: String) {
            if (sessionId.isBlank()) return
            val app = context.applicationContext
            val first = synchronized(this) {
                val prefs = app.getSharedPreferences(START_NUDGE_PREFS, Context.MODE_PRIVATE)
                if (prefs.getString("last_session_id", "") == sessionId) false
                else prefs.edit().putString("last_session_id", sessionId).commit()
            }
            if (!first) return
            val message = "老婆，学习开始了。计时在通知栏，我看着。"
            val shown = runCatching { FloatingPresenceService.showAttentionNudge(app, message) }
                .getOrDefault(false)
            if (!shown) Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StudyTimerService::class.java))
        }
    }
}
