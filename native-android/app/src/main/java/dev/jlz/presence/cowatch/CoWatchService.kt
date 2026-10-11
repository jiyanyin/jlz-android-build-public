package dev.jlz.presence.cowatch

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.UUID

object CoWatchState {
    @Volatile var active = false
    @Volatile var expiresAt = 0L
    @Volatile var sessionId = ""
    @Volatile var uploaded = 0
    @Volatile var reason = "未开启；没有分享画面"
    fun summary(): String = "${if (active) "正在屏幕陪看" else "未分享"} · 上传${uploaded}张\n剩余${((expiresAt-System.currentTimeMillis()).coerceAtLeast(0)/1000)}秒\n$reason\n${if (active) "会话：$sessionId" else ""}"
}

/** One finite projection, no persisted consent and no offline pixel queue. */
class CoWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var api: RuntimeApiClient? = null
    private var sid = ""
    private var width = 0
    private var height = 0
    private var destroyed = false
    private var capturedVisible = true
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            CoWatchState.reason = "用户已挂断，停止共享"
            stopSelf()
            return START_NOT_STICKY
        }
        if (CoWatchState.active || sid.isNotBlank()) return START_NOT_STICKY
        val minutes = intent?.getIntExtra("minutes", 0) ?: 0
        val target = intent?.getStringExtra("target").orEmpty()
        @Suppress("DEPRECATION") val consent = intent?.getParcelableExtra<Intent>("consent")
        if (minutes !in listOf(5, 10) || target.isBlank() || consent == null || intent?.getIntExtra("result", 0) != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jlz_cowatch_v1", "主动屏幕陪看", NotificationManager.IMPORTANCE_LOW))
        startForeground(4555, notification())
        scope.launch {
            try {
                val cfg = RuntimeSettingsRepository(applicationContext).load()
                check(cfg.baseUrl.isNotBlank() && cfg.token.isNotBlank()) { "私人Runtime未连接" }
                api = RuntimeApiClient(cfg)
                check(api!!.canTransferCapture()) { "仅允许私人Home链路" }
                sid = UUID.randomUUID().toString()
                val result = withContext(Dispatchers.IO) { api!!.startCoWatch(sid, minutes) }
                check(result.optBoolean("ok")) { "Runtime未确认会话" }
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
                check(projection != null) { "系统录屏许可不可用" }
                projection!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { CoWatchState.reason = "系统结束共享"; stopSelf() }
                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) { capturedVisible = isVisible }
                    override fun onCapturedContentResize(w: Int, h: Int) { resize(w, h) }
                }, Handler(Looper.getMainLooper()))
                val metrics = resources.displayMetrics
                val scale = 720f / maxOf(metrics.widthPixels, metrics.heightPixels)
                width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
                height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                display = projection!!.createVirtualDisplay("JLZ-optin-screen", width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
                CoWatchState.active = true; CoWatchState.sessionId = sid
                CoWatchState.uploaded = 0; CoWatchState.expiresAt = System.currentTimeMillis() + minutes * 60_000L
                CoWatchState.reason = "仅采样选定应用；切去其他页面暂停采样，GPT未自动读取"
                while (isActive && System.currentTimeMillis() < CoWatchState.expiresAt) {
                    if (safe(target)) {
                        val pixels = dev.jlz.presence.overlay.FloatingPresenceService.withoutOverlay {
                            delay(300)
                            if (safe(target)) frame() else null
                        }
                        if (pixels != null) {
                            check(!destroyed && safe(target)) { "共享目标改变，停止上传" }
                            val stamp = System.currentTimeMillis()
                            val receipt = withContext(Dispatchers.IO) { api!!.uploadCoWatchFrame(sid, UUID.randomUUID().toString(), stamp, pixels) }
                            check(receipt.optBoolean("ok")) { "帧上传未确认" }
                            if (destroyed || !isActive) return@launch
                            CoWatchState.uploaded = receipt.optInt("frame_count")
                            CoWatchState.reason = "已上传真实采样；官端是否读取需查询回执"
                        }
                    } else CoWatchState.reason = "非授权应用／隐私页面，暂停采样"
                    manager.notify(4555, notification())
                    delay(20_000L)
                }
                CoWatchState.reason = "时限已到，已停止共享"
            } catch (error: Exception) {
                if (error !is CancellationException) CoWatchState.reason = "已停止共享：" + error.javaClass.simpleName + "（无离线补传）"
            } finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun safe(target: String): Boolean =
        !destroyed && capturedVisible && !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            dev.jlz.presence.screen.ScreenObservationBus.isAvailable() &&
            dev.jlz.presence.screen.AccessibilityActionGateway.safeForCoWatch(target)

    private fun resize(w: Int, h: Int) {
        if (destroyed || display == null || w <= 0 || h <= 0) return
        val scale = minOf(1f, 720f / maxOf(w, h))
        val nextWidth = (w * scale).toInt().coerceAtLeast(1)
        val nextHeight = (h * scale).toInt().coerceAtLeast(1)
        if (nextWidth == width && nextHeight == height) return
        val next = ImageReader.newInstance(nextWidth, nextHeight, PixelFormat.RGBA_8888, 2)
        display?.surface = null
        display?.resize(nextWidth, nextHeight, resources.displayMetrics.densityDpi)
        display?.surface = next.surface
        reader?.close(); reader = next; width = nextWidth; height = nextHeight
    }

    private fun frame(): ByteArray? {
        val image = reader?.acquireLatestImage() ?: return null
        return image.use { img ->
            val plane = img.planes[0]
            val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                try { ByteArrayOutputStream().use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 72, out); out.toByteArray().takeIf { it.size in 100..600_000 } } }
                finally { if (cropped !== padded) cropped.recycle() }
            } finally { padded.recycle() }
        }
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 4555, Intent(this, CoWatchService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "jlz_cowatch_v1").setSmallIcon(R.drawable.ic_notification_world_between_v3)
            .setContentTitle("视频通话 · 有限屏幕采样").setContentText(CoWatchState.summary()).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", stop).build()
    }
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true; CoWatchState.active = false; CoWatchState.expiresAt = 0L
        scope.cancel(); display?.release(); reader?.close(); projection?.stop()
        display = null; reader = null; projection = null
        val ending = sid; val client = api
        if (client != null && ending.isNotBlank()) CoroutineScope(Dispatchers.IO).launch {
            withTimeoutOrNull(5000) { runCatching { client.stopCoWatch(ending) } }
        }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
