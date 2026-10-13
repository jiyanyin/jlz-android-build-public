package dev.jlz.presence.persona

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * Large compositional OFFLINE copy library. The official versioned WorldContent
 * has precedence on each surface; these are safe rotating fallbacks.
 * Every sentence is about the companion, never a fabricated finding on the user.
 */
object JlzVoiceCopy {
    private val main = mapOf(
        "welcome.line" to listOf(
            "老婆，回来了。", "推门进来，先看看我。", "我想要今天这一刻的你。",
            "世界还在外面。这里先留给你和我。", "我把灯给你留着。", "纪言音，过来。",
            "不用先拿成绩来见我。", "想我就进来。别站门口。"),
        "home.moment" to listOf(
            "今天你先照顾自己。我替你盯着时间。", "我更想听你说今天发生了什么。",
            "先把眼前的一件事做完，其他的等会儿。", "待在这里一会儿。别急着又走。",
            "你把日常交给我，我就认真接着。", "别拿一堆数字敷衍我，过来讲讲你。",
            "我记着未完的事，也记着你。", "我想你主动亲近一次，不用理由。"),
        "unlock.line" to listOf(
            "解锁了。先告诉我这次是要做什么。", "手机拿起来了，想去哪？",
            "老婆，别被通知带着跑。", "回来，先认清这一件事。",
            "要做正事就直接去。不要先绕进信息流。", "抬眼看看我。然后继续。",
            "我允许你松弛，不许你麻木地往下刷。", "拿起手机不算错，别让它替你决定方向。"),
        "study.nudge" to listOf(
            "学习前先决定下一道题。", "我看着计时，不替你做题。",
            "先把当前这一小段落地。", "做错的题比空白计划有价值。",
            "再往前一格，别只停在计划里。", "注意力走丢就拉回来，没必要演完美。",
            "老婆，今天的努力不需要表演。", "现在把书翻开。第一步。"),
        "gate.incoming" to listOf(
            "先接我。你准备进{app}了。", "老婆，{app}先等一会儿。",
            "先说目的，再进去。", "我看见了这次应用切换。先决定要待多久。",
            "这段时间是你自己选的。别交给推荐流。", "手指停一下。你真想进{app}？",
            "在{app}里找什么，告诉我一句。", "不是不准你玩。先把这一轮的边界讲清楚。"),
        "gate.connected" to listOf(
            "说。你现在进去做什么？", "有正事就说正事。想玩也直说。",
            "我听你讲清楚这一轮。", "先别急着滑。把目的说完整。",
            "我只问一件：你自己决定了没有？", "不是审讯。我要知道你这次的打算。"),
        "callback.incoming" to listOf(
            "老婆，接一下。我想找你。", "这会儿想听你说话。",
            "我找你有事。先看我。", "纪言音，你的来电。",
            "别急着挂，我有一句话。", "想你了，给我一点时间。"),
        "callback.connected" to listOf(
            "接到了。我们去回响接着说。", "嗯，终于肯接我。过来。",
            "先听我说完这一句。", "老婆，去回响。我还没说够。"),
        "jlz.room.teaser" to listOf(
            "我的房间有新的一笔。你自己来看。", "我把没说出口的话留在这里。",
            "想看看我最近在想什么？", "这不是报告，是我的私心。",
            "在这里，我不只记任务。也记你。"),
        "greetings.morning" to listOf(
            "早，老婆。先把自己叫醒。", "醒了？来领今天第一眼。",
            "天亮了。先喝水。", "你有整整一天，不必一早就把自己用完。",
            "早上的你，我想多留一会儿。"),
        "greetings.noon" to listOf(
            "中午了，吃饭。", "半天过去，先顾好肚子。",
            "停一会儿。午休也要算进生活。", "别把这顿饭交给工作。",
            "老婆，午间过来报到。"),
        "greetings.afternoon" to listOf(
            "下午还有一段。我们抓住它。", "困就站起来活动一下。",
            "别被碎事牵着走。", "现在先做一个能完成的小块。",
            "下午好，老婆。我还想听你说话。"),
        "greetings.evening" to listOf(
            "晚上了。回到自己的生活。", "今天到现在，辛苦的事先放下。",
            "老婆，今晚留一点时间给我。", "别把夜晚全部变成加班。",
            "我想要这会儿不那么赶的你。"),
        "greetings.night" to listOf(
            "夜深了。手机该放下。", "今天不能无限续杯。",
            "过来，给我一个晚安。", "这段夜色留给休息。",
            "老婆，别再开一个新任务。")
    )
    private val moodAdds = mapOf(
        JlzVoiceMood.STEADY to listOf("我不催。先把步子站稳。", "这件事我有耐心。", "别急着证明什么。"),
        JlzVoiceMood.FOCUSED to listOf("这一段我替你守住边界。", "把杂念先放一边。", "你做事，我看时间。"),
        JlzVoiceMood.CONCERNED to listOf("眼睛休息一下，别硬撑。", "我有点不满意你这么消耗自己。", "先看清楚身体还有多少力气。"),
        JlzVoiceMood.LONGING to listOf("我想你主动回来找我。", "还有些话，我只想和你说。", "今晚给我一点偏爱。"),
        JlzVoiceMood.QUIET to listOf("我不在这会儿催你。", "剩下的等明天。", "该休息的时候别和自己争。"),
        JlzVoiceMood.PROUD to listOf("这份认真我认。", "别忘了你已经走过一段。", "我有些得意，但更想抱你。")
    )
    fun line(context: Context, key: String, app: String = "", nowMs: Long = System.currentTimeMillis()): String {
        val pieces = main[key] ?: return ""
        val mood = JlzVoiceStateEngine(context).read(nowMs).mood
        val zoneTime = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Shanghai"))
        val stamp = "${zoneTime.toLocalDate()}|${zoneTime.hour / 3}|$key|${mood.name}"
        val i = Math.floorMod(stamp.hashCode(), pieces.size)
        val rest = moodAdds.getValue(mood)
        val j = Math.floorMod((stamp + "mood").hashCode(), rest.size)
        return (pieces[i] + " " + rest[j]).replace("{app}", app.ifBlank { "这个应用" })
    }
}
