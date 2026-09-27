package dev.jlz.presence.place

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.semanticPlaceDataStore by preferencesDataStore(
    name = "jlz_semantic_place"
)

data class StablePlaceRecord(
    val place: SemanticPlace = SemanticPlace.UNKNOWN,
    val stableSinceMs: Long = 0L,
    val lastTransitionAtMs: Long = 0L,
    val lastObservedAtMs: Long = 0L,
    val candidate: SemanticPlace? = null,
    val candidateSinceMs: Long = 0L,
    val confidence: Float = 0f,
    val source: String = "none"
)

data class StabilizedPlaceResult(
    val state: SemanticPlaceState,
    val previousPlace: SemanticPlace?,
    val transitioned: Boolean
)

class SemanticPlaceStabilizer(private val context: Context) {
    private object Keys {
        val place = stringPreferencesKey("place")
        val stableSince = longPreferencesKey("stable_since_ms")
        val lastTransition = longPreferencesKey("last_transition_at_ms")
        val lastObserved = longPreferencesKey("last_observed_at_ms")
        val candidate = stringPreferencesKey("candidate")
        val candidateSince = longPreferencesKey("candidate_since_ms")
        val confidence = stringPreferencesKey("confidence")
        val source = stringPreferencesKey("source")
    }

    suspend fun stabilize(
        raw: SemanticPlaceState,
        nowMs: Long = System.currentTimeMillis()
    ): StabilizedPlaceResult {
        val current = load()

        if (raw.place == SemanticPlace.UNKNOWN) {
            val lastFreshness =
                if (current.lastObservedAtMs > 0L) {
                    nowMs - current.lastObservedAtMs
                } else {
                    Long.MAX_VALUE
                }

            if (
                current.place != SemanticPlace.UNKNOWN &&
                lastFreshness <= STALE_HOLD_MS
            ) {
                val held = SemanticPlaceState(
                    place = current.place,
                    confidence = (current.confidence * 0.7f)
                        .coerceAtLeast(0.2f),
                    observedAtMs = current.lastObservedAtMs,
                    source = current.source + ":stale_hold",
                    stableSinceMs = current.stableSinceMs,
                    lastTransitionAtMs =
                        current.lastTransitionAtMs,
                    freshnessMs = lastFreshness
                )
                return StabilizedPlaceResult(
                    state = held,
                    previousPlace = current.place,
                    transitioned = false
                )
            }

            val unknown = StablePlaceRecord(
                place = SemanticPlace.UNKNOWN,
                stableSinceMs = nowMs,
                lastTransitionAtMs =
                    if (current.place != SemanticPlace.UNKNOWN) {
                        nowMs
                    } else {
                        current.lastTransitionAtMs
                    },
                lastObservedAtMs = raw.observedAtMs,
                confidence = 0f,
                source = raw.source
            )
            save(unknown)
            return StabilizedPlaceResult(
                state = unknown.toState(nowMs),
                previousPlace = current.place,
                transitioned =
                    current.place != SemanticPlace.UNKNOWN
            )
        }

        if (current.place == SemanticPlace.UNKNOWN) {
            val initial = StablePlaceRecord(
                place = raw.place,
                stableSinceMs = nowMs,
                lastTransitionAtMs = nowMs,
                lastObservedAtMs = raw.observedAtMs,
                confidence = raw.confidence,
                source = raw.source
            )
            save(initial)
            return StabilizedPlaceResult(
                state = initial.toState(nowMs),
                previousPlace = SemanticPlace.UNKNOWN,
                transitioned = true
            )
        }

        if (raw.place == current.place) {
            val refreshed = current.copy(
                lastObservedAtMs = raw.observedAtMs,
                candidate = null,
                candidateSinceMs = 0L,
                confidence = raw.confidence,
                source = raw.source
            )
            save(refreshed)
            return StabilizedPlaceResult(
                state = refreshed.toState(nowMs),
                previousPlace = current.place,
                transitioned = false
            )
        }

        val candidateSince =
            if (current.candidate == raw.place) {
                current.candidateSinceMs
                    .takeIf { it > 0L }
                    ?: nowMs
            } else {
                nowMs
            }

        val requiredDwell = dwellFor(raw.place)
        val candidateAge = nowMs - candidateSince

        if (candidateAge >= requiredDwell) {
            val changed = current.copy(
                place = raw.place,
                stableSinceMs = nowMs,
                lastTransitionAtMs = nowMs,
                lastObservedAtMs = raw.observedAtMs,
                candidate = null,
                candidateSinceMs = 0L,
                confidence = raw.confidence,
                source = raw.source
            )
            save(changed)
            return StabilizedPlaceResult(
                state = changed.toState(nowMs),
                previousPlace = current.place,
                transitioned = true
            )
        }

        val waiting = current.copy(
            candidate = raw.place,
            candidateSinceMs = candidateSince,
            lastObservedAtMs = raw.observedAtMs
        )
        save(waiting)

        return StabilizedPlaceResult(
            state = waiting.toState(nowMs).copy(
                source = current.source + ":candidate_" +
                    raw.place.name.lowercase()
            ),
            previousPlace = current.place,
            transitioned = false
        )
    }

