package dev.jlz.presence.focus

enum class EntertainmentTier { FEED, SHOPPING }

data class EntertainmentProfile(
    val packageName: String,
    val appName: String,
    val tier: EntertainmentTier
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

    fun profiles(): List<EntertainmentProfile> =
        profiles.values.sortedWith(
            compareBy<EntertainmentProfile> { it.tier.name }
                .thenBy { it.appName }
        )
}
