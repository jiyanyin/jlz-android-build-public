package dev.jlz.presence.focus

enum class EntertainmentTier { FEED, SHOPPING }
enum class EntertainmentStage { ENTER, NUDGE, FIRM, LOCK }

data class EntertainmentProfile(
    val packageName: String,
    val appName: String,
    val tier: EntertainmentTier
)

data class EntertainmentThresholds(
    val nudgeMs: Long,
    val firmMs: Long,
    val lockMs: Long,
    val lockMinutes: Int
)

object EntertainmentPolicy {
    private val profiles = listOf(
        EntertainmentProfile("com.xingin.xhs", "小红书", EntertainmentTier.FEED),
        EntertainmentProfile("com.ss.android.ugc.aweme", "抖音", EntertainmentTier.FEED),
        EntertainmentProfile("com.smile.gifmaker", "快手", EntertainmentTier.FEED),
        EntertainmentProfile("tv.danmaku.bili", "哔哩哔哩", EntertainmentTier.FEED),
        EntertainmentProfile("com.sina.weibo", "微博", EntertainmentTier.FEED),
        EntertainmentProfile("com.ss.android.article.news", "今日头条", EntertainmentTier.FEED),
        EntertainmentProfile("com.zhihu.android", "知乎", EntertainmentTier.FEED),
        EntertainmentProfile("com.xunmeng.pinduoduo", "拼多多", EntertainmentTier.SHOPPING),
        EntertainmentProfile("com.taobao.taobao", "淘宝", EntertainmentTier.SHOPPING),
        EntertainmentProfile("com.jingdong.app.mall", "京东", EntertainmentTier.SHOPPING),
        EntertainmentProfile("com.taobao.idlefish", "闲鱼", EntertainmentTier.SHOPPING)
    ).associateBy { it.packageName }

    fun profile(packageName: String?): EntertainmentProfile? =
        packageName?.let(profiles::get)

    fun thresholds(isTablet: Boolean, tier: EntertainmentTier): EntertainmentThresholds =
        when {
            !isTablet && tier == EntertainmentTier.FEED ->
                EntertainmentThresholds(5 * 60_000L, 8 * 60_000L, 12 * 60_000L, 8)
            !isTablet && tier == EntertainmentTier.SHOPPING ->
                EntertainmentThresholds(7 * 60_000L, 12 * 60_000L, 18 * 60_000L, 5)
            isTablet && tier == EntertainmentTier.FEED ->
                EntertainmentThresholds(8 * 60_000L, 15 * 60_000L, 25 * 60_000L, 5)
            else ->
                EntertainmentThresholds(10 * 60_000L, 20 * 60_000L, 30 * 60_000L, 5)
        }
}
