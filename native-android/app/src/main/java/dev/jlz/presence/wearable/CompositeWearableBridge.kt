package dev.jlz.presence.wearable

/**
 * V2: Health Connect integration was removed. This composite no longer
 * depends on HealthConnectBridge and always reports an unsupported/removed
 * state. The WearableBridge shape is retained for future local sensors /
 * peripherals (multi-device schema), without any health SDK dependency.
 */
interface WearableTelemetryBridge {
    suspend fun batteryPercent(): Int?
}

class HistoricalBand8TelemetryBridge : WearableTelemetryBridge {
    override suspend fun batteryPercent(): Int? = null
}

class CompositeWearableBridge(
    private val telemetry: WearableTelemetryBridge = HistoricalBand8TelemetryBridge()
) : WearableBridge {
    override val name = "V2_REMOVED_health_connect"

    override suspend fun latestState(): NormalizedHealthState {
        val battery = telemetry.batteryPercent()
        return NormalizedHealthState(
            source = "v2_removed_health_connect",
            observedAtMs = System.currentTimeMillis(),
            batteryPercent = battery,
            capabilities = WearableField.entries.associateWith { field ->
                if (field == WearableField.BATTERY && battery != null) {
                    CapabilityStatus.VERIFIED_SUPPORTED
                } else {
                    CapabilityStatus.VERIFIED_UNSUPPORTED
                }
            }
        )
    }
}
