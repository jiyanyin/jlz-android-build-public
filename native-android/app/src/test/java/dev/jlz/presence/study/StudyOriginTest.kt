package dev.jlz.presence.study

import android.net.Uri
import dev.jlz.presence.focus.LocalAppClassifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StudyOriginTest {
    @Test fun migrated_formal_origin_is_eligible_and_sibling_services_are_not() {
        val classifier=LocalAppClassifier(RuntimeEnvironment.getApplication())
        assertTrue(classifier.isLearningOrigin(Uri.parse(StudyShortcuts.BANDUREAD_URL)))
        assertTrue(classifier.isLearningOrigin(Uri.parse(StudyShortcuts.BANDUREAD_URL+"history")))
        listOf("http://laptop-p23k8ciu.tail9f1176.ts.net:8443/",
            "https://laptop-p23k8ciu.tail9f1176.ts.net/runtime",
            "https://laptop-p23k8ciu.tail9f1176.ts.net:443/",
            "https://laptop-p23k8ciu.tail9f1176.ts.net:8444/",
            "https://laptop-p23k8ciu.tail9f1176.ts.net.attacker.invalid:8443/",
            "https://user@laptop-p23k8ciu.tail9f1176.ts.net:8443/")
            .forEach {assertFalse(it,classifier.isLearningOrigin(Uri.parse(it)))}
        assertFalse(classifier.eligibleLearning("com.android.chrome"))
    }
}
