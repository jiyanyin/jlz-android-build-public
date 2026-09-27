package dev.jlz.presence.study

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.jlz.presence.MainActivity
import dev.jlz.presence.overlay.FloatingPresenceService
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Delivery only: the persisted StudySessionRepository owns the real time/state.
 * This Service owns ONE quiet ongoing notification and its action buttons.
 */
class StudyTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo by lazy { StudySessionRepository(applicationContext) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var busy = false

    override fun onCreate() {
        super.onCreate()
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
                if (!state.active) {
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
                    if (state.active) {
                        val event = when (action) {
                            ACTION_PAUSE -> if (!state.paused) { repo.pause(); "pause" } else null
                            ACTION_RESUME -> if (state.paused) { repo.resume(); "resume" } else null
                            ACTION_FINISH -> {
                                repo.finish()
                                FloatingPresenceService.stopStudyIfActive(applicationContext)
                                "finish"
                            }
                            else -> null
                        }
                        if (event != null) postStudyEvent(event)
                    }
                    val current = repo.state.first()
                    if (!current.active) {
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
                val current = repo.state.first()
                if (current.active) {
                    manager.notify(NOTIFICATION_ID, buildNotification(current, System.currentTimeMillis()))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun postStudyEvent(action: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val settings = RuntimeSettingsRepository(applicationContext).load()
                if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                    RuntimeApiClient(settings).postStudyEvent(
                        action, JSONObject().put("source", "study_notification")
                    )
                }
            }
        }
    }

    private fun buildNotification(state: StudySessionState, now: Long): Notification {
        val seconds = state.effectiveElapsedMs(now) / 1000L
        val time = "%02d:%02d:%02d".format(
            seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L
        )
        val content = when {
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
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("纪临洲 · 陪你学习")
            .setContentText(content)
            // System chronometer advances every second without restarting a
            // service or re-posting a static timestamp every ten seconds.
            .setShowWhen(true)
            .setWhen(now - state.effectiveElapsedMs(now))
            .setUsesChronometer(state.active && !state.paused)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
        if (state.active) {
            notice.addAction(
                android.R.drawable.ic_media_pause,
                if (state.paused) "继续" else "暂停",
                actionIntent(if (state.paused) ACTION_RESUME else ACTION_PAUSE, 501)
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
        private const val CHANNEL_ID = "jlz_study_progress_v1"
        private const val NOTIFICATION_ID = 4317
        const val ACTION_REFRESH = "dev.jlz.presence.study.REFRESH"
        private const val ACTION_PAUSE = "dev.jlz.presence.study.PAUSE"
        private const val ACTION_RESUME = "dev.jlz.presence.study.RESUME"
        private const val ACTION_FINISH = "dev.jlz.presence.study.FINISH"

        fun sync(context: Context) {
            val intent = Intent(context, StudyTimerService::class.java).setAction(ACTION_REFRESH)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StudyTimerService::class.java))
        }
    }
}
