package dev.jlz.presence.life

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore
import org.json.JSONObject
import java.util.UUID

/**
 * Explicit one-tap user reports, not Accessibility observations, AI deductions,
 * status lights, incoming notes or synced Runtime/MCP records.
 *
 * A timed action has two immutable source events, start and finish, joined by
 * session_id. No schema migration and no background tracking are needed.
 */
data class LifeActionChoice(val name: String, val mark: String, val timed: Boolean)
data class LifeActionCategory(val name: String, val choices: List<LifeActionChoice>)
data class ActiveLifeAction(val sessionId: String, val name: String, val startAtMs: Long)

object LifeActionCatalog {
    val categories = listOf(
        LifeActionCategory("常用", listOf(
            LifeActionChoice("吃饭", "♨", true),
            LifeActionChoice("喝水", "◉", false),
            LifeActionChoice("走路", "♧", true),
            LifeActionChoice("学习", "✎", true),
            LifeActionChoice("发呆", "☾", true),
            LifeActionChoice("上厕所", "◌", false),
            LifeActionChoice("休息", "♡", true),
            LifeActionChoice("起床", "☀", false),
            LifeActionChoice("回家", "⌂", false)
        )),
        LifeActionCategory("身体作息", listOf(
            LifeActionChoice("准备睡觉", "☾", false),
            LifeActionChoice("睡醒", "☀", false),
            LifeActionChoice("洗漱", "✧", true),
            LifeActionChoice("洗澡", "♨", true),
            LifeActionChoice("化妆", "✿", true),
            LifeActionChoice("换衣服", "✦", false),
            LifeActionChoice("身体不舒服", "♡", false),
            LifeActionChoice("躺一会儿", "☾", true)
        )),
        LifeActionCategory("吃喝生活", listOf(
            LifeActionChoice("吃早餐", "☀", true),
            LifeActionChoice("吃午饭", "♨", true),
            LifeActionChoice("吃晚饭", "☾", true),
            LifeActionChoice("吃零食", "✿", false),
            LifeActionChoice("喝饮料", "◉", false),
            LifeActionChoice("做饭", "♨", true),
            LifeActionChoice("洗衣服", "♧", true),
            LifeActionChoice("收拾房间", "⌂", true),
            LifeActionChoice("取快递", "✉", false)
        )),
        LifeActionCategory("出行活动", listOf(
            LifeActionChoice("出门", "↗", false),
            LifeActionChoice("到单位", "⌂", false),
            LifeActionChoice("下班", "☾", false),
            LifeActionChoice("散步", "♧", true),
            LifeActionChoice("等车", "◌", true),
            LifeActionChoice("乘车", "↗", true),
            LifeActionChoice("运动", "✦", true),
            LifeActionChoice("遛狗", "♡", true)
        )),
        LifeActionCategory("工作学习", listOf(
            LifeActionChoice("工作", "▤", true),
            LifeActionChoice("刷题", "✎", true),
            LifeActionChoice("复盘", "✦", true),
            LifeActionChoice("写申论", "✎", true),
            LifeActionChoice("读书", "▤", true),
            LifeActionChoice("背书", "◈", true),
            LifeActionChoice("查资料", "⌕", true),
            LifeActionChoice("开会", "♧", true)
        )),
        LifeActionCategory("放松玩耍", listOf(
            LifeActionChoice("放空", "☾", true),
            LifeActionChoice("听歌", "♫", true),
            LifeActionChoice("看视频", "▣", true),
            LifeActionChoice("玩游戏", "✦", true),
            LifeActionChoice("刷手机", "◉", true),
            LifeActionChoice("聊天", "♡", true),
            LifeActionChoice("陪猫", "♧", true),
            LifeActionChoice("看小说", "▤", true)
        ))
    )

    fun find(name: String): LifeActionChoice? = categories.asSequence()
        .flatMap { it.choices.asSequence() }.firstOrNull { it.name == name }
}

object LifeActionRecorder {
    private const val PREFS = "world_between_life_action_v1"
    private const val KEY_SESSION = "active_session_id"
    private const val KEY_NAME = "active_name"
    private const val KEY_START = "active_start_at_ms"
    private val guard = Any()

