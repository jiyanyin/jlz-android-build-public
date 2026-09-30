package dev.jlz.presence.capture

import android.content.Context
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.screen.ScreenshotCaptureResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Device-private finite screenshot outbox. A failed network request must
 * never silently discard a photo the user explicitly asked to share.
 * Files remain local until the server acknowledges the same event UUID.
 *
 * This queue is transport working storage, NOT the official GPT Memory.
 */
class PendingScreenshotQueue(private val context: Context) {
    private val root = File(context.applicationContext.filesDir, "jlz_capture_outbox_v1")
    private val journal = CaptureEventStore(context.applicationContext)

    data class SendResult(val sent: Boolean, val eventId: String, val reason: String)

    /**
     * Owner-authorized full screenshot reset for the 2026-09-28 capture-policy cutover.
     * Deletes screenshot transport files and screenshot event rows only.
     * Notes, replies, focus state and other local data are untouched.
     */
    @Synchronized
    fun clearAllScreenshotsOnce(marker: String): Int {
        val prefs = context.applicationContext.getSharedPreferences(
            "jlz_presence_capture_full_reset_v1", Context.MODE_PRIVATE
        )
        val key = "done:" + marker
        if (prefs.getBoolean(key, false)) return 0

        var removed = 0
        if (root.isDirectory) {
            root.listFiles().orEmpty().forEach { file ->
                if (file.delete()) removed++
            }
        }
        journal.deleteScreenshotRecordsBefore(Long.MAX_VALUE)
        check(prefs.edit().putBoolean(key, true).commit()) {
            "capture_full_reset_marker_not_saved"
        }
        return removed
    }

    /**
     * One-time screenshot clean slate authorized 2026-09-27 11:55 Asia/Shanghai.
     * The server resets the same earlier screenshot cutoff. Prevent an old
     * phone-side retry from repopulating wiped server captures.
     * Text notes, replies, skin packs and user settings are not touched.
     */
    @Synchronized
    fun clearLegacyTestImagesOnce(): Int {
        val prefs = root.parentFile?.let {
            context.applicationContext.getSharedPreferences(
                "jlz_presence_capture_reset_20260927", Context.MODE_PRIVATE
            )
        } ?: return 0
        if (prefs.getBoolean("done", false)) return 0
        val cutoffMs = 1790481300000L
        var removed = 0
        if (root.isDirectory) {
            root.listFiles().orEmpty()
                .filter { it.extension == "image" || it.extension == "uploaded" }
                .forEach { photo ->
                    val meta = File(root, "${photo.nameWithoutExtension}.json")
                    val observedAt = runCatching {
                        JSONObject(meta.readText()).optLong("observed_at_ms", 0L)
                    }.getOrDefault(0L)
                    val actualAt = if (observedAt > 0L) observedAt else photo.lastModified()
                    if (actualAt in 1 until cutoffMs && photo.delete()) {
                        meta.delete()
                        removed++
                    }
                }
        }
        journal.deleteScreenshotRecordsBefore(cutoffMs)
        check(prefs.edit().putBoolean("done", true).commit()) {
            "test_screenshot_reset_marker_not_saved"
        }
        return removed
    }

