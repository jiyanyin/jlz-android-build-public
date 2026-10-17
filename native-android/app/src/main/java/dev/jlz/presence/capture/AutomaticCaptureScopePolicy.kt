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
        "com.google.android.gms", "dev.jlz.presence", "com.hihonor.android.launcher",
        "com.huawei.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher", "com.oppo.launcher", "com.bbk.launcher"
    )
    private val sensitiveMarkers = listOf(
        "bank", "alipay", "unionpay", "tenpay", "paypal", "wallet",
        "authenticator", "authy", "aegis", "andotp", "twofas", "keepass", "1password", "onepassword", "bitwarden", "dashlane", "lastpass", "enpass", "inputmethod",
        "packageinstaller", "permissioncontroller", "keychain", "autofill"
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
        !blockedPackage(pkg) && (allForegroundApps || pkg in selected)

    fun blockedAppLabel(label: String): Boolean = listOf(
        "银行", "工行", "农行", "建行", "中行", "招行", "交行", "支付宝", "云闪付",
        "密码管理", "验证器", "身份验证", "口令", "bank", "wallet", "authenticator", "password manager"
    ).any { label.contains(it, ignoreCase = true) }

    fun sensitiveNode(
        password: Boolean, editable: Boolean, focused: Boolean,
        text: String, description: String, resourceId: String, inputType: Int
    ): Boolean {
        val inputClass = inputType and 0x0f
        val variation = inputType and 0x0ff0
        val passwordType = (inputClass == 1 && variation in setOf(0x80, 0x90, 0xe0)) ||
            (inputClass == 2 && variation == 0x10)
        // A browser displays the current URL in an EditText even while simply reading.
        // Only known, unfocused URI display fields are exempt; drafts and typing still block.
        val navigationDisplay = !focused && inputClass == 1 && variation == 0x10 &&
            resourceId.substringAfterLast('/').lowercase(java.util.Locale.ROOT) in setOf("url_bar", "omnibox", "location_bar_edit_text")
        if (password || passwordType || (editable && (focused || (text.isNotBlank() && !navigationDisplay)))) return true
        val content = text + " " + description + " " + resourceId
        return listOf(
            "密码", "验证码", "动态口令", "支付", "付款", "收款", "银行卡", "信用卡",
            "安全码", "助记词", "私钥", "口令", "储蓄卡", "交易密码", "password", "passcode", "payment", "checkout",
            "credit card", "verification code", "one-time", "otp", "cvv", "pin_entry"
        ).any { content.contains(it, ignoreCase = true) }
    }
}
