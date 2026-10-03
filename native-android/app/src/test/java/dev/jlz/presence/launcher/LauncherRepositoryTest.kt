package dev.jlz.presence.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LauncherRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: LauncherRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("jlz_launcher", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = LauncherRepository(context)
    }

    @Test
    fun defaultsAreLearningFirstAndOrdered() {
        assertEquals(
            listOf(
                "com.fenbi.android.servant",
                "com.openai.chatgpt",
                "com.tencent.mm"
            ),
            repo.pinnedPackagesInOrder()
        )
    }

    @Test
    fun pinningRestoresHiddenAppAndAppendsIt() {
        val pkg = "com.xingin.xhs"
        assertTrue(repo.isHidden(pkg))
        repo.setPinned(pkg, true)

        assertTrue(repo.isPinned(pkg))
        assertFalse(repo.isHidden(pkg))
        assertEquals(pkg, repo.pinnedPackagesInOrder().last())
    }

    @Test
    fun movePinnedChangesOnlyExplicitOrder() {
        repo.movePinned("com.openai.chatgpt", -1)
        assertEquals(
            listOf(
                "com.openai.chatgpt",
                "com.fenbi.android.servant",
                "com.tencent.mm"
            ),
            repo.pinnedPackagesInOrder()
        )
    }

    @Test
    fun unpinRemovesPackageFromOrder() {
        repo.setPinned("com.tencent.mm", false)
        assertFalse(repo.isPinned("com.tencent.mm"))
        assertEquals(
            listOf("com.fenbi.android.servant", "com.openai.chatgpt"),
            repo.pinnedPackagesInOrder()
        )
    }
}
