package dev.jlz.presence.cowatch

import dev.jlz.presence.focus.LocalAppCategory

/**
 * Video-call screen sampling is always user initiated and bound to one chosen
 * foreground package. Sensitive/system surfaces cannot become share targets.
 * AccessibilityActionGateway.safeForCoWatch() separately rejects payment,
 * editable, keyboard, foreign-app, locked or hidden windows during capture.
 */
object CoWatchTargetPolicy {
    private val contentApps = setOf(
        "com.xingin.xhs", "tv.danmaku.bili",
        "com.ss.android.ugc.aweme", "com.zhihu.android",
        "com.xunmeng.pinduoduo", "com.taobao.taobao"
    )
    private val sensitiveApps = setOf(
        "com.tencent.mm", "com.eg.android.AlipayGphone",
        "com.android.settings", "com.android.systemui",
        "com.android.chrome", "com.openai.chatgpt",
        "com.android.vending", "com.google.android.gms"
    )
    fun allowed(pkg: String, category: LocalAppCategory): Boolean =
        pkg.isNotBlank() && pkg !in sensitiveApps &&
            !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.android.") &&
            !pkg.contains("bank", ignoreCase = true) &&
            (pkg in contentApps || category in setOf(LocalAppCategory.GAME, LocalAppCategory.FEED))
}
