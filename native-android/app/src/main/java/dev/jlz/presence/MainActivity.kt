package dev.jlz.presence

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.jlz.presence.navigation.PresenceRoute
import dev.jlz.presence.navigation.PresenceRouteBus
import dev.jlz.presence.notification.NotificationReplyReceiver
import dev.jlz.presence.ui.PresenceApp
import dev.jlz.presence.ui.theme.IceCrystalTheme

class MainActivity : ComponentActivity() {
    fun shouldReturnToWebShell(): Boolean =
        intent?.getBooleanExtra(EXTRA_RETURN_TO_WEB_SHELL, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { IceCrystalTheme { PresenceApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val nativePath = intent?.data
            ?.takeIf { it.scheme == "jlz" && it.host == "native" }
            ?.path
        if (nativePath != null) {
            when (nativePath) {
                "/settings" -> PresenceRouteBus.open(PresenceRoute.Settings)
                "/permissions" -> PresenceRouteBus.open(PresenceRoute.PermissionDoctor)
                "/diagnostics" -> PresenceRouteBus.open(PresenceRoute.Diagnostics)
                "/today" -> PresenceRouteBus.open(PresenceRoute.Today)
                "/study" -> PresenceRouteBus.open(PresenceRoute.Study)
                else -> PresenceRouteBus.open(PresenceRoute.Home)
            }
            return
        }
        when (intent?.getStringExtra(EXTRA_DESTINATION)) {
            DESTINATION_STUDY -> PresenceRouteBus.open(PresenceRoute.Study)
            DESTINATION_TODAY -> PresenceRouteBus.open(PresenceRoute.Today)
            DESTINATION_QUICK_CAPTURE -> PresenceRouteBus.open(PresenceRoute.QuickCapture)
            DESTINATION_PERMISSIONS -> PresenceRouteBus.open(PresenceRoute.PermissionDoctor)
            DESTINATION_CHAT -> PresenceRouteBus.open(
                PresenceRoute.Chat(
                    intent.getStringExtra(NotificationReplyReceiver.EXTRA_EVENT_ID),
                    intent.getStringExtra(NotificationReplyReceiver.EXTRA_INTENT_ID)
                )
            )
            else -> PresenceRouteBus.open(PresenceRoute.Home)
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_STUDY = "study"
        const val DESTINATION_TODAY = "today"
        const val DESTINATION_QUICK_CAPTURE = "quick_capture"
        const val DESTINATION_PERMISSIONS = "permissions"
        const val DESTINATION_CHAT = "chat"
        const val EXTRA_RETURN_TO_WEB_SHELL = "return_to_web_shell"
    }
}