    private suspend fun load(): StablePlaceRecord =
        context.semanticPlaceDataStore.data.map { prefs ->
            StablePlaceRecord(
                place = decodePlace(
                    prefs[Keys.place],
                    SemanticPlace.UNKNOWN
                ),
                stableSinceMs = prefs[Keys.stableSince] ?: 0L,
                lastTransitionAtMs =
                    prefs[Keys.lastTransition] ?: 0L,
                lastObservedAtMs =
                    prefs[Keys.lastObserved] ?: 0L,
                candidate = prefs[Keys.candidate]
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        runCatching {
                            SemanticPlace.valueOf(it)
                        }.getOrNull()
                    },
                candidateSinceMs =
                    prefs[Keys.candidateSince] ?: 0L,
                confidence =
                    prefs[Keys.confidence]?.toFloatOrNull() ?: 0f,
                source = prefs[Keys.source] ?: "none"
            )
        }.first()

    private suspend fun save(record: StablePlaceRecord) {
        context.semanticPlaceDataStore.edit { prefs ->
            prefs[Keys.place] = record.place.name
            prefs[Keys.stableSince] = record.stableSinceMs
            prefs[Keys.lastTransition] =
                record.lastTransitionAtMs
            prefs[Keys.lastObserved] =
                record.lastObservedAtMs
            prefs[Keys.candidate] =
                record.candidate?.name.orEmpty()
            prefs[Keys.candidateSince] =
                record.candidateSinceMs
            prefs[Keys.confidence] =
                record.confidence.toString()
            prefs[Keys.source] = record.source
        }
    }

    private fun StablePlaceRecord.toState(
        nowMs: Long
    ): SemanticPlaceState =
        SemanticPlaceState(
            place = place,
            confidence = confidence,
            observedAtMs = lastObservedAtMs,
            source = source,
            stableSinceMs = stableSinceMs,
            lastTransitionAtMs = lastTransitionAtMs,
            freshnessMs =
                if (lastObservedAtMs > 0L) {
                    (nowMs - lastObservedAtMs)
                        .coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
        )

    private fun dwellFor(place: SemanticPlace): Long =
        when (place) {
            SemanticPlace.HOME,
            SemanticPlace.OFFICE -> 60_000L
            SemanticPlace.COMMUTING -> 30_000L
            SemanticPlace.OUTSIDE -> 90_000L
            SemanticPlace.UNKNOWN -> 5 * 60_000L
        }

    private fun decodePlace(
        value: String?,
        fallback: SemanticPlace
    ): SemanticPlace =
        runCatching {
            SemanticPlace.valueOf(value.orEmpty())
        }.getOrDefault(fallback)

    companion object {
        private const val STALE_HOLD_MS = 10 * 60_000L
    }
}
