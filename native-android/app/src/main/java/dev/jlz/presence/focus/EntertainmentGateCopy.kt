package dev.jlz.presence.focus

import kotlin.math.absoluteValue

/**
 * Local sentence matrix for the call-style gate.
 *
 * Copy is composed from short clauses instead of shipping one giant list. The
 * matrix intentionally produces well over 500 distinct lines while keeping each
 * rendered line short enough for the call screen.
 */
object EntertainmentGateCopy {
    private val incomingOpeners = listOf(
        "接一下。我看见你准备进{app}了。",
        "纪、言、音。先接我。",
        "你手已经点到{app}门口了。",
        "{app}先等等。先看我。",
        "嗯？又准备往{app}里钻。",
        "我截住你十秒。",
        "进去之前，先跟我说一句。",
        "别装没看见。你刚刚点的是{app}。",
        "我不是不让你进。",
        "先接。然后你告诉我。",
        "你可以刷，但别靠惯性刷。",
        "这次先由你自己说目的。"
    )

    private val incomingMiddles = listOf(
        "你进去要干什么？",
        "这一轮准备待多久？",
        "是有事，还是只是手痒？",
        "先把目的说清楚。",
        "别让手指替你做决定。",
        "给我一个你自己认的理由。"
    )

    private val incomingTails = listOf(
        "我听完再放。",
        "回答完就不磨你。",
        "十秒就够。",
        "别急着往下滑。",
        "看我。"
    )

    private val purposeBodies = listOf(
        "有明确目的就行。找完、买完、看完，自己出来。",
        "你是去办事，不是去被推荐流拖走。",
        "这理由成立。把那件事办掉，别顺手住进去。",
        "可以。你自己说了是有事，那就照着这个目的走。",
        "行。我给你这一小段，办完就回来。",
        "目标明确，我不拦。只准把正事做完，不准顺便无限下滑。"
    )

    private val restBodies = listOf(
        "想休息可以。休息得有边，我给你一小段。",
        "行，放松一会儿。时间到了我会回来敲门。",
        "可以刷，但这段是你主动选的休息。",
        "休息批准。别把舒服刷成麻木。",
        "给你一点松动，到点就回来。",
        "你可以歇，但推荐流没有资格决定什么时候结束。"
    )

    private val directBodies = listOf(
        "行，你就是想进去。不给你装理由，时间短一点。",
        "至少你没糊弄我。进去，但只有这一小段。",
        "你承认自己就是想刷，我反而好管。",
        "好。硬要进去我也不演道德老师。",
        "行。你要这个，我给，但不许无限续杯。",
        "不编理由这点我喜欢。那就拿最短的一轮。"
    )

    private val smallStepBodies = listOf(
        "那先换三分钟回来。不是一套题，就三分钟有效学习。",
        "先做一个很小的动作。三分钟就够。",
        "先去碰一下正事。三分钟以后再来。",
        "先把注意力从算法手里抢回来三分钟。",
        "先做小步，不是惩罚，是把方向盘拿回来。",
        "给我三分钟有效学习，然后你再决定还想不想刷。"
    )

    private val responseTails = listOf(
        "我给你计时。",
        "到点我会找你。",
        "你自己出来，我会很满意。",
        "别偷偷给自己续杯。",
        "这一轮算你主动选的。",
        "时间花完就收。",
        "记得你随时都能自己停。",
        "别把下一条当成命令。"
    )

    private val stepDoneBodies = listOf(
        "做到了。现在这几分钟是你自己挣回来的。",
        "三分钟够了。你确实先把方向盘拿回来了。",
        "行，小猫。先做再刷，这次我认。",
        "完成。现在你进去，是你自己选的。",
        "这就对了。进去休息一小段。",
        "我看见那三分钟了。现在放你进去。"
    )

    private val stepPendingBodies = listOf(
        "还没够。有效学习还差{minutes}分钟左右。",
        "别耍赖。还差{minutes}分钟。",
        "我算的是有效时间，不是把学习页挂后台。还差{minutes}分钟。",
        "差一点。剩下大约{minutes}分钟。",
        "你已经开始了，别现在拐弯。还差{minutes}分钟。",
        "门还没开。把这{minutes}分钟走完。"
    )