    @Synchronized
    fun enqueue(
        capture: ScreenshotCaptureResult.Captured,
        eventId: String = UUID.randomUUID().toString(),
        sourcePackage: String? = null,
        studySessionId: String? = null,
        origin: String = "manual_q"
    ): String {
        require(UUID.fromString(eventId).toString() == eventId) { "invalid_capture_event_id" }
        require(capture.mimeType == "image/jpeg" || capture.mimeType == "image/png") {
            "unsupported_capture_type"
        }
        require(capture.bytes.size in 100..MAX_SINGLE_BYTES) { "capture_size_not_supported" }
        check(root.isDirectory || root.mkdirs()) { "local_capture_outbox_unavailable" }
        cleanupForCapacity(capture.bytes.size, origin)
        val current = transportFiles()
        check(current.size < MAX_PENDING_COUNT) {
            "quota_full:capture_outbox_count_limit;official_unuploaded_preserved"
        }
        check(current.sumOf { it.length() } + capture.bytes.size <= MAX_PENDING_BYTES) {
            "quota_full:capture_outbox_byte_limit;official_unuploaded_preserved"
        }
        val photo = File(root, "$eventId.image")
        val metadata = File(root, "$eventId.json")
        val stage = File(root, ".$eventId.tmp")
        try {
            FileOutputStream(stage).use { stream ->
                stream.write(capture.bytes)
                stream.fd.sync()
            }
            val now = System.currentTimeMillis()
            val meta = JSONObject()
                .put("event_id", eventId)
                .put("source_package", sourcePackage)
                .put("study_session_id", studySessionId)
                .put("observed_at_ms", now)
                .put("mime_type", capture.mimeType)
                .put("origin", origin)
                .put("state", "queued")
                .put("retry_count", 0)
                .put("next_retry_at_ms", 0L)
                .put("last_error_stage", JSONObject.NULL)
                .put("lifecycle", JSONObject()
                    .put("requested_at_ms", now)
                    .put("captured_at_ms", now)
                    .put("queued_at_ms", now)
                    .put("uploading_at_ms", JSONObject.NULL)
                    .put("uploaded_at_ms", JSONObject.NULL)
                    .put("server_received_at_ms", JSONObject.NULL)
                    .put("gpt_fetched_at_ms", JSONObject.NULL)
                    .put("reviewed_at_ms", JSONObject.NULL)
                    .put("released_at_ms", JSONObject.NULL))
            metadata.writeText(meta.toString())
            check(stage.renameTo(photo)) { "capture_outbox_atomic_save_failed" }
            journal.add(
                kind = "screenshot",
                id = eventId,
                originPackage = sourcePackage,
                studySessionId = studySessionId,
                mode = when {
                    origin.startsWith("automatic_app_") -> "APP"
                    origin == "manual_q" -> "MANUAL"
                    origin == "official_gpt_request" -> "RUNTIME"
                    else -> "CAPTURE"
                },
                deliveryStatus = "upload_pending",
                detail = "saved_on_phone_until_matching_server_ack"
            )
            return eventId
        } finally {
            stage.delete()
            if (!photo.isFile) metadata.delete()
        }
    }

