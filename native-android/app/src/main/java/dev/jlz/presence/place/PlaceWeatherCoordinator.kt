package dev.jlz.presence.place

import android.content.Context
import org.json.JSONObject

data class PlaceWeatherSnapshot(
    val place: SemanticPlaceState,
    val weather: WeatherState?,
    val transitioned: Boolean = false,
    val previousPlace: SemanticPlace? = null
) {
    fun placeJson(): JSONObject = JSONObject()
        .put("semantic_place", place.place.name)
        .put("confidence", place.confidence)
        .put("observed_at_ms", place.observedAtMs)
        .put("source", place.source)
        .put("stable_since_ms", place.stableSinceMs)
        .put("last_transition_at_ms", place.lastTransitionAtMs)
        .put("freshness_ms", place.freshnessMs)
        .put("fresh", place.freshnessMs <= 5 * 60_000L)
}

class PlaceWeatherCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val location = DeviceLocationAdapter(appContext)
    private val anchors = PlaceAnchorRepository(appContext)
    private val semantic = SemanticPlaceEngine(anchors)
    private val stabilizer = SemanticPlaceStabilizer(appContext)
    private val weather = WeatherAdapter()

    suspend fun snapshot(): PlaceWeatherSnapshot {
        val current = location.currentLocation()
        val raw = semantic.classify(current)
        val stable = stabilizer.stabilize(raw)
        return PlaceWeatherSnapshot(
            place = stable.state,
            weather = runCatching {
                weather.current(current)
            }.getOrNull(),
            transitioned = stable.transitioned,
            previousPlace = stable.previousPlace
        )
    }

    suspend fun setCurrentAsHome(): Boolean {
        val current = location.currentLocation() ?: return false
        anchors.setHome(current)
        return true
    }

    suspend fun setCurrentAsOffice(): Boolean {
        val current = location.currentLocation() ?: return false
        anchors.setOffice(current)
        return true
    }
}
