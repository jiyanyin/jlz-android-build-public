package dev.jlz.presence.actions

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.jlz.presence.notification.PresenceNotificationListenerService
import dev.jlz.presence.screen.AccessibilityActionGateway
import org.json.JSONObject

/** Small, read-only system snapshot plus the explicitly requested wake action. */
class DeviceSystemController(private val context: Context) {
    enum class LockState { SCREEN_OFF, LOCKED_NON_SECURE, LOCKED_SECURE, UNLOCKED }

    fun lockState(): LockState {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isInteractive != true) return LockState.SCREEN_OFF
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            return if (keyguard.isDeviceSecure) LockState.LOCKED_SECURE else LockState.LOCKED_NON_SECURE
        }
        return LockState.UNLOCKED
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(): Pair<Boolean, String> {
        if (lockState() != LockState.SCREEN_OFF) return true to lockState().name
        val power = context.getSystemService(PowerManager::class.java)
            ?: return false to "power_manager_unavailable"
        return runCatching {
            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "jlz:remote-wake"
            )
            wakeLock.acquire(2_500L)
            wakeLock.release()
            true to lockState().name
        }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
    }

    fun capabilities(): JSONObject {
        val state = lockState()
        return JSONObject()
            .put("accessibility_connected", AccessibilityActionGateway.available())
            .put("notification_listener_connected", PresenceNotificationListenerService.isConnected())
            .put("screenshot_ready", AccessibilityActionGateway.available() && Build.VERSION.SDK_INT >= 30)
            .put("gesture_ready", AccessibilityActionGateway.available())
            .put("node_read_ready", AccessibilityActionGateway.available())
            .put("active_notification_read_ready", PresenceNotificationListenerService.isConnected())
            .put("file_share_ready", true)
            .put("wake_screen_ready", true)
            .put("lock_state", state.name)
            .put("secure_unlock_supported", false)
            .put("location_ready", ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            .put("observed_at_ms", System.currentTimeMillis())
            .put("system_state", systemState())
    }

    fun systemState(): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val active = connectivity?.activeNetwork
        val caps = active?.let { connectivity.getNetworkCapabilities(it) }
        val networkType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            active != null -> "other"
            else -> "offline"
        }
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        val imeVisible = if (Build.VERSION.SDK_INT >= 30) runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
            wm?.currentWindowMetrics?.windowInsets?.isVisible(WindowInsets.Type.ime())
        }.getOrNull() else null
        val display = if (Build.VERSION.SDK_INT >= 30) runCatching {
            val metrics = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics
            val insets = metrics?.windowInsets
            val bars = insets?.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            JSONObject().put("width", metrics?.bounds?.width() ?: 0).put("height", metrics?.bounds?.height() ?: 0)
                .put("inset_left", bars?.left ?: 0).put("inset_top", bars?.top ?: 0)
                .put("inset_right", bars?.right ?: 0).put("inset_bottom", bars?.bottom ?: 0)
        }.getOrNull() else JSONObject().put("width", context.resources.displayMetrics.widthPixels)
            .put("height", context.resources.displayMetrics.heightPixels)
        return JSONObject()
            .put("screen_interactive", power?.isInteractive == true)
            .put("lock_state", lockState().name)
            .put("orientation", orientation)
            .put("ime_visible", imeVisible ?: JSONObject.NULL)
            .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty())
            .put("current_activity", AccessibilityActionGateway.currentActivity().orEmpty())
            .put("battery_percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", battery?.isCharging == true)
            .put("network_type", networkType)
            .put("wifi_connected", networkType == "wifi")
            .put("volume_music", audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1)
            .put("volume_music_max", audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1)
            .put("brightness", runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(-1))
            .put("clipboard_has_content", clipboard?.hasPrimaryClip() == true)
            .put("display", display ?: JSONObject.NULL)
            .put("observed_at_ms", System.currentTimeMillis())
    }
}
