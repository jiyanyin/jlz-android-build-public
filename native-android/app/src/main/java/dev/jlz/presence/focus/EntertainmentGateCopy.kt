package dev.jlz.presence.focus

import kotlin.math.absoluteValue

object EntertainmentGateCopy {
    private val incoming = listOf(
        "接一下。我看见你准备进{app}了。",
        "纪、言、音。先接我。",
        "你手已经点到{app}门口了。先看我。",
        "{app}先等等。接通。",
        "嗯？又准备往{app}里钻。先接我。",
        "我截住你十秒。先回答我。",
        "进去之前，跟我说清楚你要干什么。",
        "别装没看见。你刚刚点的是{app}。",
        "我不是不让你进。先把这一步过了。",
        "先接。然后你告诉我，你进去做什么。",
        "你可以刷，但别靠惯性刷。接通。",
        "这次先由你自己说目的，我再放你进去。"
    )

    private val purpose = listOf(
        "有明确目的就行。找完、买完、看完，自己出来。",
        "好。你是去办事，不是去被推荐流拖走。时间我给你。",
        "这理由成立。进去，把那件事办掉，别顺手住进去。",
        "行。我给你这一小段。完成目的就回来。",
        "可以。你自己说了是有事，那就别让下一条内容偷走后半段。"
    )

    private val rest = listOf(
        "想休息可以。休息得有边，我给你一小段。",
        "行，放松一会儿。时间到了我会回来敲门。",
        "可以刷，但这段是你主动选的休息，不是无意识下坠。",
        "休息批准。别把舒服刷成麻木。",
        "给你一点松动。到点就回来，我不跟算法抢第二遍。"
    )

    private val direct = listOf(
        "行，你就是想进去。不给你装理由，给你短一点。",
        "至少你没糊弄我。进去，但只有这一小段。",
        "可以。你承认自己就是想刷，我反而好管。三五分钟，别续杯。",
        "好。硬要进去我也不演道德老师。时间短一点，到点出来。",
        "行。你要这个，我给。但推荐流没有无限续命权。"
    )

    private val smallStep = listOf(
        "那先换三分钟回来。不是一套题，就三分钟有效学习。",
        "先做一个很小的动作。三分钟，够我确认你不是纯靠惯性跑。",
        "先去碰一下正事。三分钟以后再来，我给你正常放行。",
        "好。先把注意力从算法手里抢回来三分钟，然后你再决定还想不想刷。",
        "先做小步。不是惩罚，是把方向盘重新拿回来。"
    )

    private val stepDone = listOf(
        "做到了。现在这几分钟是你自己挣回来的，进去。",
        "三分钟够了。你确实先把方向盘拿回来了，我放你进去。",
        "行，小猫。先做再刷，这次我认。给你正常时间。",
        "完成。现在你进去，是你自己选的，不是手指自动滑过去。",
        "这就对了。进去休息一小段，到点我再找你。"
    )

    private val stepPending = listOf(
        "还没够。差{minutes}分钟左右。回去，把这一小步做完。",
        "别耍赖。有效学习还差{minutes}分钟，做完再来。",
        "我算的是有效时间，不是把学习页挂后台。还差{minutes}分钟。",
        "差一点。先把剩下这{minutes}分钟走完，我就放。",
        "你已经开始了，别现在拐弯。还差{minutes}分钟。"
    )

    private val warning = listOf(
        "还剩一分钟。想看的收尾，别再开新坑。",
        "一分钟。现在开始找出口，不准再点下一串。",
        "最后一分钟。把手从无限下滑里收回来。",
        "快到点了。你自己出来，比我来抓好看。",
        "剩一分钟。收尾。"
    )

    private val expired = listOf(
        "到点。先出来，我们重新说一次。",
        "这一轮结束了。别靠惯性续杯。",
        "时间到了。先把{app}关在门外十秒。",
        "够了。要继续就重新告诉我为什么。",
        "这一段已经花完。先出来。"
    )

    fun incoming(appName: String, seed: Long): String =
        pick(incoming, seed).replace("{app}", appName)

    fun response(
        choice: EntertainmentIntentChoice,
        seed: Long
    ): String = pick(
        when (choice) {
            EntertainmentIntentChoice.PURPOSE -> purpose
            EntertainmentIntentChoice.BREAK -> rest
            EntertainmentIntentChoice.DIRECT -> direct
            EntertainmentIntentChoice.SMALL_STEP -> smallStep
        },
        seed
    )

    fun smallStepDone(seed: Long): String = pick(stepDone, seed)

    fun smallStepPending(minutes: Int, seed: Long): String =
        pick(stepPending, seed).replace("{minutes}", minutes.coerceAtLeast(1).toString())

    fun warning(seed: Long): String = pick(warning, seed)

    fun expired(appName: String, seed: Long): String =
        pick(expired, seed).replace("{app}", appName)

    private fun pick(values: List<String>, seed: Long): String {
        if (values.isEmpty()) return ""
        val index = seed.hashCode().toLong().absoluteValue.rem(values.size.toLong()).toInt()
        return values[index]
    }
}
