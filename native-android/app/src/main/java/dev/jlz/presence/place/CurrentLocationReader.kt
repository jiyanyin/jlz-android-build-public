package dev.jlz.presence.place

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * One-shot owner-location reader for an explicit Runtime command.
 *
 * This is intentionally separate from LocationForegroundService:
 * - it never starts or resumes a trip;
 * - it never persists precision coordinates;
 * - it returns one bounded snapshot with freshness/accuracy provenance.
 */
class CurrentLocationReader(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    suspend fun snapshot(
        maxAgeMs: Long = 120_000L,
        timeoutMs: Long = 7_000L
    ): JSONObject {
        val boundedMaxAge = maxAgeMs.coerceIn(5_000L, 15 * 60_000L)
        val boundedTimeout = timeoutMs.coerceIn(1_000L, 15_000L)
        if (!hasLocationPermission()) {
            return JSONObject()
                .put("ok", false)
                .put("reason", "location_permission_missing")
                .put("fresh", false)
                .put("semantics", "one_shot_snapshot_not_continuous_tracking")
        }

        val cached = bestLastKnown()
        val now = System.currentTimeMillis()
        if (cached != null && ageMs(cached, now) <= boundedMaxAge) {
            return encode(cached, now, boundedMaxAge, "android_location_last_known_fresh", false)
        }

        val current = withTimeoutOrNull(boundedTimeout) { requestCurrentFix() }
        val observedAt = System.currentTimeMillis()
        val selected = current ?: cached
        if (selected == null) {
            return JSONObject()
                .put("ok", false)
                .put("reason", if (current == null) "location_fix_unavailable" else "location_unknown")
                .put("fresh", false)
                .put("requested_current_fix", true)
                .put("observed_at_ms", observedAt)
                .put("semantics", "one_shot_snapshot_not_continuous_tracking")
        }
        return encode(
            selected,
            observedAt,
            boundedMaxAge,
            if (current != null) "android_location_current_fix" else "android_location_last_known_stale",
            current != null
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val providers = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
        return providers.asSequence()
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter(::valid)
            .maxWithOrNull(
                compareBy<Location> { it.time }
                    .thenBy { if (it.hasAccuracy()) -it.accuracy else Float.NEGATIVE_INFINITY }
            )
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentFix(): Location? {
        val enabled = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in enabled }
        for (provider in providers) {
            val fix = withTimeoutOrNull(4_500L) { requestProvider(provider) }
            if (fix != null && valid(fix)) return fix
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestProvider(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(appContext)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun valid(location: Location): Boolean =
        location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0 &&
            (!location.hasAccuracy() || (location.accuracy.isFinite() && location.accuracy >= 0f))

    private fun ageMs(location: Location, now: Long): Long =
        (now - location.time).coerceAtLeast(0L)

    private fun encode(
        location: Location,
        observedAt: Long,
        maxAgeMs: Long,
        source: String,
        requestedCurrentFixSucceeded: Boolean
    ): JSONObject {
        val age = ageMs(location, observedAt)
        return JSONObject()
            .put("ok", true)
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy_m", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
            .put("provider", location.provider ?: "")
            .put("location_time_ms", location.time)
            .put("observed_at_ms", observedAt)
            .put("age_ms", age)
            .put("fresh", age <= maxAgeMs)
            .put("max_age_ms", maxAgeMs)
            .put("source", source)
            .put("requested_current_fix_succeeded", requestedCurrentFixSucceeded)
            .put("persisted", false)
            .put("trip_started", false)
            .put("semantics", "one_shot_snapshot_not_continuous_tracking")
    }
}
