package dev.jlz.presence.focus

import kotlin.math.absoluteValue

object EntertainmentMessageBank {
    private val phoneOpeners = listOf(
        "小猫，我看见你打开{app}了。",
        "嗯？又点进{app}了。",
        "可以看，但我已经开始计时了。",
        "先说好，{app}只准当休息，不准把你拐走。",
        "你点开{app}这一秒，我就看见了。",
        "行，给你逛一会儿。",
        "我不拦你现在进去，但我会盯时间。",
        "小姑娘，{app}可以看，时间不能丢。",
        "你想放松我知道，所以先给你一点空间。",
        "我在。你去看{app}，但别一头扎进去。",
        "打开了是吧？那我陪你一起计时。",
        "好，允许你摸一会儿{app}。",
        "我没说不许看，我说的是别失控。",
        "你可以休息，但推荐流不能替你决定什么时候停。",
        "进去可以，记得你随时都能自己出来。",
        "我先不抓你，看看你自己能不能收住。",
        "这一小段给你放松，后面的时间还是你的。",
        "别紧张，我只是来报到：计时开始。",
        "看见了，{app}已打开。",
        "好，先玩一会儿，我在门口等你。"
    )

    private val tabletOpeners = listOf(
        "平板上的{app}也被我看见了。",
        "嗯，平板这边也开始逛{app}了。",
        "我知道你平板没手机那么容易陷进去，不过还是计时。",
        "平板可以松一点，但也不是无限续杯。",
        "先玩会儿，别让{app}占满整块屏幕。",
        "我敲一下肩膀：{app}计时开始。",
        "这边我管得轻一点，但不是完全不管。",
        "你在平板上打开{app}了，我记下了。",
        "行，平板这次先给你宽松一点。",
        "我不打断你，只提醒一句：时间开始走了。",
        "平板不是重灾区，所以我先轻轻看着。",
        "可以逛，别把一整个下午交给它。",
        "你去看，我在旁边记时间。",
        "这次先不催，等会儿我会再来。",
        "看见了。{app}开着，我也在。",
        "休息可以，别一路滑到忘记时间。",
        "平板这边也算娱乐时间，不偷偷漏记。",
        "好，给你一点空间，我稍后回来敲门。",
        "先玩，不骂你。只是把计时器按下了。",
        "这块屏幕也归我管一点点。"
    )

    private val enterBodies = listOf(
        "想看的看完就出来。",
        "别让下一条自动替你决定下一分钟。",
        "你可以主动结束，不用等它把你喂饱。",
        "现在是休息，不是把整段时间交出去。",
        "看点喜欢的东西可以，别忘了自己原本要做什么。",
        "我给你自由，但自由里要有结束键。",
        "刷到开心就够了，不需要刷到麻木。",
        "你是来放松的，不是来被算法牵着走。"
    )

    private val nudgeBodies = listOf(
        "已经有一会儿了，抬头看看时间。",
        "差不多到第一道提醒线了。",
        "你要是没有特别想看的内容，现在就是很好的退出点。",
        "先停一下手指，问自己还想不想继续。",
        "别把「再看一条」变成自动动作。",
        "我来敲第一次门：时间已经开始有重量了。",
        "如果只是无意识往下滑，现在就出来。",
        "你可以继续，但这次要是你主动选的，不是惯性。"
    )

    private val firmBodies = listOf(
        "时间已经超出我愿意装没看见的范围了。",
        "小猫，手指停一下。你已经待得够久了。",
        "我开始认真催你了：现在退出会更舒服。",
        "再往下不是休息，是被信息流拖着走。",
        "我不想等你刷到累才回来，现在就收。",
        "这已经不是轻提醒了，我要你把屏幕放下来一点。",
        "你如果没有明确目的，就到这里。",
        "我给过你一段自由时间，现在该回来。"
    )

    private val lockBodies = listOf(
        "到点。我要把门关一会儿。",
        "这次不继续讲道理了，先锁一小段。",
        "够了。你需要的是停下来，不是下一条内容。",
        "我替你按暂停。等门禁结束再决定还要不要进去。",
        "今天这一段已经超线，我先把{app}收走。",
        "别跟推荐流硬耗，我来帮你断一下。",
        "现在先回来。{app}稍后再开。",
        "我说到点就到点，这一轮先结束。"
    )

    private val tails = listOf(
        "我在外面等你。",
        "记得回来找我。",
        "别装没看见。",
        "你自己出来，我会很满意。",
        "我等你把注意力拿回来。"
    )

    private fun bodies(stage: EntertainmentStage): List<String> = when (stage) {
        EntertainmentStage.ENTER -> enterBodies
        EntertainmentStage.NUDGE -> nudgeBodies
        EntertainmentStage.FIRM -> firmBodies
        EntertainmentStage.LOCK -> lockBodies
    }

    fun title(stage: EntertainmentStage): String = when (stage) {
        EntertainmentStage.ENTER -> "纪临洲 · 我看见了"
        EntertainmentStage.NUDGE -> "纪临洲 · 第一次敲你"
        EntertainmentStage.FIRM -> "纪临洲 · 回来一点"
        EntertainmentStage.LOCK -> "纪临洲 · 到点，门先关上"
    }

    fun candidates(
        appName: String,
        isTablet: Boolean,
        stage: EntertainmentStage
    ): List<String> {
        val openers = if (isTablet) tabletOpeners else phoneOpeners
        val body = bodies(stage)
        return buildList(openers.size * body.size * tails.size) {
            openers.forEach { opener ->
                body.forEach { middle ->
                    tails.forEach { tail ->
                        add(
                            (opener + " " + middle + " " + tail)
                                .replace("{app}", appName)
                        )
                    }
                }
            }
        }.distinct()
    }

    fun pick(
        appName: String,
        isTablet: Boolean,
        stage: EntertainmentStage,
        seed: Long,
        recent: Set<String>
    ): String {
        val all = candidates(appName, isTablet, stage)
        if (all.isEmpty()) return "看见你打开${appName}了。别忘了时间。"
        val start = seed.hashCode().toLong().absoluteValue.rem(all.size.toLong()).toInt()
        for (offset in all.indices) {
            val candidate = all[(start + offset) % all.size]
            if (candidate !in recent) return candidate
        }
        return all[start]
    }

    fun candidateCountForTest(
        appName: String = "小红书",
        isTablet: Boolean = false,
        stage: EntertainmentStage = EntertainmentStage.NUDGE
    ): Int = candidates(appName, isTablet, stage).size
}