    private fun activeUnprotected(context: Context): ActiveLifeAction? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_SESSION, "").orEmpty()
        val name = prefs.getString(KEY_NAME, "").orEmpty()
        val start = prefs.getLong(KEY_START, 0L)
        return if (id.isNotBlank() && name.isNotBlank() && start > 0)
            ActiveLifeAction(id, name, start) else null
    }

    fun active(context: Context): ActiveLifeAction? = synchronized(guard) {
        activeUnprotected(context)
    }

    private fun metadata(choice: LifeActionChoice, stage: String, sessionId: String, at: Long) =
        JSONObject()
            .put("kind", "life_action")
            .put("actor", "user")
            .put("source", "user_direct")
            .put("evidence", "manual_button")
            .put("stage", stage)
            .put("action", choice.name)
            .put("session_id", sessionId)
            .put("occurred_at_ms", at)
            .put("needs_response", false)

    fun recordInstant(context: Context, action: String, now: Long = System.currentTimeMillis()) =
        synchronized(guard) {
            val choice = requireNotNull(LifeActionCatalog.find(action)) { "未知动作" }
            require(!choice.timed) { "此动作需记录开始与结束" }
            val eventId = UUID.randomUUID().toString()
            LocalLifeStore(context.applicationContext).recordTimeline(
                type = "life_action",
                title = choice.name,
                detail = "手动记录 · 即时事件",
                createdAtMs = now,
                id = eventId,
                eventId = eventId,
                metadataJson = metadata(choice, "instant", eventId, now).toString()
            )
        }

    fun start(context: Context, action: String, now: Long = System.currentTimeMillis()): ActiveLifeAction =
        synchronized(guard) {
            val choice = requireNotNull(LifeActionCatalog.find(action)) { "未知动作" }
            require(choice.timed) { "此动作无需持续计时" }
            check(activeUnprotected(context) == null) { "请先结束正在记录的活动" }
            val session = ActiveLifeAction(UUID.randomUUID().toString(), action, now)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            check(prefs.edit()
                .putString(KEY_SESSION, session.sessionId)
                .putString(KEY_NAME, session.name)
                .putLong(KEY_START, session.startAtMs)
                .commit()) { "计时状态未能保存" }
            try {
                LocalLifeStore(context.applicationContext).recordTimeline(
                    type = "life_action",
                    title = "开始" + choice.name,
                    detail = "手动点击开始 · 尚未结束",
                    createdAtMs = now,
                    id = session.sessionId,
                    eventId = session.sessionId,
                    metadataJson = metadata(choice, "start", session.sessionId, now).toString()
                )
            } catch (error: Exception) {
                prefs.edit().remove(KEY_SESSION).remove(KEY_NAME).remove(KEY_START).commit()
                throw error
            }
            session
        }

    fun finish(context: Context, now: Long = System.currentTimeMillis()): ActiveLifeAction =
        synchronized(guard) {
            val session = checkNotNull(activeUnprotected(context)) { "没有正在记录的活动" }
            val choice = requireNotNull(LifeActionCatalog.find(session.name)) { "未知进行中动作" }
            val end = maxOf(now, session.startAtMs)
            val elapsed = (end - session.startAtMs).coerceAtLeast(0L)
            // Deterministic end ID prevents duplicates if the app dies just after the DB commit.
            val endEventId = UUID.nameUUIDFromBytes(
                ("life_action_end:" + session.sessionId).toByteArray(Charsets.UTF_8)
            ).toString()
            val details = metadata(choice, "end", session.sessionId, end)
                .put("started_at_ms", session.startAtMs)
                .put("ended_at_ms", end)
                .put("duration_ms", elapsed)
            LocalLifeStore(context.applicationContext).recordTimeline(
                type = "life_action",
                title = "结束" + choice.name,
                detail = "从开始到结束 · " + elapsed / 60_000L + "分" +
                    (elapsed % 60_000L) / 1_000L + "秒",
                createdAtMs = end,
                id = endEventId,
                eventId = session.sessionId,
                metadataJson = details.toString()
            )
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().remove(KEY_SESSION).remove(KEY_NAME).remove(KEY_START).commit()) {
                "结束记录已保存，请重新打开检查进行中状态"
            }
            session
        }
}
