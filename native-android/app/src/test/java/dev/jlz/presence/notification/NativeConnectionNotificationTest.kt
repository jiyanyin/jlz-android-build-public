package dev.jlz.presence.notification

import android.app.Notification
import android.content.Context
import dev.jlz.presence.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeConnectionNotificationTest {
    @Test
    fun ongoingServiceNotificationContainsAppArtworkAndMonochromeStatusIcon() {
        val context: Context = RuntimeEnvironment.getApplication()
        val n = NativeConnectionNotification.build(context, "jlz_native_runtime_v3")

        // Full-color original launcher art is an icon for the notification
        // content; the status bar requires a separate monochrome drawable.
        assertNotNull(n.largeIcon)
        assertEquals(R.drawable.ic_notification_world_between_v3, n.smallIcon.resId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("我在", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }
}
