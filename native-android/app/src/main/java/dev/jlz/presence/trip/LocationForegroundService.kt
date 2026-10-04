package dev.jlz.presence.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import dev.jlz.presence.R
import dev.jlz.presence.data.LocalLifeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

data class TripPoint(
    val lat: Double, val lng: Double, val accuracy: Float, val speed: Float,
    val bearing: Float, val timestampMs: Long, val provider: String
)

data class TripSession(
    val active: Boolean = false,
    val startedAtMs: Long = 0L,
    val points: List<TripPoint> = emptyList(),
    val tracking: Boolean = true
)

/** Process-wide UI-observable trip state; the service owns the real work. */
object TripController {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun start(context: Context) {
        val app = context.applicationContext
        app.startForegroundService(Intent(app, LocationForegroundService::class.java).setAction(ACTION_START))
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        // stop is allowed from a plain startService; the service removes its FGS.
        runCatching {
            app.startService(Intent(app, LocationForegroundService::class.java).setAction(ACTION_STOP))
        }
    }

    internal fun setActive(value: Boolean) { _active.value = value }

    const val ACTION_START = "dev.jlz.presence.trip.START"
    const val ACTION_STOP = "dev.jlz.presence.trip.STOP"
}

class LocationForegroundService : Service() {
    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private val _trip = MutableStateFlow(TripSession())
    val trip: StateFlow<TripSession> = _trip
    inner class LocalBinder : Binder() { fun getService(): LocationForegroundService = this@LocationForegroundService }

    private val maxPoints = 2000
    private val maxAccuracyM = 60f
    private val minDisplacementM = 8f

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = ingest(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createChannel()
        getSystemService(NotificationManager::class.java)?.cancel(LEGACY_NOTIF_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TripController.ACTION_START -> {
                // startForeground must happen immediately for an FGS start.
                startForeground(NOTIF_ID, buildNotification(if (hasLocationPermission()) "正在记录行程" else "等待位置权限"))
                startTrip()
            }
            TripController.ACTION_STOP -> {
                finishTrip()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startTrip() {
        _trip.value = TripSession(active = true, startedAtMs = System.currentTimeMillis())
        TripController.setActive(true)
        if (!hasLocationPermission()) {
            _trip.update { it.copy(tracking = false) }
            return
        }
        runCatching {
            // Coarse/fine, GPS + network; drift is filtered in ingest().
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 5f, locationListener, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15_000L, 10f, locationListener, Looper.getMainLooper())
            }
            _trip.update { it.copy(tracking = true) }
        }.onFailure { _trip.update { it.copy(tracking = false) } }
    }

    private fun ingest(location: Location) {
        val current = _trip.value
        if (!current.active) return
        // Drift / noise filter: reject poor accuracy and jitter below min move.
        if (location.hasAccuracy() && location.accuracy > maxAccuracyM) return
        val last = current.points.lastOrNull()
        if (last != null) {
            val moved = FloatArray(1)
            Location.distanceBetween(last.lat, last.lng, location.latitude, location.longitude, moved)
            if (moved[0] < minDisplacementM && abs(location.speed) < 0.5f) return
        }
        val point = TripPoint(
            location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else -1f,
            if (location.hasSpeed()) location.speed else 0f,
            if (location.hasBearing()) location.bearing else 0f,
            System.currentTimeMillis(),
            location.provider ?: "gps"
        )
        // Bounded raw buffer: oldest points are dropped (cleanup strategy).
        val merged = (current.points + point).takeLast(maxPoints)
        _trip.value = current.copy(points = merged)
    }

    private fun finishTrip() {
        val session = _trip.value
        runCatching { locationManager.removeUpdates(locationListener) }
        if (session.active) {
            val durationMin = ((System.currentTimeMillis() - session.startedAtMs) / 60_000L).coerceAtLeast(0L)
            var distanceM = 0.0
            for (i in 1 until session.points.size) {
                val r = FloatArray(1)
                val a = session.points[i - 1]; val b = session.points[i]
                Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, r)
                distanceM += r[0]
            }
            // Timeline receives only a summary, never raw points.
            LocalLifeStore(this).recordTimeline(
                "trip_summary", "一段同行行程",
                "${durationMin}分钟 · ${session.points.size}个点 · 约${(distanceM / 1000.0).let { if (it < 1) "${distanceM.toInt()}米" else "%.1f公里".format(it) }}"
            )
        }
        // Raw points cleared after summarisation.
        _trip.value = TripSession()
        TripController.setActive(false)
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(locationListener) }
        if (_trip.value.active) finishTrip()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("jlz_trip", "同行定位", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "jlz_trip")
            .setContentTitle("带着老公")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_world_between_v2)
            .setOngoing(true)
            .setContentIntent(pi).build()
    }

    companion object {
        const val LEGACY_NOTIF_ID = 3001
        const val NOTIF_ID = 4301
    }
}