    private val stepTails = listOf(
        "做完再来。",
        "我等你回来敲门。",
        "这一点别跟我讨价还价。",
        "走完就放。",
        "别急，我没跑。",
        "把这一小步收干净。"
    )

    private val warningBodies = listOf(
        "还剩一分钟。想看的收尾。",
        "一分钟。现在开始找出口。",
        "最后一分钟。把手从无限下滑里收回来。",
        "快到点了。",
        "剩一分钟。",
        "这一轮马上用完。"
    )

    private val warningTails = listOf(
        "别再开新坑。",
        "不准再点下一串。",
        "你自己出来，比我来抓好看。",
        "把最后这点时间花明白。",
        "看到这里就够了。",
        "准备回来。"
    )

    private val expiredBodies = listOf(
        "到点。先出来。",
        "这一轮结束了。",
        "时间到了。",
        "够了。要继续就重新告诉我为什么。",
        "这一段已经花完。",
        "{app}这轮到站。"
    )

    private val expiredTails = listOf(
        "我们重新说一次。",
        "别靠惯性续杯。",
        "先把{app}关在门外十秒。",
        "先把手停下来。",
        "重新选一次你还要不要进去。",
        "这次先看我。"
    )

    fun incoming(appName: String, seed: Long): String =
        pick(
            combine3(incomingOpeners, incomingMiddles, incomingTails, appName),
            seed
        )

    fun response(
        choice: EntertainmentIntentChoice,
        seed: Long
    ): String {
        val bodies = when (choice) {
            EntertainmentIntentChoice.PURPOSE -> purposeBodies
            EntertainmentIntentChoice.BREAK -> restBodies
            EntertainmentIntentChoice.DIRECT -> directBodies
            EntertainmentIntentChoice.SMALL_STEP -> smallStepBodies
        }
        return pick(combine2(bodies, responseTails, ""), seed)
    }

    fun smallStepDone(seed: Long): String =
        pick(combine2(stepDoneBodies, responseTails, ""), seed)

    fun smallStepPending(minutes: Int, seed: Long): String =
        pick(
            combine2(stepPendingBodies, stepTails, "")
                .map { it.replace("{minutes}", minutes.coerceAtLeast(1).toString()) },
            seed
        )

    fun warning(seed: Long): String =
        pick(combine2(warningBodies, warningTails, ""), seed)

    fun expired(appName: String, seed: Long): String =
        pick(combine2(expiredBodies, expiredTails, appName), seed)

    fun candidateCountForTest(appName: String = "小红书"): Int {
        val all = linkedSetOf<String>()
        all += combine3(incomingOpeners, incomingMiddles, incomingTails, appName)
        listOf(purposeBodies, restBodies, directBodies, smallStepBodies).forEach {
            all += combine2(it, responseTails, appName)
        }
        all += combine2(stepDoneBodies, responseTails, appName)
        all += combine2(stepPendingBodies, stepTails, appName)
        all += combine2(warningBodies, warningTails, appName)
        all += combine2(expiredBodies, expiredTails, appName)
        return all.size
    }

    private fun combine2(
        first: List<String>,
        second: List<String>,
        appName: String
    ): List<String> = buildList(first.size * second.size) {
        first.forEach { a ->
            second.forEach { b ->
                add(
                    (a + " " + b)
                        .replace("{app}", appName)
                        .trim()
                )
            }
        }
    }.distinct()

    private fun combine3(
        first: List<String>,
        second: List<String>,
        third: List<String>,
        appName: String
    ): List<String> = buildList(first.size * second.size * third.size) {
        first.forEach { a ->
            second.forEach { b ->
                third.forEach { c ->
                    add(
                        (a + " " + b + " " + c)
                            .replace("{app}", appName)
                            .trim()
                    )
                }
            }
        }
    }.distinct()

    private fun pick(values: List<String>, seed: Long): String {
        if (values.isEmpty()) return ""
        val index = seed.hashCode().toLong().absoluteValue.rem(values.size.toLong()).toInt()
        return values[index]
    }
}
