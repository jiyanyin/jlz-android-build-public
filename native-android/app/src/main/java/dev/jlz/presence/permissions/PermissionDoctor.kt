package dev.jlz.presence.permissions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.Secure
import androidx.core.content.ContextCompat

data class PermissionItem(
    val key: String,
    val label: String,
    val group: String,
    val status: Status,
    val intent: Intent? = null
) {
    enum class Status { OK, MISSING, LIMITED, NOT_APPLICABLE }
}

class PermissionDoctor(private val context: Context) {
    fun checkAll(): List<PermissionItem> {
        val items = mutableListOf<PermissionItem>()
        val accEnabled = Secure.getString(context.contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(context.packageName) == true
        items += PermissionItem("accessibility", "\u65e0\u969c\u788d\u670d\u52a1", "\u773c\u775b",
            if (accEnabled) PermissionItem.Status.OK else PermissionItem.Status.MISSING,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val nlEnabled = run {
            val flat = Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            flat.split(":").any { it.startsWith(context.packageName + "/") || it == context.packageName }
        }
        items += PermissionItem("notification_listener", "\u901a\u77e5\u76d1\u542c", "\u773c\u775b",
            if (nlEnabled) PermissionItem.Status.OK else PermissionItem.Status.MISSING,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val usageAllowed = try {
            context.getSystemService(android.app.AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        items += PermissionItem("usage", "\u4f7f\u7528\u60c5\u51b5\u8bbf\u95ee", "\u773c\u775b",
            if (usageAllowed) PermissionItem.Status.OK else PermissionItem.Status.MISSING,
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val overlay = Settings.canDrawOverlays(context)
        items += PermissionItem("overlay", "\u60ac\u6d6e\u7a97", "\u624b",
            if (overlay) PermissionItem.Status.OK else PermissionItem.Status.MISSING,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val notifPerm = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        items += PermissionItem("notification", "\u901a\u77e5\u6743\u9650", "\u5728\u573a",
            if (notifPerm) PermissionItem.Status.OK else PermissionItem.Status.MISSING,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

        val pm = context.getSystemService(PowerManager::class.java)
        val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        items += PermissionItem("battery", "\u7535\u6c60\u4f18\u5316\u767d\u540d\u5355", "\u5728\u573a",
            if (batteryOk) PermissionItem.Status.OK else PermissionItem.Status.MISSING,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

        val fsiOk = if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else true
        items += PermissionItem("full_screen", "\u5168\u5c4f\u6765\u7535\u663e\u793a", "\u5728\u573a",
            if (fsiOk) PermissionItem.Status.OK else PermissionItem.Status.LIMITED)

        val locFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        items += PermissionItem("location", "\u4f4d\u7f6e\u4fe1\u606f", "\u540c\u884c",
            if (locFine) PermissionItem.Status.OK else PermissionItem.Status.MISSING)

        items += PermissionItem("home_role", "\u9ed8\u8ba4\u684c\u9762", "\u684c\u9762", PermissionItem.Status.NOT_APPLICABLE)
        return items
    }
}
