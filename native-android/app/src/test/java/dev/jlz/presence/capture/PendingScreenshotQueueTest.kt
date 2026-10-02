package dev.jlz.presence.capture

import android.content.Context
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettings
import dev.jlz.presence.screen.ScreenshotCaptureResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingScreenshotQueueTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "jlz_capture_outbox_v1").deleteRecursively()
        context.getSharedPreferences("jlz_capture_transport_backoff_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun first507PausesWholeQueueIncludingExplicitCaptures() {
        var uploadCalls = 0
        val queue = PendingScreenshotQueue(context) { _, _, _ ->
            uploadCalls++
            throw IllegalStateException(
                "Runtime HTTP 507: {\"error\":\"native_capture_pending_quota_reached\"}"
            )
        }
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val capture = ScreenshotCaptureResult.Captured(ByteArray(200) { 7 }, "image/jpeg")
        queue.enqueue(capture, first, origin = "official_gpt_request")
        queue.enqueue(capture, second, origin = "official_gpt_request")
        val api = RuntimeApiClient(RuntimeSettings(baseUrl = "https://runtime.invalid", token = "x"))

        val result = queue.sendPending(api, limit = 10, priorityEventId = first)

        assertEquals(1, uploadCalls)
        assertEquals(1, result.size)
        assertFalse(result.single().sent)
        assertTrue(result.single().reason.startsWith("quota_full:"))
        val pauseUntil = context.getSharedPreferences(
            "jlz_capture_transport_backoff_v1", Context.MODE_PRIVATE
        ).getLong("quota_pause_until_ms", 0L)
        assertTrue(pauseUntil >= System.currentTimeMillis() +
            PendingScreenshotQueue.QUOTA_RETRY_DELAY_MS - 5_000L)

        assertTrue(queue.sendPending(api, limit = 10, priorityEventId = second).isEmpty())
        assertEquals(1, uploadCalls)
        assertEquals(2, File(context.filesDir, "jlz_capture_outbox_v1")
            .listFiles().orEmpty().count { it.extension == "image" })
    }
}
