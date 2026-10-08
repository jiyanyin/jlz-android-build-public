package dev.jlz.presence.runtime

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RuntimeCommand(
    val id: String,
    val action: String,
    val payload: JSONObject,
    val packageName: String? = null,
    val appName: String? = null,
    val deviceId: String = "android-phone-native-n0",
    val intentId: String? = null,
    val origin: String = "JLZ_RUNTIME",
    val actor: String = "JLZ_RUNTIME",
    val controller: String = "runtime_backend",
    val requestedAtMs: Long? = null,
    val phoneReceivedAtMs: Long = System.currentTimeMillis()
)

data class InboxMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: String?,
    val read: Boolean,
    val eventId: String? = null,
    val intentId: String? = null
)

data class DeviceStateSnapshot(
    val deviceId: String,
    val deviceType: String = if (deviceId.contains("tablet", ignoreCase = true)) "tablet" else "phone",
    val appVersion: String,
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val observedAtMs: Long = System.currentTimeMillis(),
    val health: JSONObject? = null,
    val place: JSONObject? = null,
    val weather: JSONObject? = null,
    val presencePlan: JSONObject? = null,
    val usage: JSONObject? = null,
    val attention: JSONObject? = null,
    val screen: JSONObject? = null,
    val deviceVitals: JSONObject? = null,
    val media: JSONObject? = null,
    val calendar: JSONObject? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("device_type", deviceType)
        .put("client", "jlz-native-android")
        .put("client_version", appVersion)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("sdk_int", sdkInt)
        .put("observed_at_ms", observedAtMs)
        .also { json ->
            health?.let { json.put("health_state", it) }
            place?.let { json.put("place_state", it) }
            weather?.let { json.put("weather_state", it) }
            presencePlan?.let { json.put("presence_plan", it) }
            usage?.let { json.put("usage_state", it) }
            attention?.let { json.put("attention_state", it) }
            screen?.let { json.put("screen_state", it) }
            deviceVitals?.let { json.put("device_vitals", it) }
            media?.let { json.put("media_state", it) }
            calendar?.let { json.put("calendar_state", it) }
        }
}

class RuntimeApiClient(private val settings: RuntimeSettings) {
    private fun connection(
        path: String,
        method: String,
        readTimeoutMs: Int = 15_000
    ): HttpURLConnection {
        val base = settings.baseUrl.trim().trimEnd('/')
        require(BridgeStore.validUrl(base)) { "Runtime URL must use HTTPS or a private LAN/Tailscale IPv4 address" }
        return (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("X-Auth-Token", settings.token)
            setRequestProperty("Accept", "application/json")
        }
    }

    fun postDeviceState(snapshot: DeviceStateSnapshot) = postJson("/api/device/state", snapshot.toJson().apply {
        settings.traffic?.let { put("traffic_state", it.snapshot()) }
    })

    fun captureIndex(limit: Int = 100): JSONArray {
        requirePrivateCaptureRoute()
        val q = URLEncoder.encode(settings.deviceId, Charsets.UTF_8.name())
        val response = getJson("/api/captures?device_id=" + q +
            "&limit=" + limit.coerceIn(1, 100))
        check(response.optBoolean("ok", false)) { "capture_index_unavailable" }
        return response.optJSONArray("captures") ?: JSONArray()
    }



    fun pollCommand(waitMs: Int = 0): RuntimeCommand? {
        val encodedId = URLEncoder.encode(settings.deviceId, Charsets.UTF_8.name())
        val safeWait = waitMs.coerceIn(0, 25_000)
        val response = try { getJson(
            "/api/poll?device_id=" + encodedId + "&wait_ms=" + safeWait,
            readTimeoutMs = (safeWait + 10_000).coerceAtLeast(15_000)
        ).also { settings.bridge?.pollResult(settings.bridgeName, true) } }
        catch (e: Exception) { settings.bridge?.pollResult(settings.bridgeName, false); throw e }
        if (!response.optBoolean("ok", false) || response.isNull("command")) return null
        val command = response.optJSONObject("command") ?: return null
        val payload = command.optJSONObject("payload") ?: JSONObject()
        return RuntimeCommand(
            id = command.optString("id"),
            action = command.optString("action", "noop"),
            payload = payload,
            packageName = command.optString("package").takeIf { it.isNotBlank() },
            appName = command.optString("app").takeIf { it.isNotBlank() },
            deviceId = command.optString("device_id").ifBlank { settings.deviceId },
            intentId = command.optString("intent_id").ifBlank {
                payload.optString("intent_id")
            }.takeIf { it.isNotBlank() },
            origin = command.optString("origin").ifBlank {
                payload.optString("origin")
            }.ifBlank { "JLZ_RUNTIME" },
            actor = command.optString("actor").ifBlank {
                payload.optString("actor")
            }.ifBlank { "JLZ_RUNTIME" },
            controller = command.optString("controller").ifBlank {
                payload.optString("controller")
            }.ifBlank { "runtime_backend" },
            requestedAtMs = command.optLong("requested_at_ms", 0L).takeIf { it > 0L }
                ?: payload.optLong("requested_at_ms", 0L).takeIf { it > 0L },
            phoneReceivedAtMs = System.currentTimeMillis()
        )
    }

