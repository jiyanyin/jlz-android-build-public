package dev.jlz.presence.life

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jlz.presence.R
import dev.jlz.presence.WebShellActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-initiated '此刻我在' reminder; it never starts device surveillance.
 * Only a dedicated Android notification + flexible alarm, not a second app
 * timer or a privileged lock-screen overlay. Notification actions work while
 * WebShell is backgrounded, with durable one-time end reconciliation.
 */
internal object TimedLifeReminderPolicy {
    fun firstNudgeMs(action: String): Long = when (action) {
        "吃饭", "走路", "散步" -> 30 * 60_000L
        "学习", "刷题", "复盘", "写申论", "工作" -> 60 * 60_000L
        else -> 45 * 60_000L
    }
}

class TimedLifeReminderBridge(private val context: Context) {
    private val store get() = TimedLifeReminderStore(context)
    @JavascriptInterface fun begin(payload: String): String = runCatching {
        store.begin(JSONObject(payload))
    }.getOrElse { JSONObject().put("ok",false).put("code","invalid_life_session") }.toString()
    @JavascriptInterface fun finish(sessionId: String): String =
        store.end(sessionId,false).toString()
    @JavascriptInterface fun pendingEnds(): String = store.pendingEnds().toString()
    @JavascriptInterface fun acknowledged(eventId: String): String =
        store.ackEnd(eventId).toString()
    @JavascriptInterface fun reminderStatus(): String = store.status().toString()
}

class TimedLifeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val session = intent?.getStringExtra("session_id").orEmpty()
        if (session.isBlank()) return
        val store=TimedLifeReminderStore(context)
        when (intent?.action) {
            ACTION_REMIND -> store.remind(session)
            ACTION_END -> store.end(session,true)
            ACTION_SNOOZE -> store.snooze(session,15*60_000L)
        }
    }
    companion object {
        const val ACTION_REMIND = "dev.jlz.presence.life.REMIND"
        const val ACTION_END = "dev.jlz.presence.life.END"
        const val ACTION_SNOOZE = "dev.jlz.presence.life.SNOOZE"
    }
}

