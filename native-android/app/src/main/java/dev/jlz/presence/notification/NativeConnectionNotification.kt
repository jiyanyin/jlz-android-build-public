package dev.jlz.presence.notification

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R

/**
 * The persistent Home Node connection uses the EXISTING World Between launcher
 * artwork for its full-colour notification image. The separate monochrome small
 * icon is required by Android for the status bar.
 *
 * Chat/inline reply notifications are deliberately NOT changed here:
 * their approved JLZ planet avatar belongs to NotificationAdapter.
 */
internal object NativeConnectionNotification {
    private const val LARGE_ICON_PX = 128

    fun build(context: Context, channelId: String): Notification {
        val launcherArtwork = BitmapFactory.decodeResource(
            context.resources, R.drawable.world_between_app_icon
        )
        // Keep a small bitmap in the ongoing notification, even though the
        // original launcher illustration can be much larger.
        val brandArtwork: Bitmap? = launcherArtwork?.let { original ->
            if (original.width == LARGE_ICON_PX && original.height == LARGE_ICON_PX) original
            else Bitmap.createScaledBitmap(original, LARGE_ICON_PX, LARGE_ICON_PX, true)
                .also { if (it !== original) original.recycle() }
        }

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setLargeIcon(brandArtwork)
            .setContentTitle("我在")
            .setContentText("正在保持和 JLZ Runtime 的连接")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