    fun report(
        command: RuntimeCommand,
        ok: Boolean,
        result: String,
        executedAtMs: Long? = null,
        verifiedAtMs: Long? = null,
        beforeState: JSONObject? = null,
        afterState: JSONObject? = null
    ) {
        val structuredResult: Any = runCatching { JSONObject(result) }.getOrElse { result }
        val effectiveDeviceId = command.deviceId.ifBlank { settings.deviceId }
        val body = JSONObject()
                .put("command_id", command.id)
                .put("intent_id", command.intentId ?: JSONObject.NULL)
                .put("device_id", effectiveDeviceId)
                .put("device_type", if (effectiveDeviceId.contains("tablet", ignoreCase = true)) "tablet" else "phone")
                .put("origin", command.origin)
                .put("actor", command.actor)
                .put("controller", command.controller)
                .put("requested_at_ms", command.requestedAtMs ?: JSONObject.NULL)
                .put("phone_received_at_ms", command.phoneReceivedAtMs)
                .put("executed_at_ms", executedAtMs ?: JSONObject.NULL)
                .put("verified_at_ms", verifiedAtMs ?: JSONObject.NULL)
                .put("ok", ok)
                .put("execution_status", if (ok) "executed" else "failed")
                .put("verification_status", if (ok) "phone_reported_success" else "failed")
                .put("result", structuredResult)
                .put("before_state", beforeState ?: JSONObject.NULL)
                .put("after_state", afterState ?: JSONObject.NULL)
        val outbox = settings.reports
        if (outbox == null) {
            postJson("/api/device/report", body)
        } else {
            outbox.enqueue(settings.baseUrl, body)
            syncPendingReports()
        }
    }

    fun syncPendingReports() {
        settings.reports?.sync(settings.baseUrl, settings.deviceId) { body ->
            postJson("/api/device/report", body)
        }
    }