    /** Reuses the event UUID and original study tag on every retry. */
    @Synchronized
    fun sendPending(
        api: RuntimeApiClient,
        limit: Int = 4,
        priorityEventId: String? = null
    ): List<SendResult> {
        if (!root.isDirectory) return emptyList()
        cleanupExpired()
        val now = System.currentTimeMillis()
        val transportPrefs = context.applicationContext.getSharedPreferences(
            "jlz_capture_transport_backoff_v1", Context.MODE_PRIVATE
        )
        // AutomaticCaptureCoordinator also passes priorityEventId for its
        // 30-second capture. Priority alone does NOT imply an owner/GPT
        // request; only an explicitly tagged official capture can bypass
        // the automatic retry pause.
        val priorityIsOfficial = priorityEventId != null &&
            readMeta(priorityEventId)?.optString("origin") == "official_gpt_request"
        if (!priorityIsOfficial &&
            transportPrefs.getLong("quota_pause_until_ms", 0L) > now) {
            return emptyList()
        }
        return root.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "image" }
            .filter { photo ->
                val meta = readMeta(photo.nameWithoutExtension)
                val retries = meta?.optInt("retry_count", 0) ?: 0
                val nextRetry = meta?.optLong("next_retry_at_ms", 0L) ?: 0L
                retries < maxRetries(meta?.optString("origin").orEmpty()) && nextRetry <= now
            }
            .sortedWith(
                compareBy<File> { if (it.nameWithoutExtension == priorityEventId) 0 else 1 }
                    .thenBy { originRank(readMeta(it.nameWithoutExtension)?.optString("origin").orEmpty()) }
                    .thenBy { it.lastModified() }
            )
            .take(limit.coerceIn(1, 10))
            .mapNotNull { photo ->
                val eventId = photo.nameWithoutExtension
                if ((!priorityIsOfficial || eventId != priorityEventId) &&
                    transportPrefs.getLong("quota_pause_until_ms", 0L) >
                    System.currentTimeMillis()) {
                    return@mapNotNull null
                }
                val metadataFile = File(root, "$eventId.json")
                try {
                    val metadata = JSONObject(metadataFile.readText())
                    check(metadata.optString("event_id") == eventId) {
                        "capture_event_identity_mismatch"
                    }
                    val lifecycle = metadata.optJSONObject("lifecycle") ?: JSONObject()
                    lifecycle.put("uploading_at_ms", System.currentTimeMillis())
                    metadata.put("state", "uploading").put("lifecycle", lifecycle)
                    writeMeta(eventId, metadata)
                    val result = api.uploadScreenshot(
                        bytes = photo.readBytes(),
                        mimeType = metadata.optString("mime_type"),
                        eventId = eventId,
                        originPackage = metadata.optString("source_package")
                            .takeIf { it.isNotBlank() && it != "null" },
                        studySessionId = metadata.optString("study_session_id")
                            .takeIf { it.isNotBlank() && it != "null" },
                        capturedAtMs = metadata.optLong("observed_at_ms"),
                        captureOrigin = metadata.optString("origin").takeIf { it.isNotBlank() }
                    )
                    val filename = result.optString("filename")
                    check(result.optBoolean("ok") &&
                        result.optString("event_id") == eventId &&
                        filename.isNotBlank()) {
                        "capture_upload_ack_unconfirmed"
                    }
                    val confirmedAt = System.currentTimeMillis()
                    lifecycle.put("uploaded_at_ms", confirmedAt)
                        .put("server_received_at_ms", confirmedAt)
                    metadata.put("state", "server_received")
                        .put("remote_filename", filename)
                        .put("next_retry_at_ms", 0L)
                        .put("last_error_stage", JSONObject.NULL)
                        .put("lifecycle", lifecycle)
                    writeMeta(eventId, metadata)
                    journal.updateDelivery(
                        eventId, "upload_confirmed", filename,
                        detail = "server_received; GPT_fetch_and_review_not_yet_confirmed"
                    )
                    check(photo.renameTo(File(root, "$eventId.uploaded"))) {
                        "capture_local_uploaded_transition_failed"
                    }
                    // A successful upload proves the server is accepting
                    // screenshots again; allow the normal queue to drain.
                    transportPrefs.edit().remove("quota_pause_until_ms").apply()
                    SendResult(true, eventId, filename)
                } catch (failure: Exception) {
                    val message = (failure.message ?: failure.javaClass.simpleName).take(230)
                    val metadata = readMeta(eventId) ?: JSONObject().put("event_id", eventId)
                    val retry = metadata.optInt("retry_count", 0) + 1
                    val stage = failureStage(message)
                    if (stage == "quota_full") {
                        transportPrefs.edit().putLong(
                            "quota_pause_until_ms",
                            System.currentTimeMillis() + 60L * 60L * 1000L
                        ).apply()
                    }
                    val retryDelay = RETRY_BASE_MS * (1L shl (retry - 1).coerceIn(0, 5))
                    metadata.put("state", "failed")
                        .put("retry_count", retry)
                        .put("last_error_stage", stage)
                        .put("last_error", message)
                        .put("next_retry_at_ms", System.currentTimeMillis() + retryDelay)
                    writeMeta(eventId, metadata)
                    journal.updateDelivery(
                        eventId, "upload_failed",
                        detail = "$stage:$message".take(230)
                    )
                    SendResult(false, eventId, "$stage:$message".take(150))
                }
            }
    }

    private fun transportFiles(): List<File> =
        root.listFiles().orEmpty().filter {
            it.isFile && (it.extension == "image" || it.extension == "uploaded")
        }

    private fun readMeta(eventId: String): JSONObject? = runCatching {
        JSONObject(File(root, "$eventId.json").readText())
    }.getOrNull()

    private fun writeMeta(eventId: String, metadata: JSONObject) {
        runCatching { File(root, "$eventId.json").writeText(metadata.toString()) }
    }

    private fun originRank(origin: String): Int = when {
        origin == "official_gpt_request" -> 0
        origin == "manual_q" -> 1
        origin.startsWith("automatic_app_switch") -> 2
        origin.startsWith("automatic_app_stay") -> 3
        origin.contains("work", ignoreCase = true) -> 4
        origin.startsWith("automatic_") -> 5
        else -> 6
    }

    private fun maxRetries(origin: String): Int =
        if (origin == "official_gpt_request") 8 else 4

    private fun pixelTtlMs(origin: String, uploaded: Boolean): Long = when {
        origin == "official_gpt_request" && uploaded -> 24L * 60L * 60L * 1000L
        origin == "official_gpt_request" -> Long.MAX_VALUE
        origin.contains("work", ignoreCase = true) -> 2L * 60L * 60L * 1000L
        origin.startsWith("automatic_") -> 6L * 60L * 60L * 1000L
        uploaded -> 12L * 60L * 60L * 1000L
        else -> 12L * 60L * 60L * 1000L
    }

    private fun failureStage(message: String): String = when {
        message.contains("507") || message.contains("quota", ignoreCase = true) -> "quota_full"
        message.contains("ack_unconfirmed") || message.contains("server", ignoreCase = true) -> "server_failed"
        else -> "upload_failed"
    }

    private fun releasePixel(file: File, reason: String): Boolean {
        val eventId = file.nameWithoutExtension
        val metadata = readMeta(eventId)
        val lifecycle = metadata?.optJSONObject("lifecycle") ?: JSONObject()
        lifecycle.put("released_at_ms", System.currentTimeMillis())
        metadata?.put("state", "released")?.put("lifecycle", lifecycle)
        if (!file.delete()) return false
        // Transport metadata is disposable; CaptureEventStore keeps the
        // long-lived event evidence after pixel release.
        File(root, "$eventId.json").delete()
        journal.updateDelivery(
            eventId, "local_transport_released",
            detail = reason.take(230)
        )
        return true
    }

    /**
     * Cleanup order is deliberate: acknowledged/expired copies first, then
     * expired automatic/Work captures, then oldest noncritical automatic/Work
     * images. Never evict an unuploaded official GPT request to make room.
     */
    private fun cleanupForCapacity(requiredBytes: Int, incomingOrigin: String) {
        cleanupExpired()
        var files = transportFiles()
        fun over(): Boolean =
            files.size >= MAX_PENDING_COUNT ||
                files.sumOf { it.length() } + requiredBytes > MAX_PENDING_BYTES
        if (!over()) return

        val candidates = files.filter { file ->
            if (file.extension == "uploaded") true
            else {
                val origin = readMeta(file.nameWithoutExtension)?.optString("origin").orEmpty()
                origin != "official_gpt_request" &&
                    (origin.startsWith("automatic_") || origin.contains("work", ignoreCase = true))
            }
        }.sortedWith(compareBy<File> {
            if (it.extension == "uploaded") 0 else 1
        }.thenBy { it.lastModified() })

        for (file in candidates) {
            if (!over()) break
            releasePixel(file, "capacity_cleanup_for:$incomingOrigin")
            files = transportFiles()
        }
    }

    @Synchronized
    fun cleanupExpired(): Int {
        if (!root.isDirectory) return 0
        val now = System.currentTimeMillis()
        var removed = 0
        transportFiles().sortedBy { it.lastModified() }.forEach { file ->
            val metadata = readMeta(file.nameWithoutExtension) ?: return@forEach
            val observedAt = metadata.optLong("observed_at_ms", file.lastModified())
            val origin = metadata.optString("origin")
            val ttl = pixelTtlMs(origin, file.extension == "uploaded")
            if (ttl != Long.MAX_VALUE && now - observedAt >= ttl) {
                if (releasePixel(file, "pixel_ttl_expired:$origin")) removed++
            }
        }
        return removed
    }


    /**
     * Do not equate upload with GPT reading. A reviewed capture is removed
     * locally only after the authenticated Runtime index reports it reviewed.
     * Unavailable/evicted captures are queued again with their original UUID.
     */
    @Synchronized
    fun reconcileWithRuntime(api: RuntimeApiClient): Int {
        if (!root.isDirectory) return 0
        cleanupExpired()
        val index = api.captureIndex(limit = 100)
        val remote = mutableMapOf<String, JSONObject>()
        for (i in 0 until index.length()) {
            index.optJSONObject(i)?.let { item ->
                remote[item.optString("event_id")] = item
            }
        }
        var changed = 0
        root.listFiles().orEmpty().filter { it.extension == "uploaded" }.forEach { image ->
            val id = image.nameWithoutExtension
            val entry = remote[id]
            val metadata = File(root, "$id.json")
            if (entry?.optString("discussion_status") == "reviewed") {
                val meta = readMeta(id)
                val lifecycle = meta?.optJSONObject("lifecycle") ?: JSONObject()
                lifecycle.put("gpt_fetched_at_ms",
                    entry.optLong("gpt_fetched_at_ms", System.currentTimeMillis()))
                    .put("reviewed_at_ms",
                        entry.optLong("reviewed_at_ms", System.currentTimeMillis()))
                meta?.put("state", "reviewed")?.put("lifecycle", lifecycle)
                writeMeta(id, meta ?: JSONObject().put("event_id", id).put("lifecycle", lifecycle))
                if (releasePixel(image, "gpt_reviewed_ack")) changed++
            } else if (entry != null && !entry.optBoolean("available", false)) {
                // The server returns only the newest 100 capture records here.
                // Missing from that page does NOT mean that older image pixels
                // disappeared: do not reupload already-acknowledged screenshots.
                // A matching record with available=false is positive evidence.
                val meta = readMeta(id)
                val origin = meta?.optString("origin").orEmpty()
                val attemptCount = meta?.optInt("remote_restore_attempts", 0) ?: 0
                // An explicit server "unavailable" may justify one
                // restoration, but not indefinite auto reuploads after
                // quota eviction. Officially requested captures get two.
                val restoreLimit = if (origin == "official_gpt_request") 2 else 1
                if (meta != null && attemptCount < restoreLimit) {
                    // Persist the restore budget before changing the file
                    // back to an outbound image; failure remains uploaded.
                    val updated = JSONObject(meta.toString())
                        .put("remote_restore_attempts", attemptCount + 1)
                        .put("state", "queued")
                    val saved = runCatching {
                        File(root, "$id.json").writeText(updated.toString())
                    }.isSuccess
                    if (saved && image.renameTo(File(root, "$id.image"))) {
                        journal.updateDelivery(id, "upload_pending",
                            detail = "remote_unavailable_bounded_restore_original_uuid")
                        changed++
                    }
                }
            }
        }
        return changed
    }

    companion object {
        const val MAX_PENDING_COUNT = 48
        const val MAX_PENDING_BYTES = 96L * 1024L * 1024L
        const val AUTOMATIC_TTL_MS = 6L * 60L * 60L * 1000L
        const val WORK_TEST_TTL_MS = 2L * 60L * 60L * 1000L
        const val OFFICIAL_UPLOADED_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_SINGLE_BYTES = 12 * 1024 * 1024
        private const val RETRY_BASE_MS = 30_000L
    }
}