internal class TimedLifeReminderStore(private val context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("timed_life_reminders_v1",Context.MODE_PRIVATE)
    private val notify get()=context.getSystemService(NotificationManager::class.java)

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED

    private fun actionIntent(action: String, session: String, code: Int): PendingIntent {
        val intent=Intent(context,TimedLifeReminderReceiver::class.java)
            .setAction(action).putExtra("session_id",session)
        return PendingIntent.getBroadcast(context,code,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun open(): PendingIntent {
        val url=Uri.parse("https://between-worlds-prod.pages.dev/?shell=android&open=life")
        val intent=Intent(context,WebShellActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context,9201,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannels() {
        notify.createNotificationChannel(NotificationChannel("life_active_v1",
            "此刻我在 · 进行中", NotificationManager.IMPORTANCE_LOW).apply {
            description="用户主动开始的活动计时与结束入口"
            setShowBadge(false);enableVibration(false);setSound(null,null)
        })
        notify.createNotificationChannel(NotificationChannel("life_remind_v1",
            "此刻我在 · 记得结束", NotificationManager.IMPORTANCE_HIGH).apply {
            description="定时询问这段活动是否继续";enableVibration(false)
        })
    }

    private fun schedule(session: String, atMs: Long) {
        val pi=actionIntent(TimedLifeReminderReceiver.ACTION_REMIND,session,9241)
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,atMs.coerceAtLeast(System.currentTimeMillis()+1000),pi)
    }
    private fun cancelAlarm() {
        val pi=actionIntent(TimedLifeReminderReceiver.ACTION_REMIND,"",9241)
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
    }

    @SuppressLint("MissingPermission")
    private fun display(urgent: Boolean) {
        if(!allowed() || !notify.areNotificationsEnabled())return
        ensureChannels()
        val session=prefs.getString("active_session","").orEmpty()
        if(session.isBlank())return
        val action=prefs.getString("action","这一段").orEmpty()
        val whenStarted=prefs.getLong("start_at_ms",0L)
        val text=if(urgent)"还在$action吗？结束、稍后提醒，都由你决定。"
            else "正在记录：$action · 从 ${java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA).format(java.util.Date(whenStarted))} 开始"
        val notification=NotificationCompat.Builder(context,if(urgent)"life_remind_v1" else "life_active_v1")
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setContentTitle(if(urgent)"纪临洲 · 这一段还在继续？" else "纪临洲 · 此刻我在")
            .setContentText(text).setContentIntent(open())
            .setAutoCancel(urgent).setOngoing(!urgent)
            .setPriority(if(urgent)NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .addAction(0,"结束这一段",actionIntent(TimedLifeReminderReceiver.ACTION_END,session,9242))
            .addAction(0,"再等15分钟",actionIntent(TimedLifeReminderReceiver.ACTION_SNOOZE,session,9243))
            .build()
        runCatching{notify.notify(if(urgent)9202 else 9201,notification)}
    }

    @Synchronized fun begin(data: JSONObject): JSONObject {
        val session=data.optString("session_id","")
        val action=data.optString("action","").trim().take(70)
        val started=data.optLong("start_at_ms",0L)
        val now=System.currentTimeMillis()
        if(session.length !in 5..100 || action.isEmpty() ||
            started !in (now-10*60_000L)..(now+60_000L))
            return JSONObject().put("ok",false).put("code","invalid_start")
        val prior=prefs.getString("active_session","").orEmpty()
        if(prior.isNotBlank()&&prior!=session)
            return JSONObject().put("ok",false).put("code","already_recording")
        val due=started+TimedLifeReminderPolicy.firstNudgeMs(action)
        if(!prefs.edit().putString("active_session",session)
                .putString("action",action).putLong("start_at_ms",started)
                .putLong("next_due_at_ms",due).commit())
            return JSONObject().put("ok",false).put("code","local_save_failed")
        schedule(session,due);display(false)
        return JSONObject().put("ok",true).put("notice_permission_ready",allowed())
            .put("next_nudge_at_ms",due)
    }

    @Synchronized fun end(session: String, fromNotification: Boolean): JSONObject {
        if(session.isBlank() || prefs.getString("active_session","")!=session)
            return JSONObject().put("ok",false).put("code","session_not_active")
        val now=System.currentTimeMillis()
        val action=prefs.getString("action","").orEmpty()
        val start=prefs.getLong("start_at_ms",0L)
        val editor=prefs.edit().remove("active_session").remove("action").remove("next_due_at_ms")
        if(fromNotification){
            val id=UUID.nameUUIDFromBytes(("life-end:"+session).toByteArray()).toString()
            val event=JSONObject().put("event_id",id).put("session_id",session)
                .put("action",action).put("start_at_ms",start).put("end_at_ms",now)
            val prior=JSONArray(prefs.getString("pending_ends","[]"))
            if((0 until prior.length()).none { prior.optJSONObject(it)?.optString("event_id")==id }) prior.put(event)
            editor.putString("pending_ends",prior.toString())
        }
        val saved=editor.commit()
        if(saved){cancelAlarm();notify.cancel(9201);notify.cancel(9202)}
        return JSONObject().put("ok",saved).put("ended_at_ms",now)
    }

    @Synchronized fun snooze(session: String, millis: Long): JSONObject {
        if(prefs.getString("active_session","")!=session)
            return JSONObject().put("ok",false)
        val due=System.currentTimeMillis()+millis
        val saved=prefs.edit().putLong("next_due_at_ms",due).commit()
        if(saved){notify.cancel(9202);schedule(session,due);display(false)}
        return JSONObject().put("ok",saved).put("next_nudge_at_ms",due)
    }

    @Synchronized fun remind(session: String) {
        if(prefs.getString("active_session","")!=session)return
        if(System.currentTimeMillis()+30000 < prefs.getLong("next_due_at_ms",0L))return
        display(true)
    }

    @Synchronized fun pendingEnds(): JSONArray =
        runCatching { JSONArray(prefs.getString("pending_ends","[]")) }.getOrDefault(JSONArray())

    @Synchronized fun ackEnd(eventId: String): JSONObject {
        val all=pendingEnds()
        val next=JSONArray()
        for(i in 0 until all.length()){
            val item=all.optJSONObject(i) ?: continue
            if(item.optString("event_id")!=eventId)next.put(item)
        }
        val ok=prefs.edit().putString("pending_ends",next.toString()).commit()
        return JSONObject().put("ok",ok)
    }

    fun status(): JSONObject = JSONObject()
        .put("active",prefs.getString("active_session","").orEmpty().isNotBlank())
        .put("session_id",prefs.getString("active_session","").orEmpty())
        .put("action",prefs.getString("action","").orEmpty())
        .put("start_at_ms",prefs.getLong("start_at_ms",0L))
        .put("next_nudge_at_ms",prefs.getLong("next_due_at_ms",0L))
        .put("notification_available",allowed()&&notify.areNotificationsEnabled())
}
