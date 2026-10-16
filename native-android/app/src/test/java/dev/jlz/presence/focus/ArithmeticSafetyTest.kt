package dev.jlz.presence.focus

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArithmeticSafetyTest {
    @Test fun urgentAndVerificationAppsBypassQuizButWorkAndShoppingDoNot() {
        val classifier = LocalAppClassifier(RuntimeEnvironment.getApplication())
        for (pkg in listOf("com.android.dialer", "com.android.phone", "com.android.incallui",
            "com.android.emergency", "com.android.mms", "com.android.deskclock", "com.android.camera",
            "com.autonavi.minimap", "com.eg.android.AlipayGphone", "com.google.android.apps.authenticator2")) {
            assertTrue(pkg, classifier.arithmeticExempt(pkg))
        }
        for (pkg in listOf("com.openai.chatgpt", "com.tencent.mm", "com.taobao.taobao", "com.xingin.xhs")) {
            assertFalse(pkg, classifier.arithmeticExempt(pkg))
        }
    }
}
