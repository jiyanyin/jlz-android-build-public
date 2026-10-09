package dev.jlz.presence.place

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val Context.placeDataStore by preferencesDataStore(name = "jlz_places")

enum class SemanticPlace {
    HOME,
    OFFICE,
    COMMUTING,
    OUTSIDE,
    UNKNOWN
}

data class PlaceAnchor(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 180f
)

data class SemanticPlaceState(
    val place: SemanticPlace,
    val confidence: Float,
    val observedAtMs: Long,
    val source: String,
    val stableSinceMs: Long = observedAtMs,
    val lastTransitionAtMs: Long = 0L,
    val freshnessMs: Long = 0L
)

class PlaceAnchorRepository(private val context: Context) {
    private object Keys {
        val homeLat = stringPreferencesKey("home_lat")
        val homeLon = stringPreferencesKey("home_lon")
        val homeRadius = stringPreferencesKey("home_radius")
        val officeLat = stringPreferencesKey("office_lat")
        val officeLon = stringPreferencesKey("office_lon")
        val officeRadius = stringPreferencesKey("office_radius")
    }

    suspend fun home(): PlaceAnchor? = anchor(Keys.homeLat, Keys.homeLon, Keys.homeRadius)
    suspend fun office(): PlaceAnchor? = anchor(Keys.officeLat, Keys.officeLon, Keys.officeRadius)
    suspend fun hasHome(): Boolean = home() != null
    suspend fun hasOffice(): Boolean = office() != null

    suspend fun setHome(location: Location, radiusMeters: Float = 180f) {
        context.placeDataStore.edit { prefs ->
            prefs[Keys.homeLat] = location.latitude.toString()
            prefs[Keys.homeLon] = location.longitude.toString()
            prefs[Keys.homeRadius] = radiusMeters.toString()
        }
    }

    suspend fun setOffice(location: Location, radiusMeters: Float = 220f) {
        context.placeDataStore.edit { prefs ->
            prefs[Keys.officeLat] = location.latitude.toString()
            prefs[Keys.officeLon] = location.longitude.toString()
            prefs[Keys.officeRadius] = radiusMeters.toString()
        }
    }

    private suspend fun anchor(
        latKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        lonKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        radiusKey: androidx.datastore.preferences.core.Preferences.Key<String>
    ): PlaceAnchor? =
        context.placeDataStore.data.map { prefs ->
            val lat = prefs[latKey]?.toDoubleOrNull()
            val lon = prefs[lonKey]?.toDoubleOrNull()
            if (lat == null || lon == null) null
            else PlaceAnchor(
                latitude = lat,
                longitude = lon,
                radiusMeters = prefs[radiusKey]?.toFloatOrNull() ?: 180f
            )
        }.first()
}

class DeviceLocationAdapter(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // hasPermission gates entry; runCatching handles a revoked permission during the request.
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (last != null && System.currentTimeMillis() - last.time <= 5 * 60_000L) {
            return last
        }

        for (provider in providers) {
            val live = withTimeoutOrNull(8_000L) { requestOnce(provider) }
            if (live != null) return live
        }
        return last
    }

    // Only called by permission-checked currentLocation; failures resume with null.
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun requestOnce(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("legacy callback")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }
            runCatching {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure {
                runCatching { manager.removeUpdates(listener) }
                if (continuation.isActive) continuation.resume(null)
            }
        }
}

class SemanticPlaceEngine(private val anchors: PlaceAnchorRepository) {
    suspend fun classify(location: Location?): SemanticPlaceState {
        if (location == null) {
            return SemanticPlaceState(
                place = SemanticPlace.UNKNOWN,
                confidence = 0f,
                observedAtMs = System.currentTimeMillis(),
                source = "location_unavailable"
            )
        }

        val home = anchors.home()
        val office = anchors.office()
        if (home == null && office == null) {
            return SemanticPlaceState(
                place = SemanticPlace.UNKNOWN,
                confidence = 0f,
                observedAtMs = location.time,
                source = "anchors_unconfigured"
            )
        }

        if (location.hasAccuracy() && location.accuracy > 500f) {
            return SemanticPlaceState(
                place = SemanticPlace.UNKNOWN,
                confidence = 0.2f,
                observedAtMs = location.time,
                source = "location_accuracy_low"
            )
        }

        val accuracyAllowance = if (location.hasAccuracy()) {
            location.accuracy.coerceIn(0f, 250f)
        } else 0f
        val homeDistance = home?.let { distance(location, it) }
        val officeDistance = office?.let { distance(location, it) }

        if (
            home != null &&
            homeDistance != null &&
            homeDistance <= home.radiusMeters + accuracyAllowance
        ) {
            return SemanticPlaceState(
                SemanticPlace.HOME,
                confidence(homeDistance, home.radiusMeters + accuracyAllowance),
                location.time,
                if (accuracyAllowance > 0f) "local_anchor_accuracy_tolerant" else "local_anchor"
            )
        }
        if (
            office != null &&
            officeDistance != null &&
            officeDistance <= office.radiusMeters + accuracyAllowance
        ) {
            return SemanticPlaceState(
                SemanticPlace.OFFICE,
                confidence(officeDistance, office.radiusMeters + accuracyAllowance),
                location.time,
                if (accuracyAllowance > 0f) "local_anchor_accuracy_tolerant" else "local_anchor"
            )
        }

        val moving = location.hasSpeed() && location.speed >= 2.5f
        return SemanticPlaceState(
            place = if (moving) SemanticPlace.COMMUTING else SemanticPlace.OUTSIDE,
            confidence = if (moving) 0.78f else 0.66f,
            observedAtMs = location.time,
            source = "local_motion"
        )
    }

    private fun distance(location: Location, anchor: PlaceAnchor): Float {
        val out = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            anchor.latitude,
            anchor.longitude,
            out
        )
        return out[0]
    }

    private fun confidence(distance: Float, radius: Float): Float {
        if (radius <= 0f) return 0.5f
        return (1f - distance / (radius * 1.5f)).coerceIn(0.5f, 0.99f)
    }
}
