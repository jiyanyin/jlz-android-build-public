package dev.jlz.presence.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenObservation(
    val packageName: String?,
    val visibleText: String,
    val observedAtMs: Long
)

interface ScreenObservationAdapter {
    val observations: StateFlow<ScreenObservation?>
    fun isAvailable(): Boolean
}

object ScreenObservationBus : ScreenObservationAdapter {
    private val _observations = MutableStateFlow<ScreenObservation?>(null)
    override val observations: StateFlow<ScreenObservation?> = _observations.asStateFlow()

    @Volatile
    private var connected: Boolean = false

    override fun isAvailable(): Boolean = connected

    internal fun setConnected(value: Boolean) {
        connected = value
    }

    internal fun publish(observation: ScreenObservation) {
        _observations.value = observation
    }

    internal fun clear() {
        _observations.value = null
    }
}

sealed interface ScreenshotCaptureResult {
    data class Captured(val bytes: ByteArray, val mimeType: String) : ScreenshotCaptureResult
    data class Unavailable(val reason: String) : ScreenshotCaptureResult
}

interface ScreenshotCaptureAdapter {
    suspend fun capture(): ScreenshotCaptureResult
}

class UnavailableScreenshotCaptureAdapter : ScreenshotCaptureAdapter {
    override suspend fun capture(): ScreenshotCaptureResult =
        ScreenshotCaptureResult.Unavailable(
            "N0 capture adapter boundary exists; MediaProjection binding is not implemented yet."
        )
}
