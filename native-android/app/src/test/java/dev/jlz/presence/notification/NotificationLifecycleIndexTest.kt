package dev.jlz.presence.notification

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationLifecycleIndexTest {
    @Test fun repeatedCallbackAndRestartDoNotRepeatButUpdatesAndRemovalSurvive() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jlz_notification_lifecycle_v1",Context.MODE_PRIVATE).edit().clear().commit()
        val index = NotificationLifecycleIndex(context)
        assertEquals("posted",index.posted("notification-key","filtered text",1000))
        assertEquals("duplicate",NotificationLifecycleIndex(context).posted("notification-key","filtered text",2000))
        assertEquals("updated",index.posted("notification-key","changed safe text",3000))
        assertTrue(index.removed("notification-key",4000))
        assertFalse(NotificationLifecycleIndex(context).removed("notification-key",5000))
        assertEquals("posted",index.posted("notification-key","changed safe text",6000))
        val persisted=context.getSharedPreferences("jlz_notification_lifecycle_v1",Context.MODE_PRIVATE).getString("index","")!!
        assertFalse(persisted.contains("filtered text"))
        assertFalse(persisted.contains("notification-key"))
    }
    @Test fun missingHistoricalCallbackCannotFabricateRemovedHistory() {
        assertFalse(NotificationLifecycleIndex(RuntimeEnvironment.getApplication()).removed("never-seen-key",1000))
    }
}
