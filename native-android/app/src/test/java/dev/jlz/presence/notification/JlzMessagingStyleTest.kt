package dev.jlz.presence.notification

import android.graphics.Bitmap
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Identity regression: the user's monogram is not our chat/group avatar.
 * Notification layout on a particular OEM still requires physical QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JlzMessagingStyleTest {
    @Test
    fun realReplyNotificationIncludesSeparateOwnerAndCompanionAvatars() {
        val context = RuntimeEnvironment.getApplication()
        NotificationAdapter(context).updateReplyState(91, "纪临洲", "收到就回我", "收到", "已存本机")
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(91)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("world-between-owner", style.user.key)
        assertNotNull(style.user.icon)
        assertEquals("world-between-companion", style.messages[0].person?.key)
        assertNotNull(style.messages[0].person?.icon)
        assertEquals("world-between-owner", style.messages[1].person?.key)
    }

    private val user = Person.Builder().setName("你").build()
    private val companion = Person.Builder()
        .setName("纪临洲")
        .setIcon(
            IconCompat.createWithBitmap(
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            )
        )
        .build()

    @Test
    fun incomingMessageIsDirectChatWithCompanionIconNotGroupTitle() {
        val style = JlzMessagingStyle.incoming(user, companion, "平板专属消息", 123456L)

        assertFalse(style.isGroupConversation)
        assertNull(style.conversationTitle)
        assertEquals("你", style.user.name.toString())
        assertEquals(1, style.messages.size)
        assertEquals("纪临洲", style.messages[0].person?.name?.toString())
        assertNotNull(style.messages[0].person?.icon)
    }

    @Test
    fun notificationReplyKeepsSenderIdentityAndUserReplyWithoutGroupTitle() {
        val style = JlzMessagingStyle.afterReply(
            user, companion, "老公发来的消息", "平板收到啦，亲亲。", 123456L
        )

        assertFalse(style.isGroupConversation)
        assertNull(style.conversationTitle)
        assertEquals(2, style.messages.size)
        assertEquals("纪临洲", style.messages[0].person?.name?.toString())
        assertNotNull(style.messages[0].person?.icon)
        assertEquals("你", style.messages[1].person?.name?.toString())
        assertEquals("平板收到啦，亲亲。", style.messages[1].text.toString())
        assertEquals(123456L, style.messages[1].timestamp)
    }
}
