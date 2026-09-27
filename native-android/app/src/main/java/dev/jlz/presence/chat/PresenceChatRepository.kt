package dev.jlz.presence.chat

import android.content.Context
import dev.jlz.presence.notification.PendingReplyStore
import dev.jlz.presence.runtime.InboxMessage
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Phone-authored messages are locally retained, independent of the official
 * GPT response cadence. A server echo must never be mistaken for a GPT reply.
 */
class PresenceChatRepository(
    private val context: Context,
    private val settingsRepository: RuntimeSettingsRepository
) {
    private val outbox by lazy { PendingReplyStore(context.applicationContext) }

    suspend fun loadMessages(): List<InboxMessage> = withContext(Dispatchers.IO) {
        val pending = outbox.pending(limit = 40).map { local ->
            InboxMessage(
                id = local.id, role = "user", text = local.text,
                createdAt = Instant.ofEpochMilli(local.observedAtMs).toString(),
                read = false, eventId = local.parentEventId, intentId = local.intentId
            )
        }
        val settings = settingsRepository.load()
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) {
            return@withContext pending
        }
        val remote = runCatching {
            RuntimeApiClient(settings).getInbox()
        }.getOrDefault(emptyList())
        val remoteIds = remote.map { it.id }.toSet()
        (remote + pending.filter { it.id !in remoteIds })
            .sortedBy { it.createdAt.orEmpty() }
    }

    suspend fun sendUserMessage(
        text: String,
        eventId: String? = null,
        intentId: String? = null
    ): InboxMessage = withContext(Dispatchers.IO) {
        val saved = outbox.keep(text, eventId, intentId)
        val settings = settingsRepository.load()
        var delivered = false
        if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
            delivered = runCatching {
                saved.id in outbox.sync(RuntimeApiClient(settings), limit = 40)
            }.getOrDefault(false)
        }
        InboxMessage(
            id = saved.id, role = "user", text = saved.text,
            createdAt = Instant.ofEpochMilli(saved.observedAtMs).toString(),
            read = delivered, eventId = eventId, intentId = intentId
        )
    }
}
