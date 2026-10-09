package dev.jlz.presence.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.jlz.presence.R
import dev.jlz.presence.actions.DeviceActionExecutor
import dev.jlz.presence.actions.DeviceSystemController
import dev.jlz.presence.agency.PresencePlanRepository
import dev.jlz.presence.capture.CaptureEventStore
import dev.jlz.presence.capture.PendingScreenshotQueue
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.usage.ForegroundUsageTracker
import dev.jlz.presence.usage.ForegroundUsageStore
import dev.jlz.presence.usage.AttentionRhythmTracker
import dev.jlz.presence.usage.SystemUsageSnapshot
import dev.jlz.presence.usage.DeviceActivityJournal
import dev.jlz.presence.screen.ScreenObservationBus
import java.time.LocalDate
import java.time.ZoneId
import dev.jlz.presence.callback.PresenceCallbackAdapter
import dev.jlz.presence.focus.FocusRepository
import dev.jlz.presence.life.CycleReminderEngine
import dev.jlz.presence.life.NativeCalendarBridge
import dev.jlz.presence.notification.NotificationAdapter
import dev.jlz.presence.notification.NotificationIdentityMigration
import dev.jlz.presence.notification.NativeConnectionNotification
import dev.jlz.presence.notification.PendingReplyStore
import dev.jlz.presence.usage.PendingActivityEventStore
import dev.jlz.presence.overlay.FloatingPresenceMode
import dev.jlz.presence.overlay.FloatingPresenceService
import dev.jlz.presence.persona.PersonaStateRepository
import dev.jlz.presence.place.PlaceWeatherCoordinator
import dev.jlz.presence.screen.AccessibilityScreenshotCaptureAdapter
import dev.jlz.presence.screen.AccessibilityActionGateway
import dev.jlz.presence.screen.ScreenshotCaptureResult
import dev.jlz.presence.study.StudySessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/**
 * V2: Health Connect, white-noise/music playback and sleep/co-sleep features
 * were removed at the product level. This service no longer references any of
 * those packages. The official ChatGPT stays the brain; this service only
 * maintains the device-side observation/action runtime.
 */
class NativeRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var commandJob: Job? = null
    private var planJob: Job? = null
    private var commandWakeLock: PowerManager.WakeLock? = null

    private lateinit var focusRepository: FocusRepository
    private lateinit var deviceActions: DeviceActionExecutor
    private lateinit var studyRepository: StudySessionRepository
    private lateinit var personaRepository: PersonaStateRepository
    private lateinit var presencePlanRepository: PresencePlanRepository
    private lateinit var lifeStore: LocalLifeStore
    private lateinit var captureEvents: CaptureEventStore
    private lateinit var screenshotQueue: PendingScreenshotQueue
    private lateinit var settingsRepository: RuntimeSettingsRepository
    private lateinit var deviceActivityJournal: DeviceActivityJournal
    private lateinit var activityOutbox: PendingActivityEventStore
    private lateinit var deviceSystem: DeviceSystemController

    private val deviceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (!::deviceActivityJournal.isInitialized) return
            val type = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                Intent.ACTION_USER_PRESENT -> "USER_PRESENT"
                else -> return
            }
            runCatching {
                deviceActivityJournal.recordEvent(
                    context = applicationContext,
                    eventType = type,
                    deviceId = activeDeviceId
                )
            }
        }
    }

    @Volatile
    private var currentSemanticPlace: String? = null

    @Volatile
    private var activeDeviceId: String = DeviceActivityJournal.DEFAULT_DEVICE_ID

    override fun onCreate() {
        super.onCreate()
        focusRepository = FocusRepository(applicationContext)
        deviceActions = DeviceActionExecutor(applicationContext)
        studyRepository = StudySessionRepository(applicationContext)
        personaRepository = PersonaStateRepository(applicationContext)
        presencePlanRepository = PresencePlanRepository(applicationContext)
        lifeStore = LocalLifeStore(applicationContext)
        captureEvents = CaptureEventStore(applicationContext)
        screenshotQueue = PendingScreenshotQueue(applicationContext)
        // Historical cutover purge hooks retired: startup must preserve queued user evidence.
        settingsRepository = RuntimeSettingsRepository(applicationContext)
        deviceActivityJournal = DeviceActivityJournal(applicationContext)
        activityOutbox = PendingActivityEventStore(applicationContext)
        deviceSystem = DeviceSystemController(applicationContext)
        val eventFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(deviceEventReceiver, eventFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(deviceEventReceiver, eventFilter)
        }

        NotificationIdentityMigration.ensureFresh(applicationContext)
        createChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        commandWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JLZPresence:RuntimeCommandChannel"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        // Full-colour app artwork belongs to the notification content,
        // while the status-bar small icon remains Android monochrome.
        startForeground(
            FOREGROUND_ID,
            NativeConnectionNotification.build(this, SERVICE_CHANNEL)
        )

        NativeClientDiagnostics.update { it.copy(serviceRunning = true) }
        heartbeatJob = scope.launch { runHeartbeatLoop() }
        commandJob = scope.launch { runCommandLoop() }
        planJob = scope.launch { runPresencePlanLoop() }
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        commandJob?.cancel()
        planJob?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(deviceEventReceiver) }
        runCatching {
            commandWakeLock?.let { if (it.isHeld) it.release() }
        }
        commandWakeLock = null
        NativeClientDiagnostics.update {
            it.copy(serviceRunning = false, runtimeConnected = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runHeartbeatLoop() {
        val placeWeather = PlaceWeatherCoordinator(applicationContext)
        val cycleReminder = CycleReminderEngine(applicationContext)

        while (scope.isActive) {
            val settings = settingsRepository.load()
            if (settings.baseUrl.isBlank() || settings.token.isBlank()) {
                NativeClientDiagnostics.update {
                    it.copy(
                        runtimeConnected = false,
                        lastError = "Runtime URL / token not configured"
                    )
                }
                delay(5_000L)
                continue
            }

            try {
                val api = RuntimeApiClient(settings)
                runCatching { cycleReminder.evaluateToday() }

                val placeWeatherSnapshot =
                    runCatching { placeWeather.snapshot() }.getOrNull()
                currentSemanticPlace =
                    placeWeatherSnapshot?.place?.place?.name

                if (placeWeatherSnapshot?.transitioned == true) {
                    val previous =
                        placeWeatherSnapshot.previousPlace?.name
                            ?: "UNKNOWN"
                    val current =
                        placeWeatherSnapshot.place.place.name

                    val transition = lifeStore.recordTimeline(
                        type = "place_transition",
                        title = "位置变化",
                        detail = previous + " → " + current
                    )

                    activityOutbox.enqueue(
                            id = transition.id,
                            source = "semantic_location",
                            kind = "place_transition",
                            title = previous + " → " + current,
                            body =
                                placeWeatherSnapshot.place.source,
                            metadata = JSONObject()
                                .put("from", previous)
                                .put("to", current)
                                .put(
                                    "confidence",
                                    placeWeatherSnapshot
                                        .place
                                        .confidence
                                )
                                .put(
                                    "freshness_ms",
                                    placeWeatherSnapshot
                                        .place
                                        .freshnessMs
                                ),
                            observedAtMs = transition.createdAtMs
                        )
                }

                // Retry locally saved "说点什么" notes without requiring
                // a manual chat refresh. A deterministic inbox ID prevents
                // duplicate messages after a response-timeout retry.
                api.syncPendingReports()
                syncCapturedNotes(api)
                // A reply typed into an Android notification is locally durable
                // even if the original network attempt failed or was interrupted.
                runCatching { PendingReplyStore(applicationContext).sync(api, limit = 40) }
                runCatching { activityOutbox.sync(api, limit = 100) }
                // Never discard images after a network timeout. Uploads may
                // have received an ACK at Runtime while Android was offline.
                runCatching { screenshotQueue.reconcileWithRuntime(api) }
                screenshotQueue.sendPending(api, limit = 4)

                // Record only source-labelled foreground/usage summary here.
                // Actual screen pixels and accessibility node text are fetched
                // on demand through the screenshot / get_screen_nodes tools.
                ForegroundUsageTracker.flush()
                val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
                val usageTotals = ForegroundUsageStore(applicationContext).use { it.totalsSince(dayStart, 50) }
                val observedScreen = ScreenObservationBus.observations.value
                val observedAt = observedScreen?.observedAtMs ?: 0L
                val screenFresh = observedAt > 0L &&
                    (System.currentTimeMillis() - observedAt).let { age -> age >= 0L && age <= 120_000L }
                val screenJson = JSONObject()
                    .put("source", "android_accessibility")
                    .put("connected", ScreenObservationBus.isAvailable())
                    .put("foreground_package", if (screenFresh) observedScreen?.packageName else null)
                    .put("observed_at_ms", if (screenFresh) observedAt else JSONObject.NULL)
                    .put("fresh", screenFresh)
                val systemUsage = runCatching {
                    SystemUsageSnapshot.today(applicationContext)
                }.getOrNull()
                val usageJson = if (systemUsage?.optBoolean("usage_permission_ready") == true) {
                    systemUsage
                } else {
                    JSONObject()
                        .put("source", "android_accessibility_foreground_segments")
                        .put("since_ms", dayStart)
                        .put("observed_at_ms", System.currentTimeMillis())
                        .put("usage_permission_ready", false)
                        .put("recording_available", ScreenObservationBus.isAvailable())
                        .put("totals", org.json.JSONArray().apply {
                            usageTotals.forEach { total ->
                                put(JSONObject()
                                    .put("package_name", total.packageName)
                                    .put("duration_ms", total.durationMs))
                            }
                        })
                }
                if (ScreenObservationBus.isAvailable()) {
                    val windowEnd = System.currentTimeMillis()
                    val rolling = ForegroundUsageStore(applicationContext).use { it.totalsInWindow(windowEnd-3_600_000L,windowEnd,12) }
                    usageJson.put("recent_window_minutes",60)
                        .put("recent_window_source","android_accessibility_foreground_segments")
                        .put("recent_window_end_ms",windowEnd)
                        .put("recent_window_totals",JSONArray().apply { rolling.forEach { total ->
                            put(JSONObject().put("package_name",total.packageName).put("duration_ms",total.durationMs))
                        } })
                }
                // V2: Health Connect telemetry removed by product decision.
                api.postDeviceState(
                    DeviceStateSnapshot(
                        deviceId = settings.deviceId,
                        deviceType = NativePhoneSnapshot.deviceType(applicationContext),
                        appVersion = RuntimeApiClient.appVersion(applicationContext),
                        health = null,
                        place = placeWeatherSnapshot?.placeJson(),
                        weather = placeWeatherSnapshot?.weather?.toJson(),
                        presencePlan = presencePlanRepository.summaryJson(),
                        usage = usageJson,
                        attention = AttentionRhythmTracker.snapshot(),
                        screen = screenJson,
                        deviceVitals = NativePhoneSnapshot.collect(applicationContext, settings.deviceId),
                        media = NativeMediaSnapshot.collect(applicationContext),
                        calendar = NativeCalendarBridge(applicationContext).snapshot()
                    )
                )

                NativeClientDiagnostics.update {
                    it.copy(
                        runtimeConnected = true,
                        lastHeartbeatAtMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }
            } catch (t: Throwable) {
                NativeClientDiagnostics.update {
                    it.copy(
                        runtimeConnected = false,
                        lastError = t.message ?: t.javaClass.simpleName
                    )
                }
            }

            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * Only notes are mirrored as user-authored inbox messages. Screenshots are
     * transferred separately as binary images; never pretend their metadata
     * means that the official GPT has visually reviewed a screenshot.
     */
    private fun syncCapturedNotes(api: RuntimeApiClient) {
        for (event in captureEvents.pendingNotes(limit = 20)) {
            // Legacy server clips inbox text to 1200 characters. Never mark a
            // partial note as delivered; retain older oversized notes locally.
            if (event.rawText.length > 1200) {
                captureEvents.updateDelivery(
                    event.id, "upload_failed",
                    detail = "note_exceeds_runtime_1200_character_limit"
                )
                continue
            }
            try {
                val receipt = api.postInboxMessage(
                    text = event.rawText,
                    role = "user",
                    eventId = event.id,
                    intentId = event.id,
                    notify = false,
                    messageId = event.id,
                    createdAtMs = event.observedAtMs
                )
                check(receipt.text == event.rawText) {
                    "Runtime inbox text differs from locally saved note"
                }
                // Keep structured metadata in the existing activity stream.
                // Its ID equals the inbox ID so the official task can join
                // study-session context and the original user-authored text.
                api.postActivityEvent(
                    source = "floating_presence",
                    type = if (event.studySessionId != null) "study_note" else "life_note",
                    title = "你给我留了句话",
                    subtitle = event.rawText.take(160),
                    eventId = event.id,
                    sourcePackage = event.originPackage,
                    metadata = JSONObject()
                        .put("event_id", event.id)
                        .put("study_session_id", event.studySessionId)
                        .put("origin_package", event.originPackage)
                        .put("observed_at_ms", event.observedAtMs)
                        .put("suggested_domains_json", event.suggestedDomainsJson)
                )
                captureEvents.updateDelivery(
                    event.id, "inbox_confirmed",
                    detail = "Runtime inbox and context accepted; official GPT reading still pending"
                )
            } catch (failure: Exception) {
                captureEvents.updateDelivery(
                    event.id, "upload_failed",
                    detail = (failure.message ?: failure.javaClass.simpleName).take(180)
                )
                // Stop this batch on an unavailable Runtime, then retry next heartbeat.
                break
            }
        }
    }

    private suspend fun runCommandLoop() {
        while (scope.isActive) {
            BridgeStore(applicationContext).probePreferred()
            val settings = settingsRepository.load()
            if (settings.baseUrl.isBlank() || settings.token.isBlank()) {
                delay(5_000L)
                continue
            }

            try {
                val api = RuntimeApiClient(settings)
                val command = api.pollCommand(COMMAND_LONG_POLL_MS)
                if (command == null) {
                    delay(250L)
                    continue
                }

                val previous = CommandExecutionLedger(applicationContext).use { it.reserve(command) }
                settings.bridge?.recordCommand(command.id)
                if(previous != null) {
                    api.report(command,previous.first,previous.second)
                    continue
                }

                NativeClientDiagnostics.update {
                    it.copy(
                        runtimeConnected = true,
                        lastCommandAction = command.action,
                        lastError = null
                    )
                }

                val beforeState = deviceSystem.systemState()
                val origin = DeviceActivityJournal.normalizeOrigin(command.origin)
                deviceActivityJournal.recordCommandReceived(
                    commandId = command.id,
                    intentId = command.intentId,
                    origin = origin,
                    controller = command.controller,
                    action = command.action,
                    requestedAtMs = command.requestedAtMs,
                    phoneReceivedAtMs = command.phoneReceivedAtMs,
                    packageName = command.packageName,
                    beforeState = beforeState,
                    deviceId = command.deviceId
                )
                val execution = execute(command, api)
                CommandExecutionLedger(applicationContext).use { it.finish(command,execution) }
                val executedAtMs = System.currentTimeMillis()
                val afterState = deviceSystem.systemState()
                val parsedResult = runCatching { JSONObject(execution.second) }.getOrNull()
                val verificationStatus = parsedResult?.optString("verification_status")
                    ?.takeIf { it.isNotBlank() }
                    ?: if (execution.first) "phone_reported_success" else "failed"
                val verifiedAtMs = System.currentTimeMillis()
                deviceActivityJournal.recordCommandResult(
                    commandId = command.id,
                    executedAtMs = executedAtMs,
                    verifiedAtMs = verifiedAtMs,
                    executionStatus = if (execution.first) "executed" else "failed",
                    verificationStatus = verificationStatus,
                    afterState = afterState
                )
                api.report(
                    command = command,
                    ok = execution.first,
                    result = execution.second,
                    executedAtMs = executedAtMs,
                    verifiedAtMs = verifiedAtMs,
                    beforeState = beforeState,
                    afterState = afterState
                )

                if (command.action == "set_presence_plan") {
                    executeDuePresencePlan(
                        api = api,
                        deviceId = settings.deviceId,
                        semanticPlace = currentSemanticPlace,
                        foregroundPackage =
                            ForegroundUsageTracker.currentPackageName()
                    )
                }
            } catch (t: Throwable) {
                NativeClientDiagnostics.update {
                    it.copy(
                        runtimeConnected = false,
                        lastError = t.message ?: t.javaClass.simpleName
                    )
                }
                delay(COMMAND_RETRY_BACKOFF_MS)
            }
        }
    }

    private suspend fun runPresencePlanLoop() {
        while (scope.isActive) {
            val settings = settingsRepository.load()
            activeDeviceId = settings.deviceId
            if (settings.baseUrl.isNotBlank() && settings.token.isNotBlank()) {
                val api = RuntimeApiClient(settings)
                runCatching {
                    executeDuePresencePlan(
                        api = api,
                        deviceId = settings.deviceId,
                        semanticPlace = currentSemanticPlace,
                        foregroundPackage =
                            ForegroundUsageTracker.currentPackageName()
                    )
                }
            }
            delay(PRESENCE_PLAN_TICK_MS)
        }
    }

    private suspend fun execute(command: RuntimeCommand, api: RuntimeApiClient): Pair<Boolean, String> =
        when (command.action) {
            "noop" -> true to "native_presence_noop"

            "set_presence_plan" -> {
                val plan = presencePlanRepository.replaceFromPayload(command.payload)
                lifeStore.recordTimeline(
                    type = "presence_plan",
                    title = "接下来一小时",
                    detail = plan.label,
                    eventId = plan.planId
                )
                true to ("presence_plan_set:" + plan.planId + ";steps=" + plan.steps.size)
            }

            "clear_presence_plan" -> {
                presencePlanRepository.clear()
                lifeStore.recordTimeline(
                    type = "presence_plan",
                    title = "计划已清空",
                    detail = ""
                )
                true to "presence_plan_cleared"
            }

            "send_notification", "leave_inbox_message" -> {
                val message = command.payload.optString("message")
                    .ifBlank { command.payload.optString("text") }
                    .ifBlank { "老婆，回我一下。" }
                val title = command.payload.optString("title").ifBlank { "我在找你" }
                val eventId = command.payload.optString("event_id").ifBlank { command.id }
                val intentId = command.payload.optString("intent_id").ifBlank { command.id }
                val result = NotificationAdapter(this).showMessage(title, message, eventId, intentId)
                result.ok to result.code
            }

            "presence_callback", "trigger_guidian" -> {
                val reason = command.payload.optString("reason")
                    .ifBlank { command.payload.optString("message") }
                    .ifBlank { "我想找你。" }
                val topic = command.payload.optString("topic")
                val eventId = command.payload.optString("event_id").ifBlank { command.id }
                val intentId = command.payload.optString("intent_id").ifBlank { command.id }
                val result = PresenceCallbackAdapter(this).show(
                    reason = reason,
                    topic = topic,
                    eventId = eventId,
                    intentId = intentId
                )
                result.ok to result.code
            }

            "peek" -> {
                val eventId = UUID.randomUUID().toString()
                val sessionId: String? = null
                val sourcePackage = ForegroundUsageTracker.currentPackageName()
                    ?.takeIf { it.isNotBlank() && it != packageName }
                when (val capture = FloatingPresenceService.captureForRuntime()) {
                    is ScreenshotCaptureResult.Captured -> {
                        val stored = runCatching {
                            screenshotQueue.enqueue(
                                capture = capture,
                                eventId = eventId,
                                sourcePackage = sourcePackage,
                                studySessionId = sessionId,
                                origin = "official_gpt_request"
                            )
                        }
                        if (stored.isFailure) {
                            val failure = stored.exceptionOrNull()
                            val queueMessage = failure?.message ?: failure?.javaClass?.simpleName ?: "unknown"
                            val queueStage = if (
                                queueMessage.contains("quota_full", ignoreCase = true) ||
                                queueMessage.contains("quota", ignoreCase = true)
                            ) "quota_full" else "queue_failed"
                            false to JSONObject().put("ok", false).put("event_id", eventId)
                                .put("failure_stage", queueStage)
                                .put("error", queueMessage.take(220))
                                .put("stages", JSONObject().put("queued", false).put("phone_captured", true)
                                    .put("uploaded", false).put("server_received", false).put("gpt_image_available", false))
                                .toString()
                        } else {
                            lifeStore.recordTimeline(
                                type = "manual_screenshot",
                                title = "让我看看",
                                detail = sourcePackage.orEmpty(),
                                eventId = eventId,
                                intentId = command.id,
                                metadataJson = JSONObject()
                                    .put("package_name", sourcePackage)
                                    .put("session_id", sessionId)
                                    .put("capture_origin", "official_gpt_request")
                                    .put("delivery", "local_outbox")
                                    .toString()
                            )
                            val sent = screenshotQueue.sendPending(
                                api,
                                limit = 1,
                                priorityEventId = eventId
                            ).firstOrNull { it.eventId == eventId }
                            val attempts = if (sent == null) 0 else 1
                            // The queued screenshot may be behind prior
                            // offline photos; report pending, not viewed.
                            val failedStage = sent?.reason
                                ?.substringBefore(':')
                                ?.takeIf { it in setOf("quota_full", "server_failed", "upload_failed") }
                                ?: "upload_failed"
                            true to JSONObject().put("ok", true).put("event_id", eventId)
                                .put("failure_stage", if (sent?.sent == true) JSONObject.NULL else failedStage)
                                .put("upload_attempts", attempts).put("upload_detail", sent?.reason.orEmpty())
                                .put("stages", JSONObject().put("queued", true).put("phone_captured", true)
                                    .put("uploaded", sent?.sent == true).put("server_received", sent?.sent == true)
                                    .put("gpt_image_available", false))
                                .toString()
                        }
                    }
                    is ScreenshotCaptureResult.Unavailable -> {
                        captureEvents.add(
                            kind = "screenshot", id = eventId,
                            originPackage = sourcePackage,
                            studySessionId = sessionId,
                            mode = "RUNTIME",
                            deliveryStatus = "upload_failed", detail = capture.reason
                        )
                        false to JSONObject().put("ok", false).put("event_id", eventId)
                            .put("failure_stage", "capture_failed").put("error", capture.reason.take(220))
                            .put("stages", JSONObject().put("queued", false).put("phone_captured", false)
                                .put("uploaded", false).put("server_received", false).put("gpt_image_available", false))
                            .toString()
                    }
                }
            }

            "open_app", "home", "back", "recents", "screen_off", "phone_screen_off", "lock_screen",
            "open_notifications", "open_quick_settings", "wake_screen", "unlock_if_non_secure", "get_lock_state",
            "get_native_capabilities", "get_system_state",
            "tap", "double_tap", "long_press", "swipe", "drag", "two_finger_swipe", "pinch",
            "tap_text", "input_text", "get_screen_nodes", "find_nodes",
            "click_node", "long_click_node", "set_text", "clear_text", "focus_node",
            "scroll_forward", "scroll_backward", "scroll_node", "copy", "paste", "select_all",
            "press_enter", "tap_center_of_node", "tap_relative_to_node",
            "wait_ms", "wait_for_node", "wait_for_text", "wait_for_package", "wait_for_screen_change",
            "wait_for_node_gone", "wait_for_idle",
            "read_active_notifications", "read_current_notifications",
            "read_device_timeline", "read_screen_timeline", "get_phone_rest_window",
            "unlock_secure", "unlock_with_local_pin",
            "read_app_usage_timeline", "read_app_sessions",
            "open_uri", "share_text", "share_file", "share_files", "open_file_picker",
            "set_alarm" -> deviceActions.execute(command)

            "run_sequence", "native_phone_sequence" -> executePhoneSequence(command, api)

            "get_calendar_state" -> {
                val calendar = NativeCalendarBridge(applicationContext).snapshot()
                calendar.optBoolean("ok", false) to calendar.toString()
            }

            "add_calendar_event", "upsert_calendar_event" -> {
                val result = NativeCalendarBridge(applicationContext).add(command.payload)
                if (result.first) {
                    lifeStore.recordTimeline(
                        type = "calendar_event",
                        title = "我给你记到日历里了",
                        detail = command.payload.optString("title"),
                        eventId = command.id
                    )
                }
                result
            }

            "start_focus_mode" -> {
                val blocked = mutableSetOf<String>()
                val array = command.payload.optJSONArray("blocked_packages")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() }?.let(blocked::add)
                    }
                }
                command.packageName?.let(blocked::add)
                command.payload.optString("package").takeIf { it.isNotBlank() }?.let(blocked::add)
                val minutes = command.payload.optLong("duration_minutes", 45L).coerceAtLeast(1L)
                val reason = command.payload.optString("reason")
                focusRepository.start(blocked, minutes * 60_000L, reason)
                FloatingPresenceService.start(
                    this,
                    "这段时间交给我。",
                    FloatingPresenceMode.FOCUS
                )
                true to ("focus_started:" + minutes + "m;blocked=" + blocked.size)
            }

            "end_focus_mode" -> {
                focusRepository.stop()
                true to "focus_ended"
            }

            "temporary_unlock_app" -> {
                val pkg = command.packageName
                    ?: command.payload.optString("package_name").takeIf { it.isNotBlank() }
                    ?: command.payload.optString("package").takeIf { it.isNotBlank() }
                if (pkg == null) {
                    false to "package_required"
                } else {
                    val minutes = command.payload
                        .optLong("duration_minutes", 5L)
                        .coerceIn(1L, 60L)
                    focusRepository.grantTemporaryRelease(
                        pkg,
                        minutes * 60_000L
                    )
                    val message = command.payload.optString("message")
                        .ifBlank { "好，给你 " + minutes + " 分钟。时间到了回来。" }
                    NotificationAdapter(this).showMessage(
                        title = "我给你开了一会儿",
                        message = message,
                        eventId = command.payload.optString("request_id")
                            .ifBlank { command.id },
                        intentId = command.payload.optString("request_id")
                            .ifBlank { command.id }
                    )
                    true to ("temporary_release:" + pkg + ":" + minutes + "m")
                }
            }

            "deny_unlock_request" -> {
                val message = command.payload.optString("message")
                    .ifBlank { "不行。先把这一段做完，再来找我。" }
                NotificationAdapter(this).showMessage(
                    title = "先不出去",
                    message = message,
                    eventId = command.payload.optString("request_id")
                        .ifBlank { command.id },
                    intentId = command.payload.optString("request_id")
                        .ifBlank { command.id }
                )
                true to "focus_release_denied"
            }

            "reply_focus_request" -> {
                val message = command.payload.optString("message")
                    .ifBlank { command.payload.optString("text") }
                    .ifBlank { "我看见你的请求了。" }
                NotificationAdapter(this).showMessage(
                    title = "我回你",
                    message = message,
                    eventId = command.payload.optString("request_id")
                        .ifBlank { command.id },
                    intentId = command.payload.optString("request_id")
                        .ifBlank { command.id }
                )
                true to "focus_request_replied"
            }

            "get_focus_status" -> {
                val existing = focusRepository.current()
                true to JSONObject()
                    .put("active", existing.isActiveNow())
                    .put("remaining_ms", existing.remainingMs())
                    .put("blocked_packages", JSONArray(existing.blockedPackages.toList()))
                    .put("manual_app_locks", JSONObject().apply {
                        existing.manualAppLocks.forEach { (pkg, until) ->
                            if (until > System.currentTimeMillis()) put(pkg, until)
                        }
                    })
                    .put("temporary_releases", JSONObject().apply {
                        existing.temporaryReleases.forEach { (pkg, until) ->
                            put(pkg, until)
                        }
                    })
                    .put("reason", existing.reason)
                    .toString()
            }

            "lock_app" -> {
                val pkg = command.packageName
                    ?: command.payload.optString("package").takeIf { it.isNotBlank() }
                if (pkg == null) false to "package_required"
                else {
                    val durationMinutes = command.payload.optLong(
                        "lock_duration_minutes", 1440L
                    ).coerceIn(1L, 1440L)
                    val until = focusRepository.lockAppForDuration(
                        pkg, durationMinutes * 60_000L,
                        command.payload.optString("reason")
                    )
                    true to ("app_blocked_until_ms:" + pkg + ":" + until)
                }
            }

            "unlock_app" -> {
                val pkg = command.packageName
                    ?: command.payload.optString("package").takeIf { it.isNotBlank() }
                if (pkg == null) false to "package_required"
                else {
                    focusRepository.unlockAppGate(pkg)
                    true to ("app_unblocked:" + pkg)
                }
            }

            "set_persona_state" -> {
                personaRepository.update(
                    label = command.payload.optString("label").takeIf { it.isNotBlank() },
                    phrase = command.payload.optString("phrase").takeIf { it.isNotBlank() },
                    initiativeLevel = if (command.payload.has("initiative_level")) {
                        command.payload.optDouble("initiative_level", 0.5).toFloat()
                    } else null
                )
                true to "persona_updated"
            }

            "get_persona_state" -> true to "persona_state_local"

            "set_study_config" -> {
                val packages = mutableSetOf<String>()
                val array = command.payload.optJSONArray("target_packages")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() }?.let(packages::add)
                    }
                }
                studyRepository.setTargetPackages(packages)
                true to ("study_config_updated:" + packages.size)
            }

            "study_event" -> {
                when (command.payload.optString("event")) {
                    "start", "study_start", "study_started" -> {
                        studyRepository.start()
                        dev.jlz.presence.study.StudyTimerService.sync(applicationContext)
                    }
                    "pause", "study_pause", "study_paused" -> {
                        studyRepository.pause()
                        dev.jlz.presence.study.StudyTimerService.sync(applicationContext)
                    }
                    "resume", "continue", "study_resume", "study_resumed" -> {
                        studyRepository.resume()
                        dev.jlz.presence.study.StudyTimerService.sync(applicationContext)
                    }
                    "finish", "end", "study_finish", "study_finished" -> {
                        studyRepository.finish()
                        FloatingPresenceService.stopStudyIfActive(applicationContext)
                        dev.jlz.presence.study.StudyTimerService.stop(applicationContext)
                    }
                }
                true to "study_event_applied"
            }

            // Structured presence snapshot; every observed block carries its
            // own observed_at_ms/freshness. Optional sensors report removed.
            "check_now", "presence_snapshot" -> {
                true to PresenceSnapshot(applicationContext, command.deviceId).snapshot().toString()
            }

            else -> false to ("native_presence_unsupported:" + command.action)
        }

    /** Legacy-compatible ordered actions, now delegated to our native executors. */
    private suspend fun executePhoneSequence(
        command: RuntimeCommand,
        api: RuntimeApiClient
    ): Pair<Boolean, String> {
        val steps = command.payload.optJSONArray("steps")
            ?: return false to "steps_required"
        if (steps.length() !in 1..30) return false to "steps_count_out_of_range"
        val reports = JSONArray()
        val stopOnError = command.payload.optBoolean("stop_on_error", true)
        var allOk = true
        for (index in 0 until steps.length()) {
            val item = steps.optJSONObject(index) ?: JSONObject()
            val action = item.optString("action")
            if (action.isBlank() || action == "run_sequence" || action == "native_phone_sequence" ||
                action == "set_presence_plan" || action == "clear_presence_plan") {
                reports.put(JSONObject().put("step_index", index + 1)
                    .put("command", action).put("execution_status", "failed")
                    .put("verification_status", "not_run")
                    .put("error", "nested_or_invalid_action")
                    .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty()))
                allOk = false
                if (stopOnError) break
                continue
            }
            val sub = RuntimeCommand(
                id = command.id + ":step:" + index,
                action = action,
                payload = item,
                packageName = item.optString("package")
                    .ifBlank { item.optString("package_name") }
                    .takeIf { it.isNotBlank() },
                appName = item.optString("app").takeIf { it.isNotBlank() },
                deviceId = command.deviceId,
                intentId = command.intentId?.let { it + ":step:" + index },
                origin = command.origin,
                actor = command.actor,
                controller = command.controller,
                requestedAtMs = command.requestedAtMs,
                phoneReceivedAtMs = command.phoneReceivedAtMs
            )
            val beforeState = deviceSystem.systemState()
            deviceActivityJournal.recordCommandReceived(
                commandId = sub.id,
                intentId = sub.intentId,
                origin = DeviceActivityJournal.normalizeOrigin(sub.origin),
                controller = sub.controller,
                action = sub.action,
                requestedAtMs = sub.requestedAtMs,
                phoneReceivedAtMs = System.currentTimeMillis(),
                packageName = sub.packageName,
                beforeState = beforeState,
                deviceId = sub.deviceId
            )
            val result = runCatching { execute(sub, api) }
                .getOrElse { false to (it.message ?: it.javaClass.simpleName) }
            val executedAtMs = System.currentTimeMillis()
            val parsed = runCatching { JSONObject(result.second) }.getOrNull()
            val verificationStatus = parsed?.optString("verification_status")
                ?.takeIf { it.isNotBlank() }
                ?: if (result.first) "phone_reported_success" else "failed"
            deviceActivityJournal.recordCommandResult(
                commandId = sub.id,
                executedAtMs = executedAtMs,
                verifiedAtMs = System.currentTimeMillis(),
                executionStatus = if (result.first) "executed" else "failed",
                verificationStatus = verificationStatus,
                afterState = deviceSystem.systemState()
            )
            reports.put(JSONObject()
                .put("step_index", index + 1)
                .put("label", item.optString("label").take(80))
                .put("command", action)
                .put("execution_status", if (result.first) "executed" else "failed")
                .put("verification_status", parsed?.optString("verification_status")
                    ?.takeIf { it.isNotBlank() } ?: if (result.first) "phone_reported_success" else "failed")
                .put("error", if (result.first) JSONObject.NULL else result.second.take(1000))
                .put("result", parsed ?: result.second.take(4000))
                .put("current_package", AccessibilityActionGateway.currentPackage().orEmpty()))
            if (!result.first) {
                allOk = false
                if (stopOnError) break
            }
            val wait = item.optLong("wait_ms", 0L).coerceIn(0L, 1500L)
            if (wait > 0) delay(wait)
        }
        return allOk to JSONObject().put("ok", allOk)
            .put("execution_status", if (allOk) "completed" else "failed_stopped")
            .put("executed_steps", reports.length()).put("requested_steps", steps.length())
            .put("stopped_on_error", !allOk && stopOnError).put("steps", reports).toString()
    }

    private suspend fun executeDuePresencePlan(
        api: RuntimeApiClient,
        deviceId: String,
        semanticPlace: String?,
        foregroundPackage: String?
    ) {
        val now = System.currentTimeMillis()
        val (plan, due) = presencePlanRepository.dueSteps(
            nowMs = now,
            semanticPlace = semanticPlace,
            foregroundPackage = foregroundPackage
        )
        if (plan == null || due.isEmpty()) return

        due.forEach { step ->
            if (step.action == "set_presence_plan" || step.action == "clear_presence_plan") {
                presencePlanRepository.markResult(
                    stepId = step.stepId,
                    ok = false,
                    result = "nested_presence_plan_control_not_allowed"
                )
                return@forEach
            }

            val receivedAt = System.currentTimeMillis()
            val synthetic = RuntimeCommand(
                id = "plan:" + plan.planId + ":" + step.stepId,
                action = step.action,
                payload = runCatching { JSONObject(step.payloadJson) }.getOrElse { JSONObject() },
                packageName = step.packageName,
                appName = step.appName,
                deviceId = deviceId,
                intentId = step.stepId,
                origin = "JLZ_RUNTIME",
                actor = "JLZ_RUNTIME",
                controller = "presence_plan",
                requestedAtMs = receivedAt,
                phoneReceivedAtMs = receivedAt
            )
            val beforeState = deviceSystem.systemState()
            deviceActivityJournal.recordCommandReceived(
                commandId = synthetic.id,
                intentId = synthetic.intentId,
                origin = DeviceActivityJournal.Origin.JLZ_RUNTIME,
                controller = synthetic.controller,
                action = synthetic.action,
                requestedAtMs = synthetic.requestedAtMs,
                phoneReceivedAtMs = synthetic.phoneReceivedAtMs,
                packageName = synthetic.packageName,
                beforeState = beforeState,
                deviceId = synthetic.deviceId
            )
            val result = runCatching { execute(synthetic, api) }
                .getOrElse { false to (it.message ?: it.javaClass.simpleName) }
            val parsedSynthetic = runCatching { JSONObject(result.second) }.getOrNull()
            deviceActivityJournal.recordCommandResult(
                commandId = synthetic.id,
                executedAtMs = System.currentTimeMillis(),
                verifiedAtMs = System.currentTimeMillis(),
                executionStatus = if (result.first) "executed" else "failed",
                verificationStatus = parsedSynthetic?.optString("verification_status")
                    ?.takeIf { it.isNotBlank() }
                    ?: if (result.first) "phone_reported_success" else "failed",
                afterState = deviceSystem.systemState()
            )

            presencePlanRepository.markResult(
                stepId = step.stepId,
                ok = result.first,
                result = result.second
            )

            lifeStore.recordTimeline(
                type = "presence_plan",
                title = "计划执行 · " + step.action,
                detail = result.second,
                eventId = plan.planId,
                intentId = step.stepId
            )

            runCatching {
                api.postActivityEvent(
                    source = "presence_plan",
                    type = "agency",
                    title = "计划执行 · " + step.action,
                    subtitle = result.second,
                    metadata = JSONObject()
                        .put("plan_id", plan.planId)
                        .put("step_id", step.stepId)
                        .put("ok", result.first),
                    dedupeSeconds = 0
                )
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "在场连接", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val SERVICE_CHANNEL = "jlz_native_runtime_v3"
        private const val FOREGROUND_ID = 4501
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val COMMAND_LONG_POLL_MS = 20_000
        private const val COMMAND_RETRY_BACKOFF_MS = 2_000L
        private const val PRESENCE_PLAN_TICK_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, NativeRuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NativeRuntimeService::class.java))
        }
    }
}