    fun getInbox(limit: Int = 80): List<InboxMessage> {
        val encodedId = URLEncoder.encode(settings.deviceId, Charsets.UTF_8.name())
        val response = getJson(
            "/api/inbox?device_id=" + encodedId + "&limit=" + limit.coerceIn(1, 200)
        )
        val items = response.optJSONArray("messages") ?: return emptyList()
        // The server stores and returns its inbox newest-first (insert at index 0).
        // Chat uses the conventional chronological order, oldest at the top.
        // Reverse the fetched page ONCE at the API boundary, not in the UI.
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    InboxMessage(
                        id = item.optString("id", "message-" + index),
                        role = item.optString("role", "companion"),
                        text = item.optString("text"),
                        createdAt = item.optString("created_at").takeIf { it.isNotBlank() },
                        read = item.optBoolean("read", false),
                        eventId = item.optString("event_id").takeIf { it.isNotBlank() },
                        intentId = item.optString("intent_id").takeIf { it.isNotBlank() }
                    )
                )
            }
        }.asReversed()
    }

    fun postInboxMessage(
        text: String,
        role: String,
        eventId: String? = null,
        intentId: String? = null,
        notify: Boolean = false,
        messageId: String? = null,
        createdAtMs: Long? = null,
        replyToTitle: String? = null,
        replyToText: String? = null
    ): InboxMessage {
        val body = JSONObject()
            .put("device_id", settings.deviceId)
            .put("role", role)
            .put("text", text)
            .put("notify", notify)
            .put("source", "jlz_native_android")
        messageId?.let { body.put("id", it) }
        createdAtMs?.let { body.put("created_at_ms", it) }
        eventId?.let { body.put("event_id", it) }
        intentId?.let { body.put("intent_id", it) }
        replyToTitle?.let { body.put("reply_to_title", it.take(160)) }
        replyToText?.let { body.put("reply_to_text", it.take(1200)) }

        val response = postJson("/api/inbox/message", body)
        // A HTTP response or a locally generated fallback ID is not a delivery receipt.
        check(response.optBoolean("ok", false)) { "Runtime did not confirm inbox delivery" }
        val item = response.optJSONObject("message")
            ?: error("Runtime inbox response omitted its stored message")
        if (messageId != null) {
            check(item.optString("id") == messageId) {
                "Runtime inbox acknowledgement did not match the original event"
            }
        }

        return InboxMessage(
            id = item.optString("id"),
            role = item.optString("role", role),
            text = item.optString("text", text),
            createdAt = item.optString("created_at").takeIf { it.isNotBlank() },
            read = item.optBoolean("read", role == "user"),
            eventId = item.optString("event_id").takeIf { it.isNotBlank() } ?: eventId,
            intentId = item.optString("intent_id").takeIf { it.isNotBlank() } ?: intentId
        )
    }

    fun postActivityEvent(
        source: String,
        type: String,
        title: String,
        subtitle: String,
        metadata: JSONObject = JSONObject(),
        dedupeSeconds: Int = 0,
        eventId: String? = null,
        sourcePackage: String? = null
    ): JSONObject = postJson(
        "/api/activity/events",
        JSONObject()
            .put("device_id", settings.deviceId)
            .put("source", source)
            .put("type", type)
            .put("title", title)
            .put("subtitle", subtitle)
            .put("metadata_json", metadata)
            .put("dedupe_seconds", dedupeSeconds.coerceIn(0, 300))
            .also { body ->
                eventId?.let { body.put("id", it) }
                sourcePackage?.let { body.put("package_name", it) }
            }
    )

    fun postStudyEvent(event: String, metadata: JSONObject = JSONObject()): JSONObject =
        postJson(
            "/api/study/event",
            JSONObject()
                .put("device_id", settings.deviceId)
                .put("event", event)
                .put("source", "jlz_native_android")
                .put("metadata", metadata)
        )

    fun uploadScreenshot(
        bytes: ByteArray, mimeType: String = "image/png",
        eventId: String? = null, originPackage: String? = null,
        studySessionId: String? = null, capturedAtMs: Long? = null,
        captureOrigin: String? = null
    ): JSONObject {
        requirePrivateCaptureRoute()
        val conn = connection("/api/screenshot", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", mimeType)
            setRequestProperty("X-Device-ID", settings.deviceId)
            eventId?.let { setRequestProperty("X-Capture-Event-ID", it) }
            originPackage?.let { setRequestProperty("X-Source-Package", it.take(180)) }
            studySessionId?.let { setRequestProperty("X-Study-Session-ID", it.take(100)) }
            captureOrigin?.let { setRequestProperty("X-Capture-Origin", it.take(40)) }
            capturedAtMs?.takeIf { it > 0L }?.let {
                setRequestProperty("X-Captured-At-Ms", it.toString())
            }
            setFixedLengthStreamingMode(bytes.size)
        }
        settings.traffic?.record(upload = bytes.size.toLong(), screenshot = if (conn.url.path == "/api/screenshot") bytes.size.toLong() else 0)
        settings.bridge?.recordTransfer(sent=bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return readJson(conn)
    }

    /** Confirms Android -> Runtime -> Android image byte identity, not GPT vision. */
    fun downloadNativeCaptureBytes(eventId: String): ByteArray {
        requirePrivateCaptureRoute()
        require(Regex("[0-9a-fA-F-]{36}").matches(eventId)) { "invalid_capture_uuid" }
        val path = "/api/captures/" + eventId + "/image?device_id=" +
            URLEncoder.encode(settings.deviceId, Charsets.UTF_8.name())
        val conn = connection(path, "GET", readTimeoutMs = 20_000)
        try {
            check(conn.responseCode == 200) {
                "capture_readback_http_" + conn.responseCode
            }
            check(conn.contentType?.substringBefore(';') in listOf("image/png", "image/jpeg")) {
                "capture_readback_not_image"
            }
            val length = conn.contentLengthLong
            check(length in 100L..24_000_000L) { "capture_readback_size_invalid" }
            return conn.inputStream.use { it.readBytes() }.also { settings.traffic?.record(download = it.size.toLong()) }
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(
        path: String,
        readTimeoutMs: Int = 15_000
    ): JSONObject = readJson(connection(path, "GET", readTimeoutMs))

    fun canTransferCapture(): Boolean = settings.bridge?.hasHomeEndpoint()!=true || settings.bridgeName.startsWith("Home")

    private fun requirePrivateCaptureRoute() {
        check(canTransferCapture()) {
            "home_capture_waiting_for_private_link"
        }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val conn = connection(path, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(bytes.size)
        }
        settings.traffic?.record(upload = bytes.size.toLong(), screenshot = if (conn.url.path == "/api/screenshot") bytes.size.toLong() else 0)
        settings.bridge?.recordTransfer(sent=bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bodyBytes = stream?.use { it.readBytes() } ?: byteArrayOf()
        settings.traffic?.record(download = bodyBytes.size.toLong())
        settings.bridge?.recordTransfer(received=bodyBytes.size)
        conn.disconnect()
        val text = bodyBytes.toString(Charsets.UTF_8)
        if (code !in 200..299) {
            throw IllegalStateException("Runtime HTTP " + code + ": " + text.take(240))
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    companion object {
        fun appVersion(context: Context): String =
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                "${info.versionName ?: "unknown"}+$code"
            }.getOrDefault("unknown")
    }
}
