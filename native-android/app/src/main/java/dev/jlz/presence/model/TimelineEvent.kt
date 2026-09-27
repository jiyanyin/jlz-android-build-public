package dev.jlz.presence.model

data class TimelineEventV2(
    val id: String,
    val occurredAtMs: Long,
    val actor: Actor,
    val side: Side,
    val type: String,
    val title: String,
    val body: String = "",
    val source: String = "app_auto",
    val sourceDeviceId: String = "phone-1",
    val eventId: String? = null,
    val intentId: String? = null,
    val packageName: String? = null,
    val callStatus: String? = null,
    val provenance: Provenance = Provenance.APP_AUTO,
    val metadata: Map<String, String> = emptyMap()
) {
    enum class Actor { JLZ, USER, SYSTEM }
    enum class Side { LEFT, RIGHT, CENTER }
    enum class Provenance(val label: String) {
        APP_AUTO(""), RUNTIME(""), OFFICIAL_GPT(""), USER_DIRECT(""),
        JLZ_MANUAL_RECORD("\u8001\u516c\u4ee3\u8bb0\u5f55"), ANDROID_SHARE("")
    }
}
