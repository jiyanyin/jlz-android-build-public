package dev.jlz.presence.capture

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticCaptureScopePolicyTest {
    private lateinit var context: Context
    private lateinit var traffic: CaptureTrafficPolicy

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_capture_traffic_v1", Context.MODE_PRIVATE).edit().clear().commit()
        traffic = CaptureTrafficPolicy(context)
    }

    @Test fun upgradeDoesNotWidenExistingSelectionOrEnableCapture() {
        assertFalse(traffic.allForegroundApps())
        assertFalse(traffic.enabledFor("com.example.reader"))
        assertTrue(traffic.configureAutomatic(true, setOf("com.example.reader")))
        assertTrue(traffic.enabledFor("com.example.reader"))
        assertFalse(traffic.enabledFor("com.example.newapp"))
    }

    @Test fun allForegroundIncludesNewAppsChatReadingAndBrowsersButNeverBanks() {
        assertTrue(traffic.setAllForegroundApps(true))
        assertFalse(traffic.enabledFor("com.example.newapp")) // scope alone does not enable
        assertTrue(traffic.setAutomaticEnabled(true))
        for (pkg in listOf("com.example.newapp", "com.openai.chatgpt", "com.tencent.mm",
                "com.android.chrome", "com.google.android.apps.docs")) {
            assertTrue(pkg, traffic.enabledFor(pkg))
        }
        for (pkg in listOf("com.example.bank", "com.eg.android.AlipayGphone", "cmb.pb",
                "com.chinamworld.main", "com.android.systemui", "com.miui.home",
                "dev.jlz.presence", "com.bitwarden.app", "", "invalid package")) {
            assertFalse(pkg, traffic.enabledFor(pkg))
        }
    }

    @Test fun scopeSurvivesRestartAndCanReturnToOriginalSelection() {
        traffic.configureAutomatic(true, setOf("com.example.reader"))
        traffic.setAllForegroundApps(true)
        traffic = CaptureTrafficPolicy(context)
        assertTrue(traffic.enabledFor("com.example.newapp"))
        traffic.setAllForegroundApps(false)
        assertTrue(traffic.enabledFor("com.example.reader"))
        assertFalse(traffic.enabledFor("com.example.newapp"))
        traffic.setAutomaticEnabled(false)
        assertFalse(traffic.enabledFor("com.example.reader"))
    }

    private fun node(
        text: String = "", description: String = "", resource: String = "",
        editable: Boolean = false, focused: Boolean = false,
        password: Boolean = false, inputType: Int = 1
    ) = AutomaticCaptureScopePolicy.sensitiveNode(
        password, editable, focused, text, description, resource, inputType
    )

    @Test fun idleComposerDoesNotBlockReadingButDraftAndActiveInputDo() {
        assertFalse(node(editable = true))
        assertFalse(node(text = "普通聊天正文"))
        assertTrue(node(editable = true, focused = true))
        assertTrue(node(editable = true, text = "draft"))
    }

    @Test fun sensitiveIndicatorsBlockEvenWithoutAndroidPasswordFlag() {
        assertTrue(node(password = true))
        assertTrue(node(inputType = 0x81))
        assertTrue(node(inputType = 0x12))
        assertTrue(node(description = "付款码"))
        assertTrue(node(resource = "login_password"))
        assertTrue(node(text = "请输入验证码"))
        assertTrue(node(text = "信用卡安全码"))
    }

    @Test fun readingBrowserUrlIsAllowedButTypingOrDraftStillBlocks() {
        assertFalse(node(text = "https://example.com/article", resource = "com.android.chrome:id/url_bar", editable = true, inputType = 0x11))
        assertTrue(node(text = "https://example.com/article", resource = "com.android.chrome:id/url_bar", editable = true, focused = true, inputType = 0x11))
        assertTrue(node(text = "unsent words", resource = "chat_input", editable = true, inputType = 0x11))
        assertTrue(node(text = "https://example.com/payment", resource = "com.android.chrome:id/url_bar", editable = true, inputType = 0x11))
    }

    @Test fun financialLabelsAndSystemSurfacesStayExcludedWithoutFlagSecure() {
        for (label in listOf("中国银行", "工行", "密码管理器", "身份验证器", "My Bank"))
            assertTrue(label, AutomaticCaptureScopePolicy.blockedAppLabel(label))
        assertFalse(AutomaticCaptureScopePolicy.blockedAppLabel("浏览器"))
        assertTrue(AutomaticCaptureScopePolicy.blockedPackage("com.hihonor.android.launcher"))
        assertTrue(AutomaticCaptureScopePolicy.blockedPackage("com.miui.packageinstaller"))
    }

    @Test fun allForegroundStillHasCooldownDedupeAndByteBudget() {
        traffic.setAllForegroundApps(true)
        traffic.setAutomaticEnabled(true)
        val now = System.currentTimeMillis()
        assertTrue(traffic.reserve("com.example.newapp", byteArrayOf(1), now))
        assertFalse(traffic.canCapture("com.example.newapp", now + 1))
        assertFalse(traffic.reserve("com.example.newapp", byteArrayOf(1), now + CaptureTrafficPolicy.COOLDOWN_MS))
        assertTrue(traffic.reserveAutomaticTransfer(CaptureTrafficPolicy.DAILY_BYTES.toInt()))
        assertFalse(traffic.reserveAutomaticTransfer(1))
    }
}
