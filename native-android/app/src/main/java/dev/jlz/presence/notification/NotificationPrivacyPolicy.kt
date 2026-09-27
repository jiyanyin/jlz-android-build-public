package dev.jlz.presence.notification

import android.app.Notification

enum class NotificationDisposition {
    FORWARD,
    MASK_SENSITIVE,
    DROP_LOW_VALUE
}

data class NotificationPolicyResult(
    val disposition: NotificationDisposition,
    val title: String,
    val body: String,
    val reason: String
)

object NotificationPrivacyPolicy {
    private val sensitivePackageFragments = listOf(
        "alipay",
        "bank",
        "wallet",
        "pay"
    )

    private val sensitiveTextRegexes = listOf(
        Regex("""(?<!\d)\d{4,8}(?!\d)"""),
        Regex("""验证码|校验码|动态口令|一次性密码|OTP|支付密码""", RegexOption.IGNORE_CASE),
        Regex("""付款|支付|收款|转账|银行卡|余额""")
    )

    private val lowValueCategories = setOf(
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_PROGRESS
    )

    fun evaluate(
        packageName: String,
        title: String,
        body: String,
        category: String?,
        flags: Int,
        allowOngoingHealth: Boolean = false
    ): NotificationPolicyResult {
        val ongoing =
            flags and Notification.FLAG_ONGOING_EVENT != 0
        val observedHuaweiHealth = allowOngoingHealth &&
            HuaweiHealthNotificationReading.isHuaweiHealth(packageName)
        if ((ongoing || category in lowValueCategories) && !observedHuaweiHealth) {
            return NotificationPolicyResult(
                disposition = NotificationDisposition.DROP_LOW_VALUE,
                title = "",
                body = "",
                reason = if (ongoing) "ongoing_event" else "service_or_progress"
            )
        }

        val combined = title + "\n" + body
        val packageSensitive =
            sensitivePackageFragments.any {
                packageName.contains(it, ignoreCase = true)
            }
        val textSensitive = if (observedHuaweiHealth) {
            // A 4–8-digit step count is not an OTP, but still redact when
            // credential or payment words actually appear.
            sensitiveTextRegexes.drop(1).any { it.containsMatchIn(combined) }
        } else {
            sensitiveTextRegexes.any { it.containsMatchIn(combined) }
        }

        if (packageSensitive || textSensitive) {
            return NotificationPolicyResult(
                disposition = NotificationDisposition.MASK_SENSITIVE,
                title = "敏感通知",
                body = "内容已在手机本地遮蔽",
                reason =
                    if (packageSensitive) "sensitive_package"
                    else "sensitive_text"
            )
        }

        return NotificationPolicyResult(
            disposition = NotificationDisposition.FORWARD,
            title = title.take(160),
            body = body.take(500),
            reason = if (observedHuaweiHealth) "user_allowed_health_notification" else "allowed"
        )
    }
}
