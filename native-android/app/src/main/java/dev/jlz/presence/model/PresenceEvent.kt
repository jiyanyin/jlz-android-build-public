package dev.jlz.presence.model

import java.util.UUID

enum class PresenceEventStatus {
    OPEN, ASKED, AWAITING_REPLY, AWAITING_GPT, RESOLVED, DISMISSED, EXPIRED, CANCELLED
}

enum class PresenceEventType {
    MESSAGE, REPLY, NOTIFICATION, SCREENSHOT, STUDY, LIFE, FOCUS, CALLBACK,
    HEALTH, LOCATION, WEATHER, PENDING_THOUGHT
}

data class PresenceContext(
    val eventId: String = UUID.randomUUID().toString(),
    val intentId: String = UUID.randomUUID().toString(),
    val planId: String? = null,
    val sessionId: String? = null,
    val type: PresenceEventType = PresenceEventType.MESSAGE,
    val status: PresenceEventStatus = PresenceEventStatus.OPEN,
    val createdAtMs: Long = System.currentTimeMillis()
)
