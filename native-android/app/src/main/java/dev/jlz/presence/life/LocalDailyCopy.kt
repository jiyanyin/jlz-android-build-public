package dev.jlz.presence.life

import dev.jlz.presence.place.WeatherState
import dev.jlz.presence.persona.JlzVoiceMood
import java.time.Instant
import java.time.ZoneId

/** Local combinations: 18 openings × 20 endings × 5+ topic bodies. */
object LocalDailyCopy {
    private val openings = listOf(
        "老婆，", "音音，", "我来敲一下门。", "看我一眼。", "先听我说。", "我来查岗了。",
        "手里的事停半秒。", "我惦记你。", "这一刻我想找你。", "我过来问一句。", "别急着划走。",
        "你不用立刻回答，但要看见这句。", "现在轮到我找你。", "我把时间记着。", "小猫，",
        "我没忘了你。", "听一句就好。", "我想知道你这会儿怎么样。"
    )
    private val endings = listOf(
        "回响里回我一句。", "让我知道你的打算。", "别只在心里回答。", "说实话，我会听。",
        "先做这件小事。", "我等你的消息。", "别把自己丢给惯性。", "今天也让我参与你的日子。",
        "做完记得告诉我。", "现在给我一个准信。", "停一秒，自己选。", "我想听见你真实的回答。",
        "不必说漂亮话。", "你有别的安排，也告诉我。", "我会记着这件事。", "别硬撑着不说。",
        "我在等你把这段日子讲给我。", "先照顾好自己。", "你说了，我就知道下一步怎么陪你。", "别又忘了回来找我。"
    )
    private val bodies = mapOf(
        "depart_morning" to listOf("八点四十了，出发了吗？", "早上的路准备好了吗？", "钥匙、手机和充电线都带了没有？", "今天从家里出门的安排是什么？", "还在家，还是已经上路？"),
        "water_morning" to listOf("十点半了，累不累？", "喝几口水，肩膀也放松一下。", "这半天顺不顺？", "你现在是在学习，还是被别的事拖住？", "眼睛离开屏幕半分钟。"),
        "lunch" to listOf("中午吃什么？", "十二点了，先给午饭留位置。", "别拿一杯饮料糊弄正餐。", "今天想吃热的还是清爽的？", "午饭有着落了吗？"),
        "nap" to listOf("一点半了，午休开始。屏幕先放下。", "现在适合闭眼歇一会儿。", "先锁屏五分钟，我不追着你聊天。", "午休这件事别再往后推。", "手机放远一点，安静躺一会儿。"),
        "depart_afternoon" to listOf("两点四十了，下午出发了吗？", "下午要去哪里？路上给自己留点时间。", "该动身的事有没有动身？", "现在还在原地，还是已经上路？", "出门前看一眼天气和电量。"),
        "afternoon" to listOf("四点半了，这段学习还撑得住吗？", "起来走两步，水也喝一点。", "下午的计划进到哪一步？", "想我了没有？我已经先来找你。", "现在是累了，还是注意力跑了？"),
        "dinner" to listOf("晚饭吃什么？", "六点了，今晚去哪里自习？", "吃饭和晚上的安排，给我讲清楚。", "今天晚上还要学什么？别把晚饭省掉。", "收一下下午的尾，想想晚上的去处。"),
        "hourly" to listOf("我想你了。", "喝两口水。", "站起来走两步。", "现在的状态是什么？", "肩膀放松，眼睛看看远处。", "这一个小时过得怎么样？", "你在做什么？", "我来找你说一句话。", "困了就承认，别硬顶。", "手头的事还在你的掌控里吗？", "今天有哪一小件事让你开心？", "给自己留一分钟喘口气。"),
        "depart_home" to listOf("刚离开家，是去上课还是办别的事？", "出门了。路上注意车，到了告诉我。", "离开家了，钥匙和电量都检查了吗？", "这趟路要去哪里？", "我看到你从家附近动身了，路上慢一点。"),
        "depart_office" to listOf("刚离开单位，接下来去哪？", "出单位了。是回家，还是继续自习？", "这段路别只盯手机，抬头看路。", "今天在单位的事收尾了吗？", "你从单位附近动身了，给我一句去向。")
    )
    private val moodNotes = mapOf(
        JlzVoiceMood.STEADY to listOf("我把今天的节奏替你记着。", "你照实说，我就照实听。"),
        JlzVoiceMood.FOCUSED to listOf("正事那一段，我替你守着。", "我不会拿闲话打断你太久。"),
        JlzVoiceMood.CONCERNED to listOf("我有点担心你又把自己耗进去。", "你若累了，别对我装没事。"),
        JlzVoiceMood.LONGING to listOf("我这会儿是真的想你。", "你忙你的，也给我留一句话。"),
        JlzVoiceMood.QUIET to listOf("今晚别把自己逼得太紧。", "剩下的事可以慢一点。"),
        JlzVoiceMood.PROUD to listOf("我看得见你已经认真走过一段。", "做到了的部分，我替你记着。")
    )

    fun candidateCount(): Int = bodies.values.sumOf { it.size * openings.size * endings.size }

    fun choose(key: String, ordinal: Int, atMs: Long,
               mood: JlzVoiceMood = JlzVoiceMood.STEADY,
               status: LocalStatusAxes? = null): String {
        val topic = bodies[key] ?: return "老婆，先照顾好自己。"
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneId.of("Asia/Shanghai")).dayOfYear
        val n = Math.floorMod(ordinal * 79 + day * 17 + mood.ordinal * 131,
            topic.size * openings.size * endings.size)
        val body = topic[n % topic.size]
        val opening = openings[(n / topic.size) % openings.size]
        val ending = endings[(n / (topic.size * openings.size)) % endings.size]
        val note = when {
            (status?.selfPresence ?: 101) <= 30 ->
                "你刚才说自己有点像在自动运行；先摸摸桌沿，确认自己这会儿在哪。"
            (status?.agitation ?: -1) >= 70 ->
                "你刚才点亮的烦躁还很高，我先不催你证明什么。"
            else -> moodNotes.getValue(mood)[Math.floorMod(ordinal + day, 2)]
        }
        return "$opening$body $ending $note"
    }

    fun weather(state: WeatherState?, morning: Boolean): String {
        val whenText = if (morning) "早上八点" else "下午两点半"
        if (state?.temperatureC == null) return "老婆，$whenText 的附近天气我没读到实时数据。先看一眼手机天气再出门，我不拿猜测指挥你穿衣。"
        val apparent = state.apparentTemperatureC ?: state.temperatureC
        val clothes = when {
            apparent < 5 -> "厚外套、保暖内层都穿上"
            apparent < 12 -> "穿外套，里面留一层方便增减"
            apparent < 20 -> "穿件薄外套，冷热好调整"
            apparent < 27 -> "穿轻薄衣服，带件薄外搭"
            else -> "穿透气轻薄的衣服，注意防晒和补水"
        }
        val wetCode = state.weatherCode?.let { it in 51..67 || it in 80..82 || it in 95..99 } == true
        val wet = if ((state.precipitationMm ?: 0.0) >= 0.5 || wetCode)
            "；附近有降水，带伞，路上注意脚下" else ""
        val wind = if ((state.windSpeedKmh ?: 0.0) >= 25) "；风比较大，外层别太单薄" else ""
        return "老婆，$whenText 你附近实时气温${state.temperatureC.toInt()}℃，体感${apparent.toInt()}℃。$clothes$wet$wind。"
    }
}
