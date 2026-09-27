package dev.jlz.presence.runtime

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import dev.jlz.presence.notification.PresenceNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only media-session data exposed to the phone's notification listener.
 * This does not access private tracks, DRM or audio bytes.
 */
object NativeMediaSnapshot {
    fun collect(context: Context): JSONObject {
        val result = JSONObject()
            .put("source", "android_media_session_via_notification_listener")
            .put("observed_at_ms", System.currentTimeMillis())
            .put("sessions", JSONArray())
        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(
                context.applicationContext, PresenceNotificationListenerService::class.java
            )
            val active = manager.getActiveSessions(listener)
            val sessions = JSONArray()
            active.take(12).forEach { controller ->
                val metadata = controller.metadata
                val playback = controller.playbackState
                val playing = playback?.state == PlaybackState.STATE_PLAYING
                sessions.put(JSONObject()
                    .put("package_name", controller.packageName)
                    .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
                    .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
                    .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
                    .put("playing", playing)
                    .put("playback_state", playback?.state ?: PlaybackState.STATE_NONE)
                )
            }
            result.put("available", true).put("sessions", sessions)
        } catch (error: Exception) {
            result.put("available", false)
                .put("reason", "notification_listener_access_or_media_sessions_unavailable")
                .put("error_type", error.javaClass.simpleName)
        }
    }
}
