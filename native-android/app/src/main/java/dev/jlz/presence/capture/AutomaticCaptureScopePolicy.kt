package dev.jlz.presence.capture

/**
 * Explicit all-foreground consent widens app eligibility, never page safety.
 * No inventory of previously opened apps is needed: only the current real foreground is sampled.
 */
object AutomaticCaptureScopePolicy {
    private val packageName = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    private val systemPackages = setOf(
        "com.android.systemui", "com.android.settings", "com.miui.home",
        "com.miui.securitycenter", "com.android.intentresolver", "com.android.keychain",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.gms", "dev.jlz.presence"
    )
    private val sensitiveMarkers = listOf(
        "bank", "alipay", "unionpay", "tenpay", "paypal", "wallet",
        "authenticator", "keepass", "1password", "bitwarden", "inputmethod"
    )
    private val bankPrefixes = listOf(
        "com.chinamworld", "com.icbc", "com.ccb", "com.abchina", "com.boc.",
        "cmb.pb", "com.cmb.", "com.bankcomm", "com.pingan.pabank",
        "com.pingan.paces", "cn.com.cmbc", "cn.com.spdb", "com.spdb",
        "com.cib.", "com.hxb.", "com.ecitic", "com.citic"
    )
    fun blockedPackage(pkg: String): Boolean {
        val key = pkg.lowercase(java.util.Locale.ROOT)
        return !packageName.matches(pkg) || key in systemPackages ||
            sensitiveMarkers.any { key.contains(it) } || bankPrefixes.any { key.startsWith(it) }
    }

    fun allowed(pkg: String, selected: Set<String>, allForegroundApps: Boolean): Boolean =
        !blockedPackage(pkg) && (allForegroundApps || pkg in selected) &&
            (allForegroundApps || pkg != "com.openai.chatgpt")

    fun sensitiveNode(
        password: Boolean, editable: Boolean, focused: Boolean,
        text: String, description: String, resourceId: String, inputType: Int
    ): Boolean {
        val inputClass = inputType and 0x0f
        val variation = inputType and 0x0ff0
        val passwordType = (inputClass == 1 && variation in setOf(0x80, 0x90, 0xe0)) ||
            (inputClass == 2 && variation == 0x10)
        if (password || passwordType || (editable && (focused || text.isNotBlank()))) return true
        val content = text + " " + description + " " + resourceId
        return listOf(
            "密码", "验证码", "动态口令", "支付", "付款", "收款", "银行卡", "信用卡",
            "安全码", "助记词", "私钥", "password", "passcode", "payment", "checkout",
            "credit card", "verification code", "one-time", "otp", "cvv", "pin_entry"
        ).any { content.contains(it, ignoreCase = true) }
    }
}
