package dev.jlz.presence.notification

/**
 * Read only observations actually published in Huawei Health's Android
 * notification text. No private app DB, screen polling, inferred live sensor
 * values, or assumption that Band 8 is emitting new data.
 */
object HuaweiHealthNotificationReading {
    const val PACKAGE_NAME = "com.huawei.health"
    private val stepAfterLabel = Regex("""(?:今日)?步数\s*[:：]?\s*([0-9][0-9,， ]{0,10})\s*步?""")
    private val stepBeforeUnit = Regex("""([0-9][0-9,， ]{0,10})\s*步(?!骤)""")
    private val credentialText = Regex("""验证码|校验码|动态口令|一次性密码|OTP|支付密码""", RegexOption.IGNORE_CASE)

    fun isHuaweiHealth(packageName: String): Boolean = packageName == PACKAGE_NAME

    private val caloriePattern = Regex("""([0-9][0-9,， ]{0,9})\s*(?:千卡|大卡|kcal)""", RegexOption.IGNORE_CASE)

    fun caloriesIfShown(packageName: String, title: String, body: String): Int? {
        if (!isHuaweiHealth(packageName)) return null
        val text = (title + "\n" + body).take(1200)
        if (credentialText.containsMatchIn(text)) return null
        val raw = caloriePattern.find(text)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace(",", "").replace("，", "").replace(" ", "")
            .toIntOrNull()?.takeIf { it in 0..50_000 }
    }

    fun stepsIfShown(packageName: String, title: String, body: String): Int? {
        if (!isHuaweiHealth(packageName)) return null
        val text = (title + "\n" + body).take(1200)
        if (credentialText.containsMatchIn(text)) return null
        val raw = stepAfterLabel.find(text)?.groupValues?.get(1)
            ?: stepBeforeUnit.find(text)?.groupValues?.get(1)
            ?: return null
        return raw.replace(",", "").replace("，", "").replace(" ", "")
            .toIntOrNull()?.takeIf { it in 0..200_000 }
    }
}
