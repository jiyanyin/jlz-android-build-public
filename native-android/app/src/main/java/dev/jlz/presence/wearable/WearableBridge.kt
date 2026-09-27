package dev.jlz.presence.wearable

enum class CapabilityStatus {
    UNVERIFIED,
    VERIFIED_SUPPORTED,
    VERIFIED_UNSUPPORTED
}

enum class WearableField {
    BATTERY,
    STEPS,
    HEART_RATE,
    SLEEP,
    ACTIVITY,
    SPO2
}

data class NormalizedHealthState(
    val source: String,
    val observedAtMs: Long,
    val batteryPercent: Int? = null,
    val steps: Int? = null,
    val heartRateBpm: Int? = null,
    val sleepMinutes: Int? = null,
    val activityLabel: String? = null,
    val spo2Percent: Int? = null,
    val capabilities: Map<WearableField, CapabilityStatus>
)

interface WearableBridge {
    val name: String
    suspend fun latestState(): NormalizedHealthState
}

class UnverifiedHuaweiBand8Bridge : WearableBridge {
    override val name: String = "HUAWEI Band 8 · local bridge"

    override suspend fun latestState(): NormalizedHealthState =
        NormalizedHealthState(
            source = "gadgetbridge_pending",
            observedAtMs = System.currentTimeMillis(),
            capabilities = WearableField.entries.associateWith { field ->
                if (field == WearableField.BATTERY) {
                    CapabilityStatus.VERIFIED_SUPPORTED
                } else {
                    CapabilityStatus.UNVERIFIED
                }
            }
        )
}
